package com.stockspeaker

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// ── 行情快照（每次轮询产出一个） ──

data class MarketSnapshot(
    val time: Long,
    val price: Double,
    val changePct: Double,
    val speed: Double,
    val volRatio: Double,
    val amountStr: String,
    val currentHand: Int,
    val largeAsksCount: Int,
    val largeBidsCount: Int
)

// ── 异动模式 ──

enum class PatternType {
    VOLUME_BREAKOUT,   // 放量突破
    SPEED_ALERT,       // 涨速异动
    SHARP_REVERSAL,    // 高位急转
    BIG_ORDER_ALERT    // 大单异动
}

data class Pattern(
    val type: PatternType,
    val speakText: String      // 已经组织好的口语化文本，可直接送入 TTS
)

// ── AI 配置（从 ConfigManager 传入，每次调用时重新读取以保持最新） ──

data class AiConfig(
    val enabled: Boolean = false,
    val apiKey: String = "",
    val summaryInterval: Int = 5
)

class AIAnalyzer(
    private val aiConfigProvider: () -> AiConfig
) {
    private val recentData = ArrayDeque<MarketSnapshot>(20)
    private var broadcastCount = 0
    private var lastLargeAskCount = 0
    private var lastLargeBidCount = 0
    private val apiExecutor = Executors.newSingleThreadExecutor()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 喂入一条行情数据，返回本次检测到的异动列表 */
    fun feed(snapshot: MarketSnapshot): List<Pattern> {
        recentData.addLast(snapshot)
        if (recentData.size > 20) recentData.removeFirst()

        val patterns = mutableListOf<Pattern>()

        // 1. 放量突破：量比 >= 2.0 且 涨跌幅 >= 1.0%
        if (snapshot.volRatio >= 2.0 && Math.abs(snapshot.changePct) >= 1.0) {
            val dir = if (snapshot.changePct > 0) "放量拉升" else "放量下砸"
            patterns.add(Pattern(PatternType.VOLUME_BREAKOUT,
                "$dir，量比${snapshot.volRatio}，成交${snapshot.amountStr}。"))
        }

        // 2. 涨速异动：每秒涨速 >= 1.0%
        val absSpeed = Math.abs(snapshot.speed)
        if (absSpeed >= 1.0) {
            val desc = if (snapshot.speed > 0) "快速拉升" else "快速下跌"
            patterns.add(Pattern(PatternType.SPEED_ALERT,
                "${desc}，涨速${String.format("%.1f", absSpeed)}%每秒。"))
        }

        // 3. 高位急转：过去5个快照内从最高点回落 >= 0.5%
        if (recentData.size >= 5) {
            val last5 = recentData.takeLast(5)
            val maxPrice = last5.maxOf { it.price }
            val reversal = (maxPrice - snapshot.price) / maxPrice * 100.0
            if (reversal >= 0.5) {
                patterns.add(Pattern(PatternType.SHARP_REVERSAL,
                    "高位急转，${String.format("%.1f", reversal)}个点回落，注意风险。"))
            }
        }

        // 4. 大单异动：卖盘或买盘大单档数骤增 >= 3 档
        val askDelta = snapshot.largeAsksCount - lastLargeAskCount
        val bidDelta = snapshot.largeBidsCount - lastLargeBidCount
        lastLargeAskCount = snapshot.largeAsksCount
        lastLargeBidCount = snapshot.largeBidsCount
        if (askDelta >= 3 || bidDelta >= 3) {
            val parts = mutableListOf<String>()
            if (askDelta >= 3) parts.add("卖盘突增${askDelta}档大压单")
            if (bidDelta >= 3) parts.add("买盘突增${bidDelta}档大托单")
            patterns.add(Pattern(PatternType.BIG_ORDER_ALERT,
                "大单异动：${parts.joinToString("，")}。"))
        }

        return patterns
    }

    /** 是否该生成 AI 总结了（每 N 次常规播报触发一次） */
    fun shouldGenerateSummary(): Boolean {
        val config = aiConfigProvider()
        if (!config.enabled || config.apiKey.isBlank()) return false
        broadcastCount++
        return broadcastCount % config.summaryInterval == 0
    }

    /** 构建发给 DeepSeek 的提示词 */
    private fun buildPrompt(): String {
        if (recentData.isEmpty()) return ""
        val latest = recentData.last()
        val history = recentData.toList()

        val trend = buildString {
            val first = history.first()
            val delta = latest.price - first.price
            append(when {
                delta > 0.5 -> "持续上涨"
                delta > 0 -> "小幅上涨"
                delta < -0.5 -> "持续下跌"
                delta < 0 -> "小幅下跌"
                else -> "横盘震荡"
            })
        }

        return buildString {
            append("${latest.price}元，${if (latest.changePct > 0) "+" else ""}${latest.changePct}%，")
            append("近30秒${trend}，量比${latest.volRatio}，成交${latest.amountStr}。")
            append("口语总结盘面，像老股民跟朋友聊两句，不超过60字。")
        }
    }

    /** 异步调用 DeepSeek API，结果通过 callback 返回（在 apiExecutor 线程执行） */
    fun generateSummary(callback: (String?) -> Unit) {
        val config = aiConfigProvider()
        if (!config.enabled || config.apiKey.isBlank()) {
            callback(null)
            return
        }

        val prompt = buildPrompt()
        if (prompt.isEmpty()) {
            callback(null)
            return
        }

        apiExecutor.execute {
            try {
                val json = """
                    |{
                    |  "model": "deepseek-chat",
                    |  "messages": [
                    |    {"role": "system", "content": "你是老股民，用2-3句口语总结盘面，不超过60字。像跟朋友聊天那样自然。不要'当前''根据数据'等套话，直接说出你的判断。"},
                    |    {"role": "user", "content": ${toJsonStr(prompt)}}
                    |  ],
                    |  "max_tokens": 150,
                    |  "temperature": 0.7
                    |}
                """.trimMargin()

                val request = Request.Builder()
                    .url("https://api.deepseek.com/v1/chat/completions")
                    .header("Authorization", "Bearer ${config.apiKey}")
                    .header("Content-Type", "application/json")
                    .post(json.toRequestBody("application/json".toMediaType()))
                    .build()

                val response = httpClient.newCall(request).execute()
                val body = response.body?.string()
                val content = if (body != null) extractJsonStr(body, "content") else null
                callback(content)
            } catch (e: Exception) {
                callback(null)
            }
        }
    }

    fun shutdown() {
        apiExecutor.shutdownNow()
    }

    // ── 简易 JSON 字符串提取（不引入第三方 JSON 库） ──

    private fun toJsonStr(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        sb.append("\"")
        return sb.toString()
    }

    private fun extractJsonStr(json: String, key: String): String? {
        val needle = "\"$key\":\""
        val start = json.indexOf(needle)
        if (start == -1) return null
        var i = start + needle.length
        val sb = StringBuilder()
        while (i < json.length) {
            val c = json[i]
            if (c == '\\') {
                i++
                if (i < json.length) {
                    when (json[i]) {
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        'u' -> { i += 4 } // skip unicode escapes
                        else -> sb.append(json[i])
                    }
                }
            } else if (c == '"') {
                break
            } else {
                sb.append(c)
            }
            i++
        }
        val result = sb.toString().trim()
        return result.ifEmpty { null }
    }
}
