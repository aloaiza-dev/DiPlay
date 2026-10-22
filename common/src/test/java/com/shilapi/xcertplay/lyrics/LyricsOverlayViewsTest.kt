package com.shilapi.xcertplay.lyrics

import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class LyricsOverlayViewsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun ViewGroup.texts(): List<TextView> = (0 until childCount).map { getChildAt(it) as TextView }

    private fun touch(view: View): Boolean {
        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 1f, 1f, 0)
        return view.dispatchTouchEvent(event).also { event.recycle() }
    }

    @Test
    fun viewsTakeNoTouches() {
        for (view in listOf<View>(LyricsPillView(context), LyricsRailView(context))) {
            assertFalse(view.isClickable)
            assertFalse(view.isFocusable)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, view.importantForAccessibility)
        }
        val pill = LyricsPillView(context).apply { show("a", "b") }
        val rail = LyricsRailView(context).apply { show(listOf("a", "b", "c"), 1) }
        assertFalse(touch(pill))
        assertFalse(touch(rail))
    }

    @Test
    fun pillWithoutTextDrawsNothing() {
        val pill = LyricsPillView(context)
        pill.show("a", "b")
        pill.show(null, null)
        assertEquals(View.GONE, pill.visibility)
        pill.show("", "  ")
        assertEquals(View.GONE, pill.visibility)
    }

    @Test
    fun pillShowsCurrentAndNextLine() {
        val pill = LyricsPillView(context).apply { show("now", "later") }
        assertEquals(View.VISIBLE, pill.visibility)
        assertEquals(listOf("now", "later"), pill.texts().map { it.text.toString() })
    }

    @Test
    fun pillHidesTheNextRowWhenThereIsNoNextLine() {
        val pill = LyricsPillView(context).apply { show("now", null) }
        assertEquals(View.GONE, pill.texts()[1].visibility)
        assertEquals(View.VISIBLE, pill.texts()[0].visibility)
    }

    @Test
    fun linesKeepFirstStrongTextDirectionForArabicAndCjk() {
        val pill = LyricsPillView(context).apply { show("مرحبا بالعالم", "你好，世界") }
        assertTrue(pill.texts().all { it.textDirection == View.TEXT_DIRECTION_FIRST_STRONG })
    }

    @Test
    fun setNightSwapsColoursWithoutChangingText() {
        val pill = LyricsPillView(context).apply { setNight(true); show("a", "b") }
        assertEquals(LyricsTheme.colors(true).current, pill.texts()[0].currentTextColor)
        pill.setNight(false)
        assertEquals(LyricsTheme.colors(false).current, pill.texts()[0].currentTextColor)
        assertEquals(LyricsTheme.colors(false).next, pill.texts()[1].currentTextColor)
        assertEquals("a", pill.texts()[0].text.toString())
    }

    @Test
    fun dimmingLowersTheOpacityOfTheWholeView() {
        val pill = LyricsPillView(context).apply { show("a", "b") }
        pill.setDimmed(true)
        assertEquals(0.6f, pill.alpha, 0.001f)
        pill.setDimmed(false)
        assertEquals(1f, pill.alpha, 0.001f)
    }

    @Test
    fun reducedMotionChangesTheLineAtOnce() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        val pill = LyricsPillView(context).apply { show("a", "b") }
        pill.show("c", "d")
        assertEquals(listOf("c", "d"), pill.texts().map { it.text.toString() })
        assertEquals(1f, pill.alpha, 0.001f)
    }

    @Test
    fun aRequestWithoutAnimationChangesTheLineAtOnce() {
        val pill = LyricsPillView(context).apply { show("a", "b") }
        pill.show("c", "d", animate = false)
        assertEquals(listOf("c", "d"), pill.texts().map { it.text.toString() })
        assertEquals(1f, pill.alpha, 0.001f)
    }

    @Test
    fun pillGrowsToFitALongerLineAfterTheTextChanges() {
        val root = FrameLayout(context)
        val pill = LyricsPillView(context).apply { setWindowSizeDp(1000f, 480f) }
        root.addView(pill, FrameLayout.LayoutParams(-2, -2))
        fun layoutRoot() {
            root.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1000, 480)
        }
        pill.show("hi", "yo", animate = false)
        layoutRoot()
        val short = pill.width
        pill.show("This line is much longer than the first one", "and so is the next line after it", animate = false)
        layoutRoot()
        val grown = pill.width
        assertTrue("pill stayed $short wide, then $grown", grown > short + 20)
        pill.show("tiny", "tiny", animate = false)
        layoutRoot()
        assertTrue("pill did not shrink back: ${pill.width} after $grown", pill.width < grown)
    }

    @Test
    fun typeDoublesOnAVeryWideLowDensityWindow() {
        fun currentSize(widthDp: Float): Float {
            val pill = LyricsPillView(context).apply { setWindowSizeDp(widthDp, 700f); show("a", "b") }
            return pill.texts()[0].textSize
        }
        assertEquals(2f, currentSize(2666f) / currentSize(1000f), 0.05f)
        val narrowRail = LyricsRailView(context).apply { setWindowSizeDp(1000f, 700f); show(listOf("a", "b", "c"), 1) }
        val wideRail = LyricsRailView(context).apply { setWindowSizeDp(2666f, 700f); show(listOf("a", "b", "c"), 1) }
        assertEquals(2f, wideRail.texts()[2 - 1].textSize / narrowRail.texts()[2 - 1].textSize, 0.05f)
    }

    @Test
    fun pillWidthIsCappedAtFiftyFivePercentOfTheWindow() {
        val pill = LyricsPillView(context).apply {
            setWindowSizeDp(1000f, 480f)
            show("x".repeat(200), "y".repeat(200))
        }
        pill.measure(
            View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST),
        )
        assertTrue("width ${pill.measuredWidth}", pill.measuredWidth <= (550 * context.resources.displayMetrics.density).toInt())
    }

    @Test
    fun railCentresTheCurrentLine() {
        val lines = (0..9).map { "l$it" }
        val rail = LyricsRailView(context).apply { show(lines, 4) }
        assertEquals(View.VISIBLE, rail.visibility)
        assertEquals(listOf("l2", "l3", "l4", "l5", "l6"), rail.texts().map { it.text.toString() })
        val sizes = rail.texts().map { it.textSize }
        assertTrue(sizes[2] > sizes[1] && sizes[1] > sizes[0])
        assertEquals(LyricsTheme.colors(true).current, rail.texts()[2].currentTextColor)
    }

    @Test
    fun railPadsWithBlanksNearTheEnds() {
        val rail = LyricsRailView(context).apply { show(listOf("a", "b", "c"), 0) }
        assertEquals(listOf("", "", "a", "b", "c"), rail.texts().map { it.text.toString() })
    }

    @Test
    fun narrowWindowShowsThreeRailLines() {
        val rail = LyricsRailView(context).apply {
            setWindowSizeDp(1280f, 300f)
            show((0..9).map { "l$it" }, 4)
        }
        assertEquals(listOf("l3", "l4", "l5"), rail.texts().map { it.text.toString() })
    }

    @Test
    fun railWithoutLinesDrawsNothing() {
        val rail = LyricsRailView(context).apply { show(listOf("a"), 0) }
        rail.show(emptyList(), 0)
        assertEquals(View.GONE, rail.visibility)
    }

    @Test
    fun railWidthFollowsTheLayoutRule() {
        val rail = LyricsRailView(context).apply { setWindowSizeDp(1000f, 480f) }
        assertEquals((320 * context.resources.displayMetrics.density).toInt(), rail.preferredWidthPx())
    }

    @Test
    fun repeatingTheSameWindowSizeDoesNotRequestAnotherLayout() {
        val pill = LyricsPillView(context)
        val rail = LyricsRailView(context)
        for (view in listOf<View>(pill, rail)) {
            view.layout(0, 0, 100, 100)
        }
        pill.setWindowSizeDp(1000f, 480f)
        rail.setWindowSizeDp(1000f, 480f)
        pill.layout(0, 0, 100, 100)
        rail.layout(0, 0, 100, 100)
        pill.setWindowSizeDp(1000f, 480f)
        rail.setWindowSizeDp(1000f, 480f)
        assertFalse(pill.isLayoutRequested)
        assertFalse(rail.isLayoutRequested)
    }

    @Test
    fun viewsAreLinearLayouts() {
        assertTrue(LyricsPillView(context) is LinearLayout)
        assertTrue(LyricsRailView(context) is LinearLayout)
    }
}
