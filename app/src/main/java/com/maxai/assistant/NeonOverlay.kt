package com.maxai.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.LinearGradient
import android.graphics.Path
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import kotlin.math.cos
import kotlin.math.sin

/** The cyberpunk orb. state: 0 idle, 1 listening, 2 thinking, 3 speaking. level: 0..1 voice loudness. */
class NeonView(c: Context) : View(c) {
    @Volatile var state = 0
    @Volatile var level = 0f
    private var sm = 0f
    private var ang = 0f
    private var last = SystemClock.uptimeMillis()
    private val d = c.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val cyan = 0xFF00F0FF.toInt()
    private val pink = 0xFFFF2BD6.toInt()
    private val vio = 0xFF8A5CFF.toInt()
    private val logo: Bitmap? by lazy { runCatching { BitmapFactory.decodeResource(resources, R.drawable.max_orb) }.getOrNull() }

    private fun ring(cv: Canvas, cx: Float, cy: Float, r: Float, st: Float, sw: Float, col: Int, w: Float) {
        rect.set(cx - r, cy - r, cx + r, cy + r)
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.shader = null
        p.color = col; p.strokeWidth = w * d * 4; p.alpha = 40
        cv.drawArc(rect, st, sw, false, p)
        p.color = col; p.strokeWidth = w * d; p.alpha = 255
        cv.drawArc(rect, st, sw, false, p)
    }

    override fun onDraw(cv: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = (now - last) / 1000f; last = now
        val t = now / 1000f
        sm += (level - sm) * 0.25f
        ang += dt * 60f * when (state) { 1 -> 1.8f; 2 -> 4f; 3 -> 1.4f; else -> 0.7f }
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2; val cy = h * 0.26f; val r = minOf(w, h) * 0.17f

        // dark neon backdrop + moving scan band
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, 0f, 0f, h, 0xE6070022.toInt(), 0xB3050018.toInt(), Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, w, h, p)
        val sy = (t * 260f) % h
        p.shader = LinearGradient(0f, sy - 80, 0f, sy + 80, intArrayOf(0x0000F0FF, 0x2200F0FF, 0x0000F0FF), null, Shader.TileMode.CLAMP)
        cv.drawRect(0f, sy - 80, w, sy + 80, p)
        p.shader = null

        // title
        p.style = Paint.Style.FILL; p.color = cyan; p.textAlign = Paint.Align.CENTER
        p.textSize = 18 * d; p.typeface = Typeface.MONOSPACE; p.letterSpacing = 0.5f
        p.setShadowLayer(18f, 0f, 0f, cyan)
        cv.drawText("T A R A", cx, h * 0.07f, p)
        p.clearShadowLayer(); p.letterSpacing = 0f

        // glowing core
        val pulse = 1f + sm * 0.35f + 0.04f * sin(t * 2f)
        val cr = r * 0.8f * pulse
        p.shader = RadialGradient(cx, cy, cr, intArrayOf(Color.WHITE, pink, 0x00FF2BD6), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        p.style = Paint.Style.FILL
        cv.drawCircle(cx, cy, cr, p)
        p.shader = null
        logo?.let { lg ->
            val rr = cr * 0.92f
            val clip = Path().apply { addCircle(cx, cy, rr, Path.Direction.CW) }
            cv.save()
            cv.clipPath(clip)
            cv.drawBitmap(lg, null, RectF(cx - rr, cy - rr, cx + rr, cy + rr), null)
            cv.restore()
        }

        // rotating neon rings
        ring(cv, cx, cy, r, ang, 100f, cyan, 3f); ring(cv, cx, cy, r, ang + 180, 100f, cyan, 3f)
        ring(cv, cx, cy, r * 1.22f, -ang * 1.4f, 70f, pink, 2.5f); ring(cv, cx, cy, r * 1.22f, -ang * 1.4f + 180, 70f, pink, 2.5f)
        ring(cv, cx, cy, r * 1.42f, ang * 0.7f, 220f, vio, 2f)

        // voice bars
        val n = 56; val base = r * 1.62f
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = r * 0.045f
        for (i in 0 until n) {
            val a = Math.toRadians(i * 360.0 / n + ang * 0.3).toFloat()
            val wob = 0.5f + 0.5f * sin(i * 0.7f + t * 9f)
            val len = r * 0.06f + r * 0.45f * sm * wob + if (state == 2) r * 0.05f * wob else 0f
            p.color = ColorUtils.blendARGB(cyan, pink, i / n.toFloat())
            cv.drawLine(cx + cos(a) * base, cy + sin(a) * base, cx + cos(a) * (base + len), cy + sin(a) * (base + len), p)
        }
        postInvalidateOnAnimation()
    }
}

/** Full-screen translucent overlay that floats above every app. All methods are safe to call from any thread. */
class NeonOverlay(private val c: Context) {
    private val wm = c.getSystemService(WindowManager::class.java)
    private val ui = Handler(Looper.getMainLooper())
    private var root: FrameLayout? = null
    private var neon: NeonView? = null
    private var status: TextView? = null
    private var heardTv: TextView? = null
    private var codeBox: ScrollView? = null
    private var codeTv: TextView? = null

    fun show(onTap: () -> Unit) {
        ui.post {
            if (root != null) return@post
            val dp = c.resources.displayMetrics.density
            val screenH = c.resources.displayMetrics.heightPixels
            val fl = FrameLayout(c)
            val nv = NeonView(c)
            fl.addView(nv, FrameLayout.LayoutParams(-1, -1))

            val col = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((20 * dp).toInt(), (40 * dp).toInt(), (20 * dp).toInt(), (32 * dp).toInt())
            }
            col.addView(View(c), LinearLayout.LayoutParams(1, (screenH * 0.45f).toInt()))
            val st = TextView(c).apply {
                setTextColor(0xFF00F0FF.toInt()); textSize = 14f; typeface = Typeface.MONOSPACE
                letterSpacing = 0.2f; gravity = Gravity.CENTER; setShadowLayer(16f, 0f, 0f, 0xFF00F0FF.toInt())
            }
            col.addView(st, LinearLayout.LayoutParams(-1, -2))
            val hd = TextView(c).apply {
                setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER; maxLines = 4
                setPadding(0, (10 * dp).toInt(), 0, (10 * dp).toInt()); setShadowLayer(12f, 0f, 0f, 0xFFFF2BD6.toInt())
            }
            col.addView(hd, LinearLayout.LayoutParams(-1, -2))
            val ctv = TextView(c).apply { setTextColor(0xFF7DFFB3.toInt()); textSize = 10.5f; typeface = Typeface.MONOSPACE }
            val cb = ScrollView(c).apply {
                visibility = View.GONE
                setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
                background = GradientDrawable().apply { setColor(0xB3000000.toInt()); setStroke((1 * dp).toInt(), 0x8800F0FF.toInt()); cornerRadius = 20 * dp }
                addView(ctv)
            }
            col.addView(cb, LinearLayout.LayoutParams(-1, 0, 1f))
            fl.addView(col, FrameLayout.LayoutParams(-1, -1))
            fl.setOnClickListener { onTap() }
            fl.alpha = 0f

            val lp = WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            )
            if (runCatching { wm.addView(fl, lp) }.isSuccess) {
                root = fl; neon = nv; status = st; heardTv = hd; codeBox = cb; codeTv = ctv
                fl.animate().alpha(1f).setDuration(250).start()
            }
        }
    }

    fun hide() {
        ui.post {
            root?.let { runCatching { wm.removeView(it) } }
            root = null; neon = null; status = null; heardTv = null; codeBox = null; codeTv = null
        }
    }

    fun state(s: Int, label: String) { ui.post { neon?.state = s; status?.text = label } }
    fun level(f: Float) { ui.post { neon?.level = f } }
    fun heard(t: String) { ui.post { heardTv?.text = t } }

    fun codeOn() { ui.post { codeBox?.visibility = View.VISIBLE; codeTv?.text = "" } }
    fun code(s: String) {
        ui.post {
            val tv = codeTv ?: return@post
            tv.append(s)
            if (tv.length() > 5000) tv.text = tv.text.takeLast(3500)
            codeBox?.post { codeBox?.fullScroll(View.FOCUS_DOWN) }
        }
    }
}
