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
            pill.show(current, next)
            root.addView(pill, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = (LyricsLayout.PILL_TOP_MARGIN_DP * density).toInt()
            })
        }
    }

    private fun rail(night: Boolean, lines: List<String>, index: Int): (FrameLayout, Window) -> View = { root, window ->
        LyricsRailView(root.context).also { rail ->
            rail.setWindowSizeDp(window.widthDp.toFloat(), window.heightDp.toFloat())
            rail.setNight(night)
            rail.show(lines, index)
            root.addView(rail, FrameLayout.LayoutParams(rail.preferredWidthPx(), -1, Gravity.RIGHT))
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
            assertTrue("pill wider than 55%: ${view.width}", view.width <= root.width * LyricsLayout.PILL_MAX_WIDTH_FRACTION + 1)
            val drawn = bitmap.getPixel(view.left + view.paddingLeft / 2, view.top + view.height / 2)
            val expected = LyricsTheme.over(LyricsTheme.colors(night).scrim, backdrop)
            for (shift in listOf(16, 8, 0)) {
                assertTrue("scrim colour off at shift $shift: ${drawn.toUInt().toString(16)} vs ${expected.toUInt().toString(16)}",
                    Math.abs(((drawn ushr shift) and 0xFF) - ((expected ushr shift) and 0xFF)) <= 2)
            }
        }
    }

    @Test
    fun railInDayAndNightKeepsItsWidthRuleAndFillsTheHeight() {
        for (window in listOf(wide, narrow)) for (night in listOf(true, false)) for ((tone, backdrop) in listOf("white" to Color.WHITE, "black" to Color.BLACK)) {
            val theme = if (night) "night" else "day"
            val (root, view, _) = capture("rail-${window.name}-$theme-$tone", window, night, backdrop, rail(night, song, 3))
            assertInside(root, view)
            assertEquals(root.height, view.height)
            assertEquals((LyricsLayout.railWidthDp(window.widthDp.toFloat()) * root.resources.displayMetrics.density).toInt(), view.width)
        }
    }

    private fun truncated(row: android.widget.TextView) = row.layout.getEllipsisCount(row.lineCount - 1) > 0

    @Test
    fun aTypicalLineIsNotCutAtTheRegularFontScale() {
        for (night in listOf(true, false)) {
            val theme = if (night) "night" else "day"
            val (_, pillView, _) = capture("pill-wide-$theme-regular-scale", wide, night, Color.WHITE,
                pill(night, song[2], song[3]), fontScale = 1.0f)
            val current = (pillView as android.view.ViewGroup).getChildAt(0) as android.widget.TextView
            assertTrue("pill current line cut: ${current.text}", !truncated(current))
            val (_, railView, _) = capture("rail-wide-$theme-regular-scale", wide, night, Color.WHITE,
                rail(night, song, 3), fontScale = 1.0f)
            val centre = (railView as android.view.ViewGroup).getChildAt(2) as android.widget.TextView
            assertTrue("rail current line cut: ${centre.text}", !truncated(centre))
        }
    }

    @Test
    fun narrowRailShowsThreeLinesAndWideShowsFive() {
        val (_, narrowRail, _) = capture("rail-lines-narrow", narrow, true, Color.BLACK, rail(true, song, 3))
        val (_, wideRail, _) = capture("rail-lines-wide", wide, true, Color.BLACK, rail(true, song, 3))
        assertEquals(3, (narrowRail as android.view.ViewGroup).childCount)
        assertEquals(5, (wideRail as android.view.ViewGroup).childCount)
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
            val rows = (view as android.view.ViewGroup)
            for (index in 0 until rows.childCount) {
                val row = rows.getChildAt(index) as android.widget.TextView
                assertTrue("$name row $index wraps to ${row.lineCount} lines", row.lineCount <= 1)
            }
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
        val rows = view2 as android.view.ViewGroup
        for (index in listOf(0, 1, 3, 4)) assertTrue((rows.getChildAt(index) as android.widget.TextView).lineCount <= 1)
        assertTrue((rows.getChildAt(2) as android.widget.TextView).lineCount <= 2)
    }
}
