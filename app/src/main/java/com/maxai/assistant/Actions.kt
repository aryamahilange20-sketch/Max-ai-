package com.maxai.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.MediaStore
import android.telecom.TelecomManager

/** Everything MAX can do on the phone. Accessibility-based ones return false if the service is off. */
class Actions(private val c: Context) {
    private val a get() = MaxAccessibilityService.instance

    private fun has(p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private fun start(i: Intent) = c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    fun openApp(name: String): Boolean {
        val pm = c.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it to it.loadLabel(pm).toString().lowercase() }
        val n = name.lowercase().trim()
        if (n.isEmpty()) return false
        val hit = apps.firstOrNull { it.second == n }
            ?: apps.firstOrNull { it.second.contains(n) }
            ?: apps.firstOrNull { it.second.length >= 3 && n.contains(it.second) }
            ?: return false
        val i = pm.getLaunchIntentForPackage(hit.first.activityInfo.packageName) ?: return false
        start(i)
        return true
    }

    /** Saved MAX numbers first, then the phone's contacts. */
    fun number(name: String): String? {
        val n = name.trim().lowercase()
        if (n.count { it.isDigit() } >= 7) return n.filter { it.isDigit() || it == '+' }
        val saved = Store.contacts(c)
        saved.entries.firstOrNull { it.key.lowercase() == n }?.let { return it.value }
        saved.entries.firstOrNull { it.key.lowercase().contains(n) || n.contains(it.key.lowercase()) }?.let { return it.value }
        if (has(Manifest.permission.READ_CONTACTS)) {
            c.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$name%"), null
            )?.use { if (it.moveToFirst()) return it.getString(1) }
        }
        return null
    }

    /** Name of a number from the phone's contacts, or null. */
    fun nameFor(number: String): String? {
        if (number.isBlank() || !has(Manifest.permission.READ_CONTACTS)) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        c.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) return it.getString(0) }
        return null
    }

    /** Every name and number in the phone's contacts. */
    fun allContacts(): List<Pair<String, String>> {
        if (!has(Manifest.permission.READ_CONTACTS)) return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        c.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        )?.use {
            while (it.moveToNext()) out.add((it.getString(0) ?: "") to (it.getString(1) ?: ""))
        }
        return out.distinct()
    }

    fun call(number: String) {
        start(Intent(if (has(Manifest.permission.CALL_PHONE)) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))))
    }

    private fun telecom(block: TelecomManager.() -> Unit): Boolean {
        if (!has(Manifest.permission.ANSWER_PHONE_CALLS)) return false
        return runCatching { c.getSystemService(TelecomManager::class.java).block() }.isSuccess
    }

    fun answerCall(): Boolean = telecom { acceptRingingCall() }

    fun hangUp(): Boolean = telecom { endCall() }

    /** Opens the camera app. Android does not allow silent photos from another app. */
    fun photo(): Boolean = runCatching { start(Intent(MediaStore.ACTION_IMAGE_CAPTURE)) }.isSuccess

    /** Opens the chat with the message filled in, then the accessibility service taps Send. */
    fun whatsapp(number: String, msg: String): Boolean {
        val d = number.filter { it.isDigit() }
        val full = if (d.length == 10) "91$d" else d
        val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull {
            runCatching { c.packageManager.getPackageInfo(it, 0) }.isSuccess
        } ?: return false
        start(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$full?text=" + Uri.encode(msg))).setPackage(pkg))
        a?.clickWhatsAppSend()
        return true
    }

    fun url(u: String) {
        val link = if (u.startsWith("http")) u else "https://$u"
        try { start(Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage("com.android.chrome")) }
        catch (e: Exception) { start(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
    }

    fun torch(on: Boolean) {
        val cm = c.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } ?: return
        cm.setTorchMode(id, on)
    }

    fun volume(dir: String) {
        val am = c.getSystemService(AudioManager::class.java)
        val d = when (dir) { "up" -> AudioManager.ADJUST_RAISE; "down" -> AudioManager.ADJUST_LOWER; else -> AudioManager.ADJUST_MUTE }
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, d, AudioManager.FLAG_SHOW_UI)
    }

    fun global(cmd: String) = a?.global(cmd) ?: false
    fun click(t: String) = a?.clickText(t) ?: false
    fun type(t: String) = a?.typeText(t) ?: false
    fun scroll(dir: String) = a?.scroll(dir == "down") ?: false
    fun screenText() = a?.screenText() ?: ""
}
