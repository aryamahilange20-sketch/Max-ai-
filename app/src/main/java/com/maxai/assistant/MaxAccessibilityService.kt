package com.maxai.assistant

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Lets MAX press Back/Home, tap things on screen, type, scroll, read the screen and tap WhatsApp's Send button. */
class MaxAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: MaxAccessibilityService? = null
    }

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onUnbind(i: Intent?): Boolean { instance = null; return super.onUnbind(i) }

    fun global(cmd: String): Boolean {
        val action = when (cmd) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "screenshot" -> GLOBAL_ACTION_TAKE_SCREENSHOT
            "lock" -> GLOBAL_ACTION_LOCK_SCREEN
            else -> return false
        }
        return performGlobalAction(action)
    }

    fun clickText(t: String): Boolean {
        val n = rootInActiveWindow?.findAccessibilityNodeInfosByText(t)?.firstOrNull() ?: return false
        var x: AccessibilityNodeInfo? = n
        while (x != null && !x.isClickable) x = x.parent
        return (x ?: n).performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    fun typeText(t: String): Boolean {
        val f = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t) }
        return f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun firstScrollable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isScrollable) return n
        for (i in 0 until n.childCount) firstScrollable(n.getChild(i))?.let { return it }
        return null
    }

    fun scroll(forward: Boolean): Boolean {
        val s = firstScrollable(rootInActiveWindow) ?: return false
        return s.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }

    fun screenText(): String {
        val sb = StringBuilder()
        fun go(n: AccessibilityNodeInfo?) {
            if (n == null || sb.length > 3000) return
            n.text?.let { sb.append(it).append(". ") }
            n.contentDescription?.let { sb.append(it).append(". ") }
            for (i in 0 until n.childCount) go(n.getChild(i))
        }
        go(rootInActiveWindow)
        return sb.toString()
    }

    /** WhatsApp takes a moment to open the chat, so retry for ~8 seconds. */
    fun clickWhatsAppSend(attempt: Int = 0) {
        val r = rootInActiveWindow
        val n = r?.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send")?.firstOrNull()
            ?: r?.findAccessibilityNodeInfosByViewId("com.whatsapp.w4b:id/send")?.firstOrNull()
            ?: r?.findAccessibilityNodeInfosByText("Send")?.firstOrNull()
        if (n != null) { n.performAction(AccessibilityNodeInfo.ACTION_CLICK); return }
        if (attempt < 12) Handler(Looper.getMainLooper()).postDelayed({ clickWhatsAppSend(attempt + 1) }, 700)
    }
}
