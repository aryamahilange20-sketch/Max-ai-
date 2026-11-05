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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

        /** actions: START, STOP, TALK, TEXT, CALL_RING. */
        fun send(c: Context, action: String, text: String? = null, number: String? = null) {
            if (c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
            ContextCompat.startForegroundService(
                c, Intent(c, MaxService::class.java).setAction(action).putExtra("text", text).putExtra("number", number)
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var overlay: NeonOverlay
    private lateinit var glow: SiriGlow
    private lateinit var speaker: Speaker
    private lateinit var actions: Actions
    private var rec: SpeechRecognizer? = null
    private var job: Job? = null
    private var busy = false
    @Volatile private var speaking = false
    private var live: LiveSession? = null
    private val history = ArrayDeque<String>()
    private val wakeRe = Regex("(?i)\\bmax\\b|मैक्स")
    private val RESULTS = SpeechRecognizer.RESULTS_RECOGNITION

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startFg()
        overlay = NeonOverlay(this)
        glow = SiriGlow(this)
        speaker = Speaker(this)
        actions = Actions(this)
        running.value = true
        listenWake()
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        when (i?.action) {
            "STOP" -> stopSelf()
            "TALK" -> onWake("")
            "TEXT" -> handle(i?.getStringExtra("text").orEmpty())
            "CALL_RING" -> incomingCall(i?.getStringExtra("number").orEmpty())
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        live?.stop()
        rec?.destroy()
        speaker.stop()
        overlay.hide()
        glow.hide()
        running.value = false
        super.onDestroy()
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("max", "MAX", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, MaxService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, "max")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("MAX jaag raha hai")
            .setContentText("\"Hello MAX\" bolo")
            .setContentIntent(open).addAction(0, "Band karo", stop).setOngoing(true).build()
        ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    // ---------- listening ----------

    private fun fresh(): SpeechRecognizer {
        rec?.destroy()
        return SpeechRecognizer.createSpeechRecognizer(this).also { rec = it }
    }

    private fun intent(lang: String, silenceMs: Int = 1600) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs)

    /** Mutes the recognizer's start beep (system and notification streams) for a moment, then restores them. */
    private fun quiet(block: () -> Unit) {
        val am = getSystemService(AudioManager::class.java)
        val streams = intArrayOf(AudioManager.STREAM_SYSTEM, AudioManager.STREAM_NOTIFICATION)
        val was = streams.map { runCatching { am.isStreamMute(it) }.getOrDefault(true) }
        streams.forEachIndexed { i, s -> if (!was[i]) runCatching { am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, 0) } }
        block()
        ui.postDelayed({
            streams.forEachIndexed { i, s -> if (!was[i]) runCatching { am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0) } }
        }, 800)
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Wait before the next listening round, so the mic does not restart in a tight loop. */
    private fun again(ms: Long = 1500) {
        ui.postDelayed({ if (!busy) listenWake() }, ms)
    }

    /** Always-on, quiet loop that waits for the word "MAX". */
    private fun listenWake() {
        if (!hasMic() || busy || speaking) return
        val r = fresh()
        r.setRecognitionListener(object : L() {
            override fun onPartialResults(b: Bundle?) { check(b) }
            override fun onResults(b: Bundle?) { if (!check(b)) again(1500) }
            override fun onError(e: Int) {
                val wait = when (e) {
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 3000L
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> 10000L
                    else -> 1500L
                }
                again(wait)
            }
        })
        quiet { r.startListening(intent("en-IN", 2000)) }
    }

    private fun check(b: Bundle?): Boolean {
        if (busy || speaking) return true
        val t = b?.getStringArrayList(RESULTS)?.firstOrNull() ?: return false
        val m = wakeRe.find(t) ?: return false
        busy = true
        val rest = t.substring(m.range.last + 1).trim(' ', ',', '.', '!')
        ui.post { onWake(rest) }
        return true
    }

    private fun onWake(rest: String) {
        job?.cancel()
        speaker.stop()
        busy = true
        glow.show()
        glow.state("SUN RAHI HOON…")
        if (VoiceId.enrolled(this) && !Store.unlockedNow(this)) {
            job = scope.launch { voiceGate(rest) }
            return
        }
        if (rest.length > 2) handle(rest) else startLiveOrListen()
    }

    /** Live voice when a key exists, otherwise the simple listening mode. */
    private fun startLiveOrListen() {
        if (Store.key(this).isBlank()) { listenCommand(); return }
        startLive()
    }

    private fun startLive() {
        busy = true
        rec?.cancel()
        speaker.stop()
        glow.show()
        live?.stop()
        live = LiveSession(
            this,
            onLevel = { glow.setLevel(it) },
            onText = { t -> lastReply.value = t; glow.heard(t) },
            onEnd = { live = null; glow.setLevel(0f); finish(0) }
        ).also { it.start() }
    }

    /** Checks the boss's voice in the next 3 seconds, then runs the command or warns. */
    private suspend fun voiceGate(rest: String) {
        rec?.cancel()
        if (Store.lockedOut(this)) {
            speak("Main ab aadesh nahi maanungi. Boss Settings mein jaake unlock karein.")
            finish(300)
            return
        }
        glow.state("AWAAZ CHECK…")
        val pcm = withContext(Dispatchers.IO) { runCatching { VoiceId.record(3f) }.getOrDefault(ShortArray(0)) }
        if (VoiceId.rms(pcm) < 150.0) {
            speak("Awaaz sunai nahi di, phir se bolo.")
            finish(300)
            return
        }
        val ok = withContext(Dispatchers.IO) { VoiceId.matches(this@MaxService, pcm) }
        if (ok) {
            Store.unlock(this)
            if (rest.length > 2) handle(rest) else { speak("Haan boss, bolo."); startLiveOrListen() }
        } else {
            speak(Store.failed(this))
            finish(300)
        }
    }

    private fun listenCommand() {
        val r = fresh()
        r.setRecognitionListener(object : L() {
            override fun onRmsChanged(v: Float) { glow.setLevel(((v + 2f) / 12f).coerceIn(0f, 1f)) }
            override fun onPartialResults(b: Bundle?) { b?.getStringArrayList(RESULTS)?.firstOrNull()?.let { glow.heard(it) } }
            override fun onResults(b: Bundle?) {
                val t = b?.getStringArrayList(RESULTS)?.firstOrNull().orEmpty()
                if (t.isBlank()) finish() else handle(t)
            }
            override fun onError(e: Int) { finish() }
        })
        quiet { r.startListening(intent(Store.lang(this))) }
    }

    private fun finish(delayMs: Long = 0) {
        ui.postDelayed({
            overlay.hide()
            glow.hide()
            busy = false
            listenWake()
        }, delayMs)
    }

    private fun cancelAll() {
        job?.cancel()
        speaker.stop()
        rec?.cancel()
        finish()
    }

    /** Asks out loud and listens for yes or no. Returns 1 yes, 0 no, -1 no answer. */
    private suspend fun askYesNo(q: String): Int {
        speak(q)
        val d = CompletableDeferred<String?>()
        ui.post {
            val r = fresh()
            r.setRecognitionListener(object : L() {
                override fun onResults(b: Bundle?) { d.complete(b?.getStringArrayList(RESULTS)?.firstOrNull()) }
                override fun onError(e: Int) { d.complete(null) }
            })
            glow.state("HAAN YA NA BOLO…")
            quiet { r.startListening(intent(Store.lang(this))) }
        }
        val heard = (withTimeoutOrNull(9000) { d.await() } ?: "").lowercase()
        val words = heard.split(Regex("[\\s,.!?।]+")).filter { it.isNotEmpty() }.toSet()
        val yes = setOf("haan", "han", "yes", "yeah", "ok", "okay", "kar", "karo", "do", "bhej", "utha", "uthao", "हाँ", "हां", "ठीक", "कर", "दो", "भेज", "उठा")
        val no = setOf("na", "nahi", "mat", "cancel", "नहीं", "ना")
        return when {
            words.any { it in no } -> 0
            words.any { it in yes } -> 1
            else -> -1
        }
    }

    /** Asks before a risky action, unless the user turned that check off in Settings. */
    private suspend fun confirm(q: String): Boolean = Store.autoSend(this) || askYesNo(q) == 1

    /** Commands that work even if the AI model fails. */
    private fun localCmd(text: String): String? {
        val t = text.lowercase()
        if (listOf("creator", "developer", "kisne banaya", "tumhe kisne", "tumhara malik", "tumhara owner", "tumhara boss kaun").any { it in t }) {
            return "Mera creator Arya Mahilange hai, mere boss."
        }
        val off = listOf("off", "band", "bujha").any { it in t }
        if ("torch" in t || "flash" in t || "टॉर्च" in t) {
            actions.torch(!off)
            return if (off) "Torch band kar di." else "Torch on kar di."
        }
        if ("screenshot" in t || "screen shot" in t) {
            return if (screenshotAndShare()) "Screenshot le liya. Bhejne ke liye app chuno."
            else "Screenshot ke liye Accessibility on karo."
        }
        if (Regex("\\b(home|ghar)\\b").containsMatchIn(t)) {
            actions.global("home")
            return "Home par chali gayi."
        }
        if ("volume" in t) {
            val dir = if (listOf("kam", "down", "dhim").any { it in t }) "down" else "up"
            actions.volume(dir)
            return "Volume $dir kar diya."
        }
        val m = Regex("^(?:open|khol[oi]?)\\s+(.+)$").find(t) ?: Regex("^(.+?)\\s+khol(?:o|do|i)$").find(t)
        if (m != null) {
            val name = m.groupValues[1].trim()
            return if (actions.openApp(name)) "$name khol di." else "$name app nahi mila."
        }
        return null
    }

    /** Takes a screenshot, then opens the share sheet with it. */
    private fun screenshotAndShare(): Boolean {
        if (!actions.global("screenshot")) return false
        ui.postDelayed({ actions.latestScreenshotUri()?.let { actions.shareImage(it) } }, 1500)
        return true
    }

    /** After a reply, keep listening so the user can continue without saying "MAX" again. */
    private fun followUp() {
        ui.postDelayed({ listenCommand() }, 600)
    }

    // ---------- incoming calls ----------

    private fun incomingCall(num: String) {
        if (busy) return
        busy = true
        glow.show()
        glow.state("CALL AAYI HAI…")
        job = scope.launch {
            val who = withContext(Dispatchers.IO) { actions.nameFor(num) } ?: num.ifBlank { "Ek unknown number" }
            when (askYesNo("$who ka call aa raha hai. Uthaun? Haan ya na bolo.")) {
                1 -> { actions.answerCall(); speak("Theek hai, call utha li.") }
                0 -> { actions.hangUp(); speak("Call kaat di.") }
                else -> {}
            }
            finish()
        }
    }

    // ---------- brain ----------

    private suspend fun speak(t: String) {
        if (t.isBlank()) return
        lastReply.value = t
        glow.state("BOL RAHI HOON…")
        glow.heard(t)
        speaking = true
        try { speaker.say(t) { glow.setLevel(it) } } finally { speaking = false }
    }

    private fun handle(text: String) {
        if (text.isBlank()) return
        busy = true
        glow.heard(text)
        glow.state("SOCH RAHI HOON…")
        job?.cancel()
        job = scope.launch {
            val ctx = this@MaxService
            var hideAfter = 700L
            try {
                val blocked = Store.guard(ctx, text)
                if (blocked != null) {
                    speak(blocked)
                    followUp()
                    return@launch
                }
                val local = localCmd(text)
                if (local != null) {
                    speak(local)
                    followUp()
                    return@launch
                }
                val plan = withContext(Dispatchers.IO) { Gemini.plan(ctx, text, history.joinToString("\n")) }
                val a = plan.optString("action", "chat")
                val g = plan.optJSONObject("args") ?: JSONObject()
                var say = plan.optString("say")
                val noA11y = "Accessibility service on nahi hai, pehle usse on karo."
                when (a) {
                    "open_app" -> if (!actions.openApp(g.optString("app"))) say = "Mujhe ye app nahi mila."
                    "call" -> {
                        val name = g.optString("name")
                        val n = actions.number(name)
                        if (n == null) say = "Ye naam save nahi hai. Yaadein mein number daal do."
                        else if (!confirm("$name ko call lagaun? Haan ya na bolo.")) say = "Theek hai, call cancel."
                        else { speak(say); say = ""; actions.call(n) }
                    }
                    "whatsapp" -> {
                        val to = g.optString("to")
                        val n = actions.number(to)
                        if (n == null) say = "Ye number save nahi hai."
                        else if (!confirm("$to ko ye message bhejun: ${g.optString("message")}. Haan ya na bolo.")) say = "Theek hai, message nahi bheja."
                        else if (!actions.whatsapp(n, g.optString("message"))) say = "WhatsApp install nahi mila."
                    }
                    "save_contact" -> Store.saveContact(ctx, g.optString("name"), g.optString("number"))
                    "research" -> {
                        glow.state("SEARCH KAR RAHI HOON…")
                        say = withContext(Dispatchers.IO) { Gemini.ask(ctx, g.optString("query", text), search = true) }
                        hideAfter = 400
                    }
                    "chrome_search" -> actions.url("https://www.google.com/search?q=" + Uri.encode(g.optString("query")))
                    "open_url" -> actions.url(g.optString("url"))
                    "note_add" -> Store.addNote(ctx, g.optString("text"))
                    "note_read" -> say = Store.notes(ctx).takeLast(5).joinToString(". ").ifEmpty { "Abhi koi note nahi hai." }
                    "system" -> if (!actions.global(g.optString("cmd"))) say = noA11y
                    "screenshot" -> if (!screenshotAndShare()) say = noA11y
                    "photo" -> if (!actions.photo()) say = "Camera app nahi khul paya."
                    "vision" -> {
                        glow.state("DEKH RAHI HOON…")
                        val jpeg = withContext(Dispatchers.IO) { CameraVision.capture(ctx, g.optString("facing", "back")) }
                        say = if (jpeg == null) "Camera se photo nahi le payi. Permission ya doosra app camera use kar raha ho sakta hai."
                        else withContext(Dispatchers.IO) { Gemini.describeImage(ctx, jpeg, g.optString("question", text)) }
                    }
                    "vision_live" -> { say = visionLive(g.optString("facing", "back")); hideAfter = 600 }
                    "answer_call" -> if (!actions.answerCall()) say = "Call uthane ki permission nahi hai."
                    "reject_call" -> if (!actions.hangUp()) say = "Call kaatne ki permission nahi hai."
                    "contacts" -> say = withContext(Dispatchers.IO) { actions.allContacts() }
                        .take(10).joinToString(", ") { it.first }.ifEmpty { "Phone mein koi contact nahi mila." }
                    "emergency_sms" -> {
                        val msg = g.optString("message").ifBlank { "Mujhe madad chahiye. Please jaldi call karo." }
                        val list = Store.emergencyContacts(ctx)
                        if (list.isEmpty()) say = "Pehle Yaadein mein contact par Emergency ON karo."
                        else if (!confirm("${list.size} emergency contact ko ye SMS bhejun: $msg. Haan ya na bolo.")) say = "Theek hai, SMS nahi bheja."
                        else {
                            val ok = list.count { actions.sms(it.second, msg) }
                            say = "$ok logon ko SMS bhej diya."
                        }
                    }
                    "chatgpt" -> {
                        val prompt = g.optString("prompt").ifBlank { text }
                        if (!actions.openChatGpt()) say = "ChatGPT app is phone par install nahi hai."
                        else {
                            delay(3000)
                            val typed = actions.type(prompt)
                            delay(600)
                            val sent = actions.clickDesc("Send") || actions.click("Send")
                            say = when {
                                !typed -> "ChatGPT khul gayi, par text box mein likh nahi paayi. Wahan khud likho."
                                !sent -> "Prompt likh diya, par send button nahi mila. Wahan send dabao."
                                else -> "ChatGPT mein bhej diya. Jawab wahin dekho."
                            }
                        }
                    }
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
            overlay.hide()
            followUp()
        }
    }

    /** Captures a frame every few seconds for about a minute and narrates what changes. Stops early if told to. */
    private suspend fun visionLive(facing: String): String = coroutineScope {
        var last = ""
        var rounds = 0
        while (rounds < 12) {
            glow.state("LIVE DEKH RAHI HOON…")
            val jpeg = withContext(Dispatchers.IO) { CameraVision.capture(this@MaxService, facing) }
            if (jpeg == null) return@coroutineScope "Camera se dekh nahi payi. Permission check karo."
            val desc = withContext(Dispatchers.IO) {
                runCatching { Gemini.describeImage(this@MaxService, jpeg, "Sirf 1 chhota sentence mein batao kya dikh raha hai, pehle jaisa hi ho to khaali jawab do.") }
                    .getOrDefault("")
            }
            if (desc.isNotBlank() && desc != last) { speak(desc); last = desc }
            rounds++
            delay(5000)
        }
        "Vision live band kar di."
    }

    /** Streams the HTML from Gemini onto the code panel, saves it, opens it in Chrome. */
    private suspend fun buildSite(g: JSONObject, intro: String): String = coroutineScope {
        overlay.show { cancelAll() }
        overlay.codeOn()
        overlay.state(2, "CODE LIKH RAHI HOON…")
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
