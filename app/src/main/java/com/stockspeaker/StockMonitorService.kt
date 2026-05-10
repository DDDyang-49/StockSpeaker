package com.stockspeaker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
        const val ACTION_PAUSE = "com.stockspeaker.PAUSE"
        const val ACTION_RESUME = "com.stockspeaker.RESUME"

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
    private var mediaPlayer: MediaPlayer? = null
    private var isRunning = false
    private var lastSpeakTime = 0L
    private var lastTotalVol = 0
    private var lastChangePct = 0.0
    private val intervalLargeEvents = mutableListOf<Triple<String, String, Int>>()
    private var isSpeaking = false
    private var isPaused = false
    private var summaryBroadcastCount = 0
    // 异动连续跟报状态
    private var alertFollowUpActive = false
    private var lastAlertSpeakTime = 0L
    private var followUpCount = 0
    private val speechChunks = mutableListOf<String>()
    private var speechCursor = 0
    private val networkExecutor = Executors.newSingleThreadExecutor()

    // HTTP client for network TTS fallback
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // AI analyzer
    private lateinit var aiAnalyzer: AIAnalyzer

    // Diagnostic log buffer
    private val debugLogs = mutableListOf<String>()

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
        aiAnalyzer = AIAnalyzer {
            val c = configManager.load()
            AiConfig(enabled = c.aiEnabled, apiKey = c.aiApiKey, apiUrl = c.aiApiUrl, summaryInterval = c.aiSummaryInterval)
        }
        handler = Handler(Looper.getMainLooper())
        createNotificationChannel()
        initSystemTts() // Try system TTS first (offline/no-filter)
    }

    // ── System TTS init chain ──

    private var xiaomiPkgIdx = -1 // track which Xiaomi package we're on (-1 = not in Xiaomi loop)

    private fun initSystemTts() {
        // Read the actual default TTS engine from system settings
        val defaultSynth = try {
            Settings.Secure.getString(contentResolver, "tts_default_synth")
        } catch (_: Exception) { null }
        log("TTS: 系统默认引擎=${defaultSynth ?: "未设置"}")

        val listener = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                onTtsConnected(fromXiaomi = false)
            } else {
                log("✗ 默认引擎 init失败 status=$status")
                tryAllPkgs(0)
            }
        }
        tts = TextToSpeech(this, listener)
    }

    // Common TTS engine packages on Chinese Android devices
    private val allTtsPkgs = listOf(
        "com.iflytek.tts",           // iFlytek (讯飞) — most common
        "com.iflytek.speechsuite",   // iFlytek Speech Suite
        "com.iflytek.speechcloud",   // iFlytek Speech Cloud
        "com.google.android.tts",    // Google TTS
        "com.baidu.tts",             // Baidu TTS
        "com.baidu.speech",          // Baidu Speech
        "com.miui.voiceassist",      // XiaoAi
        "com.xiaomi.mibrain.speech", // XiaoAi Brain
        "com.miui.tts",              // MIUI TTS
        "com.xiaomi.voice.engine",   // Xiaomi Voice Engine
        "com.svox.pico",             // Pico TTS (AOSP)
        "com.android.tts"            // AOSP TTS
    )

    private fun tryAllPkgs(index: Int) {
        if (index >= allTtsPkgs.size) {
            val defaultSynth = try {
                Settings.Secure.getString(contentResolver, "tts_default_synth")
            } catch (_: Exception) { null }
            log("✗ 全部${allTtsPkgs.size}引擎失败 系统默认=$defaultSynth → 网络TTS")
            xiaomiPkgIdx = -1
            return
        }
        xiaomiPkgIdx = index
        val pkg = allTtsPkgs[index]
        log("TTS: 尝试 $pkg")
        tts?.shutdown()
        tts = TextToSpeech(this, { status ->
            if (status == TextToSpeech.SUCCESS) {
                onTtsConnected(fromXiaomi = true)
            } else {
                log("✗ $pkg 失败 status=$status")
                tryAllPkgs(index + 1)
            }
        }, pkg)
    }

    private fun onTtsConnected(fromXiaomi: Boolean) {
        val engine = tts?.defaultEngine ?: "?"
        log("TTS连接: $engine")

        var ok = false
        for (loc in listOf(Locale.SIMPLIFIED_CHINESE, Locale.CHINESE, Locale.getDefault())) {
            val r = tts?.setLanguage(loc) ?: TextToSpeech.ERROR
            val desc = when (r) {
                TextToSpeech.SUCCESS -> "SUCCESS"
                TextToSpeech.LANG_MISSING_DATA -> "MISSING_DATA"
                TextToSpeech.LANG_NOT_SUPPORTED -> "NOT_SUPPORTED"
                else -> "code=$r"
            }
            log("  setLanguage($loc) → $desc")
            if (r == TextToSpeech.SUCCESS) { ok = true; break }
        }

        if (ok) {
            tts?.setSpeechRate(0.9f)
            tts?.setOnUtteranceProgressListener(ttsListener)
            ttsReady = true
            xiaomiPkgIdx = -1
            log("✓ 系统TTS就绪 引擎=$engine")
        } else {
            log("✗ $engine 不支持中文")
            tts?.shutdown()
            tts = null
            if (fromXiaomi) {
                tryAllPkgs(xiaomiPkgIdx + 1) // continue from next
            } else {
                tryAllPkgs(0) // start full engine scan
            }
        }
    }

    private val ttsListener = object : UtteranceProgressListener() {
        override fun onStart(id: String?) { isSpeaking = true }
        override fun onDone(id: String?) {
            speechCursor++
            if (speechCursor >= speechChunks.size) {
                isSpeaking = false
                handler.removeCallbacks(speakingTimeout)
            }
            // else: next chunk already queued via QUEUE_ADD, wait for its onDone
        }
        @Deprecated("Deprecated in Java")
        override fun onError(id: String?) {
            isSpeaking = false
            speechChunks.clear()
            handler.removeCallbacks(speakingTimeout)
            log("⚠ TTS onError")
        }
        override fun onError(id: String?, code: Int) {
            isSpeaking = false
            speechChunks.clear()
            handler.removeCallbacks(speakingTimeout)
            log("⚠ TTS onError code=$code")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 处理暂停/继续通知动作
        when (intent?.action) {
            ACTION_PAUSE -> {
                isPaused = true
                stopSpeaking()
                log("⏸ 用户暂停播报")
                updateNotificationPauseState()
                return START_STICKY
            }
            ACTION_RESUME -> {
                isPaused = false
                log("▶ 用户恢复播报")
                updateNotificationPauseState()
                return START_STICKY
            }
        }
        if (!isRunning) {
            isRunning = true
            val config = configManager.load()
            lastSpeakTime = 0L
            lastTotalVol = 0
            lastChangePct = 0.0
            intervalLargeEvents.clear()

            startForeground(NOTIFICATION_ID, buildNotification(config.stockCode, isPaused))
            val existing = uiState.value
            uiState.value = existing.copy(
                statusText = if (ttsReady) "🟢 正在监控: ${config.stockCode}..."
                             else "🟢 监控中(TTS未就绪): ${config.stockCode}...",
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
        mediaPlayer?.release()
        mediaPlayer = null
        aiAnalyzer.shutdown()
        networkExecutor.shutdownNow()
        try {
            cacheDir.listFiles()?.filter { it.name.startsWith("speak_") }?.forEach { it.delete() }
        } catch (_: Exception) {}
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
        val absSpeed = Math.abs(speed)

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

        // 用户暂停 → 不做任何播报
        if (isPaused) return

        // ═══════════════════════════════════════════
        // 轨道1：实时异动（最高优先级，QUEUE_FLUSH）
        // ═══════════════════════════════════════════

        var alertSpoken = false

        // 1a. 大单异动（现手超过阈值）
        if (!alertSpoken && currentHand >= config.largeOrderThreshold) {
            val action = when {
                speed > 0.3 -> "大单买入"
                speed < -0.3 -> "大单卖出"
                else -> "大单成交"
            }
            speakAlert("注意！${spokenHand(currentHand)}${action}！")
            alertSpoken = true
            lastAlertSpeakTime = now
            alertFollowUpActive = false
        }

        // 1b. 快速拉升/砸盘（涨速超过阈值）
        if (!alertSpoken && absSpeed >= config.speedAlertThreshold) {
            val dir = if (speed > 0) "快速拉升" else "快速下跌"
            val pctLabel = if (speed > 0) "涨幅" else "跌幅"
            speakAlert("注意！$dir！${data.name}当前${spokenPrice(data.price)}，${pctLabel}${fmtPct(absSpeed)}%")
            alertSpoken = true
            lastAlertSpeakTime = now
            // 进入连续跟报模式
            alertFollowUpActive = true
            followUpCount = 0
        }

        // 1c. AI 异动检测（放量突破/高位急转/盘口大单跳变）
        if (!alertSpoken) {
            try {
                val snapshot = MarketSnapshot(
                    time = now,
                    price = data.price,
                    changePct = data.changePct,
                    speed = speed,
                    volRatio = data.volRatio,
                    amountStr = data.amountStr,
                    currentHand = currentHand,
                    largeAsksCount = data.largeAsksSpeak.size,
                    largeBidsCount = data.largeBidsSpeak.size
                )
                val patterns = aiAnalyzer.feed(snapshot)
                if (patterns.isNotEmpty()) {
                    val patternText = patterns.joinToString("") { it.speakText }
                    log("AI异动: ${patterns.map { it.type.name }.joinToString()}")
                    speakAlert(patternText)
                    alertSpoken = true
                    lastAlertSpeakTime = now
                    alertFollowUpActive = false
                }
            } catch (_: Exception) {}
        }

        // ═══════════════════════════════════════════
        // 轨道1b：连续跟报（快速拉升/砸盘持续期间，每~8秒更新一次）
        // ═══════════════════════════════════════════

        if (!alertSpoken && alertFollowUpActive) {
            if (absSpeed >= config.speedAlertThreshold * 0.3) {
                if (now - lastAlertSpeakTime >= 8000) {
                    val dir = if (speed > 0) "拉升" else "下跌"
                    speakAlert("${data.name}继续$dir，当前${spokenPrice(data.price)}，${fmtPct(absSpeed)}%")
                    lastAlertSpeakTime = now
                    followUpCount++
                    if (followUpCount > 30) alertFollowUpActive = false
                }
            } else {
                // 涨速回落，退出跟报
                alertFollowUpActive = false
            }
        }

        // 异动触发后重置常规播报定时器
        if (alertSpoken) {
            lastSpeakTime = now
            intervalLargeEvents.clear()
            uiState.value = uiState.value.copy(lastSpeakTime = nowStr)
            return
        }

        // ═══════════════════════════════════════════
        // 轨道2：定时常规播报
        // ═══════════════════════════════════════════

        // 收集间隔内大单事件（用于常规播报末尾拼接）
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
                if (trySpeak(text)) {
                    lastSpeakTime = now
                    intervalLargeEvents.clear()
                    uiState.value = uiState.value.copy(lastSpeakTime = nowStr)
                }
                tryGenerateAiSummary(data, speed)
            }
        }
    }

    private fun tryGenerateAiSummary(data: StockData, speed: Double) {
        val appConfig = configManager.load()
        if (!appConfig.aiEnabled || appConfig.aiApiKey.isBlank()) return

        summaryBroadcastCount++
        if (summaryBroadcastCount % appConfig.aiSummaryInterval != 0) return

        // 构建异动统计
        val alertStats = buildString {
            val absSpeed = Math.abs(speed)
            if (absSpeed >= appConfig.speedAlertThreshold) append("涨速${fmtPct(absSpeed)}% ")
            if (Math.abs(data.changePct) >= 2.0) append("振幅${fmtPct(Math.abs(data.changePct))}% ")
        }
        val marketContext = generateMockContext(appConfig.stockCode, data.changePct).copy(alertStats = alertStats)

        aiAnalyzer.generateSummary(marketContext) { summary ->
            if (summary != null) {
                handler.post {
                    log("AI总结: ${summary.take(30)}...")
                    speakAlert("AI点评：$summary")
                }
            }
        }
    }

    // ── TTS speak: split into sentences, queue them to avoid cutoff ──

    /** 强制停止所有正在播报的语音（TTS + MediaPlayer） */
    private fun stopSpeaking() {
        tts?.stop()
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        isSpeaking = false
        speechChunks.clear()
        speechCursor = 0
        handler.removeCallbacks(speakingTimeout)
    }

    /** 异动播报：最高优先级，QUEUE_FLUSH 清空当前语音立即插播 */
    private fun speakAlert(text: String) {
        stopSpeaking()
        log("⚠ 异动: ${text.take(40)}...")
        if (ttsReady) {
            isSpeaking = true
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "alert")
            handler.postDelayed(speakingTimeout, 30000)
        } else {
            isSpeaking = true
            networkExecutor.execute { tryNetworkTts(text) }
        }
    }

    private fun trySpeak(text: String, interrupt: Boolean = false): Boolean {
        if (isSpeaking && !interrupt) return false

        if (interrupt && isSpeaking) {
            // 中断当前播报
            tts?.stop()
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            isSpeaking = false
            speechChunks.clear()
            handler.removeCallbacks(speakingTimeout)
        }

        val sentences = text.split(Regex("(?<=[。！？])"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (sentences.isEmpty()) return false

        speechChunks.clear()
        speechChunks.addAll(sentences)
        speechCursor = 0

        // System TTS available → queue all sentences
        if (ttsReady) {
            isSpeaking = true
            val result = tts?.speak(speechChunks[0], TextToSpeech.QUEUE_FLUSH, null, "s0")
                ?: TextToSpeech.ERROR
            if (result != TextToSpeech.SUCCESS) {
                isSpeaking = false
                log("⚠ system TTS speak失败($result)")
                return false
            }
            for (i in 1 until speechChunks.size) {
                tts?.speak(speechChunks[i], TextToSpeech.QUEUE_ADD, null, "s$i")
            }
            handler.postDelayed(speakingTimeout, 30000)
            return true
        }

        // Fall through to network TTS (unsplit — remote APIs handle long text better)
        isSpeaking = true
        networkExecutor.execute { tryNetworkTts(text) }
        return true
    }

    // ── Network TTS: Edge WebSocket (primary) → HTTP fallback (Baidu Fanyi → Baidu → Youdao) ──

    private fun tryNetworkTts(text: String) {
        // Primary: Edge TTS via WebSocket (zh-CN-XiaoxiaoNeural, near-human quality)
        tryEdgeTtsWs(text) { ok ->
            if (ok) return@tryEdgeTtsWs

            // Fallback: HTTP TTS chain
            val encoded = URLEncoder.encode(text, "UTF-8")
            tryHttpTts("baidu-fanyi", "https://fanyi.baidu.com/gettts?lan=zh&text=$encoded&spd=3",
                referer = "https://fanyi.baidu.com/") { ok2 ->
                if (ok2) return@tryHttpTts
                tryHttpTts("baidu", "https://tts.baidu.com/text2audio?lan=zh&ie=UTF-8&spd=5&text=$encoded&cuid=stockspeaker&ctp=1") { ok3 ->
                    if (ok3) return@tryHttpTts
                    tryHttpTts("youdao", "http://tts.youdao.com/fanyivoice?word=$encoded&le=zh") { ok4 ->
                        if (!ok4) {
                            handler.post {
                                log("⚠ 全部TTS路径失败")
                                isSpeaking = false
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Edge TTS via WebSocket (wss://speech.platform.bing.com) ──

    private fun tryEdgeTtsWs(text: String, onResult: (Boolean) -> Unit) {
        val wsRequest = Request.Builder()
            .url("wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1?TrustedClientToken=6A5AA1D4EAFF4E9FB37E23D68491D6F4")
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0")
            .build()

        val audioChunks = mutableListOf<ByteArray>()
        var wsRef: WebSocket? = null
        val safeText = xmlEscape(text)

        val timeoutTask = Runnable {
            wsRef?.close(1000, "timeout")
            handler.post {
                log("  Edge WS timeout")
                onResult(false)
            }
        }
        handler.postDelayed(timeoutTask, 15000)

        wsRef = httpClient.newWebSocket(wsRequest, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Send speech config
                webSocket.send("Content-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" +
                    """{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":false,"wordBoundaryEnabled":true},"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}""")
                // Send SSML
                webSocket.send("Content-Type:application/ssml+xml; charset=utf-8\r\nPath:ssml\r\n\r\n" +
                    """<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='zh-CN'><voice name='zh-CN-XiaoxiaoNeural'>$safeText</voice></speak>""")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("turn.end")) {
                    handler.removeCallbacks(timeoutTask)
                    webSocket.close(1000, "done")
                    val totalSize = audioChunks.sumOf { it.size }
                    if (totalSize < 200) {
                        handler.post {
                            log("  Edge WS ${totalSize}B too small")
                            onResult(false)
                        }
                        return
                    }
                    val mp3File = File(cacheDir, "speak_${System.currentTimeMillis()}.mp3")
                    try {
                        FileOutputStream(mp3File).use { out ->
                            audioChunks.forEach { out.write(it) }
                        }
                        handler.post {
                            log("✓ Edge WS(${mp3File.length()}B)")
                            playAudio(mp3File)
                        }
                    } catch (e: Exception) {
                        handler.post {
                            log("  Edge WS save err: ${e.message?.take(30)}")
                            onResult(false)
                        }
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val raw = bytes.toByteArray()
                val audioData = stripHeader(raw)
                if (audioData.size > 0) audioChunks.add(audioData)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                handler.removeCallbacks(timeoutTask)
                handler.post {
                    log("  Edge WS fail: ${t.message?.take(40)}")
                    onResult(false)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handler.removeCallbacks(timeoutTask)
            }
        })
    }

    /** Strip WebSocket binary frame headers (Path:audio\r\n...\r\n\r\n<data>) */
    private fun stripHeader(data: ByteArray): ByteArray {
        for (i in 0 until data.size - 3) {
            if (data[i] == 0x0d.toByte() && data[i+1] == 0x0a.toByte() &&
                data[i+2] == 0x0d.toByte() && data[i+3] == 0x0a.toByte()) {
                return data.copyOfRange(i + 4, data.size)
            }
        }
        return data
    }

    private fun xmlEscape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    // ── HTTP TTS fallback ──

    private fun tryHttpTts(name: String, url: String, referer: String? = null, onResult: (Boolean) -> Unit) {
        try {
            val ref = referer ?: if (url.contains("baidu")) "https://www.baidu.com/" else "http://www.youdao.com/"
            val request = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", ref)
                .build()
            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                val bodyStr = try { response.body?.string()?.take(80) ?: "" } catch (_: Exception) { "" }
                log("  $name HTTP${response.code} $bodyStr")
                response.close()
                onResult(false)
                return
            }

            val body = response.body
            if (body == null) { log("  $name body=null"); onResult(false); return }

            val mp3File = File(cacheDir, "speak_${System.currentTimeMillis()}.mp3")
            body.byteStream().use { input ->
                FileOutputStream(mp3File).use { output -> input.copyTo(output) }
            }

            if (mp3File.length() < 200) {
                mp3File.delete()
                log("  $name ${mp3File.length()}B")
                onResult(false)
                return
            }

            handler.post {
                log("✓ $name(${mp3File.length()}B)")
                playAudio(mp3File)
            }
            onResult(true)
        } catch (e: Exception) {
            log("  $name ${e.message?.take(40)}")
            onResult(false)
        }
    }

    private fun playAudio(file: File) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(file.absolutePath)
                setOnCompletionListener { mp ->
                    isSpeaking = false
                    handler.removeCallbacks(speakingTimeout)
                    mp.release()
                    if (mediaPlayer === mp) mediaPlayer = null
                    file.delete()
                }
                setOnErrorListener { mp, what, extra ->
                    isSpeaking = false
                    handler.removeCallbacks(speakingTimeout)
                    log("⚠ 播放失败 what=$what extra=$extra")
                    mp.release()
                    if (mediaPlayer === mp) mediaPlayer = null
                    file.delete()
                    true
                }
                prepare()
                start()
            }
            handler.postDelayed(speakingTimeout, 15000)
        } catch (e: Exception) {
            isSpeaking = false
            log("⚠ 播放异常: ${e.message?.take(40)}")
            file.delete()
        }
    }

    private val speakingTimeout = Runnable {
        if (isSpeaking) {
            isSpeaking = false
            try {
                tts?.stop()
                mediaPlayer?.stop()
                mediaPlayer?.release()
            } catch (_: Exception) {}
            mediaPlayer = null
        }
    }

    private fun buildSpeakText(
        data: StockData,
        config: AppConfig,
        currentHand: Int,
        speed: Double
    ): String? {
        val parts = mutableListOf<String>()

        // ── 1. 股票名称 + 价格（口语化） ──
        val header = buildString {
            append(data.name)
            if (config.speakPrice) append(spokenPrice(data.price))
        }
        parts.add(header)

        // ── 2. 涨跌方向 + 幅度（口语化） ──
        if (config.speakPct) {
            parts.add(spokenChange(data.changePct))
        }

        // ── 3. 涨速（仅在显著时播报） ──
        if (config.speakSpeed && Math.abs(speed) >= 0.05) {
            parts.add(spokenSpeed(speed))
        }

        // ── 4. 成交额（口语化） ──
        if (config.speakAmount) {
            parts.add("成交${spokenAmount(data.amountStr)}")
        }

        // ── 5. 量比（仅在显著偏离正常时播报） ──
        if (config.speakVolRatio) {
            spokenVolRatio(data.volRatio)?.let { parts.add(it) }
        }

        // ── 6. 现手（仅在大单时播报） ──
        if (config.speakCurrentHand && currentHand >= config.largeOrderThreshold) {
            parts.add("现手${spokenHand(currentHand)}")
        }

        // ── 组装主句 ──
        val mainText = parts.joinToString("，") + "。"

        // ── 7. 盘口大单（概括而非罗列） ──
        val alertText = if (config.speakLargeOrders) {
            spokenLargeOrders(data)?.let { "注意，$it。" } ?: ""
        } else ""

        // ── 8. 间隔内最大单笔成交 ──
        val eventText = if (intervalLargeEvents.isNotEmpty()) {
            val key = intervalLargeEvents.maxByOrNull { it.third }
            if (key != null && key.third >= config.largeOrderThreshold) {
                "刚才有${spokenHand(key.third)}${key.second}。"
            } else ""
        } else ""

        val fullText = mainText + alertText + eventText
        return if (fullText == "。") null else fullText
    }

    // ── 口语格式化辅助函数 ──

    /** "1673.50" → "1673块5", "1673.00" → "1673块", "8.88" → "8块8毛8" */
    private fun spokenPrice(p: Double): String {
        val intPart = p.toInt()
        val frac = Math.round((p - intPart) * 100).toInt()
        val jiao = frac / 10
        val fen = frac % 10
        return buildString {
            append("${intPart}块")
            if (jiao > 0) append(jiao)
            if (fen > 0) append("毛$fen")
        }
    }

    /** "+2.01" → "涨了2个点", "-0.05" → "微跌", "+0.00" → "平盘" */
    private fun spokenChange(pct: Double): String {
        val abs = Math.abs(pct)
        return when {
            pct > 3.0 -> "大涨了${fmtPct(abs)}个点"
            pct > 0.05 -> "涨了${fmtPct(abs)}个点"
            pct > 0 -> "微涨"
            pct < -3.0 -> "大跌了${fmtPct(abs)}个点"
            pct < -0.05 -> "跌了${fmtPct(abs)}个点"
            pct < 0 -> "微跌"
            else -> "平盘"
        }
    }

    /** "+0.15" → "正在拉升", "+1.2" → "快速拉升" */
    private fun spokenSpeed(s: Double): String = when {
        s > 0.5 -> "快速拉升"
        s > 0.05 -> "拉升中"
        s < -0.5 -> "快速下跌"
        else -> "下跌中"
    }

    /** "15.20亿" → "15亿", "1.23亿" → "1.2亿", "5000.00万" → "5000万" */
    private fun spokenAmount(raw: String): String =
        raw.replace(Regex("\\.0+万"), "万")
           .replace(Regex("\\.0+亿"), "亿")

    /** 量比 → 仅在偏离正常区间时播报 */
    private fun spokenVolRatio(vr: Double): String? = when {
        vr >= 2.5 -> "明显放量"
        vr >= 1.3 -> "在放量"
        vr <= 0.4 -> "明显缩量"
        vr <= 0.7 -> "在缩量"
        else -> null
    }

    /** 3000 → "3000手", 13000 → "1万3千手", 25000 → "2万5千手" */
    private fun spokenHand(hand: Int): String = when {
        hand >= 10000 -> {
            val wan = hand / 10000
            val qian = (hand % 10000) / 1000
            if (qian > 0) "${wan}万${qian}千手" else "${wan}万手"
        }
        hand >= 1000 -> "${hand / 1000}千手"
        else -> "${hand}手"
    }

    /** 概括大单盘口，不逐档罗列 */
    private fun spokenLargeOrders(data: StockData): String? {
        val askCount = data.largeAsksSpeak.size
        val bidCount = data.largeBidsSpeak.size
        if (askCount == 0 && bidCount == 0) return null

        val parts = mutableListOf<String>()

        if (askCount > 0) {
            // 提取最大压单数量
            val maxAsk = data.asks.maxByOrNull { it.second }
            val maxVol = maxAsk?.second ?: 0
            parts.add(
                if (askCount >= 3) "卖盘压力不小，最大${spokenHand(maxVol)}压单"
                else "卖盘有${spokenHand(maxVol)}压单"
            )
        }

        if (bidCount > 0) {
            val maxBid = data.bids.maxByOrNull { it.second }
            val maxVol = maxBid?.second ?: 0
            parts.add(
                if (bidCount >= 3) "买盘托单较多，最大${spokenHand(maxVol)}"
                else "买盘有${spokenHand(maxVol)}托单"
            )
        }

        return parts.joinToString("；")
    }

    /** 幅度格式化：去掉多余的零 */
    private fun fmtPct(v: Double): String {
        val r = Math.round(v * 100) / 100.0
        return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
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

    private fun pauseIntent() = PendingIntent.getService(
        this, 1,
        Intent(this, StockMonitorService::class.java).setAction(ACTION_PAUSE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun resumeIntent() = PendingIntent.getService(
        this, 2,
        Intent(this, StockMonitorService::class.java).setAction(ACTION_RESUME),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildNotification(code: String, paused: Boolean = false) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(if (paused) "摸鱼听盘 ⏸" else "摸鱼听盘")
        .setContentText(if (paused) "已暂停播报" else "正在监控 $code...")
        .setSmallIcon(R.drawable.ic_notification)
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .addAction(if (paused) NotificationCompat.Action.Builder(0, "▶ 继续", resumeIntent()).build()
                   else NotificationCompat.Action.Builder(0, "⏸ 暂停", pauseIntent()).build())
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
            .setContentTitle(if (isPaused) "摸鱼听盘 ⏸" else "摸鱼听盘")
            .setContentText(if (isPaused) "已暂停播报" else content)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .addAction(if (isPaused) NotificationCompat.Action.Builder(0, "▶ 继续", resumeIntent()).build()
                       else NotificationCompat.Action.Builder(0, "⏸ 暂停", pauseIntent()).build())
            .build()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun updateNotificationPauseState() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (isPaused) "摸鱼听盘 ⏸" else "摸鱼听盘")
            .setContentText(if (isPaused) "已暂停播报" else "继续监控中...")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .addAction(if (isPaused) NotificationCompat.Action.Builder(0, "▶ 继续", resumeIntent()).build()
                       else NotificationCompat.Action.Builder(0, "⏸ 暂停", pauseIntent()).build())
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }
}
