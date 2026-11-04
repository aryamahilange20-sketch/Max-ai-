package com.maxai.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import java.net.HttpURLConnection
import java.net.URL

/** Checks each part of MAX on the phone itself. Call from a background thread. */
object SelfTest {
    fun run(c: Context): List<Triple<String, String, String>> {
        val out = mutableListOf<Triple<String, String, String>>()
        fun add(name: String, status: String, detail: String) { out.add(Triple(name, status, detail)) }
        fun perm(p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        fun okOf(b: Boolean) = if (b) "PASS" else "FAIL"

        val code = runCatching {
            val h = (URL("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1").openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("x-goog-api-key", Store.key(c))
            }
            h.responseCode
        }.getOrDefault(-1)
        add("API key", if (code == 200) "PASS" else "FAIL", "HTTP $code")

        val chat = runCatching { Gemini.ask(c, "Sirf ek shabd mein jawab do: OK") }.getOrNull()
        add("Chat (Gemini)", okOf(!chat.isNullOrBlank()), chat?.take(40) ?: "koi jawab nahi")

        add("Live model", "CHECK", LiveSession.liveModel(c))

        val pcm = runCatching { VoiceId.record(1f) }.getOrDefault(ShortArray(0))
        val r = VoiceId.rms(pcm)
        add("Mic", okOf(pcm.isNotEmpty() && r > 0.0), "level ${r.toInt()} (${pcm.size} samples)")

        add("Accessibility (tap, screenshot)", okOf(MaxAccessibilityService.instance != null), "Settings > Accessibility > MAX on karo")
        add("Overlay (neon glow)", okOf(Settings.canDrawOverlays(c)), "Setup mein allow karo")
        add("Camera permission", okOf(perm(Manifest.permission.CAMERA)), "photo ke liye")
        add("SMS permission", okOf(perm(Manifest.permission.SEND_SMS)), "emergency SMS ke liye")
        add("Contacts permission", okOf(perm(Manifest.permission.READ_CONTACTS)), "call aur Yaadein ke liye")
        add("Boss voice enrolled", if (VoiceId.enrolled(c)) "PASS" else "CHECK", "${VoiceId.count(c)} samples")

        val act = Actions(c)
        val torchOk = runCatching { act.torch(true); Thread.sleep(800); act.torch(false); true }.getOrDefault(false)
        add("Torch", if (torchOk) "CHECK" else "FAIL", "1 second ke liye on hona chahiye, dekho")
        add("Screenshot", if (act.global("screenshot")) "CHECK" else "FAIL", "Gallery mein Screenshot check karo")
        add("Phone contacts", "CHECK", "${act.allContacts().size} contacts mile")
        return out
    }
}
