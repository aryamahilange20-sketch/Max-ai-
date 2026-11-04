package com.maxai.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/** Gemini Live: real two-way voice over a WebSocket. Mic streams up, voice streams back, user can interrupt. */
class LiveSession(
    private val c: Context,
    private val onLevel: (Float) -> Unit,
    private val onText: (String) -> Unit,
    private val onEnd: () -> Unit
) {
    companion object {
        private const val IN_RATE = 16000
        private const val OUT_RATE = 24000
        private const val CHUNK = 1600 // 100 ms at 16 kHz
        private const val SYS = "You are MAX, a warm, friendly, playful Indian girl assistant on the user's phone. " +
            "Speak short natural sentences, 1 to 3 at a time. Reply in the language the user uses (Hindi, Hinglish or English). " +
            "Never use markdown or emojis."

        /** Finds the live (bidi) model this API key can use, newest first. */
        fun liveModel(c: Context): String = runCatching {
            val h = (URL("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200").openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("x-goog-api-key", Store.key(c))
            }
            val arr = JSONObject(h.inputStream.bufferedReader().readText()).getJSONArray("models")
            (0 until arr.length()).map { arr.getJSONObject(it) }
                .filter { m ->
                    val ms = m.optJSONArray("supportedGenerationMethods")
                    ms != null && (0 until ms.length()).any { ms.getString(it) == "bidiGenerateContent" }
                }
                .map { it.getString("name").removePrefix("models/") }
                .sortedDescending()
                .firstOrNull()
        }.getOrNull() ?: "gemini-2.0-flash-live-001"
    }

    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    @Volatile private var ws: WebSocket? = null
    @Volatile private var running = false
    @Volatile private var lastSpeech = 0L
    private var track: AudioTrack? = null

    private fun ui(block: () -> Unit) { main.post { block() } }

    fun start() {
        val key = Store.key(c)
        if (key.isBlank()) {
            ui { onText("API key nahi hai. Settings mein daalo.") }
            ui { onEnd() }
            return
        }
        val model = liveModel(c)
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$key"
        running = true
        lastSpeech = System.currentTimeMillis()
        ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(setupMessage(model).toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(bytes.utf8())
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ui { onText("Live connection toot gaya: ${t.message.orEmpty().take(90)}") }
                stop()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { stop() }
        })
    }

    private fun setupMessage(model: String): JSONObject = JSONObject().put("setup", JSONObject()
        .put("model", "models/$model")
        .put("generationConfig", JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put("speechConfig", JSONObject().put("voiceConfig",
                JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", Store.voice(c))))))
        .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYS))))
        .put("inputAudioTranscription", JSONObject())
        .put("outputAudioTranscription", JSONObject()))

    private fun handle(raw: String) {
        val j = runCatching { JSONObject(raw) }.getOrNull() ?: return
        if (j.has("setupComplete")) {
            startMic()
            return
        }
        val sc = j.optJSONObject("serverContent") ?: return
        if (sc.optBoolean("interrupted")) runCatching { track?.flush() }
        val parts = sc.optJSONObject("modelTurn")?.optJSONArray("parts")
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val d = parts.getJSONObject(i).optJSONObject("inlineData")?.optString("data").orEmpty()
                if (d.isNotEmpty()) play(Base64.decode(d, Base64.DEFAULT))
            }
        }
        sc.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }?.let { t ->
            ui { onText(t) }
        }
        sc.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }?.let { t ->
            val l = t.lowercase()
            if (listOf("band karo", "stop", "bye", "chup", "bas karo").any { it in l }) ui { stop() }
            else ui { onText(t) }
        }
    }

    @Suppress("MissingPermission")
    private fun startMic() {
        if (!running) return
        val min = AudioRecord.getMinBufferSize(IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            .coerceAtLeast(CHUNK * 4)
        val r = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, IN_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min)
        r.startRecording()
        ui { onText("Bolo, main sun rahi hoon.") }
        Thread {
            val buf = ShortArray(CHUNK)
            try {
                while (running) {
                    val n = r.read(buf, 0, CHUNK)
                    if (n <= 0) continue
                    var s = 0.0
                    for (i in 0 until n) s += buf[i].toDouble() * buf[i]
                    val level = (sqrt(s / n) / 3000.0).coerceIn(0.0, 1.0).toFloat()
                    if (level > 0.05f) lastSpeech = System.currentTimeMillis()
                    ui { onLevel(level) }
                    if (System.currentTimeMillis() - lastSpeech > 40_000L) { ui { stop() }; break }
                    val bytes = ByteArray(n * 2)
                    for (i in 0 until n) {
                        bytes[2 * i] = (buf[i].toInt() and 0xFF).toByte()
                        bytes[2 * i + 1] = (buf[i].toInt() shr 8).toByte()
                    }
                    val msg = JSONObject().put("realtimeInput", JSONObject().put("audio", JSONObject()
                        .put("mimeType", "audio/pcm;rate=16000")
                        .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))))
                    ws?.send(msg.toString())
                }
            } finally {
                runCatching { r.stop() }
                r.release()
            }
        }.start()
    }

    private fun play(pcm: ByteArray) {
        runCatching {
            val t = track ?: AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(OUT_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(OUT_RATE * 2)
                .setTransferMode(AudioTrack.MODE_STREAM).build()
                .also { track = it; it.play() }
            t.write(pcm, 0, pcm.size)
        }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { ws?.close(1000, null) }
        ws = null
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
        ui { onEnd() }
    }
}
