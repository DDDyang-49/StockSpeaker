package com.stockspeaker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
    val lastSpeakTime: String = ""
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
    private var isRunning = false
    private var lastSpeakTime = 0L
    private var lastTotalVol = 0
    private var lastChangePct = 0.0
    private val intervalLargeEvents = mutableListOf<Triple<String, String, Int>>()
    private var isSpeaking = false

    override fun onCreate() {
        super.onCreate()
        configManager = ConfigManager(this)
        handler = Handler(Looper.getMainLooper())

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale.CHINESE)
                }
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { isSpeaking = false }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { isSpeaking = false }
            override fun onError(utteranceId: String?, errorCode: Int) { isSpeaking = false }
        })

        createNotificationChannel()
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
            uiState.value = ServiceUiState(
                statusText = "🟢 正在监控: ${config.stockCode}...",
                isRunning = true
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
        uiState.value = ServiceUiState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun runLoop() {
        if (!isRunning) return

        val config = configManager.load()

        val data = StockFetcher.fetch(config.stockCode, config.largeOrderThreshold)
        if (data != null) {
            val currentHand = if (lastTotalVol > 0) maxOf(0, data.totalVol - lastTotalVol) else 0
            val speed = if (lastChangePct != 0.0) {
                Math.round((data.changePct - lastChangePct) * 100.0) / 100.0
            } else 0.0

            lastTotalVol = data.totalVol
            lastChangePct = data.changePct

            val now = System.currentTimeMillis()
            val nowStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now))

            uiState.value = ServiceUiState(
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
                statusText = uiState.value.statusText,
                isRunning = true,
                lastSpeakTime = uiState.value.lastSpeakTime
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

                    if (!isSpeaking) {
                        isSpeaking = true
                        val utteranceId = "s_${now}"
                        tts?.speak(speakText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                    }

                    uiState.value = uiState.value.copy(lastSpeakTime = nowStr)
                }
            }

            updateNotification(data, currentHand, speed)
        }

        handler.postDelayed({ runLoop() }, 2000)
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
            NotificationManager.IMPORTANCE_LOW
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

    private fun updateNotification(data: StockData, currentHand: Int, speed: Double) {
        val st = when {
            data.changePct > 0 -> "涨"
            data.changePct < 0 -> "跌"
            else -> "平"
        }
        val content =
            "${data.name} ${data.price} (${st}${Math.abs(data.changePct)}%) | 现手:${currentHand}"
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
