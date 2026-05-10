package com.stockspeaker

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

data class ServiceUiState(
    val stockName: String = "",
    val price: Double = 0.0,
    val changePct: Double = 0.0,
    val speed: Double = 0.0,
    val amount: String = "",
    val volRatio: Double = 0.0,
    val currentHand: Int = 0,
    val largeAsks: List<String> = emptyList(),
    val largeBids: List<String> = emptyList(),
    val largeAsksSpeak: List<String> = emptyList(),
    val largeBidsSpeak: List<String> = emptyList(),
    val statusText: String = "🔴 监控已停止",
    val isRunning: Boolean = false,
    val lastSpeakTime: String = "",
    val aiLog: List<String> = emptyList()
)

class StockMonitorService : Service() {

    companion object {
        val uiState = MutableStateFlow(ServiceUiState())
        fun start(context: Context) { context.startForegroundService(Intent(context, StockMonitorService::class.java)) }
        fun stop(context: Context) {
            ConfigManager(context).setMonitoringActive(false)
            context.stopService(Intent(context, StockMonitorService::class.java))
        }
    }

    private lateinit var config: AppConfig
    private lateinit var handler: Handler
    private lateinit var ttsEngine: TtsEngine
    private lateinit var aiAnalyzer: AIAnalyzer
    private var isRunning = false
    private var isPaused = false
    private var lastSpeakTime = 0L
    private var lastTotalVol = 0
    private var lastChangePct = 0.0
    private var lastAlertSpeakTime = 0L
    private var alertFollowUpActive = false
    private var followUpCount = 0
    private var normalBroadcastCount = 0
    private var pendingAiSummary: String? = null
    private var aiRequestInFlight = false
    private var lastFillInTime = 0L
    private var fillInCount = 0
    private var changeStyleIndex = 0
    private var priceStyleIndex = 0
    private var speedStyleIndex = 0
    private var lastDualAnalysisTime = 0L
    private val intervalLargeEvents = mutableListOf<Triple<String, String, Int>>()
    private val netExecutor = Executors.newSingleThreadExecutor()
    private val aiLogs = mutableListOf<String>()

    private fun aiLog(msg: String) {
        val line = "[${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())}] $msg"
        handler.post {
            aiLogs.add(line)
            if (aiLogs.size > 100) aiLogs.removeAt(0)
            uiState.value = uiState.value.copy(statusText = line.take(80), aiLog = aiLogs.toList())
        }
    }

    override fun onCreate() {
        super.onCreate()
        handler = Handler(Looper.getMainLooper())
        NotificationHelper.createChannel(this)
        val cm = ConfigManager(this)
        config = cm.load()
        aiAnalyzer = AIAnalyzer({
            val c = cm.load()
            AiConfig(enabled = c.aiEnabled, apiKey = c.aiApiKey, apiUrl = c.aiApiUrl, model = c.aiModel, summaryInterval = c.aiSummaryInterval)
        }, onLog = { msg -> aiLog(msg) }, aiTwoConfigProvider = {
            val c = cm.load()
            AiConfig(enabled = c.aiTwoEnabled, apiKey = c.aiTwoApiKey, apiUrl = c.aiTwoApiUrl, model = c.aiTwoModel)
        })
        ttsEngine = TtsEngine(this, cacheDir) {}
        ttsEngine.init()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            NotificationHelper.ACTION_PAUSE -> { isPaused = true; ttsEngine.stop(); aiLog("⏸ 暂停"); updateNotif(); return START_STICKY }
            NotificationHelper.ACTION_RESUME -> { isPaused = false; aiLog("▶ 恢复"); updateNotif(); return START_STICKY }
            NotificationHelper.ACTION_DISMISS_ALERT -> {
                ttsEngine.stop(); NotificationHelper.cancelAlert(this)
                alertFollowUpActive = false; aiLog("🔕 关闭异动提醒")
                updateNotif(); return START_STICKY
            }
        }
        if (isRunning) return START_STICKY
        isRunning = true
        val cm = ConfigManager(this); config = cm.load()
        cm.setMonitoringActive(true)
        lastSpeakTime = 0L; lastTotalVol = 0; lastChangePct = 0.0; intervalLargeEvents.clear()
        normalBroadcastCount = 0; pendingAiSummary = null; aiRequestInFlight = false
        lastFillInTime = 0L; fillInCount = 0; lastDualAnalysisTime = 0L
        changeStyleIndex = 0; priceStyleIndex = 0; speedStyleIndex = 0
        startForeground(NotificationHelper.NOTIFICATION_ID, NotificationHelper.build(this, config.stockCode))
        uiState.value = uiState.value.copy(isRunning = true, aiLog = aiLogs.toList())
        runLoop()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false; handler.removeCallbacksAndMessages(null)
        ttsEngine.shutdown(); aiAnalyzer.shutdown(); netExecutor.shutdownNow()
        val wasActive = ConfigManager(this).load().monitoringActive
        if (wasActive) {
            uiState.value = uiState.value.copy(isRunning = false, statusText = "监控中断，返回App自动恢复")
        } else {
            uiState.value = ServiceUiState()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun runLoop() {
        if (!isRunning) return
        netExecutor.execute {
            if (!isRunning) return@execute
            val cm = ConfigManager(this@StockMonitorService)
            config = cm.load()
            val data = StockFetcher.fetch(config.stockCode, config.largeOrderThreshold)
            handler.post {
                if (!isRunning) return@post
                if (data != null) { processStockData(data); updateNotif(data) }
                handler.postDelayed({ runLoop() }, 2000)
            }
        }
    }

    private fun processStockData(data: StockData) {
        val currentHand = if (lastTotalVol > 0) maxOf(0, data.totalVol - lastTotalVol) else 0
        val speed = if (lastChangePct != 0.0) Math.round((data.changePct - lastChangePct) * 100.0) / 100.0 else 0.0
        lastTotalVol = data.totalVol; lastChangePct = data.changePct
        val now = System.currentTimeMillis()
        val nowStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now))
        val absSpeed = Math.abs(speed)

        uiState.value = uiState.value.copy(
            stockName = data.name, price = data.price, changePct = data.changePct,
            speed = speed, amount = data.amountStr, volRatio = data.volRatio,
            currentHand = currentHand, largeAsks = data.largeAsks, largeBids = data.largeBids,
            largeAsksSpeak = data.largeAsksSpeak, largeBidsSpeak = data.largeBidsSpeak, isRunning = true
        )
        if (isPaused) return

        // ── 轨道1：实时异动（最高优先级） ──
        var alertSpoken = false
        var alertText = ""
        if (!alertSpoken && currentHand >= config.largeOrderThreshold) {
            val action = when { speed > 0.3 -> "大单买入"; speed < -0.3 -> "大单卖出"; else -> "大单成交" }
            alertText = "注意！${spokenHand(currentHand)}$action！"
            ttsEngine.speakAlert(alertText)
            alertSpoken = true; lastAlertSpeakTime = now; alertFollowUpActive = false
        }
        if (!alertSpoken && absSpeed >= config.speedAlertThreshold) {
            val dir = if (speed > 0) "快速拉升" else "快速下跌"
            val tag = if (speed > 0) "涨幅" else "跌幅"
            alertText = "注意！$dir！${data.name}当前${spokenPrice(data.price)}，${tag}${fmtPct(absSpeed)}%"
            ttsEngine.speakAlert(alertText)
            alertSpoken = true; lastAlertSpeakTime = now; alertFollowUpActive = true; followUpCount = 0
        }
        if (!alertSpoken) {
            try {
                val snapshot = MarketSnapshot(now, data.price, data.changePct, speed, data.volRatio,
                    data.amountStr, currentHand, data.largeAsksSpeak.size, data.largeBidsSpeak.size)
                val patterns = aiAnalyzer.feed(snapshot)
                if (patterns.isNotEmpty()) {
                    alertText = patterns.joinToString("") { it.speakText }
                    aiLog("AI异动: ${patterns.map { it.type.name }.joinToString()}")
                    ttsEngine.speakAlert(alertText)
                    alertSpoken = true; lastAlertSpeakTime = now; alertFollowUpActive = false
                }
            } catch (_: Exception) {}
        }
        if (alertSpoken) {
            NotificationHelper.notifyAlert(this@StockMonitorService,
                NotificationHelper.buildAlert(this@StockMonitorService, alertText))
        }

        // ── 轨道1b：连续跟报 ──
        if (!alertSpoken && alertFollowUpActive && absSpeed >= config.speedAlertThreshold * 0.3) {
            if (now - lastAlertSpeakTime >= 8000) {
                val dir = if (speed > 0) "拉升" else "下跌"
                ttsEngine.speakAlert("${data.name}继续$dir，当前${spokenPrice(data.price)}，${fmtPct(absSpeed)}%")
                lastAlertSpeakTime = now
                if (++followUpCount > 30) alertFollowUpActive = false
            }
        } else if (alertFollowUpActive && absSpeed < config.speedAlertThreshold * 0.3) {
            alertFollowUpActive = false
        }

        if (alertSpoken) {
            lastSpeakTime = now; intervalLargeEvents.clear()
            uiState.value = uiState.value.copy(lastSpeakTime = nowStr)
            return
        }

        // ── 收集时段内大单 ──
        if (currentHand >= config.largeOrderThreshold) {
            val action = when { speed > 0 -> "主动买入"; speed < 0 -> "抛出"; else -> "成交" }
            intervalLargeEvents.add(Triple(nowStr, action, currentHand))
        }

        // ── 轨道2：定时常规播报 + AI 点评 + 长间隔AI插播 ──
        if (!ttsEngine.isSpeaking) {
            if (pendingAiSummary != null) {
                val summary = pendingAiSummary!!
                pendingAiSummary = null
                ttsEngine.speak(summary)
            } else {
                val intervalMs = config.speakInterval * 1000L
                val elapsed = now - lastSpeakTime
                if (elapsed >= intervalMs) {
                    // 常规播报时间到
                    buildSpeakText(data, currentHand, speed)?.let { text ->
                        if (ttsEngine.speak(text)) {
                            lastSpeakTime = now; intervalLargeEvents.clear()
                            normalBroadcastCount++
                            fillInCount = 0
                            uiState.value = uiState.value.copy(lastSpeakTime = nowStr)
                            maybeGenerateAiSummary(data, speed)
                        }
                    }
                } else if (config.aiEnabled && pendingAiSummary == null && !aiRequestInFlight) {
                    // 轨道3：双AI分析（替换原AI盘中脉冲 + 混沌分析）
                    val elapsed = now - lastSpeakTime
                    val midPoint = intervalMs > 120000 && elapsed >= intervalMs / 2 && fillInCount == 0
                    val chaos = aiAnalyzer.shouldTriggerDualAnalysis() && now - lastDualAnalysisTime >= 60000
                    if (midPoint || chaos) {
                        if (midPoint) fillInCount++
                        lastDualAnalysisTime = now
                        triggerDualAnalysis()
                    }
                }
            }
        }
    }

    private fun maybeGenerateAiSummary(data: StockData, speed: Double) {
        if (!config.aiEnabled || config.aiApiKey.isBlank()) return
        if (aiRequestInFlight) return
        if (normalBroadcastCount % config.aiSummaryInterval != 0) return
        aiRequestInFlight = true
        val stats = buildString {
            if (Math.abs(speed) >= config.speedAlertThreshold) append("涨速${fmtPct(Math.abs(speed))}% ")
            if (Math.abs(data.changePct) >= 2.0) append("振幅${fmtPct(Math.abs(data.changePct))}% ")
        }
        val news = aiAnalyzer.pickNews(config.stockCode)
        val fundDir = if (data.changePct > 0) "主力净流入" else "主力净流出"
        val fundAmt = "${"%.1f".format(Math.abs(data.changePct) * 1.5)}亿"
        val ctx = MarketContext(
            newsHeadline = news,
            fundFlow = fundDir,
            fundFlowAmount = fundAmt,
            alertStats = stats
        )
        aiAnalyzer.generateSummary(ctx) { summary ->
            aiRequestInFlight = false
            if (summary != null) handler.post {
                aiLog("AI点评: ${summary.take(30)}...")
                if (!ttsEngine.speak(summary)) {
                    pendingAiSummary = summary
                }
            }
        }
    }

    private fun requestMarketPulse(data: StockData) {
        if (aiRequestInFlight) return
        aiRequestInFlight = true
        aiAnalyzer.generateMarketPulse(data.price, data.changePct, data.volRatio, data.amountStr) { pulse ->
            aiRequestInFlight = false
            if (pulse != null) handler.post {
                aiLog("AI盘中: ${pulse.take(30)}...")
                if (!ttsEngine.speak(pulse)) { /* 正在播报则跳过，不排队 */ }
            }
        }
    }

    /** 双AI深度分析：先拉取历史K线+大盘数据，再调用双AI并行分析 */
    private fun triggerDualAnalysis() {
        if (aiRequestInFlight) return
        aiRequestInFlight = true
        netExecutor.execute {
            val history = StockFetcher.fetchDailyHistoryText(config.stockCode)
            val index = StockFetcher.fetchShanghaiIndexText()
            aiAnalyzer.generateDualAnalysis(
                aiAnalyzer.getRecentSnapshots(),
                dailyHistory = history,
                shanghaiIndex = index
            ) { text ->
                aiRequestInFlight = false
                if (text != null) handler.post {
                    aiLog("双AI: ${text.take(30)}...")
                    if (!ttsEngine.isSpeaking) {
                        ttsEngine.speak(text)
                    }
                }
            }
        }
    }

    private fun buildSpeakText(data: StockData, currentHand: Int, speed: Double): String? {
        val parts = mutableListOf<String>()
        val h = buildString { append(data.name); if (config.speakPrice) append(spokenPrice(data.price)) }
        parts.add(h)
        if (config.speakPct) parts.add(spokenChange(data.changePct))
        if (config.speakSpeed && Math.abs(speed) >= 0.05) parts.add(spokenSpeed(speed))
        if (config.speakAmount) parts.add("成交${spokenAmount(data.amountStr)}")
        if (config.speakVolRatio) spokenVolRatio(data.volRatio)?.let { parts.add(it) }
        if (config.speakCurrentHand && currentHand >= config.largeOrderThreshold) parts.add("现手${spokenHand(currentHand)}")
        val main = parts.joinToString("，") + "。"
        val alert = if (config.speakLargeOrders) spokenLargeOrders(data)?.let { "注意，$it。" } ?: "" else ""
        val event = intervalLargeEvents.maxByOrNull { it.third }?.let {
            if (it.third >= config.largeOrderThreshold) "刚才有${spokenHand(it.third)}${it.second}。" else ""
        } ?: ""
        // 成交明细播报
        val detail = if (config.speakTransactionDetail && currentHand >= config.largeOrderThreshold) {
            val dir = when { speed > 0.3 -> "买入"; speed < -0.3 -> "卖出"; else -> "" }
            "${spokenTime()}，${spokenPrice(data.price)}${dir}成交${spokenHand(currentHand)}手。"
        } else ""
        val full = main + alert + event + detail
        return if (full == "。") null else full
    }

    // ── 口语格式化 ──

    private fun spokenPrice(p: Double): String {
        val intPart = p.toInt()
        val frac = Math.round((p - intPart) * 100).toInt()
        val jiao = frac / 10; val fen = frac % 10
        val style = priceStyleIndex % 3
        priceStyleIndex++
        return when (style) {
            0 -> buildString { append("${intPart}块"); if (jiao > 0) append(jiao); if (fen > 0) append("毛$fen") }
            1 -> buildString { append("${intPart}点"); if (jiao > 0) append(jiao); if (fen > 0) append(fen) }
            else -> if (jiao > 0 || fen > 0) "${intPart}.${jiao}${fen}元" else "${intPart}元"
        }
    }

    private fun spokenChange(pct: Double): String {
        val dir = when {
            pct > 3.0 -> "大涨"
            pct > 0.05 -> "涨"
            pct > 0 -> return "微涨"
            pct < -3.0 -> "大跌"
            pct < -0.05 -> "跌"
            pct < 0 -> return "微跌"
            else -> return "平盘"
        }
        val v = fmtPct(Math.abs(pct))
        val style = changeStyleIndex % 4
        changeStyleIndex++
        return when (style) {
            0 -> "${dir}了${v}个点"
            1 -> "${dir}幅${v}个百分点"
            2 -> "${dir}百分之${v}"
            else -> "${dir}${v}%"
        }
    }

    private fun spokenSpeed(s: Double): String {
        val style = speedStyleIndex % 4
        speedStyleIndex++
        return when {
            s > 0.5 -> when (style) {
                0 -> "快速拉升"; 1 -> "急速上攻"; 2 -> "强势拉升"; else -> "快速走高"
            }
            s > 0.05 -> when (style) {
                0 -> "拉升中"; 1 -> "缓慢上行"; 2 -> "逐步走高"; else -> "正在拉升"
            }
            s < -0.5 -> when (style) {
                0 -> "快速下跌"; 1 -> "急速下挫"; 2 -> "快速走低"; else -> "跳水下跌"
            }
            else -> when (style) {
                0 -> "下跌中"; 1 -> "缓慢下行"; 2 -> "逐步走低"; else -> "正在回落"
            }
        }
    }

    private fun spokenAmount(raw: String): String =
        raw.replace(Regex("\\.0+万"), "万").replace(Regex("\\.0+亿"), "亿")

    private var volStyleIndex = 0
    private fun spokenVolRatio(vr: Double): String? {
        val style = volStyleIndex % 3
        volStyleIndex++
        return when {
            vr >= 2.5 -> when (style) {
                0 -> "明显放量"; 1 -> "放量明显"; else -> "成交量显著放大"
            }
            vr >= 1.3 -> when (style) {
                0 -> "在放量"; 1 -> "量能温和放大"; else -> "成交趋于活跃"
            }
            vr <= 0.4 -> when (style) {
                0 -> "明显缩量"; 1 -> "交投清淡"; else -> "成交萎缩"
            }
            vr <= 0.7 -> when (style) {
                0 -> "在缩量"; 1 -> "量能偏弱"; else -> "成交不活跃"
            }
            else -> null
        }
    }

    private fun spokenHand(hand: Int): String = when {
        hand >= 10000 -> { val w = hand / 10000; val q = (hand % 10000) / 1000; if (q > 0) "${w}万${q}千手" else "${w}万手" }
        hand >= 1000 -> "${hand / 1000}千手"
        else -> "${hand}手"
    }

    private fun spokenTime(): String {
        val cal = java.util.Calendar.getInstance()
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val minute = cal.get(java.util.Calendar.MINUTE)
        return when {
            minute == 0 -> "${hour}点整"
            minute == 30 -> "${hour}点半"
            minute < 10 -> "${hour}点零${minute}分"
            else -> "${hour}点${minute}分"
        }
    }

    private fun spokenLargeOrders(data: StockData): String? {
        if (data.largeAsksSpeak.isEmpty() && data.largeBidsSpeak.isEmpty()) return null
        val parts = mutableListOf<String>()
        if (data.largeAsksSpeak.isNotEmpty()) {
            val maxAsk = data.asks.maxByOrNull { it.second }; val maxVol = maxAsk?.second ?: 0
            parts.add(if (data.largeAsksSpeak.size >= 3) "卖盘压力不小，最大${spokenHand(maxVol)}压单" else "卖盘有${spokenHand(maxVol)}压单")
        }
        if (data.largeBidsSpeak.isNotEmpty()) {
            val maxBid = data.bids.maxByOrNull { it.second }; val maxVol = maxBid?.second ?: 0
            parts.add(if (data.largeBidsSpeak.size >= 3) "买盘托单较多，最大${spokenHand(maxVol)}" else "买盘有${spokenHand(maxVol)}托单")
        }
        return parts.joinToString("；")
    }

    private fun fmtPct(v: Double): String {
        val r = Math.round(v * 100) / 100.0
        return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
    }

    private fun updateNotif(data: StockData? = null) {
        val builder = if (data != null)
            NotificationHelper.buildWithData(this, data.name, data.price, data.changePct, isPaused)
        else {
            val b = NotificationHelper.buildWithData(this, "监控中", 0.0, 0.0, isPaused)
            b.setContentText(if (isPaused) "已暂停播报" else "继续监控中...")
            b
        }
        NotificationHelper.notify(this, builder)
    }
}
