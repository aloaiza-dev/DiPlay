package com.shilapi.xcertplay.lyrics

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/** Drawn pictures of the overlay for review, plus the size rules they must keep. Not a pixel golden. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LyricsOverlayScreenshotsTest {
    private data class Window(val name: String, val widthDp: Int, val heightDp: Int)

    private val wide = Window("wide", 1000, 480)
    private val narrow = Window("narrow", 640, 320)
    private val song = listOf(
        "And I know we weren’t perfect", "But I’ve never felt this way for no one",
        "Yeah, today I drove through the suburbs", "And pictured I was driving home to you",
        "’Cause they’ll never know you the way that I do", "Today I drove through the suburbs",
        "Crying ’cause you weren’t around",
    )

    private fun capture(
        name: String,
        window: Window,
        night: Boolean,
        backdrop: Int,
        build: (FrameLayout, Window) -> View,
        fontScale: Float = 1.5f,
    ): Triple<FrameLayout, View, Bitmap> {
        RuntimeEnvironment.setFontScale(fontScale)
        val density = RuntimeEnvironment.getApplication().resources.displayMetrics.density
        val width = (window.widthDp * density).toInt()
        val height = (window.heightDp * density).toInt()
        val root = FrameLayout(RuntimeEnvironment.getApplication()).apply { setBackgroundColor(backdrop) }
        val view = build(root, window)
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        File("build/reports/lyrics-overlay").apply { mkdirs() }.let { folder ->
            FileOutputStream(File(folder, "$name.png")).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
        return Triple(root, view, bitmap)
    }

    private fun pill(night: Boolean, current: String, next: String): (FrameLayout, Window) -> View = { root, window ->
        val density = root.resources.displayMetrics.density
        LyricsPillView(root.context).also { pill ->
            pill.setWindowSizeDp(window.widthDp.toFloat(), window.heightDp.toFloat())
            pill.setNight(night)
            pill.show(current, next, animate = false)
            root.addView(pill, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = (LyricsLayout.PILL_TOP_MARGIN_DP * density).toInt()
            })
        }
    }

    private fun rail(night: Boolean, lines: List<String>, index: Int): (FrameLayout, Window) -> View = { root, window ->
        LyricsRailView(root.context).also { rail ->
            rail.setWindowSizeDp(window.widthDp.toFloat(), window.heightDp.toFloat())
            rail.setNight(night)
            rail.show(lines, index, animate = false)
            root.addView(rail, FrameLayout.LayoutParams(rail.preferredWidthPx(), rail.preferredHeightPx(), Gravity.RIGHT or Gravity.CENTER_VERTICAL).apply {
                rightMargin = rail.edgeMarginPx()
            })
        }
    }

    private fun assertInside(root: View, view: View) {
        assertTrue("${view.left},${view.top},${view.right},${view.bottom} in ${root.width}x${root.height}",
            view.left >= 0 && view.top >= 0 && view.right <= root.width && view.bottom <= root.height)
    }

    @Test
    fun pillInDayAndNightOverWhiteAndBlackStaysSmallAndVisible() {
        for (window in listOf(wide, narrow)) for (night in listOf(true, false)) for ((tone, backdrop) in listOf("white" to Color.WHITE, "black" to Color.BLACK)) {
            val theme = if (night) "night" else "day"
            val (root, view, bitmap) = capture("pill-${window.name}-$theme-$tone", window, night, backdrop,
                pill(night, song[2], song[3]))
            assertInside(root, view)
            assertTrue("pill wider than its maximum: ${view.width}", view.width <= root.width * LyricsLayout.PILL_MAX_WIDTH_FRACTION + 1)
            // Left of the text, halfway down: the glass gradient is about halfway between its two ends.
            val drawn = bitmap.getPixel(view.left + 10, view.top + view.height / 2)
            val glass = LyricsTheme.colors(night)
            fun middle(shift: Int) = (((glass.glassTop ushr shift) and 0xFF) + ((glass.glassBottom ushr shift) and 0xFF)) / 2
            val expected = LyricsTheme.over((middle(24) shl 24) or (middle(16) shl 16) or (middle(8) shl 8) or middle(0), backdrop)
            for (shift in listOf(16, 8, 0)) {
                assertTrue("glass colour off at shift $shift: ${drawn.toUInt().toString(16)} vs ${expected.toUInt().toString(16)}",
                    Math.abs(((drawn ushr shift) and 0xFF) - ((expected ushr shift) and 0xFF)) <= 8)
            }
        }
    }

    @Test
    fun railInDayAndNightKeepsItsCardRule() {
        for (window in listOf(wide, narrow)) for (night in listOf(true, false)) for ((tone, backdrop) in listOf("white" to Color.WHITE, "black" to Color.BLACK)) {
            val theme = if (night) "night" else "day"
            val (root, view, _) = capture("rail-${window.name}-$theme-$tone", window, night, backdrop, rail(night, song, 3))
            assertInside(root, view)
            assertEquals((LyricsLayout.railHeightDp(window.heightDp.toFloat()) * root.resources.displayMetrics.density).toInt(), view.height)
            assertEquals((LyricsLayout.railWidthDp(window.widthDp.toFloat()) * root.resources.displayMetrics.density).toInt(), view.width)
        }
    }

    private fun truncated(row: android.widget.TextView) = row.layout.getEllipsisCount(row.lineCount - 1) > 0

    @Test
    fun regularScaleKeepsPillLyricsWholeAndWrapsSlimRailLyricsWithoutCutting() {
        for (night in listOf(true, false)) {
            val theme = if (night) "night" else "day"
            val (_, pillView, _) = capture("pill-wide-$theme-regular-scale", wide, night, Color.WHITE,
                pill(night, song[2], song[3]), fontScale = 1.0f)
            val current = (pillView as LyricsPillView).currentRow
            assertTrue("pill current line cut: ${current.text}", !truncated(current))
            val (_, railView, _) = capture("rail-wide-$theme-regular-scale", wide, night, Color.WHITE,
                rail(night, song, 3), fontScale = 1.0f)
            val centre = (railView as LyricsRailView).row(0)
            assertTrue("a longer lyric wraps within the slim rail", centre.lineCount in 2..3)
            assertTrue("a typical lyric is not cut: ${centre.text}", !truncated(centre))
            val (_, shortRail, _) = capture("rail-wide-$theme-short-regular-scale", wide, night, Color.WHITE,
                rail(night, listOf("One line at a time"), 0), fontScale = 1.0f)
            assertTrue("a short lyric still fits whole", !truncated((shortRail as LyricsRailView).row(0)))
        }
    }

    @Test
    fun anXpengSizedWindowKeepsThePillAndRailInProportion() {
        val xpeng = Window("xpeng", 2666, 1225)
        val (root, pillView, _) = capture("pill-xpeng-night", xpeng, true, Color.DKGRAY, pill(true, song[2], song[3]), fontScale = 1.0f)
        assertInside(root, pillView)
        assertTrue("pill wider than its maximum: ${pillView.width}", pillView.width <= root.width * LyricsLayout.PILL_MAX_WIDTH_FRACTION + 1)
        val current = (pillView as LyricsPillView).currentRow
        assertTrue("pill line cut: ${current.text}", !truncated(current))
        val (railRoot, railView, _) = capture("rail-xpeng-night", xpeng, true, Color.DKGRAY, rail(true, song, 3), fontScale = 1.0f)
        assertInside(railRoot, railView)
        assertEquals((LyricsLayout.railWidthDp(2666f) * railRoot.resources.displayMetrics.density).toInt(), railView.width)
        val centre = (railView as LyricsRailView).row(0)
        assertTrue("rail line cut: ${centre.text}", !truncated(centre))
    }

    @Test
    fun aLongCurrentLineWrapsToASecondRowInsteadOfBeingCut() {
        val long = "Today I drove through the suburbs and pictured I was driving home"
        val (root, pillView, _) = capture("pill-wrap", wide, true, Color.DKGRAY, pill(true, long, song[3]), fontScale = 1.0f)
        val pill = pillView as LyricsPillView
        assertInside(root, pillView)
        assertEquals("two rows", 2, pill.currentRow.lineCount)
        assertTrue("nothing cut: ${pill.currentRow.text}", !truncated(pill.currentRow))
        assertTrue("still within the width cap", pillView.width <= root.width * LyricsLayout.PILL_MAX_WIDTH_FRACTION + 1)
        assertEquals("the preview stays one row", 1, pill.nextRow.lineCount)
    }

    // Text is only measured for real here; the plain view tests have no glyph widths.
    @Test
    fun theGlassFollowsTheTextBetweenTheMinimumAndTheMaximumWidth() {
        fun width(line: String): Int = capture("pill-width", wide, true, Color.DKGRAY, pill(true, line, line), fontScale = 1.0f).second.width
        val short = width("Hi")
        val middle = width("Yeah, today I drove through")
        val long = width("Yeah, today I drove through the suburbs and pictured I was driving home to you")
        val root = capture("pill-width", wide, true, Color.DKGRAY, pill(true, "Hi", "Hi"), fontScale = 1.0f).first
        assertEquals("a short line sits on the minimum", root.width * LyricsLayout.PILL_MIN_WIDTH_FRACTION, short.toFloat(), 1f)
        assertTrue("a longer line widens it: $short -> $middle", middle > short)
        assertTrue("a very long line is widest: $middle -> $long", long > middle)
        assertTrue("and stops at the maximum: $long", long <= root.width * LyricsLayout.PILL_MAX_WIDTH_FRACTION + 1)
    }

    @Test
    fun aLineWrapsOnlyAfterItHasShrunkToTheMinimumScale() {
        val words = "Yeah today I drove through the suburbs and pictured I was driving home to you again".split(" ")
        var wrappedAt = -1
        var lastOneRowSize = 0f
        for (count in 3..words.size) {
            val line = words.take(count).joinToString(" ")
            val pill = capture("pill-flow", wide, true, Color.DKGRAY, pill(true, line, "next"), fontScale = 1.0f).second as LyricsPillView
            val base = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, LyricsLayout.PILL_CURRENT_SP, pill.resources.displayMetrics)
            if (pill.currentRow.lineCount == 2) {
                wrappedAt = count
                assertEquals("the last one-row line was shrunk to about the minimum", base * LyricsLayout.PILL_MIN_TEXT_SCALE, lastOneRowSize, base * 0.05f)
                break
            }
            lastOneRowSize = pill.currentRow.textSize
        }
        assertTrue("a long enough line wraps", wrappedAt > 0)
    }

    @Test
    fun aLineSlightlyTooLongShrinksToOneRowInsteadOfWrapping() {
        // About 10% over the width: shrinking to 90% fits it on one row, which reads better than two short rows.
        val line = "Yeah, today I drove through the suburbs, past the park"
        val (_, pillView, _) = capture("pill-shrink", wide, true, Color.DKGRAY, pill(true, line, song[3]), fontScale = 1.0f)
        val pill = pillView as LyricsPillView
        val base = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, LyricsLayout.PILL_CURRENT_SP, pill.resources.displayMetrics)
        if (pill.currentRow.lineCount == 1) assertTrue("shrunk", pill.currentRow.textSize <= base)
        assertTrue(!truncated(pill.currentRow))
    }

    @Test
    fun aLineBeyondTwoRowsEndsInAnEllipsisOnTheSecond() {
        val (_, pillView, _) = capture("pill-ellipsis", wide, true, Color.DKGRAY, pill(true, "word ".repeat(60), song[3]), fontScale = 1.0f)
        val pill = pillView as LyricsPillView
        assertEquals(2, pill.currentRow.lineCount)
        assertTrue("cut on the second row", truncated(pill.currentRow))
    }

    @Test
    fun narrowRailUsesFewerSlotsThanWide() {
        val (_, narrowRail, _) = capture("rail-lines-narrow", narrow, true, Color.BLACK, rail(true, song, 3))
        val (_, wideRail, _) = capture("rail-lines-wide", wide, true, Color.BLACK, rail(true, song, 3))
        assertEquals(2, (narrowRail as LyricsRailView).slotReach)
        assertEquals(3, (wideRail as LyricsRailView).slotReach)
    }

    @Test
    fun arabicCjkAndTwoHundredCharacterLinesStayInsideThePill() {
        val long = "x".repeat(200)
        for ((name, now, next) in listOf(
            Triple("arabic", "مرحبا بالعالم، هذه أغنية جميلة جدا", "وهذا هو السطر التالي من الأغنية"),
            Triple("cjk", "今天我开车穿过郊区，想象着开车回家见你", "我知道我们并不完美，但我从未有过这样的感觉"),
            Triple("long", long, long),
        )) {
            val (root, view, _) = capture("pill-wide-night-$name", wide, true, Color.WHITE, pill(true, now, next))
            assertInside(root, view)
            assertTrue("$name pill too wide: ${view.width}", view.width <= root.width * LyricsLayout.PILL_MAX_WIDTH_FRACTION + 1)
            val pill = view as LyricsPillView
            assertTrue("$name current line wraps to ${pill.currentRow.lineCount} lines", pill.currentRow.lineCount <= 2)
            assertTrue("$name next line wraps to ${pill.nextRow.lineCount} lines", pill.nextRow.lineCount <= 1)
        }
    }

    @Test
    fun railRightToLeftAndLongLinesStayInsideTheRail() {
        val arabic = listOf("السطر الأول من الأغنية", "السطر الثاني من الأغنية", "السطر الثالث من الأغنية الطويلة جدا جدا جدا", "السطر الرابع", "السطر الخامس")
        val (root, view, _) = capture("rail-wide-night-arabic", wide, true, Color.WHITE, rail(true, arabic, 2))
        assertInside(root, view)
        val long = listOf("a", "b", "x".repeat(200), "c", "d")
        val (root2, view2, _) = capture("rail-wide-night-long", wide, true, Color.WHITE, rail(true, long, 2))
        assertInside(root2, view2)
        val rail = view2 as LyricsRailView
        for (offset in -rail.slotReach..rail.slotReach) {
            val row = rail.row(offset)
            if (row.visibility == View.VISIBLE) assertTrue("row $offset wraps to ${row.lineCount} lines", row.lineCount <= 3)
        }
    }
}
