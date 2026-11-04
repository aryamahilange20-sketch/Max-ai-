package com.maxai.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.util.Locale
import kotlin.math.sqrt

/** Everything MAX remembers: API key, voice, saved contacts, notes. */
object Store {
    private fun p(c: Context) = c.getSharedPreferences("max", Context.MODE_PRIVATE)
    fun key(c: Context) = p(c).getString("key", "") ?: ""
    fun setKey(c: Context, k: String) = p(c).edit().putString("key", k.trim()).apply()
    fun voice(c: Context) = p(c).getString("voice", "Leda") ?: "Leda"
    fun setVoice(c: Context, v: String) = p(c).edit().putString("voice", v).apply()
    fun lang(c: Context) = p(c).getString("lang", "hi-IN") ?: "hi-IN"
    fun setLang(c: Context, v: String) = p(c).edit().putString("lang", v).apply()
    fun done(c: Context) = p(c).getBoolean("done", false)
    fun setDone(c: Context, v: Boolean) = p(c).edit().putBoolean("done", v).apply()
    fun autoSend(c: Context) = p(c).getBoolean("autosend", false)
    fun setAutoSend(c: Context, v: Boolean) = p(c).edit().putBoolean("autosend", v).apply()
    fun dark(c: Context) = p(c).getBoolean("dark", true)
    fun setDark(c: Context, v: Boolean) = p(c).edit().putBoolean("dark", v).apply()

    // ---- boss password lock
    fun ownerPass(c: Context): String = p(c).getString("passwd", "") ?: ""
    fun setOwnerPass(c: Context, s: String) = p(c).edit().putString("passwd", s.trim()).apply()
    fun lockedOut(c: Context) = p(c).getBoolean("lockout", false)
    fun unlock(c: Context) = p(c).edit().putLong("unlock_until", System.currentTimeMillis() + 10 * 60_000L).putInt("wrong", 0).apply()
    fun unlockedNow(c: Context) = System.currentTimeMillis() < p(c).getLong("unlock_until", 0L)
    /** Counts a failed voice/password check and returns what MAX should say. */
    fun failed(c: Context): String {
        if (lockedOut(c)) return "Main ab aadesh nahi maanungi. Boss Settings mein jaake unlock karein."
        val n = p(c).getInt("wrong", 0) + 1
        p(c).edit().putInt("wrong", n).apply()
        if (n >= 3) {
            p(c).edit().putBoolean("lockout", true).apply()
            return "Mujhe nahi pata ye kaun hai. Main aadesh nahi maanungi."
        }
        return "Tum mere boss nahi ho. Warning $n of 3."
    }
    fun resetLock(c: Context) = p(c).edit().putBoolean("lockout", false).putInt("wrong", 0).putLong("unlock_until", 0L).apply()

    /** Returns a spoken reply if MAX must refuse, or null if the command may run. */
    fun guard(c: Context, text: String): String? {
        val pass = ownerPass(c)
        if (pass.isBlank()) return null
        if (lockedOut(c)) return "Main ab aadesh nahi maanungi. Boss Settings mein jaake unlock karein."
        val now = System.currentTimeMillis()
        if (now < p(c).getLong("unlock_until", 0L)) return null
        if (text.lowercase().contains(pass.lowercase())) {
            p(c).edit().putLong("unlock_until", now + 10 * 60_000L).putInt("wrong", 0).apply()
            return "Haan boss, main sun rahi hoon."
        }
        val n = p(c).getInt("wrong", 0) + 1
        p(c).edit().putInt("wrong", n).apply()
        if (n >= 3) {
            p(c).edit().putBoolean("lockout", true).apply()
            return "Mujhe nahi pata ye kaun hai. Main aadesh nahi maanungi."
        }
        return "Tum mere boss nahi ho. Boss ka password bolo. Warning $n of 3."
    }
    fun emergencyNames(c: Context): Set<String> {
        val a = JSONArray(p(c).getString("emergency", "[]") ?: "[]")
        return (0 until a.length()).map { a.getString(it) }.toSet()
    }
    fun setEmergency(c: Context, name: String, on: Boolean) {
        val s = emergencyNames(c).toMutableSet()
        if (on) s.add(name) else s.remove(name)
        p(c).edit().putString("emergency", JSONArray(s.toList()).toString()).apply()
    }
    fun emergencyContacts(c: Context): List<Pair<String, String>> {
        val names = emergencyNames(c)
        return contacts(c).filterKeys { it in names }.map { it.key to it.value }
    }

    fun contacts(c: Context): Map<String, String> {
        val o = JSONObject(p(c).getString("contacts", "{}") ?: "{}")
        return o.keys().asSequence().associateWith { o.getString(it) }
    }
    fun saveContact(c: Context, name: String, number: String) {
        val o = JSONObject(p(c).getString("contacts", "{}") ?: "{}")
        o.put(name.trim(), number.trim())
        p(c).edit().putString("contacts", o.toString()).apply()
    }
    fun deleteContact(c: Context, name: String) {
        val o = JSONObject(p(c).getString("contacts", "{}") ?: "{}")
        o.remove(name)
        p(c).edit().putString("contacts", o.toString()).apply()
    }

    fun notes(c: Context): List<String> {
        val a = JSONArray(p(c).getString("notes", "[]") ?: "[]")
        return (0 until a.length()).map { a.getString(it) }
    }
    private fun putNotes(c: Context, l: List<String>) = p(c).edit().putString("notes", JSONArray(l).toString()).apply()
    fun addNote(c: Context, t: String) = putNotes(c, notes(c) + t)
    fun deleteNote(c: Context, i: Int) = putNotes(c, notes(c).filterIndexed { j, _ -> j != i })
}

/** Gemini REST client: planner (JSON), research (Google Search grounding), streaming HTML, natural TTS. */
object Gemini {
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models/"
    // Tried in order. If one model is unavailable on the free tier, the next one is used.
    private var cachedChat: List<String>? = null
    private var cachedTts: List<String>? = null

    /** Asks Google which models this API key can use right now, newest first. */
    private fun discover(c: Context, want: (String) -> Boolean): List<String> = runCatching {
        val h = (URL("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 10000; readTimeout = 10000
            setRequestProperty("x-goog-api-key", Store.key(c))
        }
        val arr = JSONObject(h.inputStream.bufferedReader().readText()).getJSONArray("models")
        (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { m ->
                val methods = m.optJSONArray("supportedGenerationMethods")
                methods != null && (0 until methods.length()).any { methods.getString(it) == "generateContent" }
            }
            .map { it.getString("name").removePrefix("models/") }
            .filter(want)
            .sortedDescending()
    }.getOrDefault(emptyList())

    private fun chatModels(c: Context): List<String> {
        cachedChat?.let { return it }
        val found = discover(c) { n ->
            n.startsWith("gemini-") && "flash" in n && listOf("tts", "image", "live", "embed", "audio", "native").none { it in n }
        }
        return (found.ifEmpty { listOf("gemini-2.5-flash", "gemini-2.5-flash-lite") }).also { cachedChat = it }
    }

    private fun ttsModels(c: Context): List<String> {
        cachedTts?.let { return it }
        val found = discover(c) { it.startsWith("gemini-") && "tts" in it }
        return (found.ifEmpty { listOf("gemini-2.5-flash-preview-tts", "gemini-2.5-pro-preview-tts") }).also { cachedTts = it }
    }
    private const val VOICE_SYS =
        "You are MAX, a warm Indian girl assistant speaking aloud. Answer in at most 4 short sentences, in the user's language " +
            "(Hindi in Devanagari with English words in Latin script, or English). No markdown, lists or emojis."

    private fun conn(c: Context, model: String, method: String): HttpURLConnection =
        (URL("$BASE$model:$method").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 15000; readTimeout = 120000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", Store.key(c))
        }

    private fun send(h: HttpURLConnection, body: JSONObject) {
        h.outputStream.use { it.write(body.toString().toByteArray()) }
        if (h.responseCode !in 200..299) {
            val e = (h.errorStream ?: h.inputStream).bufferedReader().readText()
            throw IllegalStateException(runCatching { JSONObject(e).getJSONObject("error").getString("message") }.getOrDefault("HTTP ${h.responseCode}"))
        }
    }

    private fun call(c: Context, models: List<String>, body: JSONObject): JSONObject {
        var last: Exception = IllegalStateException("No Gemini model answered")
        for (m in models) {
            try {
                val h = conn(c, m, "generateContent")
                send(h, body)
                return JSONObject(h.inputStream.bufferedReader().readText())
            } catch (e: Exception) {
                last = e
            }
        }
        throw last
    }

    private fun text(j: JSONObject): String {
        val parts = j.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
        return (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }
    }

    private fun body(sys: String, user: String, json: Boolean = false, search: Boolean = false): JSONObject {
        val b = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sys))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user)))))
        val g = JSONObject().put("temperature", 0.8).put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        if (json) g.put("responseMimeType", "application/json")
        b.put("generationConfig", g)
        if (search) b.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        return b
    }

    private fun planPrompt(contacts: String) = """
You are MAX, a warm, playful Indian girl assistant that controls the user's Android phone by voice. Now: ${java.util.Date()}.
Understand the request (Hindi, English or Hinglish) and reply with ONE JSON object: {"action":"...","args":{...},"say":"..."}
"say" is what you speak aloud: 1-2 short, natural, friendly sentences in the user's language (Hindi in Devanagari with English words in Latin script; English for English). No emojis, no markdown.
Actions:
- chat: {} just talk or answer from your own knowledge.
- research: {"query"} for news, facts, prices, weather, sports, anything current. say = a very short "ek second, dekhti hoon" line.
- open_app: {"app"} app name in English exactly as shown on a phone (YouTube, WhatsApp, Camera, Settings...).
- call: {"name"} call a saved contact or a number.
- whatsapp: {"to","message"} send a real WhatsApp message to a saved contact or a number.
- save_contact: {"name","number"}.
- chrome_search: {"query"} open Google search in Chrome. open_url: {"url"}.
- note_add: {"text"}. note_read: {}.
- build_site: {"name","brief"} create a website. name = short english slug, brief = full detailed description of the site.
- system: {"cmd"} one of back, home, recents, notifications, quick_settings, screenshot, lock.
- ui_click: {"text"} tap the on-screen item with this text. ui_type: {"text"}. ui_scroll: {"dir"} up or down. read_screen: {}.
- flashlight: {"on":true or false}. volume: {"dir"} up, down or mute.
- photo: {} open the camera. screenshot: {} take a screenshot.
- answer_call: {} pick up the ringing call. reject_call: {} end the call.
- contacts: {} list the names in the phone's contacts.
- emergency_sms: {"message"} send an SMS to the contacts marked Emergency (SOS) in Yaadein.
- chatgpt: {"prompt"} open the ChatGPT app and send this text to it (for chat or image requests).
Saved contacts in MAX: $contacts
For call and whatsapp use the exact saved name from that list when it matches, otherwise the spoken name. Never refuse; if unsure pick the closest action or chat.
""".trimIndent()

    fun plan(c: Context, user: String, history: String): JSONObject {
        val sys = planPrompt(Store.contacts(c).keys.joinToString(", ").ifEmpty { "none" })
        val msg = if (history.isBlank()) user else "Earlier:\n$history\n\nUser now: $user"
        val t = text(call(c, chatModels(c), body(sys, msg, json = true)))
        return JSONObject(t.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
    }

    fun ask(c: Context, user: String, search: Boolean = false): String =
        text(call(c, chatModels(c), body(VOICE_SYS, user, search = search))).trim()

    /** Streams a full HTML page; onChunk receives each piece as it is written (for the transparent code screen). */
    fun streamHtml(c: Context, brief: String, onChunk: (String) -> Unit): String {
        val sys = "You are an expert front-end designer. Output ONLY one complete, self-contained, beautiful, responsive HTML file " +
            "(inline CSS and JS, Google Fonts allowed, no other external files). No explanations, no markdown fences."
        val h = conn(c, chatModels(c).first(), "streamGenerateContent?alt=sse")
        send(h, body(sys, brief))
        val sb = StringBuilder()
        h.inputStream.bufferedReader().forEachLine { l ->
            if (l.startsWith("data:")) {
                runCatching { text(JSONObject(l.substring(5).trim())) }.getOrNull()?.let { sb.append(it); onChunk(it) }
            }
        }
        return sb.toString().trim().removePrefix("```html").removePrefix("```").removeSuffix("```").trim()
    }

    /** Natural female voice. Returns 24 kHz 16-bit mono PCM. */
    fun speech(c: Context, text: String): ByteArray {
        val b = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text",
                "Say this in a sweet, soft, cheerful, warm young girl voice, gentle and caring, with natural pauses and a little smile in the voice: $text")))))
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", Store.voice(c))))))
        val d = call(c, ttsModels(c), b).getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
            .getJSONArray("parts").getJSONObject(0).getJSONObject("inlineData").getString("data")
        return Base64.decode(d, Base64.DEFAULT)
    }
}

/** Plays MAX's voice (Gemini TTS) and falls back to the phone's own TTS if the network call fails. */
class Speaker(private val c: Context) {
    private var track: AudioTrack? = null
    private var tts: TextToSpeech? = null
    private var ready = false

    init { tts = TextToSpeech(c.applicationContext) { ready = it == TextToSpeech.SUCCESS } }

    suspend fun say(text: String, onLevel: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val pcm = runCatching { Gemini.speech(c, text) }.getOrNull()
        if (pcm != null) play(pcm, onLevel) else fallback(text)
    }

    private suspend fun play(pcm: ByteArray, onLevel: (Float) -> Unit) {
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(24000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(pcm.size).setTransferMode(AudioTrack.MODE_STATIC).build()
        track = t
        try {
            t.write(pcm, 0, pcm.size)
            t.play()
            val frames = pcm.size / 2
            while (t.playState == AudioTrack.PLAYSTATE_PLAYING && t.playbackHeadPosition < frames) {
                val s = (t.playbackHeadPosition * 2).coerceIn(0, maxOf(0, pcm.size - 800))
                val end = minOf(s + 800, pcm.size - 1)
                var sum = 0.0; var i = s
                while (i < end) {
                    val v = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort() / 32768.0
                    sum += v * v; i += 2
                }
                onLevel(minOf(1f, (sqrt(sum / 400) * 4).toFloat()))
                delay(50)
            }
        } finally {
            runCatching { t.release() }; track = null; onLevel(0f)
        }
    }

    private suspend fun fallback(text: String) {
        val t = tts ?: return
        if (!ready) return
        val d = CompletableDeferred<Unit>()
        t.language = if (text.any { it in '\u0900'..'\u097F' }) Locale("hi", "IN") else Locale("en", "IN")
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { d.complete(Unit) }
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(id: String?) { d.complete(Unit) }
        })
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "max")
        withTimeoutOrNull(30000) { d.await() }
    }

    fun stop() {
        runCatching { track?.stop() }
        tts?.stop()
    }
}

/** Tiny localhost web server so Chrome can open the sites MAX builds (http://127.0.0.1:8765/...). */
object SiteServer {
    private const val PORT = 8765
    private var srv: ServerSocket? = null

    fun root(c: Context) = File(c.filesDir, "sites").apply { mkdirs() }

    @Synchronized
    fun ensure(c: Context): Int {
        if (srv != null) return PORT
        val s = ServerSocket(PORT, 10, InetAddress.getByName("127.0.0.1"))
        srv = s
        val root = root(c).canonicalFile
        Thread {
            while (true) {
                val sock = try { s.accept() } catch (e: Exception) { break }
                Thread {
                    runCatching {
                        val r = sock.getInputStream().bufferedReader()
                        val line = r.readLine() ?: return@runCatching
                        while (true) { val h = r.readLine(); if (h.isNullOrEmpty()) break }
                        var path = line.split(" ").getOrElse(1) { "/" }.substringBefore("?")
                        if (path.endsWith("/")) path += "index.html"
                        val f = File(root, path.trimStart('/')).canonicalFile
                        val out = sock.getOutputStream()
                        if (f.path.startsWith(root.path) && f.isFile) {
                            val bytes = f.readBytes()
                            val mime = when (f.extension) { "html" -> "text/html"; "css" -> "text/css"; "js" -> "text/javascript"; else -> "text/plain" }
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: $mime; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            out.write(bytes)
                        } else {
                            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        }
                        out.flush()
                    }
                    runCatching { sock.close() }
                }.start()
            }
        }.start()
        return PORT
    }
}
