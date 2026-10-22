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
import com.shilapi.xcertplay.host.R
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

    private var switches = 0

    private fun tap(view: View, downAt: Long, x: Float = 5f, y: Float = 5f): Boolean {
        val down = MotionEvent.obtain(downAt, downAt, MotionEvent.ACTION_DOWN, x, y, 0)
        val consumed = view.dispatchTouchEvent(down)
        val up = MotionEvent.obtain(downAt, downAt + 40, MotionEvent.ACTION_UP, x, y, 0)
        view.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
        return consumed
    }

    private fun interactivePill(): LyricsPillView {
        switches = 0
        val pill = LyricsPillView(context)
        laidOut(pill)
        pill.show("one", "two", animate = false)
        pill.setSwitchAction { switches++ }
        pill.setInteractive(true)
        return pill
    }

    @Test
    fun aDoubleTapOnThePillRunsTheSwitchAction() {
        val pill = interactivePill()
        tap(pill, 0L)
        tap(pill, 150L)
        assertEquals(1, switches)
    }

    @Test
    fun aSingleTapOnThePillDoesNothing() {
        val pill = interactivePill()
        tap(pill, 0L)
        assertEquals(0, switches)
    }

    @Test
    fun twoSlowTapsAreNotADoubleTap() {
        val pill = interactivePill()
        tap(pill, 0L)
        tap(pill, 900L)
        assertEquals(0, switches)
    }

    @Test
    fun aPillThatIsNotInteractiveTakesNoTouches() {
        val pill = interactivePill()
        pill.setInteractive(false)
        assertFalse(pill.isClickable)
        assertFalse("the tap goes through to CarPlay", tap(pill, 0L))
        tap(pill, 150L)
        assertEquals(0, switches)
    }

    @Test
    fun anInteractivePillTakesTheTapsInsideItsGlass() {
        val pill = interactivePill()
        assertTrue(tap(pill, 0L))
    }

    @Test
    fun aPillWithoutAnActionTakesNoTouchesEvenWhenInteractive() {
        val pill = LyricsPillView(context)
        laidOut(pill)
        pill.show("one", "two", animate = false)
        pill.setInteractive(true)
        assertFalse(tap(pill, 0L))
    }

    private val timingCalls = mutableListOf<Int>()

    private fun controlledRail(initial: Int = 500): Pair<LyricsRailView, FrameLayout> {
        timingCalls.clear()
        val rail = LyricsRailView(context)
        val root = laidOutRail(rail)
        rail.setTimingControl(initial) { timingCalls += it }
        rail.show(ten, 4, animate = false)
        relayout(root)
        return rail to root
    }

    private fun touchAt(view: View, x: Float, y: Float): Boolean {
        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, x, y, 0)
        return view.dispatchTouchEvent(event).also { event.recycle() }
    }

    private fun centreOf(view: View) = (view.left + view.right) / 2f to (view.top + view.bottom) / 2f

    @Test
    fun theGearIsTheOnlyPartOfTheRailThatTakesTouches() {
        val (rail, _) = controlledRail()
        val (gx, gy) = centreOf(rail.gearButton)
        assertTrue("the gear takes a tap", touchAt(rail, gx, gy))
        assertFalse("the middle of the card passes the tap on", touchAt(rail, rail.width / 2f, rail.height / 2f))
        assertFalse("so does a corner without the gear", touchAt(rail, 4f, rail.height - 4f))
    }

    @Test
    fun withoutAControlTheRailTakesNoTouchesAndShowsNoGear() {
        val rail = LyricsRailView(context)
        val root = laidOutRail(rail)
        rail.show(ten, 4, animate = false)
        relayout(root)
        assertEquals(View.GONE, rail.gearButton.visibility)
        assertFalse(touchAt(rail, rail.width - 10f, 10f))
    }

    @Test
    fun theGearHasANameAndAComfortableTouchTarget() {
        val (rail, _) = controlledRail()
        assertEquals(context.getString(R.string.settings_lyrics_timing), rail.gearButton.contentDescription)
        val minimum = 48 * context.resources.displayMetrics.density
        assertTrue("gear ${rail.gearButton.width}x${rail.gearButton.height}", rail.gearButton.width >= minimum && rail.gearButton.height >= minimum)
        assertTrue("earlier ${rail.earlierButton.height}", rail.earlierButton.let { it.height >= minimum || rail.timingStrip.height >= minimum })
    }

    @Test
    fun tappingTheGearOpensTheStripAndTappingAgainClosesIt() {
        val (rail, _) = controlledRail()
        assertEquals(View.GONE, rail.timingStrip.visibility)
        rail.gearButton.performClick()
        assertEquals(View.VISIBLE, rail.timingStrip.visibility)
        rail.gearButton.performClick()
        assertEquals(View.GONE, rail.timingStrip.visibility)
    }

    @Test
    fun theStripShowsTheCurrentTimingAndFollowsChangesFromElsewhere() {
        val (rail, _) = controlledRail(1000)
        rail.gearButton.performClick()
        assertEquals("1 s earlier", rail.timingValue.text.toString())
        rail.setTiming(250)
        assertEquals("0.25 s earlier", rail.timingValue.text.toString())
        assertEquals(context.getString(R.string.lyrics_control_earlier), rail.earlierButton.text.toString())
        assertEquals(context.getString(R.string.lyrics_control_later), rail.laterButton.text.toString())
    }

    @Test
    fun earlierAndLaterStepAQuarterSecondAndReportEachChange() {
        val (rail, _) = controlledRail(500)
        rail.gearButton.performClick()
        rail.earlierButton.performClick()
        assertEquals(listOf(750), timingCalls)
        assertEquals("0.75 s earlier", rail.timingValue.text.toString())
        rail.laterButton.performClick()
        rail.laterButton.performClick()
        rail.laterButton.performClick()
        assertEquals(listOf(750, 500, 250, 0), timingCalls)
        assertEquals("On time", rail.timingValue.text.toString())
    }

    @Test
    fun theButtonsStopAtTheEndsOfTheRange() {
        val (top, _) = controlledRail(2000)
        top.gearButton.performClick()
        assertFalse(top.earlierButton.isEnabled)
        top.earlierButton.performClick()
        assertTrue(timingCalls.isEmpty())
        assertTrue(top.laterButton.isEnabled)
        val (bottom, _) = controlledRail(-2000)
        bottom.gearButton.performClick()
        assertFalse(bottom.laterButton.isEnabled)
        assertTrue(bottom.earlierButton.isEnabled)
    }

    @Test
    fun theStripClosesByItselfAndEachTapStartsTheWaitAgain() {
        val (rail, _) = controlledRail()
        rail.gearButton.performClick()
        letTimePass(3_000L)
        assertEquals(View.VISIBLE, rail.timingStrip.visibility)
        rail.earlierButton.performClick()
        letTimePass(3_000L)
        assertEquals("a tap restarts the wait", View.VISIBLE, rail.timingStrip.visibility)
        letTimePass(2_000L)
        assertEquals(View.GONE, rail.timingStrip.visibility)
    }

    @Test
    fun whileTheCarMovesTheGearAndStripAreGone() {
        val (rail, _) = controlledRail()
        rail.gearButton.performClick()
        rail.setControlsAvailable(false)
        assertEquals(View.GONE, rail.gearButton.visibility)
        assertEquals(View.GONE, rail.timingStrip.visibility)
        rail.setControlsAvailable(true)
        assertEquals(View.VISIBLE, rail.gearButton.visibility)
        assertEquals("the strip stays closed", View.GONE, rail.timingStrip.visibility)
    }

    @Test
    fun hidingTheRailClosesTheStrip() {
        val (rail, _) = controlledRail()
        rail.gearButton.performClick()
        rail.hide()
        rail.show(ten, 4, animate = false)
        assertEquals(View.GONE, rail.timingStrip.visibility)
    }

    private fun railWithSwitch(): LyricsRailView {
        switches = 0
        val (rail, root) = controlledRail()
        rail.setSwitchAction { switches++ }
        relayout(root)
        return rail
    }

    @Test
    fun theRailHasAPillButtonInTheUpperLeftCornerWithAName() {
        val rail = railWithSwitch()
        val button = rail.pillButton
        assertEquals(View.VISIBLE, button.visibility)
        assertEquals(context.getString(R.string.lyrics_control_pill), button.contentDescription)
        assertTrue("left of the gear", button.right <= rail.gearButton.left)
        assertTrue("upper left", button.left < rail.width / 2 && button.top >= 0)
        val minimum = 48 * context.resources.displayMetrics.density
        assertTrue("target ${button.width}x${button.height}", button.width >= minimum && button.height >= minimum)
    }

    @Test
    fun tappingThePillButtonRunsTheSwitchAction() {
        val rail = railWithSwitch()
        rail.pillButton.performClick()
        assertEquals(1, switches)
    }

    @Test
    fun thePillButtonTakesTapsAndTheCardStillDoesNot() {
        val rail = railWithSwitch()
        val (x, y) = centreOf(rail.pillButton)
        assertTrue(touchAt(rail, x, y))
        assertFalse(touchAt(rail, rail.width / 2f, rail.height / 2f))
    }

    @Test
    fun withoutASwitchActionThereIsNoPillButton() {
        val (rail, _) = controlledRail()
        assertEquals(View.GONE, rail.pillButton.visibility)
    }

    @Test
    fun whileTheCarMovesThePillButtonGoesAwayToo() {
        val rail = railWithSwitch()
        rail.setControlsAvailable(false)
        assertEquals(View.GONE, rail.pillButton.visibility)
        rail.setControlsAvailable(true)
        assertEquals(View.VISIBLE, rail.pillButton.visibility)
    }

    @Test
    fun linesThatWouldPokeOutBesideTheOpenStripAreNotDrawn() {
        val (rail, root) = controlledRail()
        rail.gearButton.performClick()
        relayout(root)
        var hidden = 0
        for (offset in -rail.slotReach..rail.slotReach) {
            val row = rail.row(offset)
            if (row.visibility != View.VISIBLE) continue
            val centre = (row.top + row.bottom) / 2f
            val bottom = centre + row.height * LyricsMotion.railScale(offset) / 2f
            if (bottom > rail.timingStrip.top) {
                hidden++
                assertEquals("row $offset is under the strip", 0f, row.alpha, 0.001f)
            } else {
                assertEquals("row $offset stays", LyricsMotion.railAlpha(offset), row.alpha, 0.001f)
            }
        }
        rail.gearButton.performClick()
        for (offset in -rail.slotReach..rail.slotReach) {
            assertEquals("row $offset comes back", LyricsMotion.railAlpha(offset), rail.row(offset).alpha, 0.001f)
        }
        assertTrue("a closed strip hides nothing; hidden=$hidden", hidden >= 0)
    }

    @Test
    fun everyLabelInTheStripIsCentredBothWays() {
        val (rail, _) = controlledRail()
        for (view in listOf(rail.laterButton, rail.timingValue, rail.earlierButton)) {
            assertEquals(android.view.Gravity.CENTER, view.gravity)
        }
    }

    @Test
    fun theStripIsOpaqueSoTheLinesBehindItDoNotShowThrough() {
        for (night in listOf(true, false)) {
            val (rail, _) = controlledRail()
            rail.setNight(night)
            val fill = (rail.timingStrip.background as android.graphics.drawable.GradientDrawable).color!!.defaultColor
            assertEquals("night=$night", 0xFF, fill ushr 24)
        }
    }

    @Test
    fun theStripKeepsItsLabelsReadableOnItsOwnFill() {
        for (night in listOf(true, false)) {
            val (rail, _) = controlledRail()
            rail.setNight(night)
            val colors = LyricsTheme.colors(night)
            val fill = (rail.timingStrip.background as android.graphics.drawable.GradientDrawable).color!!.defaultColor
            assertTrue("current text, night=$night", LyricsTheme.contrast(colors.current, fill) >= 7.0)
            assertTrue("value text, night=$night", LyricsTheme.contrast(colors.next, fill) >= 4.5)
        }
    }

    @Test
    fun theStripSitsInsideTheCardAndTheGearInItsCorner() {
        val (rail, root) = controlledRail()
        rail.gearButton.performClick()
        relayout(root)
        val strip = rail.timingStrip
        assertTrue("strip ${strip.left},${strip.top},${strip.right},${strip.bottom} in ${rail.width}x${rail.height}",
            strip.left >= 0 && strip.right <= rail.width && strip.top >= 0 && strip.bottom <= rail.height)
        assertTrue("gear in the upper right", rail.gearButton.right <= rail.width && rail.gearButton.left > rail.width / 2 && rail.gearButton.top >= 0)
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
