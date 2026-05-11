package com.stockspeaker

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
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
    private var wakeLock: PowerManager.WakeLock? = null
    private var isRunning = false
    private var isPaused = false
    private var lastSpeakTime = 0L
    private var lastTotalVol = 0
    private var lastChangePct = 0.0
    private var lastPrice = 0.0
    private var lastSpokenPrice = 0.0  // 箱体静默：上次完整播报时的价格
    private var lastAlertSpeakTime = 0L  // 最近一次异动时间（用于跟进逻辑，非冷却）
    private var lastHandAlertTime = 0L    // 大单异动冷却
    private var lastSpeedAlertTime = 0L   // 涨速异动冷却
    private var lastPatternAlertTime = 0L // AI异动冷却
    private var alertActive = false      // 异动进行中，需等待平复
    private var alertSettleCount = 0    // 平复计数（连续无异常轮次）
    private var normalBroadcastCount = 0
    private var pendingAiSummary: String? = null
    private var aiRequestInFlight = false
    private var lastFillInTime = 0L
    private var fillInCount = 0
    private var changeStyleIndex = 0
    private var priceStyleIndex = 0
    private var speedStyleIndex = 0
    private var lastDualAnalysisTime = 0L
    private var postAlertPhase = 0
    private var normalDeferred = false
    private var lastAlertText = ""
    private var lastPostAlertData: StockData? = null
    private val batchQueue = mutableListOf<String>()
    private val intervalLargeEvents = mutableListOf<Triple<String, String, Int>>()
    private val netExecutor = Executors.newSingleThreadExecutor()
    private val aiLogs = mutableListOf<String>()
    private var lastShanghaiIndex = ""
    private var shanghaiFetchCount = 0

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
            AiConfig(enabled = c.aiEnabled, apiKey = c.aiApiKey, apiUrl = c.aiApiUrl, model = c.aiModel, thinkingModel = c.aiThinkingModel, summaryInterval = c.aiSummaryInterval)
        }, onLog = { msg -> aiLog(msg) }, aiTwoConfigProvider = {
            val c = cm.load()
            AiConfig(enabled = c.aiTwoEnabled, apiKey = c.aiTwoApiKey, apiUrl = c.aiTwoApiUrl, model = c.aiTwoModel, thinkingModel = c.aiTwoThinkingModel)
        })
        ttsEngine = TtsEngine(this, cacheDir) {}
        ttsEngine.init()
        wakeLock = try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StockSpeaker:monitor")
        } catch (_: Exception) { null }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            NotificationHelper.ACTION_PAUSE -> { isPaused = true; ttsEngine.stop(); aiLog("⏸ 暂停"); updateNotif(); return START_STICKY }
            NotificationHelper.ACTION_RESUME -> { isPaused = false; aiLog("▶ 恢复"); updateNotif(); return START_STICKY }
            NotificationHelper.ACTION_DISMISS_ALERT -> {
                ttsEngine.stop(); NotificationHelper.cancelAlert(this)
                alertActive = false; alertSettleCount = 0; postAlertPhase = 0
                lastPostAlertData = null; batchQueue.clear()
                aiLog("🔕 关闭异动提醒")
                updateNotif(); return START_STICKY
            }
            NotificationHelper.ACTION_DISMISS_ALERT_OPEN -> {
                ttsEngine.stop(); NotificationHelper.cancelAlert(this)
                alertActive = false; alertSettleCount = 0; postAlertPhase = 0
                lastPostAlertData = null; batchQueue.clear()
                aiLog("🔕 关闭异动提醒")
                updateNotif()
                // 同时打开App
                val openIntent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(openIntent)
                return START_STICKY
            }
        }
        if (isRunning) return START_STICKY
        isRunning = true
        try { wakeLock?.acquire(10 * 60 * 1000L) } catch (_: Exception) {}
        val cm = ConfigManager(this); config = cm.load()
        cm.setMonitoringActive(true)
        lastSpeakTime = 0L; lastTotalVol = 0; lastChangePct = 0.0; lastPrice = 0.0; lastSpokenPrice = 0.0; intervalLargeEvents.clear()
        normalBroadcastCount = 0; pendingAiSummary = null; aiRequestInFlight = false
        lastFillInTime = 0L; fillInCount = 0; lastDualAnalysisTime = 0L
        postAlertPhase = 0; normalDeferred = false; lastAlertText = ""
        lastPostAlertData = null; batchQueue.clear()
        alertActive = false; alertSettleCount = 0
        lastShanghaiIndex = ""; shanghaiFetchCount = 0
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
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
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
            // 每10次轮询（20秒）拉一次大盘数据
            shanghaiFetchCount++
            if (shanghaiFetchCount % 10 == 1) {
                lastShanghaiIndex = StockFetcher.fetchShanghaiIndexText()
            }
            handler.post {
                if (!isRunning) return@post
                try { wakeLock?.let { if (!it.isHeld) it.acquire(10 * 60 * 1000L) } } catch (_: Exception) {}
                if (data != null) { processStockData(data); updateNotif(data) }
                handler.postDelayed({ runLoop() }, 2000)
            }
        }
    }

    private fun processStockData(data: StockData) {
        val currentHand = if (lastTotalVol > 0) maxOf(0, data.totalVol - lastTotalVol) else 0
        val speed = if (lastChangePct != 0.0) Math.round((data.changePct - lastChangePct) * 100.0) / 100.0 else 0.0
        val prevPrice = lastPrice
        lastTotalVol = data.totalVol; lastChangePct = data.changePct; lastPrice = data.price
        val dynThreshold = getDynamicThreshold(data.turnover)
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

        // ── 分批播报队列（低优先级，不阻塞主流程） ──
        if (!ttsEngine.isSpeaking && batchQueue.isNotEmpty()) {
            val next = batchQueue.removeAt(0)
            ttsEngine.speak(next)
            uiState.value = uiState.value.copy(lastSpeakTime = nowStr)
            return
        }

        // ═══════════════════════════════════════
        // 轨道1：实时异动（最高优先级，可打断一切）
        // 每类异动独立冷却30秒，不同类型可分别触发
        // ═══════════════════════════════════════
        val alertCooldownMs = 30000L
        var alertSpoken = false
        var alertText = ""
        if (currentHand >= dynThreshold && now - lastHandAlertTime >= alertCooldownMs) {
            val (dirLabel, action) = when {
                prevPrice > 0 && data.price > prevPrice -> "大单" to "向上扫货"
                prevPrice > 0 && data.price < prevPrice -> "大单" to "向下砸盘"
                else -> "大单" to "激烈成交"
            }
            alertText = "${alertPrefix()}${spokenHand(currentHand)}${dirLabel}${action}！"
            ttsEngine.speakAlert(alertText)
            alertSpoken = true; lastHandAlertTime = now; lastAlertSpeakTime = now
        }
        if (!alertSpoken && absSpeed >= config.speedAlertThreshold && now - lastSpeedAlertTime >= alertCooldownMs) {
            val dir = if (speed > 0) "快速拉升" else "快速下跌"
            val tag = if (speed > 0) "涨幅" else "跌幅"
            alertText = "${alertPrefix()}$dir！${data.name}当前${spokenPrice(data.price)}，${tag}${fmtPct(absSpeed)}%"
            ttsEngine.speakAlert(alertText)
            alertSpoken = true; lastSpeedAlertTime = now; lastAlertSpeakTime = now
        }
        if (!alertSpoken && now - lastPatternAlertTime >= alertCooldownMs) {
            try {
                val snapshot = MarketSnapshot(now, data.price, data.changePct, speed, data.volRatio,
                    data.amountStr, currentHand, data.largeAsksSpeak.size, data.largeBidsSpeak.size)
                val patterns = aiAnalyzer.feed(snapshot)
                if (patterns.isNotEmpty()) {
                    alertText = alertPrefix() + patterns.joinToString("") { it.speakText }
                    aiLog("AI异动: ${patterns.map { it.type.name }.joinToString()}")
                    ttsEngine.speakAlert(alertText)
                    alertSpoken = true; lastPatternAlertTime = now; lastAlertSpeakTime = now
                }
            } catch (_: Exception) {}
        }
        if (alertSpoken) {
            NotificationHelper.notifyAlert(this@StockMonitorService,
                NotificationHelper.buildAlert(this@StockMonitorService, alertText))
            alertActive = true; alertSettleCount = 0
            postAlertPhase = 1; lastAlertText = alertText
            lastPostAlertData = data; normalDeferred = false
            lastSpeakTime = now; intervalLargeEvents.clear()
            batchQueue.clear()
            uiState.value = uiState.value.copy(lastSpeakTime = nowStr)
            return
        }

        // ── 轨道1b：异动平复等待 ──
        // 异动触发后持续检测盘面，直到连续3轮（6秒）无异动才进入复盘
        if (alertActive) {
            val stillAlertHand = currentHand >= dynThreshold
            val stillAlertSpeed = absSpeed >= config.speedAlertThreshold
            if (stillAlertHand || stillAlertSpeed) {
                alertSettleCount = 0
                if (now - lastAlertSpeakTime >= 8000) {
                    val update = if (stillAlertHand) {
                        val (dl, act) = when {
                            prevPrice > 0 && data.price > prevPrice -> "大单" to "向上扫货"
                            prevPrice > 0 && data.price < prevPrice -> "大单" to "向下砸盘"
                            else -> "大单" to "激烈成交"
                        }
                        "${alertPrefix()}${spokenHand(currentHand)}${dl}${act}！"
                    } else {
                        val dir = if (speed > 0) "继续拉升" else "继续下跌"
                        "${alertPrefix()}${data.name}$dir，涨速${fmtPct(absSpeed)}%"
                    }
                    ttsEngine.speakAlert(update)
                    lastAlertSpeakTime = now
                }
                return
            }
            // 当前无异动条件，累计平复轮次
            alertSettleCount++
            if (alertSettleCount < 3) return
            alertActive = false
        }

        // ═══════════════════════════════════════
        // 轨道1c：异动后跟进（仅异动平复后执行）
        // ═══════════════════════════════════════
        if (postAlertPhase > 0 && !ttsEngine.isSpeaking) {
            if (postAlertPhase == 1) {
                val d = lastPostAlertData ?: data
                ttsEngine.speak("${d.name}${conciseChange(d.changePct)}。")
                aiLog("异动跟进: ${conciseChange(d.changePct)}")
                postAlertPhase = 2; lastSpeakTime = now
                uiState.value = uiState.value.copy(lastSpeakTime = nowStr)
                return
            } else if (postAlertPhase == 2) {
                triggerPostAlertAnalysis()
                postAlertPhase = 0; lastPostAlertData = null
                return
            }
        }

        // ── 收集时段内大单 ──
        if (currentHand >= dynThreshold) {
            val act = when {
                prevPrice > 0 && data.price > prevPrice -> "向上扫货"
                prevPrice > 0 && data.price < prevPrice -> "向下砸盘"
                else -> "激烈成交"
            }
            intervalLargeEvents.add(Triple(nowStr, act, currentHand))
        }

        // ═══════════════════════════════════════
        // 轨道2：定时常规播报 + AI 点评 + 长间隔AI插播
        // ═══════════════════════════════════════
        val intervalMs = config.speakInterval * 1000L
        val elapsed = now - lastSpeakTime

        if (!ttsEngine.isSpeaking) {
            // 被推迟的正常播报 → 简洁版
            if (normalDeferred) {
                ttsEngine.speak("${data.name}${concisePrice(data.price)}，${conciseChange(data.changePct)}。")
                aiLog("简洁: ${concisePrice(data.price)} ${conciseChange(data.changePct)}")
                normalDeferred = false
                lastSpeakTime = now; intervalLargeEvents.clear()
                uiState.value = uiState.value.copy(lastSpeakTime = nowStr)
                return
            }

            if (pendingAiSummary != null) {
                val summary = pendingAiSummary!!
                pendingAiSummary = null
                ttsEngine.speak(summary)
                uiState.value = uiState.value.copy(lastSpeakTime = nowStr)
                return
            }

            if (elapsed >= intervalMs) {
                // 箱体静默：当前价与上次播报价差 < 0.3% 且量比无异动 → 跳过长播报
                val boxSilent = lastSpokenPrice > 0 &&
                    Math.abs(data.price - lastSpokenPrice) / lastSpokenPrice < 0.003 &&
                    data.volRatio < 2.0
                if (boxSilent) {
                    ttsEngine.speak("横盘震荡")
                    lastSpeakTime = now; intervalLargeEvents.clear()
                    uiState.value = uiState.value.copy(lastSpeakTime = nowStr)
                } else {
                    buildSpeakText(data, currentHand, speed, dynThreshold)?.let { text ->
                        if (ttsEngine.speak(text)) {
                            lastSpokenPrice = data.price
                            lastSpeakTime = now; intervalLargeEvents.clear()
                            normalBroadcastCount++; fillInCount = 0
                            uiState.value = uiState.value.copy(lastSpeakTime = nowStr)
                            maybeGenerateAiSummary(data, speed)
                        }
                    }
                }
            } else if (config.aiEnabled && pendingAiSummary == null && !aiRequestInFlight && postAlertPhase == 0) {
                // 轨道3：双AI / 长间隔AI插播
                val midPoint = intervalMs > 120000 && elapsed >= intervalMs / 2 && fillInCount == 0
                val chaos = aiAnalyzer.shouldTriggerDualAnalysis() && now - lastDualAnalysisTime >= 60000
                if (midPoint || chaos) {
                    if (midPoint) fillInCount++
                    lastDualAnalysisTime = now
                    triggerDualAnalysis()
                }
            }
        } else {
            // TTS 正忙 → 正常播报到时间了就标记推迟
            if (elapsed >= intervalMs && !normalDeferred && postAlertPhase == 0) {
                normalDeferred = true
            }
        }
    }

    private fun maybeGenerateAiSummary(data: StockData, speed: Double) {
        if (!config.aiEnabled || config.aiApiKey.isBlank()) return
        if (aiRequestInFlight) return
        if (normalBroadcastCount % config.aiSummaryInterval != 0) return
        aiRequestInFlight = true
        val ctx = MarketContext(
            newsHeadline = aiAnalyzer.pickNews(config.stockCode),
            fundFlow = if (data.changePct > 0) "主力净流入" else "主力净流出",
            fundFlowAmount = "${"%.1f".format(Math.abs(data.changePct) * 1.5)}亿",
            alertStats = ""
        )
        aiAnalyzer.generateSummary(ctx, lastShanghaiIndex) { summary ->
            aiRequestInFlight = false
            if (summary != null) handler.post {
                aiLog("AI点评: ${summary.take(30)}...")
                if (summary.length > 60) {
                    // 长文本 → 辅AI拆分为短句分批播报
                    aiAnalyzer.splitIntoBatches(summary) { batches ->
                        handler.post {
                            batchQueue.addAll(batches)
                            if (!ttsEngine.isSpeaking && batchQueue.isNotEmpty()) {
                                val next = batchQueue.removeAt(0)
                                ttsEngine.speak(next)
                            }
                        }
                    }
                } else {
                    if (!ttsEngine.isSpeaking && batchQueue.isEmpty()) {
                        if (!ttsEngine.speak(summary)) pendingAiSummary = summary
                    } else {
                        pendingAiSummary = summary
                    }
                }
            }
        }
    }

    /** 异动后AI复盘 */
    private fun triggerPostAlertAnalysis() {
        if (!config.aiEnabled || config.aiApiKey.isBlank()) return
        if (aiRequestInFlight) return
        aiRequestInFlight = true
        val snapshots = aiAnalyzer.getRecentSnapshots()
        val alertText = lastAlertText
        aiAnalyzer.generatePostAlertAnalysis(alertText, snapshots, lastShanghaiIndex) { result ->
            aiRequestInFlight = false
            if (result != null) handler.post {
                aiLog("异动复盘: ${result.take(30)}...")
                if (!ttsEngine.isSpeaking) {
                    ttsEngine.speak(result)
                } else {
                    pendingAiSummary = result
                }
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

    private fun buildSpeakText(data: StockData, currentHand: Int, speed: Double, dynThreshold: Int): String? {
        val parts = mutableListOf<String>()
        val h = buildString { append(data.name); if (config.speakPrice) append(spokenPrice(data.price)) }
        parts.add(h)
        if (config.speakPct) parts.add(spokenChange(data.changePct))
        if (config.speakSpeed && Math.abs(speed) >= 0.05) parts.add(spokenSpeed(speed))
        if (config.speakAmount) parts.add("成交${spokenAmount(data.amountStr)}")
        if (config.speakVolRatio) spokenVolRatio(data.volRatio)?.let { parts.add(it) }
        if (config.speakCurrentHand && currentHand >= dynThreshold) parts.add("现手${spokenHand(currentHand)}")
        val main = parts.joinToString("，") + "。"
        val alert = if (config.speakLargeOrders) spokenLargeOrders(data)?.let { "注意，$it。" } ?: "" else ""
        val event = intervalLargeEvents.maxByOrNull { it.third }?.let {
            if (it.third >= dynThreshold) "刚才有${spokenHand(it.third)}${it.second}。" else ""
        } ?: ""
        // 成交明细播报
        val detail = if (config.speakTransactionDetail && currentHand >= dynThreshold) {
            val dir = when { speed > 0.3 -> "买入"; speed < -0.3 -> "卖出"; else -> "" }
            "${spokenTime()}，${spokenPrice(data.price)}${dir}成交${spokenHand(currentHand)}手。"
        } else ""
        val full = main + alert + event + detail
        return if (full == "。") null else full
    }

    // ── 异动前缀（随机切换，确保异动播报有辨识度） ──

    private fun alertPrefix(): String {
        val prefixes = listOf("注意：", "异动：", "注意注意：", "警报：", "提醒：")
        return prefixes.random()
    }

    // ── 换手率动态阈值（防开盘乱叫，大小盘自适应） ──

    private fun getDynamicThreshold(turnover: Double): Int {
        val base = config.largeOrderThreshold
        val cal = java.util.Calendar.getInstance()
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val minute = cal.get(java.util.Calendar.MINUTE)
        // 换手率系数（9:30-10:00 换手率没走出来，不做缩放）
        val turnoverCoef = when {
            hour == 9 && minute in 30..59 || hour == 10 && minute == 0 -> 1.0
            turnover > 15 -> 2.5
            turnover >= 8 -> 1.5
            turnover < 2 -> 0.6
            else -> 1.0
        }
        // 时间系数：早盘噪音大→提阈值，午后瞌睡期→降阈值捕捉偷袭
        val timeCoef = when {
            hour == 9 && minute >= 30 || hour == 10 && minute == 0 -> 1.5
            hour == 13 || hour == 14 && minute == 0 -> 0.8
            else -> 1.0
        }
        return (base * turnoverCoef * timeCoef).toInt().coerceAtLeast(1)
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

    // ── 简洁播报（省略单位，如"三十五，涨二点七五"） ──

    private fun numToCn(n: Int): String {
        if (n == 0) return "零"
        val d = listOf("", "一", "二", "三", "四", "五", "六", "七", "八", "九")
        return buildString {
            val q = n / 1000; val b = (n % 1000) / 100; val s = (n % 100) / 10; val g = n % 10
            if (q > 0) append(d[q] + "千")
            if (b > 0) append(d[b] + "百")
            if (s > 0) { if (s == 1 && q == 0 && b == 0) append("十") else append(d[s] + "十") }
            if (g > 0) append(d[g])
        }.let { if (it.startsWith("一十")) it.substring(1) else it }
    }

    private fun digitCn(d: Int): String = listOf("零","一","二","三","四","五","六","七","八","九")[d]

    private fun concisePrice(p: Double): String {
        val intPart = p.toInt()
        val dec = Math.round((p - intPart) * 100).toInt()
        if (dec == 0) return numToCn(intPart)
        val j = dec / 10; val f = dec % 10
        return buildString { append(numToCn(intPart)); append("点"); if (j > 0) append(digitCn(j)); append(digitCn(f)) }
    }

    private fun conciseChange(pct: Double): String {
        val abs = Math.abs(pct)
        val dir = when { pct > 0.01 -> "涨"; pct < -0.01 -> "跌"; else -> return "平盘" }
        val intPart = abs.toInt(); val dec = Math.round((abs - intPart) * 100).toInt()
        return buildString {
            append(dir); append(numToCn(intPart))
            if (dec > 0) { append("点"); val t = dec / 10; val o = dec % 10; if (t > 0) append(digitCn(t)); append(digitCn(o)) }
        }
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
