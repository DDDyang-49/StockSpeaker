package com.stockspeaker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
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
    val debugLog: List<String> = emptyList()
)

class StockMonitorService : Service() {

    companion object {
        const val CHANNEL_ID = "stock_monitor"
        const val NOTIFICATION_ID = 1

        val uiState = MutableStateFlow(ServiceUiState())

        fun start(context: Context) {
            val intent = Intent(context, StockMonitorService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, StockMonitorService::class.java))
        }
    }

    private lateinit var configManager: ConfigManager
    private lateinit var handler: Handler
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var isRunning = false
    private var lastSpeakTime = 0L
    private var lastTotalVol = 0
    private var lastChangePct = 0.0
    private val intervalLargeEvents = mutableListOf<Triple<String, String, Int>>()
    private var isSpeaking = false
    private val networkExecutor = Executors.newSingleThreadExecutor()

    // Diagnostic log buffer
    private val debugLogs = mutableListOf<String>()
    private val triedEnginePkgs = mutableSetOf<String?>() // track which engines we've attempted

    private fun log(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "[$ts] $msg"
        debugLogs.add(line)
        if (debugLogs.size > 100) debugLogs.removeAt(0)
        // Bubble latest diagnostic info into status so user sees it immediately
        val current = uiState.value
        uiState.value = current.copy(
            statusText = line.take(80),
            debugLog = debugLogs.toList()
        )
    }

    override fun onCreate() {
        super.onCreate()
        configManager = ConfigManager(this)
        handler = Handler(Looper.getMainLooper())
        createNotificationChannel()
        initTts()
    }

    // ── TTS: try default engine, then enumerate all engines on device ──

    private fun initTts() {
        log("TTS初始化开始...")
        tryTtsEngine(null) // null = use system default engine
    }

    private fun tryTtsEngine(enginePkg: String?) {
        // Clean up previous failed instance
        tts?.shutdown()
        tts = null

        triedEnginePkgs.add(enginePkg)
        val label = enginePkg ?: "系统默认"
        log("尝试引擎: $label")

        val cb = TextToSpeech.OnInitListener { status ->
            if (status != TextToSpeech.SUCCESS) {
                log("✗ $label init失败 status=$status")
                onEngineFailed(enginePkg)
                return@OnInitListener
            }

            val actualEngine = tts?.defaultEngine ?: "?"
            log("引擎连接成功: $actualEngine")

            // Try locales: zh_CN → zh → default
            var langOk = false
            for (loc in listOf(Locale.SIMPLIFIED_CHINESE, Locale.CHINESE, Locale.getDefault())) {
                val r = tts?.setLanguage(loc) ?: TextToSpeech.ERROR
                val desc = when (r) {
                    TextToSpeech.SUCCESS -> "SUCCESS"
                    TextToSpeech.LANG_MISSING_DATA -> "MISSING_DATA"
                    TextToSpeech.LANG_NOT_SUPPORTED -> "NOT_SUPPORTED"
                    else -> "UNKNOWN($r)"
                }
                log("  setLanguage($loc) → $desc")
                if (r == TextToSpeech.SUCCESS) {
                    langOk = true
                    break
                }
            }

            if (langOk) {
                tts?.setSpeechRate(0.9f)
                tts?.setOnUtteranceProgressListener(progressListener)
                ttsReady = true
                log("✓ TTS就绪 引擎=$actualEngine")
            } else {
                log("✗ $actualEngine 不支持中文")
                onEngineFailed(enginePkg)
            }
        }

        tts = if (enginePkg != null) {
            TextToSpeech(this, cb, enginePkg)
        } else {
            TextToSpeech(this, cb)
        }
    }

    private fun onEngineFailed(enginePkg: String?) {
        // Query all TTS engines on the device
        val pm = packageManager
        val intent = Intent("android.intent.action.TTS_SERVICE")
        val engines = pm.queryIntentServices(intent, PackageManager.GET_META_DATA)

        val allPkgs = engines.map { it.serviceInfo.packageName }
        if (allPkgs.isEmpty()) {
            log("✗ 设备上没有任何TTS引擎！请安装Google文字转语音")
            return
        }

        log("设备TTS引擎列表: ${allPkgs.joinToString()}")

        val next = allPkgs.firstOrNull { it !in triedEnginePkgs }
        if (next != null) {
            log("切换到下一个引擎: $next")
            tryTtsEngine(next)
        } else {
            log("✗ 已尝试所有引擎，均不支持中文")
            log("请在 设置→辅助功能→文字转语音→下载中文语音数据")
            log("已尝试: ${triedEnginePkgs.filterNotNull().joinToString()}")
        }
    }

    // Progress listener (shared across engine attempts)
    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            isSpeaking = true
        }
        override fun onDone(utteranceId: String?) {
            isSpeaking = false
            handler.removeCallbacks(speakingTimeout)
        }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            isSpeaking = false
            handler.removeCallbacks(speakingTimeout)
            log("⚠ TTS onError(deprecated)")
        }
        override fun onError(utteranceId: String?, errorCode: Int) {
            isSpeaking = false
            handler.removeCallbacks(speakingTimeout)
            log("⚠ TTS onError code=$errorCode")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            isRunning = true
            val config = configManager.load()
            lastSpeakTime = 0L
            lastTotalVol = 0
            lastChangePct = 0.0
            intervalLargeEvents.clear()

            startForeground(NOTIFICATION_ID, buildNotification(config.stockCode))
            val existing = uiState.value
            uiState.value = existing.copy(
                statusText = if (ttsReady) "🟢 正在监控: ${config.stockCode}..."
                             else "${existing.statusText.take(70)}\n等待TTS就绪...",
                isRunning = true,
                debugLog = debugLogs.toList()
            )
            runLoop()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
        networkExecutor.shutdownNow()
        uiState.value = ServiceUiState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun runLoop() {
        if (!isRunning) return

        networkExecutor.execute {
            if (!isRunning) return@execute

            val config = configManager.load()
            val data = StockFetcher.fetch(config.stockCode, config.largeOrderThreshold)

            handler.post {
                if (!isRunning) return@post

                if (data != null) {
                    processStockData(data, config)
                    updateNotification(data)
                }
                handler.postDelayed({ runLoop() }, 2000)
            }
        }
    }

    private fun processStockData(data: StockData, config: AppConfig) {
        val currentHand = if (lastTotalVol > 0) maxOf(0, data.totalVol - lastTotalVol) else 0
        val speed = if (lastChangePct != 0.0) {
            Math.round((data.changePct - lastChangePct) * 100.0) / 100.0
        } else 0.0

        lastTotalVol = data.totalVol
        lastChangePct = data.changePct

        val now = System.currentTimeMillis()
        val nowStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now))

        uiState.value = uiState.value.copy(
            stockName = data.name,
            price = data.price,
            changePct = data.changePct,
            speed = speed,
            amount = data.amountStr,
            volRatio = data.volRatio,
            currentHand = currentHand,
            largeAsks = data.largeAsks,
            largeBids = data.largeBids,
            largeAsksSpeak = data.largeAsksSpeak,
            largeBidsSpeak = data.largeBidsSpeak,
            isRunning = true
        )

        if (currentHand >= config.largeOrderThreshold) {
            val action = when {
                speed > 0 -> "主动买入"
                speed < 0 -> "抛出"
                else -> "成交"
            }
            intervalLargeEvents.add(Triple(nowStr, action, currentHand))
        }

        buildSpeakText(data, config, currentHand, speed)?.let { text ->
            val interval = config.speakInterval * 1000L
            if (now - lastSpeakTime >= interval) {
                lastSpeakTime = now

                var speakText = text
                if (intervalLargeEvents.isNotEmpty()) {
                    val largest = intervalLargeEvents.maxByOrNull { it.third }
                    if (largest != null) {
                        speakText += "。期间${largest.first}${largest.second}${largest.third}手。"
                    }
                    intervalLargeEvents.clear()
                }

                if (trySpeak(speakText)) {
                    uiState.value = uiState.value.copy(lastSpeakTime = nowStr)
                }
            }
        }
    }

    private fun trySpeak(text: String): Boolean {
        if (!ttsReady) {
            if (debugLogs.none { it.contains("ttsReady=false") }) {
                log("speak跳过: ttsReady=false")
            }
            return false
        }
        if (isSpeaking) return false

        isSpeaking = true
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "stock_speak")
            ?: TextToSpeech.ERROR
        if (result != TextToSpeech.SUCCESS) {
            isSpeaking = false
            log("⚠ speak()返回ERROR tts=$tts ttsReady=$ttsReady")
            return false
        }

        // Safety timeout: force reset after 10 seconds
        handler.postDelayed(speakingTimeout, 10000)
        return true
    }

    private val speakingTimeout = Runnable {
        if (isSpeaking) {
            isSpeaking = false
            tts?.stop()
        }
    }

    private fun buildSpeakText(
        data: StockData,
        config: AppConfig,
        currentHand: Int,
        speed: Double
    ): String? {
        val st = when {
            data.changePct > 0 -> "涨"
            data.changePct < 0 -> "跌"
            else -> "平"
        }

        val parts = mutableListOf(data.name)
        if (config.speakPrice) parts.add("${data.price}元")
        if (config.speakPct) parts.add("${st}${Math.abs(data.changePct)}%")
        if (config.speakSpeed && Math.abs(speed) > 0) parts.add("涨速${Math.abs(speed)}%")
        if (config.speakAmount) parts.add("成交额${data.amountStr}")
        if (config.speakVolRatio) parts.add("量比${data.volRatio}")
        if (config.speakCurrentHand) parts.add("现手${currentHand}")

        var text = parts.joinToString("，") + "。"

        if (config.speakLargeOrders) {
            if (data.largeAsksSpeak.isNotEmpty()) {
                text += "注意：${data.largeAsksSpeak.joinToString("，")}。"
            }
            if (data.largeBidsSpeak.isNotEmpty()) {
                text += "注意：${data.largeBidsSpeak.joinToString("，")}。"
            }
        }

        return if (text == "。") null else text
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = getString(R.string.channel_desc) }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(code: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("摸鱼听盘")
        .setContentText("正在监控 $code...")
        .setSmallIcon(R.drawable.ic_notification)
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .build()

    private fun updateNotification(data: StockData) {
        val st = when {
            data.changePct > 0 -> "涨"
            data.changePct < 0 -> "跌"
            else -> "平"
        }
        val content =
            "${data.name} ${data.price} (${st}${Math.abs(data.changePct)}%)"
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("摸鱼听盘")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .build()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }
}
