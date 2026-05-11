package com.stockspeaker

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

enum class TtsPriority { HIGH, NORMAL }

class TtsEngine(
    private val context: Context,
    private val cacheDir: File,
    private val onLog: (String) -> Unit
) {
    var isReady = false; private set
    var isSpeaking = false; private set

    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    @Volatile private var hasAudioFocus = false
    private val netExecutor = Executors.newSingleThreadExecutor()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private var tts: TextToSpeech? = null
    private var mediaPlayer: MediaPlayer? = null
    private var speechChunks = mutableListOf<String>()
    private var speechCursor = 0
    private var engineScanIndex = -1

    // ── 音频焦点（播报时自动降低其他音量，播完复原） ──

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> { stop(); abandonAudioFocus() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> stop()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {} // 我们请求MAY_DUCK，这是预期行为
            AudioManager.AUDIOFOCUS_GAIN -> {} // 焦点恢复，不做额外处理
        }
    }

    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()
            audioManager.requestAudioFocus(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(focusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasAudioFocus
    }

    private fun abandonAudioFocus() {
        if (!hasAudioFocus) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()
            audioManager.abandonAudioFocusRequest(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusChangeListener)
        }
        hasAudioFocus = false
    }

    // ── 初始化 ──

    fun init() {
        val defaultSynth = try {
            Settings.Secure.getString(context.contentResolver, "tts_default_synth")
        } catch (_: Exception) { null }
        onLog("TTS: 系统默认引擎=${defaultSynth ?: "未设置"}")

        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) onConnected()
            else {
                onLog("✗ 默认引擎 init失败 status=$status")
                tryEngine(0)
            }
        }
    }

    // ── 引擎扫描 ──

    private val ttsPkgs = listOf(
        "com.iflytek.tts", "com.iflytek.speechsuite", "com.iflytek.speechcloud",
        "com.google.android.tts", "com.baidu.tts", "com.baidu.speech",
        "com.miui.voiceassist", "com.xiaomi.mibrain.speech", "com.miui.tts",
        "com.xiaomi.voice.engine", "com.svox.pico", "com.android.tts"
    )

    private fun tryEngine(index: Int) {
        if (index >= ttsPkgs.size) {
            onLog("✗ 全部${ttsPkgs.size}引擎失败 → 网络TTS")
            return
        }
        engineScanIndex = index
        val pkg = ttsPkgs[index]
        onLog("TTS: 尝试 $pkg")
        tts?.shutdown()
        tts = TextToSpeech(context, { status ->
            if (status == TextToSpeech.SUCCESS) onConnected()
            else {
                onLog("✗ $pkg 失败 status=$status")
                tryEngine(index + 1)
            }
        }, pkg)
    }

    private fun onConnected() {
        val engine = tts?.defaultEngine ?: "?"
        onLog("TTS连接: $engine")

        var ok = false
        for (loc in listOf(Locale.SIMPLIFIED_CHINESE, Locale.CHINESE, Locale.getDefault())) {
            val r = tts?.setLanguage(loc) ?: TextToSpeech.ERROR
            if (r == TextToSpeech.SUCCESS) { ok = true; break }
        }
        if (ok) {
            tts?.setSpeechRate(0.9f)
            tts?.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            tts?.setOnUtteranceProgressListener(ttsListener)
            isReady = true
            engineScanIndex = -1
            onLog("✓ 系统TTS就绪 引擎=$engine")
        } else {
            onLog("✗ $engine 不支持中文")
            tts?.shutdown(); tts = null
            if (engineScanIndex >= 0) tryEngine(engineScanIndex + 1)
            else tryEngine(0)
        }
    }

    private val ttsListener = object : UtteranceProgressListener() {
        override fun onStart(id: String?) { isSpeaking = true }
        override fun onDone(id: String?) {
            speechCursor++
            if (speechCursor >= speechChunks.size) {
                isSpeaking = false
                handler.removeCallbacks(speakingTimeout)
                abandonAudioFocus()
            }
        }
        @Deprecated("Deprecated in Java")
        override fun onError(id: String?) { onTtsError() }
        override fun onError(id: String?, code: Int) { onTtsError() }
    }

    private fun onTtsError() {
        isSpeaking = false; speechChunks.clear(); speechCursor = 0
        handler.removeCallbacks(speakingTimeout)
        abandonAudioFocus()
        onLog("⚠ TTS onError")
    }

    // ── 播报 ──

    /** 异动播报：中断当前语音，立即插播 */
    fun speakAlert(text: String) {
        stop()
        requestAudioFocus()
        onLog("⚠ 异动: ${text.take(40)}...")
        if (isReady) {
            isSpeaking = true
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "alert")
            handler.postDelayed(speakingTimeout, 30000)
        } else {
            isSpeaking = true
            netExecutor.execute { tryNetworkTts(text) }
        }
    }

    /** 常规播报：队列追加，正在播报时返回 false */
    fun speak(text: String, priority: TtsPriority = TtsPriority.NORMAL): Boolean {
        if (priority == TtsPriority.HIGH) {
            stop()
        } else if (isSpeaking) {
            return false
        }

        val sentences = text.split(Regex("(?<=[。！？])"))
            .map { it.trim() }.filter { it.isNotEmpty() }
        if (sentences.isEmpty()) return false

        requestAudioFocus()

        speechChunks.clear(); speechChunks.addAll(sentences)
        speechCursor = 0

        if (isReady) {
            isSpeaking = true
            val r = tts?.speak(speechChunks[0], TextToSpeech.QUEUE_FLUSH, null, "s0") ?: TextToSpeech.ERROR
            if (r != TextToSpeech.SUCCESS) {
                isSpeaking = false; onLog("⚠ TTS speak失败($r)"); return false
            }
            for (i in 1 until speechChunks.size)
                tts?.speak(speechChunks[i], TextToSpeech.QUEUE_ADD, null, "s$i")
            handler.postDelayed(speakingTimeout, 30000)
            return true
        }

        isSpeaking = true
        netExecutor.execute { tryNetworkTts(text) }
        return true
    }

    fun stop() {
        tts?.stop()
        mediaPlayer?.stop(); mediaPlayer?.release(); mediaPlayer = null
        isSpeaking = false; speechChunks.clear(); speechCursor = 0
        handler.removeCallbacks(speakingTimeout)
        abandonAudioFocus()
    }

    fun shutdown() {
        stop()
        tts?.shutdown(); tts = null; isReady = false
        netExecutor.shutdownNow()
        try { cacheDir.listFiles()?.filter { it.name.startsWith("speak_") }?.forEach { it.delete() } } catch (_: Exception) {}
    }

    // ── 网络TTS ──

    private fun tryNetworkTts(text: String) {
        tryEdgeTtsWs(text) { ok ->
            if (ok) return@tryEdgeTtsWs
            val encoded = URLEncoder.encode(text, "UTF-8")
            tryHttpTts("baidu-fanyi", "https://fanyi.baidu.com/gettts?lan=zh&text=$encoded&spd=3",
                "https://fanyi.baidu.com/") { ok2 ->
                if (ok2) return@tryHttpTts
                tryHttpTts("baidu", "https://tts.baidu.com/text2audio?lan=zh&ie=UTF-8&spd=5&text=$encoded") { ok3 ->
                    if (ok3) return@tryHttpTts
                    tryHttpTts("youdao", "http://tts.youdao.com/fanyivoice?word=$encoded&le=zh") { ok4 ->
                        if (!ok4) handler.post { onLog("⚠ 全部网络TTS路径失败"); isSpeaking = false }
                    }
                }
            }
        }
    }

    private fun tryEdgeTtsWs(text: String, onResult: (Boolean) -> Unit) {
        val req = Request.Builder()
            .url("wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1?TrustedClientToken=6A5AA1D4EAFF4E9FB37E23D68491D6F4")
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()
        val audioChunks = mutableListOf<ByteArray>()
        var wsRef: WebSocket? = null
        val safeText = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val timeoutTask = Runnable { wsRef?.close(1000, "timeout"); handler.post { onResult(false) } }
        handler.postDelayed(timeoutTask, 15000)

        wsRef = httpClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                ws.send("Content-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" +
                    """{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":false,"wordBoundaryEnabled":true},"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}""")
                ws.send("Content-Type:application/ssml+xml; charset=utf-8\r\nPath:ssml\r\n\r\n" +
                    "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='zh-CN'><voice name='zh-CN-XiaoxiaoNeural'>$safeText</voice></speak>")
            }
            override fun onMessage(ws: WebSocket, text: String) {
                if (text.contains("turn.end")) {
                    handler.removeCallbacks(timeoutTask); ws.close(1000, "done")
                    val size = audioChunks.sumOf { it.size }
                    if (size < 200) { handler.post { onResult(false) }; return }
                    saveAndPlay(audioChunks) { handler.post { onResult(true) } }
                }
            }
            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                val data = stripWsHeader(bytes.toByteArray())
                if (data.isNotEmpty()) audioChunks.add(data)
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                handler.removeCallbacks(timeoutTask); handler.post { onResult(false) }
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) { handler.removeCallbacks(timeoutTask) }
        })
    }

    private fun tryHttpTts(name: String, url: String, referer: String? = null, onResult: (Boolean) -> Unit) {
        try {
            val ref = referer ?: if (url.contains("baidu")) "https://www.baidu.com/" else "http://www.youdao.com/"
            val response = httpClient.newCall(Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0").header("Referer", ref).build()).execute()
            if (!response.isSuccessful) { response.close(); onResult(false); return }
            val body = response.body ?: run { onResult(false); return }
            val mp3File = File(cacheDir, "speak_${System.currentTimeMillis()}.mp3")
            body.byteStream().use { i -> FileOutputStream(mp3File).use { o -> i.copyTo(o) } }
            if (mp3File.length() < 200) { mp3File.delete(); onResult(false); return }
            handler.post { playAudio(mp3File) }; onResult(true)
        } catch (_: Exception) { onResult(false) }
    }

    private fun saveAndPlay(chunks: List<ByteArray>, onDone: () -> Unit) {
        val mp3File = File(cacheDir, "speak_${System.currentTimeMillis()}.mp3")
        try {
            FileOutputStream(mp3File).use { out -> chunks.forEach { out.write(it) } }
            onLog("✓ Edge WS(${mp3File.length()}B)")
            playAudio(mp3File)
            onDone()
        } catch (e: Exception) {
            onLog("  Edge WS err: ${e.message?.take(30)}")
            onDone()
        }
    }

    private fun playAudio(file: File) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    isSpeaking = false; handler.removeCallbacks(speakingTimeout)
                    abandonAudioFocus()
                    release(); if (mediaPlayer === this) mediaPlayer = null
                    file.delete()
                }
                setOnErrorListener { mp, what, extra ->
                    isSpeaking = false; handler.removeCallbacks(speakingTimeout)
                    abandonAudioFocus()
                    onLog("⚠ 播放失败 what=$what"); mp.release()
                    if (mediaPlayer === mp) mediaPlayer = null; file.delete(); true
                }
                prepare(); start()
            }
            handler.postDelayed(speakingTimeout, 15000)
        } catch (e: Exception) {
            isSpeaking = false; file.delete()
        }
    }

    private val speakingTimeout = Runnable {
        if (isSpeaking) {
            isSpeaking = false
            try { tts?.stop(); mediaPlayer?.stop(); mediaPlayer?.release() } catch (_: Exception) {}
            mediaPlayer = null
            abandonAudioFocus()
        }
    }

    private fun stripWsHeader(data: ByteArray): ByteArray {
        for (i in 0 until data.size - 3)
            if (data[i] == 0x0d.toByte() && data[i+1] == 0x0a.toByte() &&
                data[i+2] == 0x0d.toByte() && data[i+3] == 0x0a.toByte())
                return data.copyOfRange(i + 4, data.size)
        return data
    }
}
