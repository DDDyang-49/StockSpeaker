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
    val aiLog: List<String> = emptyList(),
    val audioState: PrivateAudioState = PrivateAudioState.NO_HEADSET
)

class StockMonitorService : Service() {

    companion object {
        val uiState = MutableStateFlow(ServiceUiState())
        fun start(context: Context) { context.startForegroundService(Intent(context, StockMonitorService::class.java)) }
        fun stop(context: Context) {
            ConfigManager(context).setMonitoringActive(false)
            context.stopService(Intent(context, StockMonitorService::class.java))
        }
        @Volatile private var routeGuardRef: AudioRouteGuard? = null
        fun resumePrivateAudio(): Boolean = routeGuardRef?.resume() ?: false
    }

    private lateinit var config: AppConfig
    private lateinit var uiHandler: Handler
    private lateinit var loopThread: HandlerThread
    private lateinit var loopHandler: Handler
    private lateinit var ttsEngine: TtsEngine
    private lateinit var routeGuard: AudioRouteGuard
    private lateinit var aiAnalyzer: AIAnalyzer
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var isRunning = false
    private var isPaused = false
    private var lastSpeakTime = 0L
    private var lastTotalVol = 0
    private var lastChangePct = 0.0
    private var lastPrice = 0.0
    private var lastSpokenPrice = 0.0  // 听觉锚点：上次真正交给 TTS 的价格
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
    private val alertFollowUp = AlertFollowUpTracker()
    private val orderBookAlerts = OrderBookAlertTracker()
    private var alertStage = SignalStage.QUIET
    private var normalBroadcastCount = 0
    private var pendingAiSummary: String? = null
    private var aiRequestInFlight = false
    private var lastFillInTime = 0L
    private var fillInCount = 0
    private var lastDualAnalysisTime = 0L
    private var normalDeferred = false
    private val listeningEngine = LocalListeningEngine()
    private val contextPool = SilentContextPool()
    private val quoteGate = QuoteFreshnessGate()
    private var lastMetrics: ListeningMetrics? = null
    @Volatile private var pendingContextText: String? = null
    private var lastQuoteReceivedAt = 0L
    private var monitorStartedAt = 0L
    private var quoteInterruptedAnnounced = false
    private val netExecutor = Executors.newSingleThreadExecutor()
    private val sentimentExecutor = Executors.newSingleThreadExecutor()  // 情绪抓取独立线程，不阻塞行情轮询
    private val aiLogs = mutableListOf<String>()
    private var lastShanghaiIndex = ""
    private var shanghaiFetchCount = 0
    private var lastTtsCheckTime = 0L  // TTS 防卡死：上次检查时间
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
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
            AiConfig(enabled = c.aiEnabled, apiKey = BuildConfig.SENSENOVA_API_KEY,
                apiUrl = "https://token.sensenova.cn/v1/chat/completions",
                model = "sensenova-6.7-flash-lite", summaryInterval = c.aiSummaryInterval,
                provider = "sensenova")
        }, onLog = { msg -> aiLog(msg) })
        routeGuard = AudioRouteGuard(this,
            onRouteLost = { if (::ttsEngine.isInitialized) ttsEngine.stop() },
            onStateChanged = { state ->
                uiState.value = uiState.value.copy(
                    audioState = state,
                    statusText = when (state) {
                        PrivateAudioState.NO_HEADSET -> "🎧 请连接耳机"
                        PrivateAudioState.LATCHED_MUTE -> "🔇 播报已锁定，重连后请点恢复"
                        PrivateAudioState.READY -> "✅ 耳机播报已就绪"
                        PrivateAudioState.SPEAKING -> "🔊 正在耳机播报"
                    }
                )
            })
        routeGuardRef = routeGuard
        ttsEngine = TtsEngine(this, cacheDir, { msg -> aiLog(msg) }, routeGuard)
        ttsEngine.init()
        routeGuard.start()
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
        routeGuard.resume()
        cm.setMonitoringActive(true)
        lastSpeakTime = 0L; lastTotalVol = 0; lastChangePct = 0.0; lastPrice = 0.0; lastSpokenPrice = 0.0; lastAlertHand = 0
        normalBroadcastCount = 0; pendingAiSummary = null; aiRequestInFlight = false
        lastFillInTime = 0L; fillInCount = 0; lastDualAnalysisTime = 0L
        normalDeferred = false
        alertActive = false; alertSettleCount = 0; lastTtsCheckTime = 0L; fetchStartedAt = 0L; watchdogWarned = false
        lastShanghaiIndex = ""; shanghaiFetchCount = 0
        globalSentiment = GlobalSentiment(); lastSentimentFetchTime = 0L
        fundFlowCache = FundFlowData(); lastFundFlowFetchTime = 0L
        conceptBlockCache = ConceptBlockData(); lastConceptFetchTime = 0L
        dragonTigerTag = ""
        listeningEngine.reset(); contextPool.reset(); alertFollowUp.reset(); orderBookAlerts.reset()
        alertStage = SignalStage.QUIET; lastMetrics = null; pendingContextText = null
        quoteGate.reset(); lastQuoteReceivedAt = 0L; monitorStartedAt = System.currentTimeMillis(); quoteInterruptedAnnounced = false
        lastAiTime = 0L; lastAiPrice = 0.0; lastAiFundDir = ""  // 重置冷冻期
        startForeground(NotificationHelper.NOTIFICATION_ID, NotificationHelper.buildMinimal(this))
        uiState.value = uiState.value.copy(isRunning = true, aiLog = aiLogs.toList())
        // 启动时异步抓取龙虎榜静态标签（SharedPreferences日期缓存，过期自动刷新）
        netExecutor.execute {
            try {
                listeningEngine.setHistoricalAnchors(StockFetcher.fetchHistoricalAnchors(config.stockCode))
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
        ttsEngine.shutdown(); routeGuard.stop(); routeGuardRef = null; aiAnalyzer.shutdown()
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

        // 非 A 股有效时段不抓取、不计算、更不播旧价。集合竞价单独建基线。
        val wallNow = System.currentTimeMillis()
        val phaseNow = TradingPhase.at(wallNow)
        if (phaseNow == TradingPhase.CLOSED) {
            val shanghai = java.time.Instant.ofEpochMilli(wallNow)
                .atZone(java.time.ZoneId.of("Asia/Shanghai"))
            if (shanghai.dayOfWeek.value <= 5 && shanghai.hour == 15 && shanghai.minute >= 5) stopSelf()
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
                val data = StockFetcher.fetch(config.stockCode)
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
                            if (!s.isEmpty) {
                                globalSentiment = s
                                contextPool.observeSentiment(s)
                            }
                            val slice = s.toSentimentSlice()
                            uiHandler.post {
                                when {
                                    slice.isNotBlank() -> aiLog("情绪: ${slice.take(60)}")
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
                val receivedAt = System.currentTimeMillis()
                val sourceFresh = quoteGate.accept(data, receivedAt)
                if (sourceFresh) {
                    val freshData = data!!
                    lastQuoteReceivedAt = receivedAt
                    if (quoteInterruptedAnnounced) {
                        quoteInterruptedAnnounced = false
                        speakBusinessAlert("行情恢复", freshData)
                    }
                    processStockData(freshData)
                } else {
                    val freshnessBase = if (lastQuoteReceivedAt > 0L) lastQuoteReceivedAt else monitorStartedAt
                    if (freshnessBase > 0L && receivedAt - freshnessBase > 30_000L) announceQuoteInterrupted()
                    // 无成交时行情源会重复同一时间戳，这是正常静默帧，不污染运行日志。
                }
            } finally {
                fetchInFlight = false
                fetchStartedAt = 0L
            }
        }
    }

    private fun processStockData(data: StockData) {
        val currentHand = if (lastTotalVol > 0) maxOf(0, data.totalVol - lastTotalVol) else 0
        val metrics = listeningEngine.onQuote(data) ?: return
        lastMetrics = metrics
        contextPool.onFrame(data.changePct, contextSectorPct(), indexChangePct())?.let {
            pendingContextText = it
        }
        val speed = metrics.speed15sPct
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

        // ═══════════════════════════════════════
        // 轨道1：实时异动（最高优先级，可打断一切）
        // 本地状态事件优先；连续大额成交保留高频反馈，但只播当前聚合结果。
        // ═══════════════════════════════════════
        val alertCooldownMs = 3000L
        var alertSpoken = false
        var followUpEligible = false
        var alertText = ""
        val stageChanged = metrics.stage != alertStage
        alertStage = metrics.stage

        if (config.speakLargeOrders) {
            orderBookAlerts.evaluate(data, config.largeOrderThreshold, now)?.let { bookText ->
                if (speakBusinessAlert(bookText, data)) {
                    aiLog("触发：盘口大挂单")
                    alertSpoken = true
                }
            }
        }
        if (metrics.eventText != null) {
            alertText = "盘面事件：${metrics.eventText}" + pendingContextText?.let { "，$it" }.orEmpty()
            if (!alertSpoken && speakBusinessAlert(alertText, data)) {
                pendingContextText = null
                alertFollowUp.start(data.price, now)
                followUpEligible = true
                alertSpoken = true; lastPatternAlertTime = now; lastAlertSpeakTime = now
                aiLog("触发：状态升级 ${metrics.stage}")
            }
        }
        val direction = describeDirection(prevPrice, data.price).second
        val burst = if (config.speakLargeOrders) {
            listeningEngine.recordLargeOrder(data.sourceTimeMillis, currentHand, direction, dynThreshold)
        } else null
        val timePassed = now - lastHandAlertTime >= alertCooldownMs
        val isBiggerOrder = lastAlertHand > 0 && currentHand >= (lastAlertHand * 1.5)
        if (!alertSpoken && burst != null && (timePassed || isBiggerOrder)) {
            alertText = "异动：${burst.speech}"
            val accepted = if (alertFollowUp.isActive) speakCompactAlert(alertText, data) else speakBusinessAlert(alertText, data)
            if (accepted) {
                pendingContextText = null
                alertFollowUp.start(data.price, now)
                followUpEligible = true
                alertSpoken = true; lastHandAlertTime = now; lastAlertSpeakTime = now
                lastAlertHand = currentHand
                aiLog("触发：连续大额成交第${burst.count}次")
            }
        }
        if (config.speakSpeed && !alertSpoken && absSpeed >= config.speedAlertThreshold && now - lastSpeedAlertTime >= alertCooldownMs) {
            val dir = if (speed > 0) "价格快速上行" else "价格快速下行"
            alertText = "异动：$dir，十五秒变化${fmtPct(absSpeed)}%"
            val accepted = if (alertFollowUp.isActive) speakCompactAlert(alertText, data) else speakBusinessAlert(alertText, data)
            if (accepted) {
                pendingContextText = null
                alertFollowUp.start(data.price, now)
                followUpEligible = true
                alertSpoken = true; lastSpeedAlertTime = now; lastAlertSpeakTime = now
                aiLog("触发：短周期涨速")
            }
        }
        if (alertSpoken) {
            // v1.1.0+ 异动通知暂禁用
            // NotificationHelper.notifyAlert(this@StockMonitorService,
            //     NotificationHelper.buildAlert(this@StockMonitorService, alertText))
            alertActive = followUpEligible; alertSettleCount = 0
            normalDeferred = false
            lastSpeakTime = now
            uiState.value = uiState.value.copy(lastSpeakTime = nowStr)
            return
        }

        // ── 轨道1b：异动平复等待 ──
        // 异动触发后持续检测盘面，直到连续3轮（6秒）无异动才进入复盘
        if (alertActive) {
            val stillAlertHand = config.speakLargeOrders && currentHand >= dynThreshold
            val stillAlertSpeed = config.speakSpeed && absSpeed >= config.speedAlertThreshold
            if (stillAlertHand || stillAlertSpeed) {
                alertSettleCount = 0
                alertFollowUp.continuing(data.price, now, stageChanged)?.let { follow ->
                    if (!ttsEngine.isSpeaking && speakCompactAlert(follow, data)) {
                        aiLog("异动跟进：出现新变化")
                        lastSpeakTime = now
                    }
                }
                return
            }
            // 当前无异动条件，累计平复轮次
            alertSettleCount++
            if (alertSettleCount < 3) return
            alertActive = false
            alertFollowUp.settle(data.price)?.let { settled ->
                if (!ttsEngine.isSpeaking && speakCompactAlert(settled, data)) {
                    aiLog("异动跟进：平复")
                    lastSpeakTime = now
                }
                uiState.value = uiState.value.copy(lastSpeakTime = nowStr)
                return
            }
        }

        // ═══════════════════════════════════════
        // 轨道2：定时常规播报 + AI 点评 + 长间隔AI插播
        // ═══════════════════════════════════════
        val intervalMs = config.speakInterval * 1000L
        val elapsed = now - lastSpeakTime

        if (!ttsEngine.isSpeaking) {
            // 被推迟的正常播报 → 简洁版
            if (normalDeferred) {
                speakBusiness("${conciseChange(data.changePct)}。", data)
                aiLog("简洁: ${concisePrice(data.price)} ${conciseChange(data.changePct)}")
                normalDeferred = false
                lastSpeakTime = now
                uiState.value = uiState.value.copy(lastSpeakTime = nowStr)
                return
            }

            if (pendingAiSummary != null) {
                val summary = pendingAiSummary!!
                pendingAiSummary = null
                speakCompactAlert(summary, data)
                uiState.value = uiState.value.copy(lastSpeakTime = nowStr)
                return
            }

            if (elapsed >= intervalMs) {
                val currentAmplitude = if (metrics.recentLow > 0.0) {
                    (metrics.recentHigh - metrics.recentLow) / metrics.recentLow * 100.0
                } else 999.0
                val boxSilent = currentAmplitude < 0.4 &&
                    data.volRatio < 2.0 &&
                    absSpeed < 0.3
                if (boxSilent) {
                    val heartbeat = listOfNotNull("价格窄幅震荡", pendingContextText).joinToString("，") + "。"
                    if (speakBusiness(heartbeat, data)) pendingContextText = null
                    lastSpeakTime = now
                    normalBroadcastCount++
                    uiState.value = uiState.value.copy(lastSpeakTime = nowStr)
                    maybeGenerateAiSummary(data, speed)
                } else {
                    buildSpeakText(data, currentHand, speed, dynThreshold, skipName = normalBroadcastCount > 0)?.let { text ->
                        if (speakBusiness(text, data)) {
                            pendingContextText = null
                            lastSpokenPrice = data.price
                            lastSpeakTime = now
                            normalBroadcastCount++; fillInCount = 0
                            uiState.value = uiState.value.copy(lastSpeakTime = nowStr)
                            maybeGenerateAiSummary(data, speed)
                        }
                    }
                }
            }
        } else {
            // TTS 正忙 → 正常播报到时间了就标记推迟
            if (elapsed >= intervalMs && !normalDeferred) {
                normalDeferred = true
            }
        }
    }

    private fun maybeGenerateAiSummary(data: StockData, speed: Double) {
        if (!config.aiEnabled || BuildConfig.SENSENOVA_API_KEY.isBlank()) return
        if (config.aiSummaryInterval <= 0) return
        if (aiRequestInFlight) return
        val now = System.currentTimeMillis()
        val aiIntervalMs = maxOf(60_000L, config.aiSummaryInterval.toLong() * config.speakInterval * 1000L)
        if (lastAiTime > 0L && now - lastAiTime < aiIntervalMs) return
        val slots = buildAiEvidenceSlots(data, speed)
        if (slots.isEmpty()) {
            aiLog("AI辅助：证据不足，本次跳过")
            lastAiTime = now
            return
        }
        aiRequestInFlight = true
        aiLog("AI辅助：请求中（${slots.size}项本地证据）")
        aiAnalyzer.generateSummary(slots) { summary ->
            aiRequestInFlight = false
            // 更新冷冻期状态
            lastAiTime = System.currentTimeMillis()
            lastAiPrice = data.price
            lastAiFundDir = when {
                fundFlowCache.mainForce > 100 -> "in"
                fundFlowCache.mainForce < -100 -> "out"
                else -> ""
            }
            uiHandler.post {
                if (summary == null) {
                    aiLog("AI辅助：返回未通过本地校验")
                } else {
                    aiLog("AI辅助：已生成 ${summary.take(30)}")
                    val concise = "AI辅助，${summary.take(70)}"
                    if (!ttsEngine.isSpeaking) {
                        if (!speakCompactAlert(concise)) pendingAiSummary = concise
                    } else {
                        pendingAiSummary = concise
                    }
                }
            }
        }
    }

    private fun buildAiEvidenceSlots(data: StockData, speed: Double): List<AiEvidenceSlot> = buildList {
        when {
            data.changePct >= 1.0 -> add(AiEvidenceSlot("change_up", "change_up", "当日上涨${fmtPct(data.changePct)}%", "当日价格偏强"))
            data.changePct <= -1.0 -> add(AiEvidenceSlot("change_down", "change_down", "当日下跌${fmtPct(-data.changePct)}%", "当日价格偏弱"))
            else -> add(AiEvidenceSlot("change_flat", "change_flat", "当日涨跌${fmtPct(data.changePct)}%", "当日价格震荡"))
        }
        lastMetrics?.let { add(AiEvidenceSlot("local_stage", "local_stage", "本地状态${it.stage}", when (it.stage) {
            SignalStage.BREAKOUT_ATTEMPT -> "正在尝试突破"
            SignalStage.BREAKOUT_CONFIRMED -> "突破已经确认"
            SignalStage.ACCELERATING -> "短周期仍在加速"
            SignalStage.EXHAUSTION_RISK -> "高位动能开始衰减"
            SignalStage.REVERSAL_CONFIRMED -> "短周期反转已经确认"
            SignalStage.EXIT_SIGNAL -> "本地风险信号已升级"
            else -> "短周期暂时平稳"
        })) }
        if (speed >= 0.2) add(AiEvidenceSlot("speed_up", "price_accelerating",
            "十五秒价格上行${fmtPct(speed)}%", "价格正在加速"))
        if (speed <= -0.2) add(AiEvidenceSlot("speed_down", "price_weakening",
            "十五秒价格下行${fmtPct(-speed)}%", "短周期价格转弱"))
        lastMetrics?.anchorText?.let {
            add(AiEvidenceSlot("price_anchor", "near_anchor", it, it))
        }
        if (!fundFlowCache.isEmpty && fundFlowCache.mainForce > 0) {
            add(AiEvidenceSlot("fund_in", "fund_in", "资金净流入${fundFlowCache.mainForceStr}", "资金增量偏强"))
        }
        if (!fundFlowCache.isEmpty && fundFlowCache.mainForce < 0) {
            add(AiEvidenceSlot("fund_out", "fund_out", "资金净流出${fundFlowCache.mainForceStr.removePrefix("-")}", "资金增量偏弱"))
        }
        val relative = conceptBlockCache.relativeStrength(data.changePct)
        if (relative.contains("强于")) add(AiEvidenceSlot("sector_strong", "sector_strong", relative, "个股强于板块"))
        if (relative.contains("弱于")) add(AiEvidenceSlot("sector_weak", "sector_weak", relative, "个股弱于板块"))
        pendingContextText?.let { add(AiEvidenceSlot("market_context", "market_context", it, it)) }
    }

    private fun buildSpeakText(data: StockData, currentHand: Int, speed: Double, dynThreshold: Int, skipName: Boolean = false): String? {
        val parts = mutableListOf<String>()
        // 连续播报时跳过股票名称，只播报价格
        parts.add(businessPrefix(data))
        if (config.speakPct) parts.add(spokenChange(data.changePct))

        // v1.1.0: 资金流向放在涨幅之后、其他数据之前，确保突出
        // 资金流向和成交额各自独立控制
        if (config.fundFlowEnabled && !fundFlowCache.isEmpty) {
            val amount = fundFlowCache.mainForceStr.removePrefix("-")
            parts.add(when {
                fundFlowCache.mainForce > 0 -> "资金净流入$amount"
                fundFlowCache.mainForce < 0 -> "资金净流出$amount"
                else -> "资金流平衡"
            })
        }
        if (config.speakAmount) {
            parts.add("${spokenAmount(data.amountStr)}")
        }

        if (config.speakSpeed && Math.abs(speed) >= 0.1) parts.add(spokenSpeed(speed))
        if (config.speakVolRatio) parts.add(spokenVolRatio(data.volRatio))
        if (config.speakCurrentHand && currentHand >= dynThreshold) {
            parts.add(ListeningSpeechFormatter.addedVolume(lastMetrics?.frameWindowSeconds ?: 2, currentHand))
        }
        lastMetrics?.anchorText?.let(parts::add)
        pendingContextText?.let(parts::add)

        // v1.1.0: 板块相对强弱（概念板块自动识别时）
        if (config.conceptAutoDetect && !conceptBlockCache.isEmpty) {
            val rs = conceptBlockCache.relativeStrength(data.changePct)
            if (rs.isNotBlank()) parts.add(rs)
        }

        val main = parts.joinToString("，") + "。"
        val alert = if (config.speakLargeOrders) spokenLargeOrders(data)?.let { "$it。" } ?: "" else ""
        // 成交明细播报
        val detail = if (config.speakTransactionDetail && currentHand >= dynThreshold) {
            val dir = when { speed > 0.3 -> "向上成交"; speed < -0.3 -> "向下成交"; else -> "成交" }
            "${spokenTime()}，近${lastMetrics?.frameWindowSeconds ?: 2}秒${dir}${spokenHand(currentHand)}。"
        } else ""
        val full = main + alert + detail
        return if (full == "。") null else full
    }

    // ── 大单方向判断（必须用当前价vs上一秒价，严禁用changePct） ──

    private fun describeDirection(prevPrice: Double, price: Double): Pair<String, String> = when {
        prevPrice > 0 && price > prevPrice -> "大单" to "向上成交"
        prevPrice > 0 && price < prevPrice -> "大单" to "向下成交"
        else -> "大单" to "激烈成交"
    }

    private fun dismissAlert() {
        ttsEngine.stop()
        // v1.1.0+ 异动通知暂禁用
        // NotificationHelper.cancelAlert(this)
        alertActive = false; alertSettleCount = 0; alertFollowUp.reset()
        aiLog("🔕 关闭异动提醒")
        // updateNotif()
    }

    private fun businessPrefix(data: StockData): String =
        ListeningSpeechFormatter.prefix(data.name, data.price, 0.0)

    private fun announceQuoteInterrupted() {
        if (quoteInterruptedAnnounced) return
        quoteInterruptedAnnounced = true
        ttsEngine.speakAlert("行情中断，暂停行情播报")
        aiLog("行情中断：30秒没有收到新鲜行情")
    }

    private fun withLatestPrice(text: String, data: StockData? = lastStockData): String? {
        val quote = data?.takeIf { it.price > 0.0 } ?: return null
        val plainPrefix = businessPrefix(quote)
        val prefix = ListeningSpeechFormatter.prefix(quote.name, quote.price, lastSpokenPrice)
        val body = text.removePrefix(plainPrefix).trimStart('，')
        return listOf(prefix, body).filter { it.isNotBlank() }.joinToString("，")
    }

    private fun speakBusiness(text: String, data: StockData? = lastStockData): Boolean {
        val quote = data?.takeIf { it.price > 0.0 } ?: return false
        val accepted = withLatestPrice(text, quote)?.let { ttsEngine.speak(it) } ?: false
        if (accepted) lastSpokenPrice = quote.price
        return accepted
    }

    private fun speakBusinessAlert(text: String, data: StockData? = lastStockData): Boolean {
        val quote = data?.takeIf { it.price > 0.0 } ?: return false
        val accepted = withLatestPrice(text, quote)?.let { ttsEngine.speakAlert(it) } == true
        if (accepted) lastSpokenPrice = quote.price
        return accepted
    }

    private fun speakCompactAlert(text: String, data: StockData? = lastStockData): Boolean {
        val quote = data?.takeIf { it.price > 0.0 } ?: return false
        val compact = "现价${"%.2f".format(quote.price)}，${text.removeSuffix("。")}"
        val accepted = ttsEngine.speakAlert(compact)
        if (accepted) lastSpokenPrice = quote.price
        return accepted
    }

    private fun contextSectorPct(): Double = when {
        conceptBlockCache.industryPct != 0.0 -> conceptBlockCache.industryPct
        else -> conceptBlockCache.topConceptPct
    }

    private fun indexChangePct(): Double = Regex("\\(([+-]?\\d+(?:\\.\\d+)?)%\\)")
        .find(lastShanghaiIndex)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: 0.0

    // ── 换手率动态阈值（防开盘乱叫，大小盘自适应） ──

    private fun getDynamicThreshold(turnover: Double, volRatio: Double): Int {
        val base = config.largeOrderThreshold
        val turnoverCoef = when {
            turnover > 15 -> 2.5
            turnover >= 8 -> 1.5
            turnover < 2 && volRatio < 0.8 -> 0.6
            else -> 1.0
        }
        val phaseCoef = when (lastMetrics?.phase ?: TradingPhase.CLOSED) {
            TradingPhase.CALL_AUCTION -> 2.0
            TradingPhase.OPENING -> 1.5
            TradingPhase.MORNING -> 1.0
            TradingPhase.AFTERNOON_REOPEN -> 0.9
            TradingPhase.AFTERNOON -> 0.8
            TradingPhase.CLOSING -> 1.2
            TradingPhase.CLOSED -> 2.0
        }
        return (base * turnoverCoef * phaseCoef).toInt().coerceAtLeast(1)
    }

    // ── 口语格式化 ──

    private fun spokenPrice(p: Double): String {
        return "${"%.2f".format(Locale.US, p)}元"
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
        return "${dir}${v}%"
    }

    private fun spokenSpeed(s: Double): String {
        return if (s >= 0) "十五秒上行${fmtPct(s)}%" else "十五秒下行${fmtPct(-s)}%"
    }

    private fun spokenAmount(raw: String): String =
        raw.replace(Regex("\\.0+万"), "万").replace(Regex("\\.0+亿"), "亿")

    private fun spokenVolRatio(vr: Double): String = "量比${fmtPct(vr)}"

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
        val asks = data.asks.filter { it.first > 0.0 && it.second >= config.largeOrderThreshold }
        val bids = data.bids.filter { it.first > 0.0 && it.second >= config.largeOrderThreshold }
        if (asks.isEmpty() && bids.isEmpty()) return null
        val parts = mutableListOf<String>()
        if (asks.isNotEmpty()) {
            val maxVol = asks.maxOf { it.second }
            parts.add("卖盘最大挂单${spokenHand(maxVol)}")
        }
        if (bids.isNotEmpty()) {
            val maxVol = bids.maxOf { it.second }
            parts.add("买盘最大挂单${spokenHand(maxVol)}")
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
