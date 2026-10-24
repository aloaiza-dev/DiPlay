package com.shilapi.xcertplay.lyrics

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class LyricsBackdropSamplerTest {
    private var clockNanos = 0L
    private var captures = 0
    private var costNanos = 1_000_000L
    private var lastFrameWidth = 0
    private var paint: (Bitmap) -> Unit = { it.eraseColor(0xFF3050A0.toInt()) }
    private val looper get() = Looper.getMainLooper()

    private val source = FrameSource { bitmap ->
        if (!capturesOk) return@FrameSource false
        captures++
        lastFrameWidth = bitmap.width
        paint(bitmap)
        clockNanos += costNanos
        true
    }

    private class FakeTarget(var bounds: Rect? = Rect(200, 100, 800, 300)) : BackdropTarget {
        var bitmap: Bitmap? = null
        var updates = 0
        var cleared = 0

        override fun backdropBounds(out: Rect): Boolean = bounds?.let { out.set(it); true } ?: false

        override fun onBackdrop(bitmap: Bitmap?) {
            if (bitmap == null) cleared++ else updates++
            this.bitmap = bitmap
        }
    }

    private var enabled = true
    private var capturesOk = true

    private fun sampler(target: BackdropTarget, width: Int = 2400, height: Int = 1200) =
        LyricsBackdropSampler(source, { width }, { height }, Handler(looper), { clockNanos }, { enabled }).also {
            it.addTarget(target)
            it.setNight(true)
        }

    private fun run(millis: Long) = shadowOf(looper).idleFor(Duration.ofMillis(millis))

    @Test
    fun aPlainPictureBecomesItsShadedColourBehindTheTarget() {
        val target = FakeTarget()
        sampler(target).wake()
        run(50L)
        val bitmap = checkNotNull(target.bitmap)
        val expected = LyricsScrim.pill(true).shade(0xFF3050A0.toInt())
        for (x in listOf(0, bitmap.width / 2, bitmap.width - 1)) for (y in listOf(0, bitmap.height - 1)) {
            val pixel = bitmap.getPixel(x, y)
            for (shift in listOf(16, 8, 0)) assertEquals("pixel $x,$y channel $shift", ((expected ushr shift) and 0xFF).toDouble(), ((pixel ushr shift) and 0xFF).toDouble(), 3.0)
        }
    }

    @Test
    fun theColoursOfTheEdgeBleedIntoEachOtherNotCutAtALine() {
        paint = { bitmap ->
            for (x in 0 until bitmap.width) for (y in 0 until bitmap.height) {
                bitmap.setPixel(x, y, if (x < bitmap.width / 2) 0xFFA03020.toInt() else 0xFF2040A0.toInt())
            }
        }
        val target = FakeTarget(Rect(1000, 100, 1400, 300))
        sampler(target).wake()
        run(50L)
        val bitmap = checkNotNull(target.bitmap)
        val row = bitmap.height / 2
        val reds = (0 until bitmap.width).map { (bitmap.getPixel(it, row) ushr 16) and 0xFF }
        assertTrue("red falls from left to right: $reds", reds.first() > reds.last() + 10)
        val middle = reds[bitmap.width / 2]
        assertTrue("the middle is between the two sides: $reds", middle < reds.first() && middle > reds.last())
        val steps = reds.zipWithNext { a, b -> kotlin.math.abs(a - b) }.max()
        assertTrue("no hard edge, biggest step $steps", steps < (reds.first() - reds.last()) * 0.6)
    }

    @Test
    fun theFrameCopyIsSmall() {
        sampler(FakeTarget()).wake()
        run(50L)
        assertTrue("copied at $lastFrameWidth px wide", lastFrameWidth in 1..LyricsBackdropSampler.MAX_FRAME_WIDTH)
    }

    @Test
    fun aHiddenTargetIsSkipped() {
        val target = FakeTarget(bounds = null)
        sampler(target).wake()
        run(500L)
        assertEquals(0, target.updates)
    }

    @Test
    fun sleepingStopsTheCopies() {
        val s = sampler(FakeTarget())
        s.wake()
        run(500L)
        val before = captures
        assertTrue(before > 0)
        s.sleep()
        run(2_000L)
        assertEquals(before, captures)
    }

    @Test
    fun aPictureThatDoesNotChangeIsCopiedLessAndLessOften() {
        val s = sampler(FakeTarget())
        s.wake()
        run(3_000L)
        val staticCopies = captures
        captures = 0
        var tick = 0
        paint = { bitmap -> bitmap.eraseColor(if (tick++ % 2 == 0) 0xFF204080.toInt() else 0xFF802040.toInt()) }
        s.sleep()
        s.wake()
        run(3_000L)
        assertTrue("static $staticCopies vs moving $captures", staticCopies < captures)
    }

    @Test
    fun slowCopiesSlowTheRateDown() {
        var tick = 0
        paint = { it.eraseColor(0xFF000000.toInt() or (tick++ * 0x010101)) }
        val fast = sampler(FakeTarget()).also { it.wake() }
        run(3_000L)
        val fastCopies = captures
        fast.sleep()
        captures = 0
        costNanos = 15_000_000L
        val slow = sampler(FakeTarget()).also { it.wake() }
        run(3_000L)
        assertTrue("fast $fastCopies vs slow ${captures}", captures < fastCopies)
        slow.sleep()
    }

    @Test
    fun copiesThatStayFarTooSlowSwitchTheBlurOffForGood() {
        costNanos = 40_000_000L
        val target = FakeTarget()
        val s = sampler(target)
        s.wake()
        run(5_000L)
        assertNull("the target is told to draw plain glass", target.bitmap)
        assertTrue(target.cleared >= 1)
        val before = captures
        s.wake()
        run(5_000L)
        assertEquals("it never comes back in this session", before, captures)
        assertTrue(s.disabled)
    }

    @Test
    fun aTargetThatComesBackIsSampledAtOnceEvenOnAStillPicture() {
        val target = FakeTarget(Rect(0, 0, 600, 200))
        val s = sampler(target)
        s.wake()
        run(4_000L) // a still picture: the rate has backed off
        val clearedBefore = target.cleared
        target.bounds = null
        s.wake() // the lyrics call this on every render
        assertEquals("the stale bitmap is dropped, not stretched onto the next view", clearedBefore + 1, target.cleared)
        val before = target.updates
        target.bounds = Rect(0, 0, 300, 200)
        s.wake()
        run(60L)
        assertTrue("sampled again at once", target.updates > before)
        assertTrue(checkNotNull(target.bitmap).width < 600 / 6 + 2)
    }

    @Test
    fun copiesThatKeepFailingFallBackToPlainGlassAndBackOff() {
        val target = FakeTarget()
        val s = sampler(target)
        s.wake()
        run(100L)
        assertNotNull(target.bitmap)
        capturesOk = false
        run(6_000L)
        assertNull("plain glass while the picture cannot be copied", target.bitmap)
        assertEquals("it asked once and stayed quiet", 1, target.cleared)
        assertTrue(!s.disabled)
        capturesOk = true
        run(3_000L)
        assertNotNull("it recovers when copies work again", target.bitmap)
    }

    @Test
    fun turningTheSettingOffClearsTheGlassAndStopsCopying() {
        val target = FakeTarget()
        val s = sampler(target)
        s.wake()
        run(200L)
        assertNotNull(target.bitmap)
        enabled = false
        s.wake()
        assertNull(target.bitmap)
        val before = captures
        run(2_000L)
        assertEquals(before, captures)
        enabled = true
        s.wake()
        run(200L)
        assertNotNull("and back on when it is switched on again", target.bitmap)
    }

    @Test
    fun aTargetSizeChangeGetsAMatchingBitmap() {
        val target = FakeTarget(Rect(0, 0, 600, 200))
        val s = sampler(target)
        s.wake()
        run(50L)
        val first = checkNotNull(target.bitmap)
        target.bounds = Rect(0, 0, 300, 200)
        run(2_000L)
        val second = checkNotNull(target.bitmap)
        assertNotNull(second)
        assertTrue("narrower region, narrower bitmap: ${first.width} vs ${second.width}", second.width < first.width)
    }
}
