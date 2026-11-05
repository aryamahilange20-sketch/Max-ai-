package com.maxai.assistant

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/** Siri-style glowing rim around the whole screen, with a small status/transcript label. Click-through: it never blocks the app underneath. */
class SiriGlow(private val c: Context) {
    private val wm = c.getSystemService(WindowManager::class.java)
    private val ui = Handler(Looper.getMainLooper())
    private var root: FrameLayout? = null
    private var glowView: GlowView? = null
    private var statusTv: TextView? = null
    private var heardTv: TextView? = null

    fun show() {
        ui.post {
            if (root != null || !Settings.canDrawOverlays(c)) return@post
            val dp = c.resources.displayMetrics.density
            val fl = FrameLayout(c)
            val gv = GlowView(c)
            fl.addView(gv, FrameLayout.LayoutParams(-1, -1))

            val st = TextView(c).apply {
                setTextColor(0xFF00F0FF.toInt())
                textSize = 13f
                typeface = Typeface.MONOSPACE
                letterSpacing = 0.18f
                gravity = Gravity.CENTER
                setShadowLayer(14f, 0f, 0f, 0xFF00F0FF.toInt())
            }
            val hd = TextView(c).apply {
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 16f
                gravity = Gravity.CENTER
                maxLines = 3
                setPadding((28 * dp).toInt(), (6 * dp).toInt(), (28 * dp).toInt(), 0)
                setShadowLayer(12f, 0f, 0f, 0xFFFF2BD6.toInt())
            }
            val lpSt = FrameLayout.LayoutParams(-1, -2).apply {
                gravity = Gravity.TOP
                topMargin = (56 * dp).toInt()
            }
            val lpHd = FrameLayout.LayoutParams(-1, -2).apply {
                gravity = Gravity.TOP
                topMargin = (84 * dp).toInt()
            }
            fl.addView(st, lpSt)
            fl.addView(hd, lpHd)

            val lp = WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            if (runCatching { wm.addView(fl, lp) }.isSuccess) {
                root = fl; glowView = gv; statusTv = st; heardTv = hd
                fl.alpha = 0f
                fl.animate().alpha(1f).setDuration(350).start()
            }
        }
    }

    fun setLevel(f: Float) {
        ui.post {
            glowView?.level = f
            glowView?.postInvalidate()
        }
    }

    fun state(label: String) {
        ui.post { statusTv?.text = label }
    }

    fun heard(text: String) {
        ui.post { heardTv?.text = text }
    }

    fun hide() {
        ui.post {
            val v = root ?: return@post
            root = null; glowView = null; statusTv = null; heardTv = null
            v.animate().alpha(0f).setDuration(450).withEndAction { runCatching { wm.removeView(v) } }.start()
        }
    }
}

class GlowView(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    var level = 0f
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
        val inset = 1f * dp
        rect.set(inset, inset, w - inset, h - inset)
        val r = 56f * dp
        for ((sw, al, blur) in listOf(Triple(90f * dp, 130, true), Triple(30f * dp, 230, false), Triple(7f * dp, 255, false))) {
            p.shader = sg
            p.strokeWidth = sw
            p.alpha = (al * (0.55f + level)).toInt().coerceIn(0, 255)
            p.maskFilter = if (blur) BlurMaskFilter(26f * dp, BlurMaskFilter.Blur.NORMAL) else null
            cv.drawRoundRect(rect, r, r, p)
        }
        postInvalidateOnAnimation()
    }
}
