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
    private var mediaPlayer: MediaPlayer? = null
    private var isRunning = false
    private var lastSpeakTime = 0L
    private var lastTotalVol = 0
    private var lastChangePct = 0.0
    private val intervalLargeEvents = mutableListOf<Triple<String, String, Int>>()
    private var isSpeaking = false
    private val networkExecutor = Executors.newSingleThreadExecutor()

    // HTTP client for Youdao TTS API
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
        log("✓ 语音引擎: 有道TTS (网络)")
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
                statusText = "🟢 正在监控: ${config.stockCode}...",
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
        mediaPlayer?.release()
        mediaPlayer = null
        networkExecutor.shutdownNow()
        // Clean up temp MP3 files
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

    // ── TTS: Baidu primary, Youdao fallback ──

    private fun trySpeak(text: String): Boolean {
        if (isSpeaking) return false

        isSpeaking = true

        networkExecutor.execute {
            // Baidu TTS first (more reliable in China), then Youdao
            val encoded = URLEncoder.encode(text, "UTF-8")
            val urls = listOf(
                "baidu" to "https://tts.baidu.com/text2audio?lan=zh&ie=UTF-8&spd=5&text=$encoded",
                "youdao" to "https://tts.youdao.com/fanyivoice?word=$encoded&le=zh"
            )

            var lastError = ""
            for ((name, url) in urls) {
                if (!isRunning) break
                try {
                    val request = Request.Builder().url(url)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                        .header("Accept", "audio/mpeg,audio/*")
                        .build()
                    val response = httpClient.newCall(request).execute()

                    if (!response.isSuccessful) {
                        val bodyStr = try {
                            response.body?.string()?.take(150) ?: ""
                        } catch (_: Exception) { "" }
                        lastError = "$name HTTP ${response.code} body:$bodyStr"
                        response.close()
                        continue
                    }

                    val body = response.body
                    if (body == null) {
                        lastError = "$name body=null"
                        continue
                    }

                    val mp3File = File(cacheDir, "speak_${System.currentTimeMillis()}.mp3")
                    body.byteStream().use { input ->
                        FileOutputStream(mp3File).use { output ->
                            input.copyTo(output)
                        }
                    }

                    if (mp3File.length() < 200) {
                        mp3File.delete()
                        lastError = "$name 返回${mp3File.length()}字节(非音频)"
                        continue
                    }

                    handler.post {
                        log("✓ TTS: $name")
                        playAudio(mp3File)
                    }
                    return@execute
                } catch (e: Exception) {
                    lastError = "$name 异常: ${e.message?.take(40)}"
                }
            }

            // All providers failed
            handler.post {
                log("⚠ $lastError")
                isSpeaking = false
            }
        }

        return true
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
