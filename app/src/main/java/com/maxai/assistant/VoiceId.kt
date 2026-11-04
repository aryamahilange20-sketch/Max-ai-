package com.maxai.assistant

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import org.json.JSONArray
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Simple on-device voice check: log-mel features of the boss's 5 enrolled samples,
 * compared with cosine similarity. This is NOT strong biometric security;
 * the boss password stays as the backup.
 */
object VoiceId {
    private const val SR = 16000
    private const val FRAME = 400
    private const val HOP = 160
    private const val NFFT = 512
    private const val BINS = 257
    private const val BANDS = 26
    private const val THRESH = 0.85
    private const val PREFS = "voice"

    private val cosT: Array<DoubleArray> by lazy { Array(BINS) { k -> DoubleArray(NFFT) { n -> cos(2 * PI * k * n / NFFT) } } }
    private val sinT: Array<DoubleArray> by lazy { Array(BINS) { k -> DoubleArray(NFFT) { n -> sin(2 * PI * k * n / NFFT) } } }
    private val hann = DoubleArray(FRAME) { 0.5 - 0.5 * cos(2 * PI * it / (FRAME - 1)) }
    private val filters: Array<DoubleArray> by lazy { melFilters() }

    private fun melFilters(): Array<DoubleArray> {
        fun mel(f: Double) = 2595 * log10(1 + f / 700)
        fun hz(m: Double) = 700 * (10.0.pow(m / 2595) - 1)
        val maxM = mel(SR / 2.0)
        val pts = DoubleArray(BANDS + 2) { hz(maxM * it / (BANDS + 1)) }
        val bins = IntArray(BANDS + 2) { floor(pts[it] * NFFT / SR).toInt() }
        return Array(BANDS) { b ->
            DoubleArray(BINS) { k ->
                val l = bins[b]; val c = bins[b + 1]; val r = bins[b + 2]
                when {
                    c > l && k in l..c -> (k - l).toDouble() / (c - l)
                    r > c && k in c..r -> (r - k).toDouble() / (r - c)
                    else -> 0.0
                }
            }
        }
    }

    /** Records mono 16 kHz audio for the given seconds. Needs RECORD_AUDIO. */
    @Suppress("MissingPermission")
    fun record(seconds: Float): ShortArray {
        val n = (SR * seconds).toInt()
        val min = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096)
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min)
        val out = ShortArray(n)
        var got = 0
        val buf = ShortArray(1024)
        try {
            rec.startRecording()
            while (got < n) {
                val r = rec.read(buf, 0, minOf(buf.size, n - got))
                if (r <= 0) break
                System.arraycopy(buf, 0, out, got, r)
                got += r
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
        return out.copyOf(got)
    }

    fun rms(p: ShortArray): Double {
        if (p.isEmpty()) return 0.0
        var s = 0.0
        for (v in p) s += v.toDouble() * v
        return sqrt(s / p.size)
    }

    /** Log-mel mean and spread over all speech frames. Returns empty if there is no speech. */
    fun features(p: ShortArray): DoubleArray {
        val frames = mutableListOf<DoubleArray>()
        val x = DoubleArray(FRAME)
        var start = 0
        while (start + FRAME <= p.size) {
            var e = 0.0
            for (i in 0 until FRAME) {
                x[i] = p[start + i].toDouble() * hann[i]
                e += x[i] * x[i]
            }
            if (sqrt(e / FRAME) > 150) {
                val pw = DoubleArray(BINS)
                for (k in 0 until BINS) {
                    var re = 0.0
                    var im = 0.0
                    val ct = cosT[k]; val st = sinT[k]
                    for (n in 0 until FRAME) {
                        re += x[n] * ct[n]
                        im -= x[n] * st[n]
                    }
                    pw[k] = re * re + im * im
                }
                val lm = DoubleArray(BANDS) { b ->
                    var s = 0.0
                    val f = filters[b]
                    for (k in 0 until BINS) s += pw[k] * f[k]
                    ln(s + 1e-10)
                }
                frames.add(lm)
            }
            start += HOP
        }
        if (frames.isEmpty()) return DoubleArray(0)
        val mean = DoubleArray(BANDS)
        for (f in frames) for (b in 0 until BANDS) mean[b] += f[b] / frames.size
        val std = DoubleArray(BANDS)
        for (f in frames) for (b in 0 until BANDS) std[b] += (f[b] - mean[b]) * (f[b] - mean[b]) / frames.size
        return DoubleArray(BANDS * 2) { if (it < BANDS) mean[it] else sqrt(std[it - BANDS]) }
    }

    fun save(c: Context, list: List<DoubleArray>) {
        val a = JSONArray()
        list.forEach { v -> a.put(JSONArray(v.toList())) }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("samples", a.toString()).apply()
    }

    fun samples(c: Context): List<DoubleArray> {
        val raw = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("samples", "[]") ?: "[]"
        val a = JSONArray(raw)
        return (0 until a.length()).map { i ->
            val row = a.getJSONArray(i)
            DoubleArray(row.length()) { row.getDouble(it) }
        }
    }

    fun enrolled(c: Context) = samples(c).isNotEmpty()
    fun count(c: Context) = samples(c).size
    fun clear(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("samples").apply()

    private fun cosine(a: DoubleArray, b: DoubleArray): Double {
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return dot / (sqrt(na) * sqrt(nb) + 1e-9)
    }

    /** True if this audio sounds like one of the enrolled samples. */
    fun matches(c: Context, pcm: ShortArray): Boolean {
        val ref = samples(c)
        if (ref.isEmpty()) return true
        val f = features(pcm)
        if (f.isEmpty()) return false
        val d = f.size
        val mu = DoubleArray(d) { i -> ref.sumOf { it[i] } / ref.size }
        val fc = DoubleArray(d) { f[it] - mu[it] }
        val best = ref.maxOf { r -> cosine(fc, DoubleArray(d) { r[it] - mu[it] }) }
        return best >= THRESH
    }
}
