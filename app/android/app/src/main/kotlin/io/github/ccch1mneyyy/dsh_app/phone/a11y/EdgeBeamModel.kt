// Portions derived from Libraries.dev Border beam and Distortion waves (https://github.com/Jakubantalik/Libraries.dev), MIT, Copyright (c) 2026 Jakub Antalik. See THIRD_PARTY_NOTICES.md.

package io.github.ccch1mneyyy.dsh_app.phone.a11y

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

// The screen-edge rim's pure half (EdgeBeamModelTest): the colour Distortion waves leaves on the
// screen's edges (its shader's rim and WaveRim.lumps), on its own and kept still in place. EdgeBeam.kt
// draws what these decide.

/** The rim's numbers. */
internal object EdgeBeamSpec {
    /** How far in the colour reaches where the rim is of average thickness: Distortion waves' Edges default (rimWidth 14). */
    const val WIDTH_DP = 14f

    /** The still line the rim settles into while paused: the same falloff, this wide. */
    const val LINE_DP = 3f

    /** Peak opacity on the screen's edge, and the still line's. */
    class Preset(val rim: Float, val line: Float)

    val DARK = Preset(rim = 0.42f, line = 0.26f)
    val LIGHT = Preset(rim = 0.26f, line = 0.16f)

    /** Fade in 0.6 s, out 0.5 s, CSS `ease`. */
    const val FADE_IN_MS = 600L
    const val FADE_OUT_MS = 500L

    /** Paused: the lumps come to rest and the rim narrows to the still line over this long. */
    const val STILL_MS = 500L

    /** The reference's drift (±22 pt/s) and breath, slowed to this share: the colour sits there, it doesn't flow. */
    const val CALM = 0.5

    /** Corner radius when the display does not report one (before Android 12). */
    const val CORNER_FALLBACK_DP = 28f

    /** One frame at most every this many ms: 30 fps is plenty for colour drifting a few dp a second. */
    const val FRAME_MS = 33L

    /**
     * How far in anything is drawn, as a share of [WIDTH_DP]: past it the thickest lump (×[EdgeRim.MAX_THICKNESS])
     * is under half a step of 8-bit alpha. The shader fades to nothing before it, so two lumps overlapping
     * never end in a hard edge.
     */
    val REACH: Float get() = EdgeRim.MAX_THICKNESS * 2.4f

    fun preset(dark: Boolean) = if (dark) DARK else LIGHT

    /** The display's corner radius in px, or the fallback. */
    fun cornerPx(reported: Int?, density: Float): Float =
        reported?.takeIf { it > 0 }?.toFloat() ?: (CORNER_FALLBACK_DP * density)
}

/**
 * The lumps that make the rim uneven (Distortion waves' WaveRim, `pulse` 0): seven of them round the
 * screen, each with its own length and depth, drifting along the edge and breathing. Worked out once
 * a frame, not per pixel; the shader (EdgeBeam.kt) sums them as [thickness] does.
 */
internal object EdgeRim {
    const val COUNT = 7

    /** Floats per lump in [lumps]' output: centre (px along the perimeter, clockwise from the top centre), length (px), depth, unused. */
    const val STRIDE = 4

    /** Fixed per lump, so the pattern is the same on every run (the reference's hashes). */
    private val A = DoubleArray(COUNT) { hash(it.toDouble(), 12.9898, 1.3) }
    private val B = DoubleArray(COUNT) { hash(it.toDouble(), 78.233, 4.1) }
    private val C = DoubleArray(COUNT) { hash(it.toDouble(), 39.425, 2.7) }

    /** The rim's thickness never gets past this share of [EdgeBeamSpec.WIDTH_DP] (one lump at its deepest breath). */
    val MAX_THICKNESS: Float = (B.maxOf { (0.2 + 2.6 * it) * 1.25 } + BASE).div(NORM).toFloat()

    /** The length round a [w] × [h] rect with corners of radius [r] (any unit). */
    fun perimeter(w: Float, h: Float, r: Float): Float {
        val ex = max(w / 2 - r, 0f)
        val ey = max(h / 2 - r, 0f)
        return (4 * (ex + ey + 0.5 * PI * r)).toFloat()
    }

    /**
     * The lumps at [time] s of travel round a [perimeter] px edge, [density] px per dp, into [out]
     * ([COUNT] × [STRIDE] floats). Drift and breath are the reference's at [EdgeBeamSpec.CALM].
     */
    fun lumps(time: Double, perimeter: Float, density: Float, out: FloatArray) {
        val t = time * EdgeBeamSpec.CALM
        val p = perimeter.toDouble()
        for (i in 0 until COUNT) {
            val fi = i.toDouble()
            val centre = p * (fi + 0.35 * A[i]) / COUNT + t * (-22 + 44 * B[i]) * density
            val breath = 0.25 * sin(t * (1.1 + 1.2 * A[i]) + fi * 2.4)
            out[i * STRIDE] = (centre - p * floor(centre / p)).toFloat()
            out[i * STRIDE + 1] = (p / COUNT * (0.2 + 0.25 * C[i])).toFloat()
            out[i * STRIDE + 2] = ((0.2 + 2.6 * B[i]) * (1 + breath)).toFloat()
            out[i * STRIDE + 3] = 0f
        }
    }

    /** Drift speed of lump [i], dp per second of travel. */
    fun driftDpPerS(i: Int): Double = (-22 + 44 * B[i]) * EdgeBeamSpec.CALM

    /**
     * The rim's thickness at [s] along a [perimeter] edge, as a share of [EdgeBeamSpec.WIDTH_DP]: the
     * lumps over a thin base, averaging about 1 (the shader's rimThickness, unevenness 1).
     */
    fun thickness(s: Float, perimeter: Float, lumps: FloatArray): Float {
        var t = BASE
        for (i in 0 until COUNT) {
            var ds = (s - lumps[i * STRIDE]).toDouble()
            ds -= perimeter * floor(ds / perimeter + 0.5)
            val k = ds / lumps[i * STRIDE + 1]
            t += lumps[i * STRIDE + 2] * exp(-k * k)
        }
        return max((t / NORM).toFloat(), 0.04f)
    }

    /** The rim's opacity (0–1 of the preset's) [inset] px in from the edge where it is [thickness] thick, [widthPx] the average. */
    fun hug(inset: Float, thickness: Float, widthPx: Float): Float {
        val k = inset / (widthPx * thickness)
        return exp(-k * k)
    }

    private const val BASE = 0.08
    private const val NORM = 1.0498

    private fun hash(i: Double, a: Double, b: Double): Double {
        val x = sin(i * a + b) * 43758.5453
        return x - floor(x)
    }
}

/** The theme palette: nine soft tones round the app's accent hue (BeamPalette.fromSeed in the Dart port). */
internal object EdgeBeamPalette {
    /** ARGB of the nine rim colours for the accent [seed] (ARGB). */
    fun fromSeed(seed: Int): IntArray {
        val (hue, s, _) = hsl(seed)
        val sat = (s * 0.85f).coerceIn(0.4f, 0.6f)
        fun tone(shift: Float, s: Float, l: Float) = fromHsl(((hue + shift) % 360f + 360f) % 360f, s.coerceIn(0f, 1f), l)
        return intArrayOf(
            tone(0f, sat, 0.62f),
            tone(-24f, sat * 0.9f, 0.66f),
            tone(18f, sat, 0.6f),
            tone(-10f, sat * 1.1f, 0.58f),
            tone(32f, sat * 0.85f, 0.66f),
            tone(-36f, sat, 0.62f),
            tone(8f, sat * 0.9f, 0.7f),
            tone(-16f, sat, 0.6f),
            tone(26f, sat, 0.64f),
        )
    }

    /** Hue (0–360), saturation and lightness of an ARGB colour (Flutter's HSLColor.fromColor). */
    fun hsl(argb: Int): Triple<Float, Float, Float> {
        val r = (argb shr 16 and 0xFF) / 255f
        val g = (argb shr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        val hue = when {
            max == 0f || delta == 0f -> 0f
            max == r -> 60f * ((((g - b) / delta) % 6f + 6f) % 6f)
            max == g -> 60f * ((b - r) / delta + 2f)
            else -> 60f * ((r - g) / delta + 4f)
        }
        val l = (max + min) / 2f
        val s = if (l == 1f) 0f else (delta / (1f - abs(2f * l - 1f))).coerceIn(0f, 1f)
        return Triple(hue, s, l)
    }

    /** Opaque ARGB from hue, saturation, lightness (Flutter's HSLColor.toColor). */
    fun fromHsl(hue: Float, s: Float, l: Float): Int {
        val chroma = (1f - abs(2f * l - 1f)) * s
        val secondary = chroma * (1f - abs((hue / 60f) % 2f - 1f))
        val match = l - chroma / 2f
        val (r, g, b) = when {
            hue < 60f -> Triple(chroma, secondary, 0f)
            hue < 120f -> Triple(secondary, chroma, 0f)
            hue < 180f -> Triple(0f, chroma, secondary)
            hue < 240f -> Triple(0f, secondary, chroma)
            hue < 300f -> Triple(secondary, 0f, chroma)
            else -> Triple(chroma, 0f, secondary)
        }
        fun ch(v: Float) = ((v + match) * 255f).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }
}

/** CSS timing shared by the rim's fades. */
internal object BeamMath {
    /** y at progress [x] on a CSS cubic-bezier curve. */
    fun cubicBezier(x1: Double, y1: Double, x2: Double, y2: Double, x: Double): Double {
        if (x <= 0) return 0.0
        if (x >= 1) return 1.0
        var lo = 0.0
        var hi = 1.0
        var t = x
        repeat(30) {
            val mt = 1 - t
            val bx = 3 * mt * mt * t * x1 + 3 * mt * t * t * x2 + t * t * t
            if (bx < x) lo = t else hi = t
            t = (lo + hi) / 2
        }
        val mt = 1 - t
        return 3 * mt * mt * t * y1 + 3 * mt * t * t * y2 + t * t * t
    }

    fun ease(x: Double) = cubicBezier(0.25, 0.1, 0.25, 1.0, x)
}

/** A value easing from [from] to [to] over [ms] from [start] (uptime ms), CSS `ease`. */
internal class BeamRamp(val from: Float, val to: Float, val start: Long, val ms: Long) {
    fun at(now: Long): Float {
        if (ms <= 0) return to
        val p = ((now - start).toDouble() / ms).coerceIn(0.0, 1.0)
        return (from + (to - from) * BeamMath.ease(p)).toFloat()
    }

    fun done(now: Long) = now - start >= ms

    companion object {
        fun still(v: Float) = BeamRamp(v, v, 0L, 0L)
    }
}

/**
 * What the rim shows from moment to moment: [fadeNow] (0 off … 1 on, 0.6 s in, 0.5 s out),
 * [stillNow] (0 the rim … 1 the still line, while paused) and the travelled [phase] in seconds,
 * which slows to a stop as it goes still. Without [motion] (移除动画) nothing travels and every
 * change is at once: the rim stands as it is, the still line replaces it while paused. Main
 * thread, times in uptime ms.
 */
internal class EdgeBeamClock(private val motion: Boolean) {
    private var fade = BeamRamp.still(0f)
    private var still = BeamRamp.still(0f)
    private var last = -1L

    var phase = 0.0
        private set

    /** Wanted on (a task's control shows) or off. */
    fun show(on: Boolean, now: Long) {
        val target = if (on) 1f else 0f
        if (fade.to == target) return
        fade = BeamRamp(fade.at(now), target, now, if (!motion) 0 else if (on) EdgeBeamSpec.FADE_IN_MS else EdgeBeamSpec.FADE_OUT_MS)
    }

    /** Paused: the rim settles into the still line; resumed: it comes back and drifts again. */
    fun pause(paused: Boolean, now: Long) {
        val target = if (paused) 1f else 0f
        if (still.to == target) return
        still = BeamRamp(still.at(now), target, now, if (motion) EdgeBeamSpec.STILL_MS else 0)
    }

    /** The fade and the stillness at the last [frame]. */
    var fadeNow = 0f
        private set
    var stillNow = 0f
        private set

    /** Advances to [now]: [fadeNow], [stillNow], and [phase] on by the time the rim was drifting. */
    fun frame(now: Long) {
        stillNow = still.at(now)
        fadeNow = fade.at(now)
        if (motion && last >= 0) phase += (now - last).coerceIn(0, 100) / 1000.0 * (1f - stillNow)
        last = now
    }

    /** A frame break (hidden, no frames asked for): the next frame doesn't count the gap. */
    fun rest() { last = -1L }

    /**
     * Frames are needed: something ramps, or the rim is on and drifting. [drifts] is false for a
     * renderer whose picture stands still (the band below Android 13): it only needs the ramps.
     */
    fun animating(now: Long, drifts: Boolean = true): Boolean =
        !fade.done(now) || !still.done(now) || (drifts && motion && fade.to > 0f && still.to < 1f)

    /** Faded out completely. */
    fun gone(now: Long): Boolean = fade.to == 0f && fade.done(now)
}
