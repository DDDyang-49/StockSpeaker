package com.stockspeaker

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.net.wifi.WifiManager
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
    private lateinit var uiHandler: Handler
    private lateinit var loopThread: HandlerThread
    private lateinit var loopHandler: Handler
    private lateinit var ttsEngine: TtsEngine
    private lateinit var aiAnalyzer: AIAnalyzer
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var isRunning = false
    private var isPaused = false
    private var lastSpeakTime = 0L
    private var lastTotalVol = 0
    private var lastChangePct = 0.0
    private var lastPrice = 0.0
    private var lastSpokenPrice = 0.0  // 箱体静默：上次完整播报时的价格
    private var lastAlertSpeakTime = 0L  // 最近一次异动时间（用于跟进逻辑，非冷却）
    private var lastHandAlertTime = 0L    // 大单异动冷却
    private var lastAlertHand = 0          // 上次报警的大单手数（阶梯报警用）
    private var lastSpeedAlertTime = 0L   // 涨速异动冷却
    private var lastPatternAlertTime = 0L // AI异动冷却
    // ── AI 状态机冷冻期（模块3：拦截无效调用，降本增效） ──
    private var lastAiTime = 0L
    private var lastAiPrice = 0.0
    private var lastAiFundDir = ""  // "in"/"out"/""
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
    private val priceHistory = ArrayDeque<Double>(8)  // 滑动窗口价格轨迹（8点/15秒）
    private val intervalLargeEvents = mutableListOf<Triple<String, String, Int>>()
    private val netExecutor = Executors.newSingleThreadExecutor()
    private val sentimentExecutor = Executors.newSingleThreadExecutor()  // 情绪抓取独立线程，不阻塞行情轮询
    private val aiLogs = mutableListOf<String>()
    private var lastShanghaiIndex = ""
    private var shanghaiFetchCount = 0
    private var lastTtsCheckTime = 0L  // TTS 防卡死：上次检查时间
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val calendar = java.util.Calendar.getInstance()  // 复用，避免热路径每次new
    private var globalSentiment = GlobalSentiment()     // 全市场情绪缓存
    private var lastSentimentFetchTime = 0L              // 上次情绪抓取时间
    // 新数据源缓存（v1.1.0 — 北向已砍，龙虎榜改为静态标签）
    private var fundFlowCache = FundFlowData()
    private var lastFundFlowFetchTime = 0L
    private var conceptBlockCache = ConceptBlockData()
    private var lastConceptFetchTime = 0L
    private var dragonTigerTag = ""  // 龙虎榜静态标签，仅启动时抓一次
    // v1.1.0+ 通知功能暂禁用
    // private var lastNotifPrice = -1.0
    // private var lastNotifPct = 0.0
    // private var lastNotifPaused = false
    private var lastStockData: StockData? = null  // 缓存最近行情供双AI分析使用
    private var lastSpeed = 0.0
    @Volatile private var fetchInFlight = false  // 防止Doze期间网络阻塞导致任务堆积
    // AI stance 冷却：避免连续重复同一建议（如反复"危险清仓"）
    private var lastSpokenStance: Stance? = null
    private var lastStanceSpeakTime = 0L
    private val stanceCooldownMs = 300000L  // 5分钟内不重复同一 stance
    @Volatile private var fetchStartedAt = 0L  // fetchInFlight 变为 true 的时间戳（看门狗用）
    @Volatile private var watchdogWarned = false  // 看门狗是否已报警

    private fun aiLog(msg: String) {
        val line = "[${timeFmt.format(Date())}] $msg"
        uiHandler.post {
            aiLogs.add(line)
            if (aiLogs.size > 100) aiLogs.removeAt(0)
            uiState.value = uiState.value.copy(statusText = line.take(80), aiLog = aiLogs.toList())
        }
    }

    override fun onCreate() {
        super.onCreate()
        uiHandler = Handler(Looper.getMainLooper())
        loopThread = HandlerThread("StockLoop").apply { start() }
        loopHandler = Handler(loopThread.looper)
        NotificationHelper.createChannel(this)
        val cm = ConfigManager(this)
        config = cm.load()
        aiAnalyzer = AIAnalyzer({
            val c = cm.load()
            AiConfig(enabled = c.aiEnabled, apiKey = c.aiApiKey, apiUrl = c.aiApiUrl, model = c.aiModel, thinkingModel = c.aiThinkingModel, summaryInterval = c.aiSummaryInterval, provider = c.aiProvider)
        }, onLog = { msg -> aiLog(msg) }, aiTwoConfigProvider = {
            val c = cm.load()
            AiConfig(enabled = c.aiTwoEnabled, apiKey = c.aiTwoApiKey, apiUrl = c.aiTwoApiUrl, model = c.aiTwoModel, thinkingModel = c.aiTwoThinkingModel, provider = c.aiTwoProvider)
        })
        ttsEngine = TtsEngine(this, cacheDir) { msg -> aiLog(msg) }
        ttsEngine.init()
        wakeLock = try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StockSpeaker:monitor").apply {
                setReferenceCounted(false)
            }
        } catch (_: Exception) { null }
        wifiLock = try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "StockSpeaker:wifi")
        } catch (_: Exception) { null }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // v1.1.0+ 通知操作按钮暂禁用
        /*
        when (intent?.action) {
            NotificationHelper.ACTION_PAUSE -> { isPaused = true; ttsEngine.stop(); aiLog("⏸ 暂停"); updateNotif(); return START_STICKY }
            NotificationHelper.ACTION_RESUME -> { isPaused = false; aiLog("▶ 恢复"); updateNotif(); return START_STICKY }
            NotificationHelper.ACTION_DISMISS_ALERT -> { dismissAlert(); return START_STICKY }
            NotificationHelper.ACTION_DISMISS_ALERT_OPEN -> {
                dismissAlert()
                startActivity(Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                })
                return START_STICKY
            }
        }
        */
        if (isRunning) return START_STICKY
        isRunning = true
        try { wakeLock?.acquire() } catch (_: Exception) {}
        try { wifiLock?.acquire() } catch (_: Exception) {}
        val cm = ConfigManager(this); config = cm.load()
        cm.setMonitoringActive(true)
        lastSpeakTime = 0L; lastTotalVol = 0; lastChangePct = 0.0; lastPrice = 0.0; lastSpokenPrice = 0.0; lastAlertHand = 0; intervalLargeEvents.clear()
        normalBroadcastCount = 0; pendingAiSummary = null; aiRequestInFlight = false
        lastFillInTime = 0L; fillInCount = 0; lastDualAnalysisTime = 0L
        postAlertPhase = 0; normalDeferred = false; lastAlertText = ""
        lastPostAlertData = null; batchQueue.clear()
        alertActive = false; alertSettleCount = 0; lastTtsCheckTime = 0L; fetchStartedAt = 0L; watchdogWarned = false
        lastShanghaiIndex = ""; shanghaiFetchCount = 0
        globalSentiment = GlobalSentiment(); lastSentimentFetchTime = 0L
        fundFlowCache = FundFlowData(); lastFundFlowFetchTime = 0L
        conceptBlockCache = ConceptBlockData(); lastConceptFetchTime = 0L
        dragonTigerTag = ""
        changeStyleIndex = 0; priceStyleIndex = 0; speedStyleIndex = 0
        priceHistory.clear()
        lastSpokenStance = null; lastStanceSpeakTime = 0L  // 重置stance冷却
        lastAiTime = 0L; lastAiPrice = 0.0; lastAiFundDir = ""  // 重置冷冻期
        startForeground(NotificationHelper.NOTIFICATION_ID, NotificationHelper.buildMinimal(this))
        uiState.value = uiState.value.copy(isRunning = true, aiLog = aiLogs.toList())
        // 启动时异步抓取龙虎榜静态标签（SharedPreferences日期缓存，过期自动刷新）
        netExecutor.execute {
            try {
                dragonTigerTag = DragonTigerFetcher.fetchDailyTag(config.stockCode, this@StockMonitorService)
                if (dragonTigerTag.isNotBlank()) aiLog("龙虎榜: $dragonTigerTag")
            } catch (_: Exception) {}
        }
        runLoop()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false; uiHandler.removeCallbacksAndMessages(null)
        loopHandler.removeCallbacksAndMessages(null); loopThread.quitSafely()
        ttsEngine.shutdown(); aiAnalyzer.shutdown()
        netExecutor.shutdownNow(); sentimentExecutor.shutdownNow()
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        try { wifiLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
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

        // ── 必须在阻塞工作之前调度下一轮，确保定时链独立于网络请求 ──
        // 息屏后 Doze 挂起整个进程的网络 I/O（OkHttp 超时线程也被暂停），
        // fetch() 可能无限期阻塞。如果把 postDelayed 放在 fetch() 之后，
        // 定时链会因网络阻塞而断裂 —— 这就是息屏停播的根因。
        loopHandler.postDelayed({ runLoop() }, 2000)

        // ── 收盘自动停止播报（15:05 后自动关闭，留 5 分钟缓冲） ──
        calendar.timeInMillis = System.currentTimeMillis()
        val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
        val minute = calendar.get(java.util.Calendar.MINUTE)
        val dayOfWeek = calendar.get(java.util.Calendar.DAY_OF_WEEK)
        if (dayOfWeek in 2..6 && (hour > 15 || (hour == 15 && minute >= 5))) {
            aiLog("🕞 收盘了，自动停止播报")
            ttsEngine.stop()
            stopSelf()
            return
        }

        // 防御性重申请：国产 ROM 会偷偷释放锁，setReferenceCounted(false) 下
        // acquire() 重复调用是安全的。不检查 isHeld——部分 ROM 的 isHeld 返回值不可靠
        try { wakeLock?.acquire(600000L) } catch (_: Exception) {}
        try { wifiLock?.acquire() } catch (_: Exception) {}

        // ── TTS 防卡死（必须在 runLoop 内执行，不能依赖网络 fetch 成功） ──
        // 息屏后 processStockData 因 fetchInFlight 阻塞不被调用，
        // processStockData 内的防卡死检查无法执行 → isSpeaking 永久卡死。
        // 此处在网络阻塞路径之外独立执行，确保每 2 秒循环都能检测。
        val ttsCheckNow = System.currentTimeMillis()
        if (ttsEngine.isSpeaking) {
            if (lastTtsCheckTime == 0L) lastTtsCheckTime = ttsCheckNow
            else if (ttsCheckNow - lastTtsCheckTime > 35000) {
                ttsEngine.stop()
                lastTtsCheckTime = 0L
                watchdogWarned = false
            }
        } else {
            lastTtsCheckTime = 0L
        }

        // ── 网络请求看门狗：fetchInFlight 超过 60 秒未复位 → 强制重置 ──
        // OkHttp 3 秒超时在 Doze 期间被系统挂起，socket 可能在唤醒后处于
        // 半死状态（既不返回数据也不抛超时异常），导致 fetchInFlight 永久为 true。
        if (fetchInFlight) {
            if (fetchStartedAt > 0 && ttsCheckNow - fetchStartedAt > 60000) {
                fetchInFlight = false
                fetchStartedAt = 0L
                if (!watchdogWarned) { watchdogWarned = true }
            }
            return  // 上一轮网络请求仍在阻塞，跳过防止任务堆积
        }
        fetchInFlight = true
        fetchStartedAt = ttsCheckNow
        watchdogWarned = false

        netExecutor.execute {
            if (!isRunning) { fetchInFlight = false; fetchStartedAt = 0L; return@execute }
            try {
                val cm = ConfigManager(this@StockMonitorService)
                config = cm.load()
                val data = StockFetcher.fetch(config.stockCode, config.largeOrderThreshold)
                // 每10次轮询（20秒）拉一次大盘数据
                shanghaiFetchCount++
                if (shanghaiFetchCount % 10 == 1) {
                    lastShanghaiIndex = StockFetcher.fetchShanghaiIndexText()
                }
                // 每5分钟拉一次全市场情绪（异步，不阻塞主轮询）
                val sentimentNow = System.currentTimeMillis()
                if (sentimentNow - lastSentimentFetchTime >= 300000) {
                    lastSentimentFetchTime = sentimentNow
                    sentimentExecutor.execute {
                        try {
                            val s = MarketSentimentFetcher.fetchAll()
                            if (!s.isEmpty) globalSentiment = s
                            val slice = s.toSentimentSlice()
                            val flash = s.toFlashNewsBlock()
                            uiHandler.post {
                                when {
                                    slice.isNotBlank() -> aiLog("情绪: ${slice.take(60)}")
                                    flash.isNotBlank() -> aiLog(flash.take(100))
                                    !s.isEmpty -> aiLog("情绪: 数据已更新")
                                    else -> aiLog("情绪: 暂无数据（非交易时段）")
                                }
                            }
                        } catch (_: Exception) {
                            uiHandler.post { aiLog("情绪: 获取失败") }
                        }
                    }
                }
                // ── v1.1.0: 新数据源拉取 ──
                // 资金流向（每30秒）
                if (sentimentNow - lastFundFlowFetchTime >= 30000) {
                    lastFundFlowFetchTime = sentimentNow
                    sentimentExecutor.execute {
                        try {
                            val ff = FundFlowFetcher.fetch(config.stockCode)
                            if (!ff.isEmpty) fundFlowCache = ff
                        } catch (_: Exception) {}
                    }
                }
                // 概念板块（第一次+每5分钟）
                if (lastConceptFetchTime == 0L || sentimentNow - lastConceptFetchTime >= 300000) {
                    lastConceptFetchTime = sentimentNow
                    sentimentExecutor.execute {
                        try {
                            val cb = ConceptBlockFetcher.fetch(config.stockCode)
                            if (!cb.isEmpty) conceptBlockCache = cb
                        } catch (_: Exception) {}
                    }
                }

                // processStockData 必须在主线程外执行
                // —— 息屏后 uiHandler.post 内的代码不执行，TTS 播报整个停摆
                if (data != null) { processStockData(data) }
                else { uiHandler.post { aiLog("⚠ 行情获取为空，检查是否息屏断网") } }
            } finally {
                fetchInFlight = false
                fetchStartedAt = 0L
            }
        }
    }

    private fun processStockData(data: StockData) {
        val currentHand = if (lastTotalVol > 0) maxOf(0, data.totalVol - lastTotalVol) else 0
        // 滑动窗口区间涨速：用~30秒价格轨迹计算真实区间涨幅，捕捉蚂蚁搬家式连续点火
        priceHistory.addLast(data.price)
        if (priceHistory.size > 8) priceHistory.removeFirst()
        val speed = if (priceHistory.size >= 2 && priceHistory.first() > 0) {
            Math.round((data.price - priceHistory.first()) / priceHistory.first() * 10000.0) / 100.0
        } else 0.0
        val prevPrice = lastPrice
        lastTotalVol = data.totalVol; lastChangePct = data.changePct; lastPrice = data.price
        lastStockData = data; lastSpeed = speed
        val dynThreshold = getDynamicThreshold(data.turnover, data.volRatio)
        val now = System.currentTimeMillis()
        val nowStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now))
        val absSpeed = Math.abs(speed)

        val prev = uiState.value
        if (prev.price != data.price || prev.changePct != data.changePct ||
            prev.speed != speed || prev.currentHand != currentHand ||
            prev.volRatio != data.volRatio || prev.stockName != data.name || !prev.isRunning) {
            uiState.value = prev.copy(
                stockName = data.name, price = data.price, changePct = data.changePct,
                speed = speed, amount = data.amountStr, volRatio = data.volRatio,
                currentHand = currentHand, largeAsks = data.largeAsks, largeBids = data.largeBids,
                largeAsksSpeak = data.largeAsksSpeak, largeBidsSpeak = data.largeBidsSpeak, isRunning = true
            )
        }
        if (isPaused) return

        // ── TTS 防卡死：息屏后 isSpeaking 可能永远不回调 → 35秒强制重置 ──
        if (ttsEngine.isSpeaking) {
            if (lastTtsCheckTime == 0L) lastTtsCheckTime = now
            else if (now - lastTtsCheckTime > 35000) {
                ttsEngine.stop()
                lastTtsCheckTime = 0L
                aiLog("⚠ TTS 卡死，强制重置")
            }
        } else {
            lastTtsCheckTime = 0L
        }

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
        val timePassed = now - lastHandAlertTime >= alertCooldownMs
        val isBiggerOrder = lastAlertHand > 0 && currentHand >= (lastAlertHand * 1.5)
        if (currentHand >= dynThreshold && (timePassed || isBiggerOrder)) {
            val (dirLabel, action) = describeDirection(prevPrice, data.price)
            alertText = "${alertPrefix()}${spokenHand(currentHand)}${dirLabel}${action}！"
            ttsEngine.speakAlert(alertText)
            alertSpoken = true; lastHandAlertTime = now; lastAlertSpeakTime = now
            lastAlertHand = currentHand
        }
        if (config.speakSpeed && !alertSpoken && absSpeed >= config.speedAlertThreshold && now - lastSpeedAlertTime >= alertCooldownMs) {
            val dir = if (speed > 0) "快速拉升" else "快速下跌"
            val tag = if (speed > 0) "涨幅" else "跌幅"
            alertText = "${alertPrefix()}$dir！${data.name}当前${spokenPrice(data.price)}，${tag}${fmtPct(absSpeed)}%"
            ttsEngine.speakAlert(alertText)
            alertSpoken = true; lastSpeedAlertTime = now; lastAlertSpeakTime = now
        }
        if (!alertSpoken && now - lastPatternAlertTime >= alertCooldownMs) {
            try {
                val snapshot = MarketSnapshot(now, data.price, data.changePct, speed, data.volRatio,
                    data.amountStr, currentHand, data.largeAsksSpeak.size, data.largeBidsSpeak.size, data.turnover)
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
            // v1.1.0+ 异动通知暂禁用
            // NotificationHelper.notifyAlert(this@StockMonitorService,
            //     NotificationHelper.buildAlert(this@StockMonitorService, alertText))
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
            val stillAlertSpeed = config.speakSpeed && absSpeed >= config.speedAlertThreshold
            if (stillAlertHand || stillAlertSpeed) {
                alertSettleCount = 0
                if (now - lastAlertSpeakTime >= 8000) {
                    val update = if (stillAlertHand) {
                        val (dl, act) = describeDirection(prevPrice, data.price)
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
            val (_, action) = describeDirection(prevPrice, data.price)
            intervalLargeEvents.add(Triple(nowStr, action, currentHand))
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
                    normalBroadcastCount++
                    uiState.value = uiState.value.copy(lastSpeakTime = nowStr)
                    maybeGenerateAiSummary(data, speed)
                } else {
                    buildSpeakText(data, currentHand, speed, dynThreshold)?.let { text ->
                        if (ttsEngine.speak(text)) {
                            lastSpokenPrice = data.price
                            lastSpeakTime = now; intervalLargeEvents.clear()
                            normalBroadcastCount++; fillInCount = 0
                            uiState.value = uiState.value.copy(lastSpeakTime = nowStr)
                            maybeGenerateAiSummary(data, speed)
                        }
                    } ?: run { intervalLargeEvents.clear() }
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

    /** 从所有缓存数据源构建富上下文（v1.1.0 — 四象限防伪 + Alpha差值） */
    private fun buildContext(data: StockData, speed: Double = 0.0): MarketContext {
        val sector = if (config.conceptAutoDetect && conceptBlockCache.industry.isNotBlank()) {
            buildString {
                append(conceptBlockCache.industry)
                if (conceptBlockCache.topConcept.isNotBlank()) append("/${conceptBlockCache.topConcept}")
            }
        } else config.stockSector

        // ── 四象限资金防伪判定 ──
        val fundInflow = fundFlowCache.mainForce > 100
        val fundOutflow = fundFlowCache.mainForce < -100
        val speedUp = speed > 0.5
        val speedDown = speed < -0.5
        val speedNegative = speed < 0
        val speedPositive = speed > 0
        val quadrant = when {
            fundInflow && speedUp -> "主力真拉升（净流入+涨速共振）"
            fundInflow && speedNegative -> "主力暗中派发（净流入但涨速为负，量价背离）"
            fundOutflow && speedUp -> "散户推升/无量空涨（净流出但涨速为正，缺大单支持）"
            fundOutflow && speedDown -> "主力真砸盘（净流出+跌速共振）"
            fundInflow && speedPositive -> "主力偏多，涨速温和"
            fundOutflow && speedNegative -> "主力偏空，跌速温和"
            fundInflow -> "主力流入但涨速不明"
            fundOutflow -> "主力流出但跌速不明"
            else -> ""
        }

        // Alpha差值
        val sectorPct = if (conceptBlockCache.industryPct != 0.0) conceptBlockCache.industryPct
                        else conceptBlockCache.topConceptPct
        val alpha = conceptBlockCache.alphaDiff(data.changePct)

        return MarketContext(
            stockSector = sector,
            fundFlowDirection = fundFlowCache.directionLabel.takeIf { config.fundFlowEnabled } ?: "",
            fundFlowAmount = fundFlowCache.mainForceStr.takeIf { config.fundFlowEnabled } ?: "",
            fundFlowQuadrant = quadrant.takeIf { config.fundFlowEnabled && it.isNotBlank() } ?: "",
            blockInfo = conceptBlockCache.toContextSlice().trimEnd('。').takeIf { config.conceptAutoDetect } ?: "",
            relativeStrength = conceptBlockCache.relativeStrength(data.changePct).takeIf { config.conceptAutoDetect } ?: "",
            alphaDiff = alpha.takeIf { config.conceptAutoDetect && it.isNotBlank() } ?: "",
            sectorPct = sectorPct,
            dragonTigerTag = dragonTigerTag.takeIf { config.dragonTigerEnabled && it.isNotBlank() } ?: "",
            mcapContext = data.mcapAssessment.takeIf { it.isNotBlank() } ?: "",
            limitDistance = buildString {
                if (config.alertLimitDistance) {
                    if (data.limitUpDist > 0 && data.limitUpDist < 3.0) append("距涨停仅${"%.1f".format(data.limitUpDist)}%")
                    if (data.limitDownDist > 0 && data.limitDownDist < 3.0) append("距跌停仅${"%.1f".format(data.limitDownDist)}%")
                }
            }.takeIf { it.isNotBlank() } ?: "",
            newsHeadline = globalSentiment.toFlashNewsBlock().takeIf { it.isNotBlank() }?.take(80) ?: "",
            alertStats = ""
        )
    }

    private fun maybeGenerateAiSummary(data: StockData, speed: Double) {
        if (!config.aiEnabled || config.aiApiKey.isBlank()) return
        if (aiRequestInFlight) return
        // ── 状态机冷冻期：价格+资金方向无实质变化则跳过 ──
        val now = System.currentTimeMillis()
        val curFundDir = when {
            fundFlowCache.mainForce > 100 -> "in"
            fundFlowCache.mainForce < -100 -> "out"
            else -> ""
        }
        if (lastAiTime > 0 && now - lastAiTime < 180000) {
            val priceChange = if (lastAiPrice > 0) Math.abs(data.price - lastAiPrice) / lastAiPrice * 100 else 999.0
            if (priceChange < 0.5 && curFundDir == lastAiFundDir) {
                return  // 价格和资金方向均无实质变化，沿用上次结果
            }
        }
        if (normalBroadcastCount % config.aiSummaryInterval != 0) return
        aiRequestInFlight = true
        val ctx = buildContext(data, speed)
        aiAnalyzer.generateSummary(ctx, lastShanghaiIndex, globalSentiment) { summary ->
            aiRequestInFlight = false
            // 更新冷冻期状态
            lastAiTime = System.currentTimeMillis()
            lastAiPrice = data.price
            lastAiFundDir = when {
                fundFlowCache.mainForce > 100 -> "in"
                fundFlowCache.mainForce < -100 -> "out"
                else -> ""
            }
            if (summary != null) uiHandler.post {
                // stance 冷却检查：5分钟内不重复同一建议
                val currentStance = aiAnalyzer.lastStance
                val now = System.currentTimeMillis()
                if (currentStance != null && currentStance == lastSpokenStance &&
                    now - lastStanceSpeakTime < stanceCooldownMs) {
                    aiLog("AI点评: 跳过重复stance ${currentStance.label}")
                    return@post
                }
                if (currentStance != null) {
                    lastSpokenStance = currentStance
                    lastStanceSpeakTime = now
                }

                aiLog("AI点评: ${summary.take(30)}...")
                if (summary.length > 60) {
                    // 长文本 → 辅AI拆分为短句分批播报
                    aiAnalyzer.splitIntoBatches(summary) { batches ->
                        uiHandler.post {
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
        val postCtx = lastPostAlertData?.let { buildContext(it, 0.0) } ?: MarketContext(stockSector = config.stockSector)
        aiAnalyzer.generatePostAlertAnalysis(alertText, snapshots, lastShanghaiIndex, globalSentiment, config.stockSector, postCtx) { result ->
            aiRequestInFlight = false
            if (result != null) uiHandler.post {
                // stance 冷却检查：异动复盘也需要避免重复
                val currentStance = aiAnalyzer.lastStance
                val now = System.currentTimeMillis()
                if (currentStance != null && currentStance == lastSpokenStance &&
                    now - lastStanceSpeakTime < stanceCooldownMs) {
                    aiLog("异动复盘: 跳过重复stance ${currentStance.label}")
                    return@post
                }
                if (currentStance != null) {
                    lastSpokenStance = currentStance
                    lastStanceSpeakTime = now
                }

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
        aiLog("双AI: 启动分析...")
        netExecutor.execute {
            val history = StockFetcher.fetchDailyHistoryText(config.stockCode)
            val index = StockFetcher.fetchShanghaiIndexText()
            // 复用 buildContext() 确保双AI获取完整上下文（四象限/流通市值/涨跌停等）
            val ctx = lastStockData?.let { buildContext(it, lastSpeed) } ?: MarketContext()
            aiAnalyzer.generateDualAnalysis(
                aiAnalyzer.getRecentSnapshots(),
                dailyHistory = history,
                shanghaiIndex = index,
                globalSentiment = globalSentiment,
                context = ctx
            ) { text ->
                aiRequestInFlight = false
                if (text != null) uiHandler.post {
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

        // v1.1.0: 资金流向放在涨幅之后、其他数据之前，确保突出
        // 资金流向和成交额各自独立控制
        if (config.fundFlowEnabled && !fundFlowCache.isEmpty) {
            val flowText = "${fundFlowCache.directionLabel}${fundFlowCache.mainForceStr}"
            // 四象限：流入+跌速 → 派发警告；流出+涨速 → 无量空涨
            val fundInflow = fundFlowCache.mainForce > 100
            val fundOutflow = fundFlowCache.mainForce < -100
            if (fundInflow && speed < 0) {
                parts.add("⚠主力派发，$flowText")
            } else if (fundOutflow && speed > 0.5) {
                parts.add("⚠无量空涨，$flowText")
            } else {
                parts.add(flowText)
            }
        } else if (config.speakAmount) {
            parts.add("成交${spokenAmount(data.amountStr)}")
        }

        if (config.speakSpeed && Math.abs(speed) >= 0.05) parts.add(spokenSpeed(speed))
        if (config.speakVolRatio) spokenVolRatio(data.volRatio)?.let { parts.add(it) }
        if (config.speakCurrentHand && currentHand >= dynThreshold) parts.add("现手${spokenHand(currentHand)}")

        // v1.1.0: 板块相对强弱（概念板块自动识别时）
        if (config.conceptAutoDetect && !conceptBlockCache.isEmpty) {
            val rs = conceptBlockCache.relativeStrength(data.changePct)
            if (rs.isNotBlank()) parts.add(rs)
        }

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

    // ── 大单方向判断（必须用当前价vs上一秒价，严禁用changePct） ──

    private fun describeDirection(prevPrice: Double, price: Double): Pair<String, String> = when {
        prevPrice > 0 && price > prevPrice -> "大单" to "向上扫货"
        prevPrice > 0 && price < prevPrice -> "大单" to "向下砸盘"
        else -> "大单" to "激烈成交"
    }

    private fun dismissAlert() {
        ttsEngine.stop()
        // v1.1.0+ 异动通知暂禁用
        // NotificationHelper.cancelAlert(this)
        alertActive = false; alertSettleCount = 0; postAlertPhase = 0
        lastPostAlertData = null; batchQueue.clear()
        aiLog("🔕 关闭异动提醒")
        // updateNotif()
    }

    // ── 异动前缀（随机切换，确保异动播报有辨识度） ──

    private fun alertPrefix(): String {
        val prefixes = listOf("注意：", "异动：", "注意注意：", "警报：", "提醒：")
        return prefixes.random()
    }

    // ── 换手率动态阈值（防开盘乱叫，大小盘自适应） ──

    private fun getDynamicThreshold(turnover: Double, volRatio: Double): Int {
        val base = config.largeOrderThreshold
        calendar.timeInMillis = System.currentTimeMillis()
        val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
        val minute = calendar.get(java.util.Calendar.MINUTE)
        // 换手率系数
        // 9:30-10:30 换手率绝对值太低无意义，只看量比
        // 10:30 之后恢复原逻辑，但底部条件必须换手+量比双低才降阈值
        val turnoverCoef = when {
            hour == 9 && minute >= 30 || hour == 10 && minute < 30 -> {
                if (volRatio > 2.0) 1.5 else 1.0
            }
            turnover > 15 -> 2.5
            turnover >= 8 -> 1.5
            turnover < 2 && volRatio < 0.8 -> 0.6
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
            0 -> buildString {
                append("${intPart}块")
                if (jiao > 0) { append(jiao); if (fen > 0) append("毛$fen") }
                else if (fen > 0) append("零$fen")
            }
            1 -> buildString {
                append("${intPart}点")
                if (jiao > 0) { append(jiao); if (fen > 0) append(fen) }
                else if (fen > 0) append("零$fen")
            }
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

    // v1.1.0+ 通知栏实时更新暂禁用
    /*
    private fun updateNotif(data: StockData? = null) {
        if (data != null && data.price == lastNotifPrice && data.changePct == lastNotifPct && isPaused == lastNotifPaused) return
        lastNotifPrice = data?.price ?: -1.0
        lastNotifPct = data?.changePct ?: 0.0
        lastNotifPaused = isPaused
        val builder = if (data != null)
            NotificationHelper.buildWithData(this, data.name, data.price, data.changePct, isPaused)
        else {
            val b = NotificationHelper.buildWithData(this, "监控中", 0.0, 0.0, isPaused)
            b.setContentText(if (isPaused) "已暂停播报" else "继续监控中...")
            b
        }
        NotificationHelper.notify(this, builder)
    }
    */
}
