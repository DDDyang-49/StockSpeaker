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
    val apiUrl: String = "https://api.deepseek.com/v1/chat/completions",
    val model: String = "deepseek-chat",
    val summaryInterval: Int = 5
)

class AIAnalyzer(
    private val aiConfigProvider: () -> AiConfig,
    private val onLog: (String) -> Unit = {}
) {
    private val recentData = ArrayDeque<MarketSnapshot>(20)
    private var lastLargeAskCount = 0
    private var lastLargeBidCount = 0
    private val apiExecutor = Executors.newSingleThreadExecutor()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // ── AI 总结多样式轮换 ──

    private val analysisStyles = listOf(
        "股价走势" to "用2-3句口语分析今日该股股价波动特征，像老股民跟朋友聊盘面，不超过60字。",
        "消息面" to "用2-3句口语聊聊该股可能受什么消息影响，像老股民跟朋友分析，不超过60字。直接说看法。",
        "买卖点" to "用2-3句口语说说买卖时机判断，像老股民跟朋友交流操作思路，不超过60字。直接了当。",
        "资金动向" to "用2-3句口语分析资金流向和主力意图，像老股民跟朋友拆解盘面，不超过60字。",
        "技术形态" to "用2-3句口语分析技术形态和趋势信号，像老股民跟朋友聊技术，不超过60字。",
        "风险机会" to "用2-3句口语提示风险和机会，像老股民跟朋友提个醒，不超过60字。直接说重点。"
    )
    private var styleIndex = 0

    /** 喂入一条行情数据，返回本次检测到的异动列表（实时检测，无冷却） */
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

    /** 构建发给 LLM 的提示词（轮换不同分析角度 + 消息面 + 异动统计） */
    private fun buildPrompt(context: MarketContext = MarketContext()): String {
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

        val changeStr = if (latest.changePct > 0) "+${latest.changePct}%" else "${latest.changePct}%"
        val (styleName, stylePrompt) = analysisStyles[styleIndex % analysisStyles.size]
        styleIndex++

        return buildString {
            append("${latest.price}元，$changeStr，")
            append("近30秒${trend}，量比${latest.volRatio}，成交${latest.amountStr}。")
            if (context.alertStats.isNotBlank()) append("异动：${context.alertStats}。")
            if (context.newsHeadline.isNotBlank()) append("消息：${context.newsHeadline}。")
            if (context.fundFlow.isNotBlank()) append("资金：${context.fundFlow}${context.fundFlowAmount}。")
            append(stylePrompt)
        }
    }

    /** 异步调用 LLM API，结果通过 callback 返回（在 apiExecutor 线程执行） */
    fun generateSummary(context: MarketContext = MarketContext(), callback: (String?) -> Unit) {
        val config = aiConfigProvider()
        if (!config.enabled || config.apiKey.isBlank()) {
            callback(null)
            return
        }

        val prompt = buildPrompt(context)
        if (prompt.isEmpty()) {
            callback(null)
            return
        }

        apiExecutor.execute {
            try {
                onLog("🤖 AI: 调用 ${config.model}...")
                val json = """
                    |{
                    |  "model": "${config.model}",
                    |  "messages": [
                    |    {"role": "system", "content": "你是老股民，用1-2句口语点评盘面，不超过50字。像朋友聊天那样自然。不要'当前''根据数据'等套话，直接说出你的判断。"},
                    |    {"role": "user", "content": ${toJsonStr(prompt)}}
                    |  ],
                    |  "max_tokens": 80,
                    |  "temperature": 0.7
                    |}
                """.trimMargin()

                val request = Request.Builder()
                    .url(config.apiUrl)
                    .header("Authorization", "Bearer ${config.apiKey}")
                    .header("Content-Type", "application/json")
                    .post(json.toRequestBody("application/json".toMediaType()))
                    .build()

                val response = httpClient.newCall(request).execute()
                val body = response.body?.string()
                if (!response.isSuccessful) {
                    onLog("🤖 AI: ✗ HTTP ${response.code} ${body?.take(60) ?: ""}")
                    callback(null)
                    return@execute
                }
                val content = if (body != null) extractJsonStr(body, "content") else null
                if (content != null) {
                    onLog("🤖 AI: ✓ ${content.take(40)}...")
                } else {
                    onLog("🤖 AI: ✗ 解析响应失败")
                }
                callback(content)
            } catch (e: Exception) {
                onLog("🤖 AI: ✗ ${e.message?.take(50) ?: "未知错误"}")
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
