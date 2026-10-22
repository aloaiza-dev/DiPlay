package com.shilapi.xcertplay.lyrics

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Design sheets: the overlay drawn at the car's real size and density over a real screenshot of its screen,
 * so the look can be judged without a car. Local review tool. Pictures go to `build/reports/lyrics-design`.
 * A backdrop is read from `LYRICS_BACKDROP`, else `.private/design/car-backdrop-night.png`, else a drawn one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w2666dp-h1333dp-144dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LyricsDesignSheetTest {
    private val widthPx = 2400
    private val heightPx = 1200

    private fun backdrop(night: Boolean): Bitmap {
        val file = System.getenv("LYRICS_BACKDROP")?.takeIf { night }?.let(::File)
            ?: File("../.private/design/car-backdrop-${if (night) "night" else "day"}.png")
        if (file.isFile) BitmapFactory.decodeFile(file.path)?.let { return Bitmap.createScaledBitmap(it, widthPx, heightPx, true) }
        return Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888).also { bitmap ->
            val paint = Paint().apply {
                shader = if (night) LinearGradient(0f, 0f, widthPx.toFloat(), heightPx.toFloat(), 0xFF3A3640.toInt(), 0xFF1B1A20.toInt(), Shader.TileMode.CLAMP)
                else LinearGradient(0f, 0f, widthPx.toFloat(), heightPx.toFloat(), 0xFFE9E4DA.toInt(), 0xFFB9C3D6.toInt(), Shader.TileMode.CLAMP)
            }
            Canvas(bitmap).drawRect(0f, 0f, widthPx.toFloat(), heightPx.toFloat(), paint)
        }
    }

    private fun sheet(name: String, night: Boolean, build: (FrameLayout) -> Unit): Bitmap {
        val app = RuntimeEnvironment.getApplication()
        val root = FrameLayout(app)
        root.background = android.graphics.drawable.BitmapDrawable(app.resources, backdrop(night))
        build(root)
        root.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, widthPx, heightPx)
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val folder = File("build/reports/lyrics-design").apply { mkdirs() }
        FileOutputStream(File(folder, "$name.png")).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        // A 2x crop of the top centre, where the pill sits.
        val crop = Bitmap.createBitmap(bitmap, 600, 0, 1200, 200)
        FileOutputStream(File(folder, "$name-zoom.png")).use { Bitmap.createScaledBitmap(crop, 2400, 400, true).compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    private fun density() = RuntimeEnvironment.getApplication().resources.displayMetrics.density

    private fun addPill(root: FrameLayout, night: Boolean, current: String, next: String?) {
        val pill = LyricsPillView(root.context)
        pill.setWindowSizeDp(widthPx / density(), heightPx / density())
        pill.setNight(night)
        pill.show(current, next, animate = false)
        root.addView(pill, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = (LyricsLayout.PILL_TOP_MARGIN_DP * density()).toInt()
        })
    }

    private fun addRail(root: FrameLayout, night: Boolean, lines: List<String>, index: Int) {
        val rail = LyricsRailView(root.context)
        rail.setWindowSizeDp(widthPx / density(), heightPx / density())
        rail.setNight(night)
        rail.show(lines, index, animate = false)
        root.addView(rail, FrameLayout.LayoutParams(rail.preferredWidthPx(), rail.preferredHeightPx(), Gravity.RIGHT or Gravity.CENTER_VERTICAL).apply {
            rightMargin = rail.edgeMarginPx()
        })
    }

    private val lines = listOf(
        "Me dicen el Matador, nací en Barracas", "Si hablamos de matar, mis palabras matan",
        "No hace mucho tiempo que cayó el León Santillán", "Y ahora sé que en cualquier momento me la van a dar",
        "¿Dónde estás, matador?", "Me dicen el Matador, me están buscando",
    )

    /** The roll at several moments, top centre of the screen, stacked: read it from the top down. */
    private fun filmstrip(name: String, night: Boolean) {
        val moments = listOf(0f, 0.08f, 0.18f, 0.3f, 0.45f, 0.65f, 1f)
        val strip = Bitmap.createBitmap(1500, 190 * moments.size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(strip)
        val app = RuntimeEnvironment.getApplication()
        moments.forEachIndexed { index, e ->
            val root = FrameLayout(app)
            root.background = android.graphics.drawable.BitmapDrawable(app.resources, backdrop(night))
            val pill = LyricsPillView(app).apply {
                setWindowSizeDp(widthPx / density(), heightPx / density())
                setNight(night)
                show(lines[2], lines[3], animate = false)
            }
            root.addView(pill, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = (LyricsLayout.PILL_TOP_MARGIN_DP * density()).toInt()
            })
            fun layoutRoot() {
                root.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, widthPx, heightPx)
            }
            layoutRoot()
            pill.freezeRollFrame(lines[4], e)
            layoutRoot()
            val frame = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(frame))
            canvas.drawBitmap(Bitmap.createBitmap(frame, 450, 0, 1500, 190), 0f, (190 * index).toFloat(), null)
        }
        val folder = File("build/reports/lyrics-design").apply { mkdirs() }
        FileOutputStream(File(folder, "$name.png")).use { assertTrue(strip.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    /** The rail's roll to the next line at six moments, in a grid of two columns: read left to right, top down. */
    private fun railFilmstrip(name: String, night: Boolean) {
        val moments = listOf(0f, 0.12f, 0.25f, 0.4f, 0.65f, 1f)
        val cell = 540
        val grid = Bitmap.createBitmap(cell * 3, cell * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(grid)
        val app = RuntimeEnvironment.getApplication()
        moments.forEachIndexed { index, e ->
            val root = FrameLayout(app)
            root.background = android.graphics.drawable.BitmapDrawable(app.resources, backdrop(night))
            val rail = LyricsRailView(app).apply {
                setWindowSizeDp(widthPx / density(), heightPx / density())
                setNight(night)
                show(lines, 2, animate = false)
            }
            root.addView(rail, FrameLayout.LayoutParams(rail.preferredWidthPx(), rail.preferredHeightPx(), Gravity.RIGHT or Gravity.CENTER_VERTICAL).apply {
                rightMargin = rail.edgeMarginPx()
            })
            fun layoutRoot() {
                root.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, widthPx, heightPx)
            }
            layoutRoot()
            rail.freezeRollFrame(e)
            layoutRoot()
            val frame = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(frame))
            val crop = Bitmap.createBitmap(frame, 1500, 150, 900, 900)
            canvas.drawBitmap(Bitmap.createScaledBitmap(crop, cell, cell, true), (cell * (index % 3)).toFloat(), (cell * (index / 3)).toFloat(), null)
        }
        val folder = File("build/reports/lyrics-design").apply { mkdirs() }
        FileOutputStream(File(folder, "$name.png")).use { assertTrue(grid.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    @Test
    fun rollFilmstrips() {
        filmstrip("roll-night", true)
        filmstrip("roll-day", false)
        railFilmstrip("rail-roll-night", true)
    }

    @Test
    fun densityIsTheCars() {
        assertTrue("density ${density()}", density() in 0.88f..0.92f)
    }

    @Test
    fun pillNightAndDay() {
        sheet("pill-night", true) { addPill(it, true, lines[2], lines[3]) }
        sheet("pill-day", false) { addPill(it, false, lines[2], lines[3]) }
    }

    @Test
    fun railNightAndDay() {
        sheet("rail-night", true) { addRail(it, true, lines, 2) }
        sheet("rail-day", false) { addRail(it, false, lines, 2) }
    }

    @Test
    fun pillLongLine() {
        sheet("pill-long", true) {
            addPill(it, true, "Yeah, today I drove through the suburbs and pictured I was driving home to you", "And I know we weren’t perfect but I’ve never felt this way for no one")
        }
    }
}
