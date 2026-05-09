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
    private val networkExecutor = Executors.newSingleThreadExecutor()

    // HTTP client for network TTS fallback
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

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
            isSpeaking = false
            handler.removeCallbacks(speakingTimeout)
        }
        @Deprecated("Deprecated in Java")
        override fun onError(id: String?) {
            isSpeaking = false
            handler.removeCallbacks(speakingTimeout)
            log("⚠ TTS onError")
        }
        override fun onError(id: String?, code: Int) {
            isSpeaking = false
            handler.removeCallbacks(speakingTimeout)
            log("⚠ TTS onError code=$code")
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

    // ── TTS speak: system TTS first, network fallback ──

    private fun trySpeak(text: String): Boolean {
        if (isSpeaking) return false

        // System TTS available → use it
        if (ttsReady) {
            isSpeaking = true
            val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "stock_speak")
                ?: TextToSpeech.ERROR
            if (result == TextToSpeech.SUCCESS) {
                handler.postDelayed(speakingTimeout, 15000)
                return true
            }
            isSpeaking = false
            log("⚠ system TTS speak失败($result)")
        }

        // Fall through to network TTS
        isSpeaking = true
        networkExecutor.execute { tryNetworkTts(text) }
        return true
    }

    // ── Network TTS chain: Google → Baidu Fanyi → Baidu → Youdao ──

    private fun tryNetworkTts(text: String) {
        val encoded = URLEncoder.encode(text, "UTF-8")

        // 1) Baidu Fanyi TTS (Chinese service, simpler endpoint, no auth)
        trySimpleTts(
            "baidu-fanyi", "https://fanyi.baidu.com/gettts?lan=zh&text=$encoded&spd=3",
            referer = "https://fanyi.baidu.com/"
        ) { ok ->
            if (ok) return@trySimpleTts

            // 2) Google Translate TTS (international fallback, simple HTTPS GET)
            tryGoogleTts(text, encoded) { ok2 ->
                if (ok2) return@tryGoogleTts

                // 3) Baidu TTS (legacy endpoint)
                trySimpleTts(
                    "baidu", "https://tts.baidu.com/text2audio?lan=zh&ie=UTF-8&spd=5&text=$encoded&cuid=stockspeaker&ctp=1"
                ) { ok3 ->
                    if (ok3) return@trySimpleTts

                    // 4) Youdao HTTP
                    trySimpleTts(
                        "youdao", "http://tts.youdao.com/fanyivoice?word=$encoded&le=zh"
                    ) { ok4 ->
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

    private fun tryGoogleTts(text: String, encoded: String, onResult: (Boolean) -> Unit) {
        try {
            // Google Translate TTS — free, no auth, returns MP3
            val url = "https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl=zh-CN&q=$encoded"
            val request = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .header("Accept", "audio/mpeg,audio/*")
                .build()

            val response = httpClient.newCall(request).execute()
            val ct = response.header("Content-Type", "?")
            if (!response.isSuccessful) {
                val bodyStr = try { response.body?.string()?.take(100) ?: "" } catch (_: Exception) { "" }
                log("  gTTS HTTP${response.code} ct=$ct $bodyStr")
                response.close()
                onResult(false)
                return
            }

            val body = response.body
            if (body == null) { log("  gTTS body=null"); onResult(false); return }

            val mp3File = File(cacheDir, "speak_${System.currentTimeMillis()}.mp3")
            body.byteStream().use { input ->
                FileOutputStream(mp3File).use { output -> input.copyTo(output) }
            }

            log("  gTTS ct=$ct size=${mp3File.length()}")
            if (mp3File.length() < 200) {
                mp3File.delete()
                onResult(false)
                return
            }

            handler.post {
                log("✓ gTTS(${mp3File.length()}B)")
                playAudio(mp3File)
            }
            onResult(true)
        } catch (e: Exception) {
            log("  gTTS ${e.message?.take(40)}")
            onResult(false)
        }
    }

    private fun trySimpleTts(name: String, url: String, referer: String? = null, onResult: (Boolean) -> Unit) {
        try {
            val ref = referer ?: if (url.contains("baidu")) "https://www.baidu.com/" else "http://www.youdao.com/"
            val request = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", ref)
                .build()
            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                val bodyStr = try { response.body?.string()?.take(100) ?: "" } catch (_: Exception) { "" }
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

            val ct = response.header("Content-Type", "?")
            if (mp3File.length() < 200) {
                mp3File.delete()
                log("  $name ${mp3File.length()}B ct=$ct")
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
