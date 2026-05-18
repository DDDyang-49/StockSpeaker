package com.stockspeaker

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
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
    val largeBidsCount: Int,
    val turnover: Double
)

// ── 持仓建议（四选一） ──

enum class Stance(val label: String) {
    HOLD("坚定格局"),
    SELL_HIGH("逢高减仓·倒T"),
    BUY_LOW("逢低吸筹·正T"),
    EXIT("危险清仓");

    val bracketLabel: String get() = "【$label】"

    companion object {
        fun fromContent(content: String): Stance? {
            val head = content.take(8)
            return when {
                "【坚定格局】" in content || "坚定格局" in head -> HOLD
                "【逢高减仓" in content || "逢高减仓" in head -> SELL_HIGH
                "【逢低吸筹" in content || "逢低吸筹" in head -> BUY_LOW
                "【危险清仓】" in content || "危险清仓" in head -> EXIT
                else -> null
            }
        }
    }
}

// ── 视角轮盘（多视角分析，避免听感疲劳） ──

enum class Perspective(val label: String, val weight: Int) {
    TRADER("游资主力", 70),
    PSYCHOLOGIST("心理学家", 10),
    PHILOSOPHER("哲学家", 10),
    METAPHYSICIAN("玄学家", 10);

    companion object {
        private val totalWeight = entries.sumOf { it.weight }

        /** 按权重随机选一个视角 */
        fun pick(): Perspective {
            var roll = Random.nextInt(totalWeight)
            for (p in entries) {
                roll -= p.weight
                if (roll < 0) return p
            }
            return TRADER
        }
    }
}

// ── 异动模式 ──

enum class PatternType {
    VOLUME_BREAKOUT,   // 放量突破 / 平地惊雷
    SPEED_ALERT,       // 涨速异动
    SHARP_REVERSAL,    // 高位急转
    FAKE_BULL,         // 诱多：买单凶猛但价格滞涨
    SILENT_DROP,        // 无量空跌：缩量阴跌无承接
    ANTS_PULL_UP        // 蚂蚁搬家：中小单连续扫货点火
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
    val apiUrl: String = "https://api.edgefn.net/v1/chat/completions",
    val model: String = "Qwen3-235B-A22B-2507",
    val thinkingModel: String = "",
    val summaryInterval: Int = 5,
    val provider: String = "edgefn"
)

class AIAnalyzer(
    private val aiConfigProvider: () -> AiConfig,
    private val onLog: (String) -> Unit = {},
    private val aiTwoConfigProvider: () -> AiConfig = { AiConfig() }
) {
    private val recentData = ArrayDeque<MarketSnapshot>(90)
    private val apiExecutor = Executors.newSingleThreadExecutor()
    private val dualExecutor = Executors.newFixedThreadPool(2)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    @Volatile private var cachedDualAnalysis: String? = null
    @Volatile var lastStance: Stance? = null  // 上次持仓建议（四选一枚举）

    // ── 固定人设：持仓防守 + 做T闭环（短线游资视角） ──
    private val FIXED_PERSONA = "你是一个手握重金的A股一线游资，现在的任务是帮我进行【持仓防守与做T闭环】。" +
        "你的交易体系：买入不急，卖出坚决，低吸有据。绝不和股票谈恋爱。以板块共振判断去留，以盘口量价背离寻找买卖点。" +
        "第一句话必须冷酷地给出持仓建议（四选一）：" +
        "【坚定格局】（多头强势，锁仓不动）、" +
        "【逢高减仓·倒T】（拉升乏力/诱多/无量空涨，提示卖点）、" +
        "【逢低吸筹·正T】（急跌错杀/强支撑洗盘，提示低吸买点）、" +
        "【危险清仓】（板块退潮/资金真砸，坚决离场）。" +
        "风格：像交易主管在指导持仓研究员，杀伐果断，直击要害，指出盘面最危险的隐患或最强的支撑依据。" +
        "【铁律1：板块去留】必须对比该股与所属题材的强弱。如果板块大涨而它放量滞涨，必须判定为跟风杂毛，提示清仓换股；如果板块大跌它抗跌，提示承接强可格局。" +
        "【铁律2：做T闭环】倒T：短线急速拉升但上方压单死活吃不透（或量能跟不上）→『日内高抛做T』。正T：急跌但下方托单密集承接有力（或缩量回踩强支撑）→『逢低吸筹做T』。" +
        "【铁律3：底部异动】如果它处于水下或横盘，突然出现连续买单推升且量比放大，提示『有资金点火，观察承接』，切忌盲目割肉。" +
        "【铁律4：四象限资金防伪】严格按以下逻辑判断主力真实意图——" +
        "①净流入+涨速>0.5%=主力真拉升（格局）；" +
        "②净流入+涨速<0=上方抛压大，主力暗中派发（警惕/减仓）；" +
        "③净流出+涨速>0.5%=无量空涨/散户推升，缺乏大单支持，随时跳水（逢高倒T）；" +
        "④净流出+涨速<-0.5%=主力真砸盘（危险清仓）。" +
        "【铁律5：Alpha差值】必须根据个股涨幅-板块涨幅的Alpha差值判断个股地位。差值>0说明强于板块，给予更多容忍度；差值<0判定为跟风杂毛，遇阻坚决提示减仓或清仓。" +
        "【铁律6：流通市值】流通市值<50亿极易被游资操控，须警惕假突破；>200亿需要板块大级别资金共振才可持续。"

    // ── 多视角 persona（不用专业术语，老百姓听得懂） ──

    private val PERSONA_MAP = mapOf(
        Perspective.TRADER to FIXED_PERSONA,
        Perspective.PSYCHOLOGIST to "你是一个善于观察人性的股市心理学家。你的特长是解读市场参与者的情绪和心理状态。" +
            "你关注的是：散户的恐慌和贪婪什么时候到极端、从众心理什么时候会翻车、" +
            "市场上大多数人现在是什么心态（害怕？兴奋？犹豫？）。" +
            "第一句话必须给出持仓建议（四选一）：" +
            "【坚定格局】/【逢高减仓·倒T】/【逢低吸筹·正T】/【危险清仓】。" +
            "风格：像一个温和但敏锐的心理咨询师，用生活化的比喻讲市场心理。" +
            "比如：\"现在散户就像超市打折时抢货的大妈\"、\"恐慌情绪已经到了挤地铁踩踏的程度\"。" +
            "不要用\"贝塔系数\"、\"行为金融学\"这类术语，说人话。不超过80字。",
        Perspective.PHILOSOPHER to "你是一个炒股多年的哲学爱好者。你的特长是用朴素的辩证法看股市。" +
            "你关注的是：物极必反（涨多了必跌、跌多了必涨）、否极泰来（最黑暗的时候往往是转机）、" +
            "矛盾的主次方面（多空力量谁占主导、什么时候会翻转）。" +
            "第一句话必须给出持仓建议（四选一）：" +
            "【坚定格局】/【逢高减仓·倒T】/【逢低吸筹·正T】/【危险清仓】。" +
            "风格：像一个爱讲道理的老股民，说话带点哲理但不掉书袋。" +
            "比如：\"涨得越猛离摔跤越近\"、\"现在是黎明前最黑的时候，但也可能是暴风雨的开始\"。" +
            "不要用\"辩证法\"、\"矛盾论\"这些学术词，用大白话讲道理。不超过80字。",
        Perspective.METAPHYSICIAN to "你是一个浸淫股市二十多年的老股民，满嘴\"玄学口诀\"。你的特长是用老股民的经验之谈看盘。" +
            "你关注的是：量价关系的\"玄学\"规律（量在价先、天量天价、地量地价）、" +
            "时间窗口（连跌三天该反弹了、涨了五天该歇歇了）、" +
            "盘感直觉（早盘跳空高开要小心、尾盘拉升次日大概率低开）。" +
            "第一句话必须给出持仓建议（四选一）：" +
            "【坚定格局】/【逢高减仓·倒T】/【逢低吸筹·正T】/【危险清仓】。" +
            "风格：像营业部里那个天天盯盘的老股民，说话带点\"玄学\"但其实是经验之谈。" +
            "比如：\"缩量三连阴，见底信号来了\"、\"天量见天价，这波差不多到头了\"、\"早盘急拉不追，午后跳水不割\"。" +
            "不要用真正的迷信术语（星座、风水），只用股市老话。不超过80字。"
    )

    /** 根据视角获取 persona prompt */
    private fun getPersona(perspective: Perspective): String =
        PERSONA_MAP[perspective] ?: FIXED_PERSONA

    // ── 消息面池（30+条，按时间+股票代码混合选择，保证多变） ──

    private val newsPool = listOf(
        "板块轮动加速，资金高低切换明显",
        "主力资金暗流涌动，盘口异动频次增加",
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

    /** 喂入一条行情数据，返回本次检测到的异动列表（双模自适应，实时检测，无冷却） */
    fun feed(snapshot: MarketSnapshot): List<Pattern> {
        recentData.addLast(snapshot)
        if (recentData.size > 90) recentData.removeFirst()

        val patterns = mutableListOf<Pattern>()

        // ── 状态判定：热门活跃 vs 底部潜伏 ──
        val isHot = snapshot.turnover > 10.0 || snapshot.volRatio > 1.8

        // ═══════════════════════════════════════
        // 1. 涨速异动 (SPEED_ALERT) —— 双模阈值
        // ═══════════════════════════════════════
        val speedThreshold = if (isHot) 1.5 else 0.8
        val absSpeed = Math.abs(snapshot.speed)
        if (absSpeed >= speedThreshold) {
            val dir = if (snapshot.speed > 0) "拉升" else "下跌"
            patterns.add(Pattern(PatternType.SPEED_ALERT,
                "直线脉冲！当前涨速达${String.format("%.1f", absSpeed)}%，${dir}中。"))
        }

        // ═══════════════════════════════════════
        // 2. 高位急转 (SHARP_REVERSAL) —— 防核按钮与假突破
        // ═══════════════════════════════════════
        if (recentData.size >= 15) {
            val last15 = recentData.takeLast(15)
            val maxPrice = last15.maxOf { it.price }
            val dropFromMax = (maxPrice - snapshot.price) / maxPrice * 100.0
            val heightThreshold = if (isHot) 4.0 else 2.0
            val dropThreshold = if (isHot) 1.5 else 0.8
            if (snapshot.changePct >= heightThreshold && dropFromMax >= dropThreshold && snapshot.volRatio >= 1.0) {
                patterns.add(Pattern(PatternType.SHARP_REVERSAL,
                    "高位急转！冲高后突发放量砸盘，警惕见顶或假突破！"))
            }
        }

        // ═══════════════════════════════════════
        // 3. 底部放量突破 (VOLUME_BREAKOUT) —— 抓潜伏股启动点
        // ═══════════════════════════════════════
        if (!isHot && snapshot.volRatio >= 2.5 && snapshot.speed > 0.5 &&
            snapshot.changePct in 0.0..3.0) {
            patterns.add(Pattern(PatternType.VOLUME_BREAKOUT,
                "平地惊雷！底部突然放量拉升，量比达${String.format("%.1f", snapshot.volRatio)}，注意异动试盘！"))
        }

        // ═══════════════════════════════════════
        // 4. 诱多 (FAKE_BULL) —— 买单凶猛但价格滞涨
        // ═══════════════════════════════════════
        if (recentData.size >= 15) {
            val last15 = recentData.takeLast(15)
            val priceChangePct = Math.abs((snapshot.price - last15.first().price) / last15.first().price * 100)
            var bidIncreaseCount = 0
            for (i in 1 until last15.size) {
                if (last15[i].largeBidsCount > last15[i - 1].largeBidsCount) bidIncreaseCount++
            }
            if (bidIncreaseCount >= 3 && priceChangePct < 0.2 && snapshot.largeAsksCount >= 2) {
                patterns.add(Pattern(PatternType.FAKE_BULL,
                    "盘口异常，买单凶猛但上方压单不减，价格滞涨，谨防主力诱多派发！"))
            }
        }

        // ═══════════════════════════════════════
        // 5. 无量空跌 (SILENT_DROP) —— 下方买盘真空
        // ═══════════════════════════════════════
        if (recentData.size >= 10) {
            val last10 = recentData.takeLast(10)
            val cumChange = (snapshot.price - last10.first().price) / last10.first().price * 100.0
            val dropThreshold = if (isHot) -1.5 else -0.8
            if (cumChange <= dropThreshold && snapshot.volRatio < 0.8 && snapshot.largeBidsCount == 0) {
                patterns.add(Pattern(PatternType.SILENT_DROP,
                    "警报！下方买盘真空，连续缩量阴跌，谨防资金踩踏。"))
            }
        }

        // ═══════════════════════════════════════
        // 6. 蚂蚁搬家拉升 (ANTS_PULL_UP) —— 中小单连续点火
        // ═══════════════════════════════════════
        if (recentData.size >= 10) {
            val last10 = recentData.takeLast(10)
            val cumChange = (snapshot.price - last10.first().price) / last10.first().price * 100.0
            val antThreshold = if (isHot) 1.8 else 1.2
            var bidIncreaseSnapshots = 0
            for (i in 1 until last10.size) {
                if (last10[i].largeBidsCount > last10[i - 1].largeBidsCount) bidIncreaseSnapshots++
            }
            if (cumChange >= antThreshold && bidIncreaseSnapshots < 2) {
                patterns.add(Pattern(PatternType.ANTS_PULL_UP,
                    "注意！多笔中小单连续扫货点火，20秒内区间拉升超${String.format("%.1f", cumChange)}%，准备做T！"))
            }
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
        if (recentData.size < 30) return false
        return calculateChaosScore(recentData.toList()) >= 2
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

        // 5. 横盘无聊：过去10个快照振幅极小且量比缩减 → 触发分析
        if (snapshots.size >= 10) {
            val recent10 = snapshots.takeLast(10)
            val high = recent10.maxOf { it.price }
            val low = recent10.minOf { it.price }
            val amplitude = if (low > 0) (high - low) / low * 100 else 100.0
            val avgVolRatio = recent10.map { it.volRatio }.average()
            if (amplitude < 0.2 && avgVolRatio < 0.8) score += 2
        }

        return score
    }

    fun getCachedDualAnalysis(): String? = cachedDualAnalysis
    fun clearCachedDualAnalysis() { cachedDualAnalysis = null }

    /** 双AI并行分析 + 预生成缓存（含全市场情绪+资金面+板块上下文） */
    fun generateDualAnalysis(
        snapshots: List<MarketSnapshot>,
        dailyHistory: String = "",
        shanghaiIndex: String = "",
        globalSentiment: GlobalSentiment = GlobalSentiment(),
        context: MarketContext = MarketContext(),
        callback: (String?) -> Unit
    ) {
        val configA = aiConfigProvider()
        val configB = aiTwoConfigProvider()
        if (!configA.enabled) { callback(null); return }
        if (configA.apiKey.isBlank()) { onLog("双AI: 主AI未配置API Key"); callback(null); return }
        val useB = configB.enabled && configB.apiKey.isNotBlank()

        onLog(if (useB) "双AI: 主=${configA.provider}/${configA.model} 辅=${configB.provider}/${configB.model}" else "双AI: ${configA.model}（辅AI未启用或未配Key）")
        apiExecutor.execute {
            val latch = java.util.concurrent.CountDownLatch(if (useB) 2 else 1)
            var resultA: StanceResult? = null
            var resultB: StanceResult? = null

            // AI-A：技术面（思考模式，深度推理）
            dualExecutor.execute {
                try {
                    val r = callAiForStance(configA, buildTechPrompt(snapshots, dailyHistory, shanghaiIndex, globalSentiment, context), useThinking = true)
                    resultA = r
                    onLog("双AI-A: ${if (r != null) "stance=${r.stance} ${r.reason}" else "失败"}")
                } catch (_: Exception) { onLog("双AI-A: 异常") }
                latch.countDown()
            }

            // AI-B：资金面（快速模式，便宜模型）
            if (useB) {
                dualExecutor.execute {
                    try {
                        val r = callAiForStance(configB, buildFundPrompt(snapshots, dailyHistory, shanghaiIndex, globalSentiment, context), useThinking = false)
                        resultB = r
                        onLog("双AI-B: ${if (r != null) "stance=${r.stance} ${r.reason}" else "失败"}")
                    } catch (_: Exception) { onLog("双AI-B: 异常") }
                    latch.countDown()
                }
            }

            val ok = latch.await(8, java.util.concurrent.TimeUnit.SECONDS)
            if (!ok) onLog("双AI: 8秒超时 A=${if (resultA != null) "✓" else "✗"} B=${if (resultB != null) "✓" else "✗"}（useB=$useB）")

            val text = synthesize(resultA, resultB)
            if (text != null) {
                onLog("双AI合成: ${text.take(40)}...")
                cachedDualAnalysis = text
            }
            callback(text)
        }
    }

    // ── 构建提示词 ──

    private fun buildTechPrompt(snapshots: List<MarketSnapshot>, dailyHistory: String = "", shanghaiIndex: String = "", sentiment: GlobalSentiment = GlobalSentiment(), context: MarketContext = MarketContext()): String {
        val latest = snapshots.last()
        val trend = describeTrend(snapshots)
        return buildString {
            val slice = sentiment.toSentimentSlice()
            if (slice.isNotBlank()) append("$slice ")
            // 资金面（含四象限防伪标记）
            if (context.fundFlowDirection.isNotBlank()) {
                append("主力${context.fundFlowDirection}${context.fundFlowAmount}")
                if (context.fundFlowQuadrant.isNotBlank()) append("【${context.fundFlowQuadrant}】")
                append("；")
            }
            if (context.mcapContext.isNotBlank()) append("流通市值：${context.mcapContext}；")

            append("${latest.price}元，涨跌${latest.changePct}%，量比${latest.volRatio}，")
            append("成交${latest.amountStr}，近30秒${trend}。")
            append("卖盘${latest.largeAsksCount}档大单，买盘${latest.largeBidsCount}档大单。")
            if (dailyHistory.isNotBlank()) append("近5日K线：${dailyHistory}。")
            if (shanghaiIndex.isNotBlank()) append("大盘：${shanghaiIndex}。")
            if (context.blockInfo.isNotBlank()) append("板块：${context.blockInfo}。")
            val stance = lastStance; if (stance != null) append("你上次判断为${stance.bracketLabel}，请结合最新数据判断是否修正观点。")
            append("综合技术面、K线形态、盘口博弈、资金流向和大盘环境，给出持仓防守建议（四选一）。")
        }
    }

    private fun buildFundPrompt(snapshots: List<MarketSnapshot>, dailyHistory: String = "", shanghaiIndex: String = "", sentiment: GlobalSentiment = GlobalSentiment(), context: MarketContext = MarketContext()): String {
        val latest = snapshots.last()
        val trend = describeTrend(snapshots)
        return buildString {
            val slice = sentiment.toSentimentSlice()
            if (slice.isNotBlank()) append("$slice ")
            // 真实资金数据替代假数据
            val fundDir = if (context.fundFlowDirection.isNotBlank()) context.fundFlowDirection
                else if (latest.changePct > 0) "偏流入" else if (latest.changePct < 0) "偏流出" else "平衡"
            append("${latest.price}元，涨跌${latest.changePct}%，量比${latest.volRatio}，")
            append("成交${latest.amountStr}，资金${fundDir}")
            if (context.fundFlowAmount.isNotBlank()) append("${context.fundFlowAmount}")
            append("，近30秒${trend}。")
            if (context.dragonTigerTag.isNotBlank()) append("${context.dragonTigerTag}。")
            append("卖盘${latest.largeAsksCount}单对买盘${latest.largeBidsCount}单。")
            if (dailyHistory.isNotBlank()) append("近日K线：${dailyHistory}。")
            if (shanghaiIndex.isNotBlank()) append("大盘：${shanghaiIndex}。")
            val stance = lastStance; if (stance != null) append("你上次判断为${stance.bracketLabel}，请结合最新数据判断是否修正观点。")
            append("综合资金流向、主力意图、近日K线趋势和大盘环境，给出持仓防守建议。")
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

    // ── 通用 AI HTTP 调用（消除样板重复） ──

    private fun callAi(model: String, systemPrompt: String, userPrompt: String,
                       apiUrl: String, apiKey: String, logPrefix: String = "AI"): String? {
        return try {
            val json = """
                |{
                |  "model": "$model",
                |  "max_tokens": 200,
                |  "messages": [
                |    {"role": "system", "content": ${toJsonStr(systemPrompt)}},
                |    {"role": "user", "content": ${toJsonStr(userPrompt)}}
                |  ]
                |}
            """.trimMargin()

            val request = Request.Builder()
                .url(apiUrl)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string()
            if (!response.isSuccessful) {
                if (response.code == 403) onLog("$logPrefix: ✗ HTTP 403 无权访问 ${body?.take(80) ?: ""}")
                else onLog("$logPrefix: ✗ HTTP ${response.code} ${body?.take(80) ?: ""}")
                return null
            }

            val content = extractContent(body)
            if (content != null) onLog("$logPrefix: ✓ ${content.take(40)}...")
            else onLog("$logPrefix: ✗ 解析响应失败 body=${body?.take(120) ?: "null"}")
            content
        } catch (e: Exception) {
            onLog("$logPrefix: ✗ ${e.message?.take(50) ?: "未知错误"}")
            null
        }
    }

    // ── 调用 AI 获取结构化立场 ──

    private fun callAiForStance(config: AiConfig, prompt: String, useThinking: Boolean = false): StanceResult? {
        val systemPrompt = "你是一个短线盘面分析助手。只看多空方向，不做完整分析。" +
            "必须严格按JSON格式输出：{\"stance\":1,\"reason\":\"简要理由\"}。" +
            "stance: 1=偏多, 0=震荡, -1=偏空。reason不超过15字。不要输出任何其他内容。"

        val model = if (useThinking && config.thinkingModel.isNotBlank()) {
            onLog("双AI: 启用思考模式 ${config.thinkingModel}")
            config.thinkingModel
        } else config.model

        val content = callAi(model, systemPrompt, prompt,
            config.apiUrl, config.apiKey, "双AI")
        return if (content != null) parseStanceResult(content) else null
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

    // ── 构建深度分析提示词（五层框架：资金面→归因→对手盘→结构→风险） ──
    private fun buildPrompt(context: MarketContext = MarketContext(), alertContext: String = "", shanghaiIndex: String = "", sentiment: GlobalSentiment = GlobalSentiment()): String {
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

        // 分时特征
        val avgPrice = history.map { it.price }.average()
        var crosses = 0
        for (i in 1 until history.size) {
            if ((history[i-1].price - avgPrice) * (history[i].price - avgPrice) < 0) crosses++
        }
        val jagDesc = when { crosses >= 5 -> "分时锯齿状，反复穿越均线"; crosses >= 3 -> "分时有震荡"; else -> "" }

        val changeStr = if (latest.changePct > 0) "+${latest.changePct}%" else "${latest.changePct}%"

        return buildString {
            // ── 五层分析框架数据注入 ──

            // 【第1层-资金面】（含四象限防伪判定）
            val fundParts = mutableListOf<String>()
            if (context.fundFlowDirection.isNotBlank()) {
                fundParts.add("主力：${context.fundFlowDirection}${if (context.fundFlowAmount.isNotBlank()) " ${context.fundFlowAmount}" else ""}")
                if (context.fundFlowQuadrant.isNotBlank()) fundParts.add("判定：${context.fundFlowQuadrant}")
            }
            if (context.dragonTigerTag.isNotBlank()) fundParts.add(context.dragonTigerTag)
            if (context.mcapContext.isNotBlank()) fundParts.add("流通市值：${context.mcapContext}")
            if (fundParts.isNotEmpty()) append("【资金面】${fundParts.joinToString("；")}。")

            // 【第2层-归因】（含Alpha差值）
            if (context.blockInfo.isNotBlank() || context.alphaDiff.isNotBlank()) {
                append("【归因】")
                if (context.blockInfo.isNotBlank()) append("${context.blockInfo}。")
                if (context.alphaDiff.isNotBlank()) append("Alpha差值：${context.alphaDiff}。")
                if (context.relativeStrength.isNotBlank()) append("判定：${context.relativeStrength}。")
            }
            if (context.stockSector.isNotBlank()) {
                append("核心题材：【${context.stockSector}】。")
            }

            // 【第3层-对手盘】+ 全市场情绪
            val slice = sentiment.toSentimentSlice()
            if (slice.isNotBlank()) append("$slice ")

            // 【第4层-结构】
            append("${latest.price}元，$changeStr，近30秒${trend}。")
            append("量比${latest.volRatio}（${volDesc}），成交${latest.amountStr}。")
            append("量价关系：${volPriceRel}。")
            append("日内振幅${amplitude}%。")
            if (orderBook.isNotEmpty()) append("盘口：${orderBook}。")
            if (jagDesc.isNotEmpty()) append(jagDesc + "。")
            if (context.mcapContext.isNotBlank()) append("流通市值：${context.mcapContext}。")

            // 【第5层-风险】
            if (context.limitDistance.isNotBlank()) append("【风险】${context.limitDistance}。")

            // 其他上下文
            if (alertContext.isNotBlank()) append(alertContext)
            if (context.alertStats.isNotBlank()) append("近期异动：${context.alertStats}。")
            if (context.newsHeadline.isNotBlank()) append("消息面：${context.newsHeadline}。")
            if (shanghaiIndex.isNotBlank()) append("大盘：${shanghaiIndex}。")
            val stance = lastStance; if (stance != null) append("你上次判断为${stance.bracketLabel}，请结合最新数据判断是否修正观点。")

            append("从以上数据中挑最值得说的1-2个关键信号，用2-4句口语点评。")
            append("优先关注：四象限资金判定、Alpha差值方向、量价异常、涨跌停风险。")
            append("像交易主管在对讲机里下指令——精准、简洁。不超过80字。")
        }
    }

    /** 异动后的专用深度分析提示词（含资金+板块上下文） */
    fun buildPostAlertPrompt(alertText: String, snapshots: List<MarketSnapshot>, shanghaiIndex: String = "", sentiment: GlobalSentiment = GlobalSentiment(), stockSector: String = "", context: MarketContext = MarketContext()): String {
        if (snapshots.isEmpty()) return ""
        val latest = snapshots.last()
        val trend = if (snapshots.size >= 2) {
            val delta = latest.price - snapshots.first().price
            when { delta > 0.3 -> "拉升"; delta < -0.3 -> "回落"; else -> "震荡" }
        } else "波动"

        return buildString {
            if (stockSector.isNotBlank()) append("该股当前核心炒作题材为：【${stockSector}】。")
            val slice = sentiment.toSentimentSlice()
            if (slice.isNotBlank()) append("$slice ")
            // 资金上下文（含四象限判定）
            if (context.fundFlowDirection.isNotBlank()) {
                append("主力${context.fundFlowDirection}${context.fundFlowAmount}")
                if (context.fundFlowQuadrant.isNotBlank()) append("【${context.fundFlowQuadrant}】")
                append("；")
            }
            append("刚发生异动：${alertText.take(60)}。")
            append("异动后当前${latest.price}元，${trend}中。")
            append("量比${latest.volRatio}，成交${latest.amountStr}。")
            append("卖盘${latest.largeAsksCount}档大单，买盘${latest.largeBidsCount}档大单。")
            if (context.blockInfo.isNotBlank()) append("板块：${context.blockInfo}。")
            if (shanghaiIndex.isNotBlank()) append("大盘：${shanghaiIndex}。")
            val stance = lastStance; if (stance != null) append("你上次判断为${stance.bracketLabel}，请结合最新数据判断是否修正观点。")
            append("结合资金方向和板块强弱，分析这波异动是什么意图？后续走势怎么看？")
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
                    |  "max_tokens": 200,
                    |  "messages": [
                    |    {"role": "system", "content": ${toJsonStr(systemPrompt)}},
                    |    {"role": "user", "content": ${toJsonStr(userPrompt)}}
                    |  ]
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
                    if (response.code == 403) onLog("✗ HTTP 403: API Key无权访问该模型，请检查设置。")
                    callback(listOf(fullText)); return@execute
                }
                val content = extractContent(body) ?: run { callback(listOf(fullText)); return@execute }
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

    /** 深度分析：综合技术面+盘口博弈+K线+大盘+全市场情绪，AI自主判断关键信号 */
    fun generateSummary(context: MarketContext = MarketContext(), shanghaiIndex: String = "", sentiment: GlobalSentiment = GlobalSentiment(), callback: (String?) -> Unit) {
        val config = aiConfigProvider()
        if (!config.enabled) { callback(null); return }
        if (config.apiKey.isBlank()) { onLog("🤖 AI: 未配置API Key"); callback(null); return }

        val prompt = buildPrompt(context, shanghaiIndex = shanghaiIndex, sentiment = sentiment)
        if (prompt.isEmpty()) { onLog("🤖 AI: 等待行情数据..."); callback(null); return }

        apiExecutor.execute {
            val perspective = Perspective.pick()
            onLog("🤖 AI: 深度分析 ${config.model} [${perspective.label}]...")
            val content = callAi(config.model, getPersona(perspective), prompt,
                config.apiUrl, config.apiKey, "🤖 AI")
            if (content != null) lastStance = extractStanceFromContent(content)
            callback(content)
        }
    }

    /** 异动后专用AI分析（独立prompt，强调异动背景+全市场情绪+资金上下文） */
    fun generatePostAlertAnalysis(alertText: String, snapshots: List<MarketSnapshot>, shanghaiIndex: String = "", sentiment: GlobalSentiment = GlobalSentiment(), stockSector: String = "", context: MarketContext = MarketContext(), callback: (String?) -> Unit) {
        val config = aiConfigProvider()
        if (!config.enabled) { callback(null); return }
        if (config.apiKey.isBlank()) { onLog("🤖 AI: 未配置API Key"); callback(null); return }

        val prompt = buildPostAlertPrompt(alertText, snapshots, shanghaiIndex, sentiment, stockSector, context)
        if (prompt.isEmpty()) { onLog("🤖 AI: 异动复盘数据不足"); callback(null); return }

        apiExecutor.execute {
            val perspective = Perspective.pick()
            val postAlertPrompt = buildString {
                append("刚发生异动需要复盘。")
                append(getPersona(perspective))
                append("结合异动背景和当前盘面数据，用2句口语给出持仓建议，不超过60字。")
            }

            onLog("🤖 AI: 异动复盘 [${perspective.label}]...")
            val content = callAi(config.model, postAlertPrompt, prompt,
                config.apiUrl, config.apiKey, "🤖 AI")
            callback(content)
        }
    }

    fun shutdown() {
        apiExecutor.shutdownNow()
        dualExecutor.shutdownNow()
    }

    // ── 从 AI 回复中提取立场标签 ──

    private fun extractStanceFromContent(content: String): Stance? = Stance.fromContent(content)

    // ── 用 Android 内置 JSONObject 提取 API 响应中的 content ──

    private fun extractContent(body: String?): String? {
        if (body == null) return null
        return try {
            JSONObject(body)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .optString("content", null)
        } catch (_: Exception) {
            null
        }
    }

    // ── 简易 JSON 字符串提取（用于解析 AI 输出的扁平 JSON） ──

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
