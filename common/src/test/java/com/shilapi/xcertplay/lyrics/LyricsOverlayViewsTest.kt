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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class LyricsOverlayViewsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun ViewGroup.texts(): List<TextView> = (0 until childCount).map { getChildAt(it) as TextView }

    private fun LyricsPillView.rows(): List<TextView> = listOf(currentRow, nextRow)

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
        assertEquals(listOf("now", "later"), pill.rows().map { it.text.toString() })
    }

    @Test
    fun pillHidesTheNextRowWhenThereIsNoNextLine() {
        val pill = LyricsPillView(context).apply { show("now", null) }
        assertEquals(View.GONE, pill.rows()[1].visibility)
        assertEquals(View.VISIBLE, pill.rows()[0].visibility)
    }

    @Test
    fun linesKeepFirstStrongTextDirectionForArabicAndCjk() {
        val pill = LyricsPillView(context).apply { show("مرحبا بالعالم", "你好，世界") }
        assertTrue(pill.rows().all { it.textDirection == View.TEXT_DIRECTION_FIRST_STRONG })
    }

    @Test
    fun setNightSwapsColoursWithoutChangingText() {
        val pill = LyricsPillView(context).apply { setNight(true); show("a", "b") }
        assertEquals(LyricsTheme.colors(true).current, pill.rows()[0].currentTextColor)
        pill.setNight(false)
        assertEquals(LyricsTheme.colors(false).current, pill.rows()[0].currentTextColor)
        assertEquals(LyricsTheme.colors(false).next, pill.rows()[1].currentTextColor)
        assertEquals("a", pill.rows()[0].text.toString())
    }

    @Test
    fun dimmingLowersTheOpacityOfTheWholeView() {
        val pill = LyricsPillView(context).apply { show("a", "b", animate = false) }
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
        assertEquals(listOf("c", "d"), pill.rows().map { it.text.toString() })
        assertEquals(1f, pill.alpha, 0.001f)
    }

    @Test
    fun aRequestWithoutAnimationChangesTheLineAtOnce() {
        val pill = LyricsPillView(context).apply { show("a", "b") }
        pill.show("c", "d", animate = false)
        assertEquals(listOf("c", "d"), pill.rows().map { it.text.toString() })
        assertEquals(1f, pill.alpha, 0.001f)
    }

    private fun letTimePass(millis: Long) = shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(millis))

    private fun laidOut(pill: LyricsPillView): FrameLayout = FrameLayout(context).also { root ->
        root.addView(pill, FrameLayout.LayoutParams(-2, -2))
        pill.setWindowSizeDp(1000f, 480f)
        root.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1000, 480)
    }

    @Test
    fun aRollStartsWithGhostsOfTheOldLinesAndEndsWithOnlyTheRealRows() {
        val pill = LyricsPillView(context)
        laidOut(pill)
        pill.show("one", "two", animate = false)
        pill.show("two", "three")
        assertEquals(View.VISIBLE, pill.outgoingRow.visibility)
        assertEquals("the old current line leaves", "one", pill.outgoingRow.text.toString())
        assertEquals("the old next line rises", "two", pill.morphRow.text.toString())
        assertEquals("the new current line starts unseen", 0f, pill.currentRow.alpha, 0.01f)
        letTimePass(500L)
        assertEquals(View.GONE, pill.outgoingRow.visibility)
        assertEquals(View.GONE, pill.morphRow.visibility)
        for (row in pill.rows()) {
            assertEquals(1f, row.alpha, 0.001f)
            assertEquals(0f, row.translationY, 0.001f)
            assertEquals(1f, row.scaleX, 0.001f)
        }
        assertEquals(listOf("two", "three"), pill.rows().map { it.text.toString() })
    }

    @Test
    fun aLineThatIsNotTheNextOneDipsInsteadOfRolling() {
        val pill = LyricsPillView(context)
        laidOut(pill)
        pill.show("one", "two", animate = false)
        pill.show("far away", "other")
        assertEquals(View.GONE, pill.outgoingRow.visibility)
        assertEquals(LyricsMotion.DIP_ALPHA, pill.currentRow.alpha, 0.01f)
        letTimePass(500L)
        assertEquals(1f, pill.currentRow.alpha, 0.001f)
    }

    @Test
    fun reducedMotionRollsAtOnceWithNoGhosts() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        val pill = LyricsPillView(context)
        laidOut(pill)
        pill.show("one", "two")
        pill.show("two", "three")
        assertEquals(View.GONE, pill.outgoingRow.visibility)
        assertEquals(listOf("two", "three"), pill.rows().map { it.text.toString() })
        assertEquals(1f, pill.currentRow.alpha, 0.001f)
    }

    @Test
    fun aNewPillFadesInAndSettles() {
        val pill = LyricsPillView(context)
        laidOut(pill)
        pill.show("a", "b")
        assertEquals(View.VISIBLE, pill.visibility)
        assertEquals(0f, pill.alpha, 0.01f)
        assertTrue("rises into place", pill.translationY > 0f)
        letTimePass(500L)
        assertEquals(1f, pill.alpha, 0.001f)
        assertEquals(0f, pill.translationY, 0.001f)
    }

    @Test
    fun fadeOutHidesTheViewAfterTheFade() {
        val pill = LyricsPillView(context)
        laidOut(pill)
        pill.show("a", "b", animate = false)
        pill.fadeOut()
        assertEquals("still fading", View.VISIBLE, pill.visibility)
        pill.fadeOut() // idempotent
        letTimePass(400L)
        assertEquals(View.GONE, pill.visibility)
    }

    @Test
    fun fadeOutIsInstantWhenAnimationsAreOff() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        val pill = LyricsPillView(context)
        laidOut(pill)
        pill.show("a", "b")
        pill.fadeOut()
        assertEquals(View.GONE, pill.visibility)
    }

    @Test
    fun showingDuringAFadeOutBringsTheLineBack() {
        val pill = LyricsPillView(context)
        laidOut(pill)
        pill.show("a", "b", animate = false)
        pill.fadeOut()
        pill.show("c", "d")
        letTimePass(600L)
        assertEquals(View.VISIBLE, pill.visibility)
        assertEquals(1f, pill.alpha, 0.001f)
        assertEquals("c", pill.currentRow.text.toString())
    }

    @Test
    fun theGlassPaddingFollowsTheTextSizeOnAnyScreen() {
        for (widthDp in listOf(1000f, 2666f)) {
            val pill = LyricsPillView(context).apply { setWindowSizeDp(widthDp, 700f); show("hello", null, animate = false) }
            pill.measure(View.MeasureSpec.makeMeasureSpec(5000, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(5000, View.MeasureSpec.AT_MOST))
            val size = pill.currentRow.textSize
            val padding = (pill.measuredWidth - pill.currentRow.measuredWidth) / 2f
            assertEquals("width $widthDp", LyricsLayout.PILL_PADDING_H_EM * size, padding, 2f)
        }
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
            return pill.rows()[0].textSize
        }
        assertEquals(2f, currentSize(2666f) / currentSize(1000f), 0.05f)
        val narrowRail = LyricsRailView(context).apply { setWindowSizeDp(1000f, 700f) }
        val wideRail = LyricsRailView(context).apply { setWindowSizeDp(2666f, 700f) }
        assertEquals(2f, wideRail.row(0).textSize / narrowRail.row(0).textSize, 0.05f)
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

    private val ten = (0..9).map { "l$it" }

    private fun relayout(root: FrameLayout) {
        root.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1000, 480)
    }

    private fun laidOutRail(rail: LyricsRailView): FrameLayout = FrameLayout(context).also { root ->
        rail.setWindowSizeDp(1000f, 480f)
        root.addView(rail, FrameLayout.LayoutParams(rail.preferredWidthPx(), rail.preferredHeightPx(), android.view.Gravity.RIGHT))
        relayout(root)
    }

    @Test
    fun railShowsTheLinesAroundTheCurrentOneInSlots() {
        val rail = LyricsRailView(context).apply { show(ten, 4, animate = false) }
        assertEquals(View.VISIBLE, rail.visibility)
        assertEquals(3, rail.slotReach)
        assertEquals(listOf("l1", "l2", "l3", "l4", "l5", "l6", "l7"), (-3..3).map { rail.row(it).text.toString() })
        assertTrue("one weight for every line", (-3..3).all { rail.row(it).typeface == rail.row(0).typeface })
        assertEquals(LyricsTheme.colors(true).current, rail.row(0).currentTextColor)
    }

    @Test
    fun railLinesWrapInBalancedRowsSoNoSingleWordIsLeftAlone() {
        val rail = LyricsRailView(context)
        assertTrue((-rail.slotReach..rail.slotReach).all { rail.row(it).breakStrategy == android.text.Layout.BREAK_STRATEGY_BALANCED })
    }

    @Test
    fun theWheelScalesAndFadesLinesByDistance() {
        val rail = LyricsRailView(context)
        val root = laidOutRail(rail)
        rail.show(ten, 4, animate = false)
        relayout(root)
        for (offset in -2..2) {
            assertEquals("scale $offset", LyricsMotion.railScale(offset), rail.row(offset).scaleX, 0.001f)
            assertEquals("alpha $offset", LyricsMotion.railAlpha(offset), rail.row(offset).alpha, 0.001f)
        }
    }

    @Test
    fun theCurrentLineSitsAtTheCardsVerticalCentreAndTheOthersStackOutward() {
        val rail = LyricsRailView(context)
        val root = laidOutRail(rail)
        rail.show(ten, 4, animate = false)
        relayout(root)
        fun centre(offset: Int) = rail.row(offset).let { (it.top + it.bottom) / 2f }
        assertEquals(rail.height / 2f, centre(0), 1.5f)
        for (offset in -2..1) assertTrue("row $offset above row ${offset + 1}", centre(offset) < centre(offset + 1))
    }

    @Test
    fun railLeavesBlankSlotsHiddenNearTheEnds() {
        val rail = LyricsRailView(context).apply { show(listOf("a", "b", "c"), 0, animate = false) }
        assertEquals(listOf("", "", "", "a", "b", "c", ""), (-3..3).map { rail.row(it).text.toString() })
        assertEquals(View.GONE, rail.row(-1).visibility)
        assertEquals(View.VISIBLE, rail.row(0).visibility)
    }

    @Test
    fun narrowWindowUsesFewerSlots() {
        val rail = LyricsRailView(context).apply { setWindowSizeDp(1280f, 300f); show(ten, 4, animate = false) }
        assertEquals(2, rail.slotReach)
        assertEquals(listOf("l2", "l3", "l4", "l5", "l6"), (-2..2).map { rail.row(it).text.toString() })
    }

    @Test
    fun railWithoutLinesDrawsNothing() {
        val rail = LyricsRailView(context).apply { show(listOf("a"), 0, animate = false) }
        rail.show(emptyList(), 0)
        assertEquals(View.GONE, rail.visibility)
    }

    @Test
    fun theRailCardFollowsTheLayoutRule() {
        val rail = LyricsRailView(context).apply { setWindowSizeDp(1000f, 480f) }
        val density = context.resources.displayMetrics.density
        assertEquals((360 * density).toInt(), rail.preferredWidthPx())
        assertEquals((0.62f * 480f * density).toInt(), rail.preferredHeightPx())
        assertEquals((0.012f * 480f * density).toInt(), rail.edgeMarginPx())
    }

    @Test
    fun aRailRollStartsEachRowAtTheSlotBelowAndSettlesInPlace() {
        val rail = LyricsRailView(context)
        val root = laidOutRail(rail)
        rail.show(ten, 4, animate = false)
        relayout(root)
        rail.show(ten, 5)
        assertTrue("the new current line starts below", rail.row(0).translationY > 0f)
        assertEquals("at the next line's size", LyricsMotion.railScale(1), rail.row(0).scaleX, 0.001f)
        assertEquals(LyricsMotion.railAlpha(1), rail.row(0).alpha, 0.001f)
        letTimePass(500L)
        relayout(root)
        assertEquals(0f, rail.row(0).translationY, 0.001f)
        assertEquals(1f, rail.row(0).scaleX, 0.001f)
        assertEquals(1f, rail.row(0).alpha, 0.001f)
        assertEquals("l5", rail.row(0).text.toString())
    }

    @Test
    fun aJumpOfSeveralLinesDipsInsteadOfRolling() {
        val rail = LyricsRailView(context)
        val root = laidOutRail(rail)
        rail.show(ten, 2, animate = false)
        relayout(root)
        rail.show(ten, 7)
        assertEquals(0f, rail.row(0).translationY, 0.001f)
        assertEquals(LyricsMotion.DIP_ALPHA, rail.row(0).alpha, 0.01f)
        letTimePass(500L)
        assertEquals(1f, rail.row(0).alpha, 0.001f)
    }

    @Test
    fun aNewRailFadesInAndFadeOutHidesIt() {
        val rail = LyricsRailView(context)
        laidOutRail(rail)
        rail.show(ten, 4)
        assertEquals(0f, rail.alpha, 0.01f)
        letTimePass(500L)
        assertEquals(1f, rail.alpha, 0.001f)
        rail.fadeOut()
        assertEquals(View.VISIBLE, rail.visibility)
        letTimePass(400L)
        assertEquals(View.GONE, rail.visibility)
    }

    @Test
    fun railFadeOutIsInstantWhenAnimationsAreOff() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        val rail = LyricsRailView(context)
        laidOutRail(rail)
        rail.show(ten, 4)
        rail.fadeOut()
        assertEquals(View.GONE, rail.visibility)
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

}
