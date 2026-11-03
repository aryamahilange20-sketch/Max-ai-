package com.maxai.assistant

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.SweepGradient
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager

/** Siri-style glowing rim around the whole screen. Shown only when overlay permission is on. */
class SiriGlow(private val c: Context) {
    private val wm = c.getSystemService(WindowManager::class.java)
    private val ui = Handler(Looper.getMainLooper())
    private var view: GlowView? = null

    fun show() {
        ui.post {
            if (view != null || !Settings.canDrawOverlays(c)) return@post
            val v = GlowView(c)
            val lp = WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            if (runCatching { wm.addView(v, lp) }.isSuccess) {
                view = v
                v.alpha = 0f
                v.animate().alpha(1f).setDuration(350).start()
            }
        }
    }

    fun hide() {
        ui.post {
            val v = view ?: return@post
            view = null
            v.animate().alpha(0f).setDuration(450).withEndAction { runCatching { wm.removeView(v) } }.start()
        }
    }
}

class GlowView(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val t0 = SystemClock.uptimeMillis()
    private val dp = c.resources.displayMetrics.density
    private val colors = intArrayOf(0xFF00F0FF.toInt(), 0xFF8A5CFF.toInt(), 0xFFFF2BD6.toInt(), 0xFF00F0FF.toInt())

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return
        val t = (SystemClock.uptimeMillis() - t0) / 1000f
        val sg = SweepGradient(w / 2, h / 2, colors, null)
        sg.setLocalMatrix(Matrix().apply { setRotate((t * 120f) % 360f, w / 2, h / 2) })
        val inset = 6f * dp
        rect.set(inset, inset, w - inset, h - inset)
        val r = 56f * dp
        for ((sw, al, blur) in listOf(Triple(44f * dp, 110, true), Triple(14f * dp, 210, false), Triple(3.5f * dp, 255, false))) {
            p.shader = sg
            p.strokeWidth = sw
            p.alpha = al
            p.maskFilter = if (blur) BlurMaskFilter(26f * dp, BlurMaskFilter.Blur.NORMAL) else null
            cv.drawRoundRect(rect, r, r, p)
        }
        postInvalidateOnAnimation()
    }
}
