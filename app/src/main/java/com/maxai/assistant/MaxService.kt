package com.maxai.assistant

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File

/** RecognitionListener with empty defaults so we only override what we need. */
open class L : RecognitionListener {
    override fun onReadyForSpeech(p: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(v: Float) {}
    override fun onBufferReceived(b: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onError(e: Int) {}
    override fun onResults(b: Bundle?) {}
    override fun onPartialResults(b: Bundle?) {}
    override fun onEvent(t: Int, p: Bundle?) {}
}

class MaxService : Service() {
    companion object {
        val running = MutableStateFlow(false)
        val lastReply = MutableStateFlow("")

        /** actions: START, STOP, TALK (open mic now), TEXT (typed command). */
        fun send(c: Context, action: String, text: String? = null) {
            if (c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
            ContextCompat.startForegroundService(c, Intent(c, MaxService::class.java).setAction(action).putExtra("text", text))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var overlay: NeonOverlay
    private lateinit var speaker: Speaker
    private lateinit var actions: Actions
    private var rec: SpeechRecognizer? = null
    private var job: Job? = null
    private var busy = false
    private val history = ArrayDeque<String>()
    private val wakeWords = listOf("hello max", "hallo max", "helo max", "hey max", "hi max", "हेलो मैक्स", "हैलो मैक्स", "मैक्स", "max", "maks")
    private val RESULTS = SpeechRecognizer.RESULTS_RECOGNITION

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startFg()
        overlay = NeonOverlay(this); speaker = Speaker(this); actions = Actions(this)
        running.value = true
        listenWake()
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        when (i?.action) {
            "STOP" -> stopSelf()
            "TALK" -> onWake("")
            "TEXT" -> handle(i?.getStringExtra("text").orEmpty())
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel(); rec?.destroy(); speaker.stop(); overlay.hide(); running.value = false
        super.onDestroy()
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("max", "MAX", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, MaxService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, "max")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("MAX jaag rahi hai")
            .setContentText("\"Hello MAX\" bolo")
            .setContentIntent(open).addAction(0, "Band karo", stop).setOngoing(true).build()
        ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    // ---------- listening ----------

    private fun fresh(): SpeechRecognizer {
        rec?.destroy()
        return SpeechRecognizer.createSpeechRecognizer(this).also { rec = it }
    }

    private fun intent(lang: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1600)

    /** Mutes the recognizer's start beep for a moment (only if the user hadn't muted that stream). */
    private fun quiet(block: () -> Unit) {
        val am = getSystemService(AudioManager::class.java)
        val muted = runCatching { am.isStreamMute(AudioManager.STREAM_NOTIFICATION) }.getOrDefault(true)
        if (!muted) runCatching { am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_MUTE, 0) }
        block()
        if (!muted) ui.postDelayed({ runCatching { am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0) } }, 700)
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun again(ms: Long = 250) { ui.postDelayed({ if (!busy) listenWake() }, ms) }

    /** Always-on loop: listens in short bursts for "MAX". */
    private fun listenWake() {
        if (!hasMic() || busy) return
        val r = fresh()
        r.setRecognitionListener(object : L() {
            override fun onPartialResults(b: Bundle?) { check(b) }
            override fun onResults(b: Bundle?) { if (!check(b)) again() }
            override fun onError(e: Int) { again(if (e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1500 else 250) }
        })
        quiet { r.startListening(intent("en-IN")) }
    }

    private fun check(b: Bundle?): Boolean {
        if (busy) return true
        val t = b?.getStringArrayList(RESULTS)?.firstOrNull() ?: return false
        val low = t.lowercase()
        val w = wakeWords.firstOrNull { low.contains(it) } ?: return false
        busy = true
        val rest = t.substring((low.indexOf(w) + w.length).coerceAtMost(t.length)).trim(' ', ',', '.', '!')
        ui.post { onWake(rest) }
        return true
    }

    private fun onWake(rest: String) {
        job?.cancel(); speaker.stop(); busy = true
        overlay.show { cancelAll() }
        overlay.state(1, "SUN RAHI HOON…")
        if (rest.length > 2) handle(rest) else listenCommand()
    }

    private fun listenCommand() {
        val r = fresh()
        r.setRecognitionListener(object : L() {
            override fun onRmsChanged(v: Float) { overlay.level(((v + 2f) / 12f).coerceIn(0f, 1f)) }
            override fun onPartialResults(b: Bundle?) { b?.getStringArrayList(RESULTS)?.firstOrNull()?.let { overlay.heard(it) } }
            override fun onResults(b: Bundle?) {
                val t = b?.getStringArrayList(RESULTS)?.firstOrNull().orEmpty()
                if (t.isBlank()) finish() else handle(t)
            }
            override fun onError(e: Int) { finish() }
        })
        quiet { r.startListening(intent(Store.lang(this))) }
    }

    private fun finish(delayMs: Long = 0) {
        ui.postDelayed({ overlay.hide(); busy = false; listenWake() }, delayMs)
    }

    private fun cancelAll() {
        job?.cancel(); speaker.stop(); rec?.cancel(); finish()
    }

    // ---------- brain ----------

    /** Asks out loud and waits for a spoken yes/no. Nothing sensitive happens without this. */
    private suspend fun confirm(q: String): Boolean {
        speak(q)
        val d = CompletableDeferred<String>()
        ui.post {
            val r = fresh()
            r.setRecognitionListener(object : L() {
                override fun onResults(b: Bundle?) { d.complete(b?.getStringArrayList(RESULTS)?.firstOrNull().orEmpty()) }
                override fun onError(e: Int) { d.complete("") }
            })
            overlay.state(1, "HAAN YA NA BOLO…")
            quiet { r.startListening(intent(Store.lang(this))) }
        }
        val a = withTimeoutOrNull(9000) { d.await() }.orEmpty().lowercase()
        return listOf("haan", "han ", "yes", "yeah", "ok", "kar do", "kardo", "bhej do", "हाँ", "हां", "ठीक", "कर दो", "भेज दो").any { a.contains(it) }
    }

    private suspend fun speak(t: String) {
        if (t.isBlank()) return
        lastReply.value = t
        overlay.state(3, "BOL RAHI HOON…"); overlay.heard(t)
        speaker.say(t) { overlay.level(it) }
    }

    private fun handle(text: String) {
        if (text.isBlank()) return
        busy = true
        overlay.heard(text); overlay.state(2, "SOCH RAHI HOON…")
        job?.cancel()
        job = scope.launch {
            val ctx = this@MaxService
            var hideAfter = 700L
            try {
                val plan = withContext(Dispatchers.IO) { Gemini.plan(ctx, text, history.joinToString("\n")) }
                val a = plan.optString("action", "chat")
                val g = plan.optJSONObject("args") ?: JSONObject()
                var say = plan.optString("say")
                val noA11y = "Accessibility service on nahi hai, pehle usse on karo."
                when (a) {
                    "open_app" -> if (!actions.openApp(g.optString("app"))) say = "Mujhe ye app nahi mila."
                    "call" -> {
                        val n = actions.number(g.optString("name"))
                        if (n == null) say = "Ye naam mere paas save nahi hai. Yaadein mein number daal do."
                        else if (!confirm("${g.optString("name")} ko call lagaun? Haan ya na bolo.")) say = "Theek hai, call cancel."
                        else { speak(say); say = ""; actions.call(n) }
                    }
                    "whatsapp" -> {
                        val n = actions.number(g.optString("to"))
                        if (n == null) say = "Ye number save nahi hai."
                        else if (!confirm("${g.optString("to")} ko ye message bhejun: ${g.optString("message")}. Haan ya na bolo.")) say = "Theek hai, message nahi bheja."
                        else if (!actions.whatsapp(n, g.optString("message"))) say = "WhatsApp install nahi mila."
                    }
                    "save_contact" -> Store.saveContact(ctx, g.optString("name"), g.optString("number"))
                    "research" -> {
                        overlay.state(2, "SEARCH KAR RAHI HOON…")
                        say = withContext(Dispatchers.IO) { Gemini.ask(ctx, g.optString("query", text), search = true) }
                        hideAfter = 400
                    }
                    "chrome_search" -> actions.url("https://www.google.com/search?q=" + Uri.encode(g.optString("query")))
                    "open_url" -> actions.url(g.optString("url"))
                    "note_add" -> Store.addNote(ctx, g.optString("text"))
                    "note_read" -> say = Store.notes(ctx).takeLast(5).joinToString(". ").ifEmpty { "Abhi koi note nahi hai." }
                    "system" -> if (!actions.global(g.optString("cmd"))) say = noA11y
                    "ui_click" -> if (!actions.click(g.optString("text"))) say = "Screen par ye mujhe nahi dikha."
                    "ui_type" -> if (!actions.type(g.optString("text"))) say = "Koi text box select nahi hai."
                    "ui_scroll" -> if (!actions.scroll(g.optString("dir"))) say = noA11y
                    "read_screen" -> {
                        val s = actions.screenText()
                        say = if (s.isBlank()) noA11y
                        else withContext(Dispatchers.IO) { Gemini.ask(ctx, "Briefly tell me what is on my phone screen:\n$s") }
                    }
                    "flashlight" -> actions.torch(g.optBoolean("on", true))
                    "volume" -> actions.volume(g.optString("dir"))
                    "build_site" -> { say = buildSite(g, say); hideAfter = 1500 }
                }
                speak(say)
                history.addLast("User: $text\nMAX: $say")
                while (history.size > 6) history.removeFirst()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                speak("Kuch gadbad ho gayi. ${e.message.orEmpty().take(90)}")
            }
            finish(hideAfter)
        }
    }

    /** Streams the HTML from Gemini onto the transparent code screen, saves it, opens it in Chrome. */
    private suspend fun buildSite(g: JSONObject, intro: String): String = coroutineScope {
        overlay.show { cancelAll() }; overlay.codeOn(); overlay.state(2, "CODE LIKH RAHI HOON…")
        val talk = launch { speak(intro) }
        val slug = g.optString("name", "site").lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "site" }
        val html = withContext(Dispatchers.IO) {
            Gemini.streamHtml(this@MaxService, g.optString("brief")) { overlay.code(it) }
        }
        talk.join()
        val dir = File(SiteServer.root(this@MaxService), slug).apply { mkdirs() }
        File(dir, "index.html").writeText(html)
        actions.url("http://127.0.0.1:${SiteServer.ensure(this@MaxService)}/$slug/index.html")
        "Ho gaya! Website Chrome mein khol di."
    }
}
