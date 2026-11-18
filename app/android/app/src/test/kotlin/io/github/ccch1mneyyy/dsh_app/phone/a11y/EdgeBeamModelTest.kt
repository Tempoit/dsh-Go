package io.github.ccch1mneyyy.dsh_app.phone.a11y

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp

/** The screen-edge rim's numbers on a phone: 1080 × 2400 px at 2.625 px/dp, corners of 110 px. */
class EdgeBeamModelTest {
    private val density = 2.625f
    private val perimeter = EdgeRim.perimeter(1080f, 2400f, 110f)
    private fun lumpsAt(t: Double) = FloatArray(EdgeRim.COUNT * EdgeRim.STRIDE).also { EdgeRim.lumps(t, perimeter, density, it) }

    // ---- 色带：整圈都有，不均匀 -----------------------------------------------------------------------

    @Test fun thePerimeterGoesRoundTheRoundedCorners() {
        assertEquals(2 * (1080f + 2400f), EdgeRim.perimeter(1080f, 2400f, 0f), 0.01f)
        // Each corner swaps two straight radii for a quarter circle.
        assertEquals(2 * (1080f + 2400f) - 8 * 110f + (2 * PI * 110).toFloat(), perimeter, 0.01f)
    }

    @Test fun theRimIsAllRoundUnevenAndAveragesTheReferenceWidth() {
        val l = lumpsAt(40.0)
        val n = 2000
        val t = FloatArray(n) { EdgeRim.thickness(perimeter * it / n, perimeter, l) }
        val mean = t.average()
        assertTrue("mean thickness $mean", mean in 0.75..1.25)
        // Nowhere gone (the colour is all round), nowhere past the bound the shader's reach is built on.
        assertTrue(t.min() >= 0.04f)
        assertTrue(t.max() <= EdgeRim.MAX_THICKNESS * 1.25f)
        // Uneven: thin stretches and lumps several times as deep.
        assertTrue("min ${t.min()} max ${t.max()}", t.max() > 3 * t.min())
    }

    @Test fun theColourFadesInwardToNothingWithinTheReach() {
        val w = EdgeBeamSpec.WIDTH_DP * density
        assertEquals(1f, EdgeRim.hug(0f, 1f, w), 0f)
        assertEquals(exp(-1f), EdgeRim.hug(w, 1f, w), 1e-6f)
        // At the edge of the drawn band even the thickest lump is under half a step of 8-bit alpha.
        val reach = EdgeBeamSpec.REACH * w
        assertTrue(EdgeRim.hug(reach, EdgeRim.MAX_THICKNESS, w) * EdgeBeamSpec.DARK.rim * 255 < 0.5f)
        assertEquals(14f, EdgeBeamSpec.WIDTH_DP, 0f)
    }

    @Test fun theLumpsDriftSlowerThanTheReference() {
        for (i in 0 until EdgeRim.COUNT) {
            val v = abs(EdgeRim.driftDpPerS(i))
            assertTrue("lump $i at $v dp/s", v <= 22 * EdgeBeamSpec.CALM)
        }
        assertTrue(EdgeBeamSpec.CALM <= 1.0)
        // Two seconds apart a lump has moved a few dp along a 2 600 dp edge: it sits there.
        val a = lumpsAt(40.0)
        val b = lumpsAt(42.0)
        for (i in 0 until EdgeRim.COUNT) {
            var d = abs(b[i * EdgeRim.STRIDE] - a[i * EdgeRim.STRIDE])
            d = minOf(d, perimeter - d)
            assertTrue("lump $i moved $d px", d / density <= 2 * 11.01f)
        }
    }

    @Test fun theLumpsBreatheButStayTheSamePattern() {
        val a = lumpsAt(0.0)
        val b = lumpsAt(0.0)
        assertTrue(a.contentEquals(b))
        val depths = (0 until 200).map { lumpsAt(it * 0.1)[2] }
        // ±25 % round the lump's own depth.
        assertTrue(depths.max() / depths.min() in 1.3f..1.7f)
        // Centres stay on the edge.
        val far = lumpsAt(100_000.0)
        for (i in 0 until EdgeRim.COUNT) assertTrue(far[i * EdgeRim.STRIDE] in 0f..perimeter)
    }

    @Test fun thePresetsStayUnderTheBorderBeamsOpacity() {
        assertTrue(EdgeBeamSpec.DARK.rim <= 0.42f)
        assertTrue(EdgeBeamSpec.LIGHT.rim <= 0.26f)
        assertTrue(EdgeBeamSpec.DARK.line < EdgeBeamSpec.DARK.rim)
        assertTrue(EdgeBeamSpec.LIGHT.line < EdgeBeamSpec.LIGHT.rim)
        // At most 60 fps.
        assertTrue(EdgeBeamSpec.FRAME_MS >= 16)
    }

    @Test fun theDisplaysCornerRadiusWinsOverTheFallback() {
        assertEquals(120f, EdgeBeamSpec.cornerPx(120, 2.625f), 0f)
        assertEquals(28 * 2.625f, EdgeBeamSpec.cornerPx(null, 2.625f), 0.001f)
        assertEquals(28 * 2.625f, EdgeBeamSpec.cornerPx(0, 2.625f), 0.001f)
    }

    // ---- 配色：跟 App 的主色 ------------------------------------------------------------------------

    @Test fun thePaletteIsTheDartPortsThemePalette() {
        // BeamPalette.fromSeed for 雾蓝 (#4B67B4), as Flutter's HSLColor rounds it.
        val p = EdgeBeamPalette.fromSeed(0xFF4B67B4.toInt())
        assertEquals(9, p.size)
        assertEquals(0xFF778CC5.toInt(), p[0])
        assertEquals(0xFF89B3C8.toInt(), p[1])
        assertEquals(0xFF8B7EC8.toInt(), p[8])
    }

    @Test fun thePaletteStaysNearTheAccentHueAndCalm() {
        for (seed in intArrayOf(0xFF4B67B4.toInt(), 0xFF236185.toInt(), 0xFFD0453B.toInt(), 0xFF808080.toInt())) {
            val (hue, _, _) = EdgeBeamPalette.hsl(seed)
            for (c in EdgeBeamPalette.fromSeed(seed)) {
                val (h, s, l) = EdgeBeamPalette.hsl(c)
                val d = abs(((h - hue + 540f) % 360f) - 180f)
                assertTrue("hue $h vs $hue", d <= 36.5f)
                assertTrue("saturation $s", s in 0.33f..0.67f)
                assertTrue("lightness $l", l in 0.57f..0.71f)
            }
        }
    }

    @Test fun hslRoundTrips() {
        val (h, s, l) = EdgeBeamPalette.hsl(0xFF778CC5.toInt())
        assertEquals(0xFF778CC5.toInt(), EdgeBeamPalette.fromHsl(h, s, l))
    }

    @Test fun cssEase() {
        assertEquals(0.8024, BeamMath.ease(0.5), 1e-4)
        assertEquals(0.0, BeamMath.ease(0.0), 0.0)
        assertEquals(1.0, BeamMath.ease(1.0), 0.0)
    }

    // ---- 渐入渐出、暂停、移除动画 -----------------------------------------------------------------------

    @Test fun itFadesInOverSixTenthsAndOutOverHalfASecond() {
        val c = EdgeBeamClock(motion = true)
        c.show(true, 1_000)
        c.frame(1_000)
        assertEquals(0f, c.fadeNow, 0f)
        c.frame(1_300)
        assertEquals(BeamMath.ease(0.5).toFloat(), c.fadeNow, 1e-4f)
        c.frame(1_600)
        assertEquals(1f, c.fadeNow, 0f)
        assertTrue(c.animating(1_600)) // on and drifting
        c.show(false, 2_000)
        assertFalse(c.gone(2_400))
        assertTrue(c.gone(2_500))
    }

    @Test fun switchingBackMidFadeCarriesOnFromWhereItIs() {
        val c = EdgeBeamClock(motion = true)
        c.show(true, 0)
        c.show(false, 300)
        c.frame(300)
        val at = c.fadeNow
        assertTrue(at in 0.1f..0.95f)
        c.frame(301)
        assertEquals(at, c.fadeNow, 0.01f)
    }

    @Test fun pausedTheLumpsComeToRestAndNoFramesAreAskedFor() {
        val c = EdgeBeamClock(motion = true)
        c.show(true, 0)
        for (t in 0L..1_000L step 50) c.frame(t)
        assertEquals(1.0, c.phase, 1e-9)
        c.pause(true, 1_000)
        for (t in 1_050L..1_500L step 50) c.frame(t)
        assertEquals(1f, c.stillNow, 0f)
        val stopped = c.phase
        assertTrue(stopped in 1.0..1.5) // it slowed to a stop within half a second rather than halting
        c.frame(2_500)
        assertEquals(stopped, c.phase, 0.0)
        assertFalse(c.animating(2_500))
        c.pause(false, 3_000)
        assertTrue(c.animating(3_000))
    }

    @Test fun aRendererThatStandsStillAsksForFramesOnlyWhileItRamps() {
        val c = EdgeBeamClock(motion = true)
        c.show(true, 0)
        assertTrue(c.animating(100, drifts = false)) // fading in
        c.frame(EdgeBeamSpec.FADE_IN_MS)
        assertFalse(c.animating(EdgeBeamSpec.FADE_IN_MS, drifts = false)) // on, and nothing moves
        assertTrue(c.animating(EdgeBeamSpec.FADE_IN_MS)) // the shader's rim keeps drifting
        c.pause(true, 5_000)
        assertTrue(c.animating(5_100, drifts = false)) // settling into the line
    }

    @Test fun withoutAnimationsTheRimStandsStillAndPausingSwapsAtOnce() {
        val c = EdgeBeamClock(motion = false)
        c.show(true, 0)
        c.frame(0)
        assertEquals(1f, c.fadeNow, 0f)
        assertEquals(0f, c.stillNow, 0f) // the rim, not the line
        assertFalse(c.animating(0))
        c.frame(5_000)
        assertEquals(0.0, c.phase, 0.0) // nothing drifts
        c.pause(true, 5_000)
        c.frame(5_000)
        assertEquals(1f, c.stillNow, 0f)
        c.show(false, 5_010)
        assertTrue(c.gone(5_010))
    }
}
