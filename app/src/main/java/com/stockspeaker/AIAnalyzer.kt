package com.stockspeaker

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

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

// ── 双AI 结构化立场 ──

data class StanceResult(
    val stance: Int,    // 1=多, 0=震荡, -1=空
    val reason: String  // ≤15字简短理由
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
    private val onLog: (String) -> Unit = {},
    private val aiTwoConfigProvider: () -> AiConfig = { AiConfig() }
) {
    private val recentData = ArrayDeque<MarketSnapshot>(20)
    private var lastLargeAskCount = 0
    private var lastLargeBidCount = 0
    private val apiExecutor = Executors.newSingleThreadExecutor()
    private val dualExecutor = Executors.newFixedThreadPool(2)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    @Volatile private var cachedDualAnalysis: String? = null

    // ── 技术角度标签（供 prompt 参考，不随机选择，让 AI 自行判断重点） ──

    private val techAngles = listOf(
        "量价关系", "盘口博弈", "资金意图", "技术形态",
        "分时特征", "短线情绪", "主力动向", "关键价位",
        "趋势强弱", "变盘信号", "支撑压力", "筹码分布"
    )

    // ── AI 人格轮换（4种角色，让点评语气多变） ──

    private val personalities = listOf(
        "你是老股民，用口语点评盘面。像朋友聊天那样自然，直接说出判断。",
        "你是游资操盘手，从短线博弈角度犀利点评。说话干脆利落，一针见血。",
        "你是基本面研究员，从价值角度审视盘面异动。理性冷静，言之有物。",
        "你是技术派交易员，从K线和量价角度点评。说话带着盯盘的感觉，接地气。"
    )
    private var personalityIndex = 0

    // ── 消息面池（30+条，按时间+股票代码混合选择，保证多变） ──

    private val newsPool = listOf(
        "板块轮动加速，资金高低切换明显",
        "北向资金今日净买入，权重股获青睐",
        "行业政策面利好频出，市场情绪回暖",
        "量能持续萎缩，短线博弈加剧",
        "大单资金午后异动，游资活跃度提升",
        "主力资金净流入板块龙头，跟风盘增多",
        "市场分歧加大，多空博弈激烈",
        "外围市场走强，情绪传导至A股",
        "机构调仓迹象明显，部分品种遭减持",
        "业绩预告窗口期，资金谨慎观望",
        "技术面出现底背离信号，抄底资金试探",
        "政策利好预期升温，相关板块受关注",
        "大宗交易溢价成交，机构抢筹迹象",
        "融资余额连续上升，杠杆资金活跃",
        "量化资金高频进出，盘口波动加大",
        "ETF资金持续流入，被动配置需求强",
        "解禁压力临近，部分资金提前出逃",
        "业绩超预期个股获追捧，联动效应",
        "行业龙头发布回购公告，信心提振",
        "宏观数据好于预期，顺周期板块走强",
        "地缘风险缓释，市场风险偏好回升",
        "汇率波动加大，出口导向型企业承压",
        "社保基金增持消息，长线资金入场",
        "短线游资获利出逃，高位股承压",
        "次新股活跃，投机氛围浓厚",
        "股指期货贴水收窄，市场预期改善",
        "两融余额创新高，多空对赌加剧",
        "机构研报密集推荐，关注度提升",
        "产业链上下游联动，景气度传导",
        "定增解禁到期，短期抛压增加",
        "高送转预期升温，投机资金涌入",
        "大宗商品价格波动，相关个股异动"
    )

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

    /** 选取下一条消息面（带时间+代码混合因子，保证每次不同） */
    fun pickNews(code: String): String {
        val seed = (code.hashCode().ushr(16) + (System.currentTimeMillis() / 30000).toInt()) % newsPool.size
        val idx = (seed + Random.nextInt(0, newsPool.size)) % newsPool.size
        return newsPool[idx]
    }

    // ═══════════════════════════════════════════
    // 双AI 混沌分析
    // ═══════════════════════════════════════════

    fun getRecentSnapshots(): List<MarketSnapshot> = recentData.toList()

    fun shouldTriggerDualAnalysis(): Boolean {
        if (recentData.size < 10) return false
        return calculateChaosScore(recentData.toList()) >= 4
    }

    fun calculateChaosScore(snapshots: List<MarketSnapshot>): Int {
        if (snapshots.isEmpty()) return 0
        var score = 0
        val latest = snapshots.last()

        // 1. 放量滞涨/滞跌：量比大但价格几乎不动 = 筹码交换剧烈但没方向
        if (latest.volRatio > 1.3 && Math.abs(latest.changePct) < 0.3) score += 2

        // 2. 多空对垒：买盘和卖盘同时出现大单堆积
        if (latest.largeAsksCount >= 3 && latest.largeBidsCount >= 3) score += 1

        // 3. 分时锯齿：价格反复穿越均线，方向混乱
        if (snapshots.size >= 10) {
            var crosses = 0
            val avgPrice = snapshots.map { it.price }.average()
            for (i in 1 until snapshots.size) {
                val prevDiff = snapshots[i - 1].price - avgPrice
                val currDiff = snapshots[i].price - avgPrice
                if (prevDiff * currDiff < 0) crosses++
            }
            if (crosses >= 4) score += 2
        }

        // 4. 振幅极小但成交不缩：压抑，可能酝酿突破
        if (Math.abs(latest.changePct) < 0.15 && latest.volRatio in 0.9..1.2) score += 1

        return score
    }

    fun getCachedDualAnalysis(): String? = cachedDualAnalysis
    fun clearCachedDualAnalysis() { cachedDualAnalysis = null }

    /** 双AI并行分析 + 预生成缓存 */
    fun generateDualAnalysis(
        snapshots: List<MarketSnapshot>,
        dailyHistory: String = "",
        shanghaiIndex: String = "",
        callback: (String?) -> Unit
    ) {
        val configA = aiConfigProvider()
        val configB = aiTwoConfigProvider()
        if (!configA.enabled || configA.apiKey.isBlank()) { callback(null); return }
        val useB = configB.enabled && configB.apiKey.isNotBlank()

        apiExecutor.execute {
            val latch = java.util.concurrent.CountDownLatch(if (useB) 2 else 1)
            var resultA: StanceResult? = null
            var resultB: StanceResult? = null

            // AI-A：技术面（主 AI，通常用好模型）
            dualExecutor.execute {
                try {
                    val r = callAiForStance(configA, buildTechPrompt(snapshots, dailyHistory, shanghaiIndex))
                    resultA = r
                    onLog("双AI-A: ${if (r != null) "stance=${r.stance} ${r.reason}" else "失败"}")
                } catch (_: Exception) { onLog("双AI-A: 异常") }
                latch.countDown()
            }

            // AI-B：资金面（辅 AI，可用便宜模型）
            if (useB) {
                dualExecutor.execute {
                    try {
                        val r = callAiForStance(configB, buildFundPrompt(snapshots, dailyHistory, shanghaiIndex))
                        resultB = r
                        onLog("双AI-B: ${if (r != null) "stance=${r.stance} ${r.reason}" else "失败"}")
                    } catch (_: Exception) { onLog("双AI-B: 异常") }
                    latch.countDown()
                }
            }

            val ok = latch.await(8, java.util.concurrent.TimeUnit.SECONDS)
            if (!ok) onLog("双AI: 超时 A=${resultA != null} B=${resultB != null}")

            val text = synthesize(resultA, resultB)
            if (text != null) {
                onLog("双AI合成: ${text.take(40)}...")
                cachedDualAnalysis = text
            }
            callback(text)
        }
    }

    // ── 构建提示词 ──

    private fun buildTechPrompt(snapshots: List<MarketSnapshot>, dailyHistory: String = "", shanghaiIndex: String = ""): String {
        val latest = snapshots.last()
        val trend = describeTrend(snapshots)
        return buildString {
            append("${latest.price}元，涨跌${latest.changePct}%，量比${latest.volRatio}，")
            append("成交${latest.amountStr}，近30秒${trend}。")
            append("卖盘${latest.largeAsksCount}档大单，买盘${latest.largeBidsCount}档大单。")
            if (dailyHistory.isNotBlank()) append("近5日K线：${dailyHistory}。")
            if (shanghaiIndex.isNotBlank()) append("大盘：${shanghaiIndex}。")
            append("综合技术面、K线形态、盘口博弈和大盘环境，判断多空方向。")
        }
    }

    private fun buildFundPrompt(snapshots: List<MarketSnapshot>, dailyHistory: String = "", shanghaiIndex: String = ""): String {
        val latest = snapshots.last()
        val trend = describeTrend(snapshots)
        val fundDir = if (latest.changePct > 0) "偏流入" else if (latest.changePct < 0) "偏流出" else "平衡"
        return buildString {
            append("${latest.price}元，涨跌${latest.changePct}%，量比${latest.volRatio}，")
            append("成交${latest.amountStr}，资金${fundDir}，近30秒${trend}。")
            append("卖盘${latest.largeAsksCount}单对买盘${latest.largeBidsCount}单。")
            if (dailyHistory.isNotBlank()) append("近日K线：${dailyHistory}。")
            if (shanghaiIndex.isNotBlank()) append("大盘：${shanghaiIndex}。")
            append("综合资金流向、主力意图、近日K线趋势和大盘环境，判断多空方向。")
        }
    }

    private fun describeTrend(snapshots: List<MarketSnapshot>): String {
        if (snapshots.size < 2) return "平稳"
        val first = snapshots.first()
        val latest = snapshots.last()
        val delta = latest.price - first.price
        return when {
            delta > 0.5 -> "持续上涨"
            delta > 0 -> "小幅上涨"
            delta < -0.5 -> "持续下跌"
            delta < 0 -> "小幅下跌"
            else -> "横盘震荡"
        }
    }

    // ── 调用 AI 获取结构化立场 ──

    private fun callAiForStance(config: AiConfig, prompt: String): StanceResult? {
        val systemPrompt = "你是一个短线盘面分析助手。只看多空方向，不做完整分析。" +
            "必须严格按JSON格式输出：{\"stance\":1,\"reason\":\"简要理由\"}。" +
            "stance: 1=偏多, 0=震荡, -1=偏空。reason不超过15字。不要输出任何其他内容。"

        return try {
            val json = """
                |{
                |  "model": "${config.model}",
                |  "messages": [
                |    {"role": "system", "content": "${toJsonStr(systemPrompt)}"},
                |    {"role": "user", "content": ${toJsonStr(prompt)}}
                |  ],
                |  "max_tokens": 40,
                |  "temperature": 0.3
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
            if (!response.isSuccessful) { onLog("双AI HTTP ${response.code}"); return null }

            val content = if (body != null) extractJsonStr(body, "content") else null
            if (content != null) parseStanceResult(content) else null
        } catch (e: Exception) {
            onLog("双AI调用失败: ${e.message?.take(40)}")
            null
        }
    }

    /** 解析AI返回的结构化JSON，清洗Markdown包裹 */
    private fun parseStanceResult(raw: String): StanceResult? {
        val clean = raw
            .replace("```json", "")
            .replace("```", "")
            .replace("`", "")
            .trim()
        val stance = extractJsonInt(clean, "stance") ?: return null
        if (stance !in -1..1) return null
        val reason = extractJsonStr(clean, "reason") ?: return null
        return StanceResult(stance, reason.take(15))
    }

    private fun extractJsonInt(json: String, key: String): Int? {
        val needle = "\"$key\":"
        val start = json.indexOf(needle)
        if (start == -1) return null
        val sub = json.substring(start + needle.length).trim()
        val end = sub.indexOfFirst { it == ',' || it == '}' || it == '\n' }
        return if (end > 0) sub.substring(0, end).trim().toIntOrNull()
        else sub.trim().toIntOrNull()
    }

    // ── 合成层：根据双方立场组合输出 ──

    private fun synthesize(a: StanceResult?, b: StanceResult?): String? {
        if (a == null && b == null) return null

        // 单边存活
        if (b == null) return singleView(a!!, "技术面")
        if (a == null) return singleView(b, "资金面")

        // 一致看多
        if (a.stance == 1 && b.stance == 1) {
            val patterns = listOf(
                "技术面和资金面一致看多，${a.reason}，${b.reason}。",
                "双维度共振偏多——技术面${a.reason}，资金面也${b.reason}。",
                "两路AI都偏多：${a.reason}，同时${b.reason}。"
            )
            return patterns[Random.nextInt(patterns.size)]
        }

        // 一致看空
        if (a.stance == -1 && b.stance == -1) {
            val patterns = listOf(
                "技术面和资金面一致偏空，${a.reason}，${b.reason}。",
                "双维度共振偏空——技术面${a.reason}，资金面也${b.reason}。",
                "两路AI都偏空：${a.reason}，同时${b.reason}。"
            )
            return patterns[Random.nextInt(patterns.size)]
        }

        // 一致震荡
        if (a.stance == 0 && b.stance == 0) {
            val patterns = listOf(
                "技术面和资金面都看震荡，${a.reason}，暂时没有明确方向。",
                "两路AI一致判断：${a.reason}，盘面在等方向。",
                "多空都没有明显优势——${a.reason}。"
            )
            return patterns[Random.nextInt(patterns.size)]
        }

        // 多空分歧（最核心的场景）
        if ((a.stance == 1 && b.stance == -1) || (a.stance == -1 && b.stance == 1)) {
            val bull = if (a.stance == 1) a else b
            val bear = if (a.stance == -1) a else b
            val patterns = listOf(
                "盘面分歧很大！技术面看${bull.reason}，但资金面看${bear.reason}，多空在较劲。",
                "注意，两路AI出现分歧——一方认为${bull.reason}，另一方觉得${bear.reason}，说明方向不明。",
                "多空博弈激烈：${bull.reason}，但${bear.reason}。这种时候方向选择最危险。"
            )
            return patterns[Random.nextInt(patterns.size)]
        }

        // 一方震荡，另一方有方向
        val directed = if (a.stance != 0) a else b
        val sideways = if (a.stance == 0) a else b
        val dirLabel = if (directed.stance == 1) "偏多" else "偏空"
        val patterns = listOf(
            "${sideways.reason}，但${dirLabel}信号也在积累——${directed.reason}。",
            "整体${sideways.reason}，不过${dirLabel}力量在酝酿：${directed.reason}。",
            "盘面偏混沌，${sideways.reason}，但${directed.reason}值得留意。"
        )
        return patterns[Random.nextInt(patterns.size)]
    }

    private fun singleView(r: StanceResult, source: String): String {
        val dir = when (r.stance) { 1 -> "偏多"; -1 -> "偏空"; else -> "震荡" }
        val patterns = listOf(
            "${source}判断${dir}：${r.reason}。",
            "从${source}看，${dir}——${r.reason}。",
            "${source}信号${dir}，${r.reason}。"
        )
        return patterns[Random.nextInt(patterns.size)]
    }

    // ── 构建深度分析提示词（AI 自主判断关键信号，不限定角度） ──
    private fun buildPrompt(context: MarketContext = MarketContext(), alertContext: String = ""): String {
        if (recentData.isEmpty()) return ""
        val latest = recentData.last()
        val history = recentData.toList()

        // 趋势判定
        val first = history.first()
        val delta = latest.price - first.price
        val trend = when {
            delta > 0.5 -> "持续上涨"; delta > 0 -> "小幅上涨"
            delta < -0.5 -> "持续下跌"; delta < 0 -> "小幅下跌"
            else -> "横盘震荡"
        }

        // 日内振幅
        val highPrice = history.maxOf { it.price }
        val lowPrice = history.minOf { it.price }
        val amplitude = if (lowPrice > 0) "%.2f".format((highPrice - lowPrice) / lowPrice * 100) else "0"

        // 量能判断
        val volDesc = when {
            latest.volRatio >= 2.5 -> "剧烈放量"
            latest.volRatio >= 1.5 -> "明显放量"
            latest.volRatio >= 1.1 -> "温和放量"
            latest.volRatio <= 0.4 -> "极度缩量"
            latest.volRatio <= 0.7 -> "偏缩量"
            else -> "量能正常"
        }

        // 量价关系
        val volPriceRel = when {
            latest.changePct > 0.3 && latest.volRatio > 1.3 -> "价升量增，多头主动"
            latest.changePct > 0.3 && latest.volRatio < 0.8 -> "价升量缩，上攻乏力有背离"
            latest.changePct < -0.3 && latest.volRatio > 1.3 -> "价跌量增，空头打压"
            latest.changePct < -0.3 && latest.volRatio < 0.8 -> "价跌量缩，抛压减轻"
            Math.abs(latest.changePct) < 0.15 && latest.volRatio > 1.5 -> "放量滞涨，多空分歧大"
            Math.abs(latest.changePct) < 0.08 && latest.volRatio < 0.5 -> "缩量横盘，变盘前兆"
            else -> "量价配合正常"
        }

        // 盘口博弈
        val orderBook = buildString {
            if (latest.largeAsksCount > 0 || latest.largeBidsCount > 0) {
                if (latest.largeAsksCount > 0) append("卖盘${latest.largeAsksCount}档大单")
                if (latest.largeAsksCount > 0 && latest.largeBidsCount > 0) append("对")
                if (latest.largeBidsCount > 0) append("买盘${latest.largeBidsCount}档大单")
                append(when {
                    latest.largeAsksCount > latest.largeBidsCount + 2 -> "，空方占优"
                    latest.largeBidsCount > latest.largeAsksCount + 2 -> "，多方占优"
                    else -> "，多空均衡"
                })
            }
        }

        // 分时特征：价格穿越均线次数（锯齿程度）
        val avgPrice = history.map { it.price }.average()
        var crosses = 0
        for (i in 1 until history.size) {
            if ((history[i-1].price - avgPrice) * (history[i].price - avgPrice) < 0) crosses++
        }
        val jagDesc = when { crosses >= 5 -> "分时锯齿状，反复穿越均线"; crosses >= 3 -> "分时有震荡"; else -> "" }

        val changeStr = if (latest.changePct > 0) "+${latest.changePct}%" else "${latest.changePct}%"

        return buildString {
            append("${latest.price}元，$changeStr，近30秒${trend}。")
            append("量比${latest.volRatio}（${volDesc}），成交${latest.amountStr}。")
            append("量价关系：${volPriceRel}。")
            append("日内振幅${amplitude}%。")
            if (orderBook.isNotEmpty()) append("盘口：${orderBook}。")
            if (jagDesc.isNotEmpty()) append(jagDesc + "。")
            if (alertContext.isNotBlank()) append(alertContext)
            if (context.alertStats.isNotBlank()) append("近期异动：${context.alertStats}。")
            if (context.newsHeadline.isNotBlank()) append("消息面：${context.newsHeadline}。")
            if (context.fundFlow.isNotBlank()) append("资金：${context.fundFlow}${context.fundFlowAmount}。")
            append("从以上数据中挑最值得说的1-2个关键信号，用2-4句口语点评。")
            append("优先关注量价背离、盘口异动、变盘信号。像老股民跟朋友聊盘面，直接说出判断。不超过80字。")
        }
    }

    /** 异动后的专用深度分析提示词 */
    fun buildPostAlertPrompt(alertText: String, snapshots: List<MarketSnapshot>): String {
        if (snapshots.isEmpty()) return ""
        val latest = snapshots.last()
        val trend = if (snapshots.size >= 2) {
            val delta = latest.price - snapshots.first().price
            when { delta > 0.3 -> "拉升"; delta < -0.3 -> "回落"; else -> "震荡" }
        } else "波动"

        return buildString {
            append("刚发生异动：${alertText.take(60)}。")
            append("异动后当前${latest.price}元，${trend}中。")
            append("量比${latest.volRatio}，成交${latest.amountStr}。")
            append("卖盘${latest.largeAsksCount}档大单，买盘${latest.largeBidsCount}档大单。")
            append("分析这波异动可能是什么意图？后续走势怎么看？")
            append("用2-3句口语点评，像老股民复盘异动，不超过60字。")
        }
    }

    /** 将长文本发给辅AI拆分为分批播报的短句 */
    fun splitIntoBatches(fullText: String, callback: (List<String>) -> Unit) {
        val configB = aiTwoConfigProvider()
        if (!configB.enabled || configB.apiKey.isBlank() || fullText.length <= 60) {
            // 辅AI不可用时直接用句号拆分
            val parts = fullText.split(Regex("[。！？]"))
                .map { it.trim() }.filter { it.isNotEmpty() && it.length > 3 }
            callback(if (parts.size > 1) parts else listOf(fullText))
            return
        }

        apiExecutor.execute {
            try {
                val systemPrompt = "你是文本拆分助手。严格按JSON数组格式输出，不要其他内容。"
                val userPrompt = "将以下股评拆分为2-3段独立的口语短句，每段15-35字，适合语音播报。直接返回JSON数组：[\"段1\",\"段2\",\"段3\"]。原文：${fullText}"
                val json = """
                    |{
                    |  "model": "${configB.model}",
                    |  "messages": [
                    |    {"role": "system", "content": "${toJsonStr(systemPrompt)}"},
                    |    {"role": "user", "content": "${toJsonStr(userPrompt)}"}
                    |  ],
                    |  "max_tokens": 80,
                    |  "temperature": 0.2
                    |}
                """.trimMargin()

                val request = Request.Builder()
                    .url(configB.apiUrl)
                    .header("Authorization", "Bearer ${configB.apiKey}")
                    .header("Content-Type", "application/json")
                    .post(json.toRequestBody("application/json".toMediaType()))
                    .build()

                val response = httpClient.newCall(request).execute()
                val body = response.body?.string()
                if (!response.isSuccessful) {
                    callback(listOf(fullText)); return@execute
                }
                val content = body?.let { extractJsonStr(it, "content") } ?: run { callback(listOf(fullText)); return@execute }
                val cleaned = content.replace("```json", "").replace("```", "").trim()
                val start = cleaned.indexOf('[')
                val end = cleaned.lastIndexOf(']')
                if (start == -1 || end == -1) { callback(listOf(fullText)); return@execute }
                val arrStr = cleaned.substring(start, end + 1)
                val items = Regex("\"([^\"]*)\"").findAll(arrStr).map { it.groupValues[1] }.toList()
                callback(if (items.isNotEmpty()) items else listOf(fullText))
            } catch (_: Exception) {
                callback(listOf(fullText))
            }
        }
    }

    /** 深度分析：综合技术面+盘口博弈+K线+大盘，AI自主判断关键信号 */
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

        // 轮换人格
        val persona = personalities[personalityIndex % personalities.size]
        personalityIndex++

        val systemPrompt = buildString {
            append(persona)
            append("你盯盘20年，精通量价关系、波浪结构、盘口博弈、筹码分布。")
            append("从技术面+盘口博弈角度，挑盘面最值得说的1-2个信号点评。")
            append("如果看到量价背离、关键位突破/失守、盘口异动，优先说。")
            append("用2-4句口语，像跟朋友聊盘面。不超过80字。")
            append("不要'当前''根据数据'套话，直接说出判断。")
        }

        apiExecutor.execute {
            try {
                onLog("🤖 AI: 深度分析 ${config.model}...")
                val json = """
                    |{
                    |  "model": "${config.model}",
                    |  "messages": [
                    |    {"role": "system", "content": "${toJsonStr(systemPrompt)}"},
                    |    {"role": "user", "content": ${toJsonStr(prompt)}}
                    |  ],
                    |  "max_tokens": 120,
                    |  "temperature": 0.8
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
                val content = body?.let { extractJsonStr(it, "content") }
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

    /** 异动后专用AI分析（独立prompt，强调异动背景） */
    fun generatePostAlertAnalysis(alertText: String, snapshots: List<MarketSnapshot>, callback: (String?) -> Unit) {
        val config = aiConfigProvider()
        if (!config.enabled || config.apiKey.isBlank()) {
            callback(null)
            return
        }

        val prompt = buildPostAlertPrompt(alertText, snapshots)
        if (prompt.isEmpty()) { callback(null); return }

        val persona = personalities[personalityIndex % personalities.size]
        personalityIndex++
        val systemPrompt = "${persona}用2-3句口语复盘这波异动，不超过60字。像老股民看到异动后的第一反应。"

        apiExecutor.execute {
            try {
                onLog("🤖 AI: 异动复盘...")
                val json = """
                    |{
                    |  "model": "${config.model}",
                    |  "messages": [
                    |    {"role": "system", "content": "${toJsonStr(systemPrompt)}"},
                    |    {"role": "user", "content": ${toJsonStr(prompt)}}
                    |  ],
                    |  "max_tokens": 80,
                    |  "temperature": 0.8
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
                if (!response.isSuccessful) { callback(null); return@execute }
                val content = body?.let { extractJsonStr(it, "content") }
                if (content != null) onLog("🤖 AI: ✓ ${content.take(40)}...")
                else onLog("🤖 AI: ✗ 解析响应失败")
                callback(content)
            } catch (e: Exception) {
                onLog("🤖 AI: ✗ ${e.message?.take(50) ?: "未知错误"}")
                callback(null)
            }
        }
    }

    fun shutdown() {
        apiExecutor.shutdownNow()
        dualExecutor.shutdownNow()
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
