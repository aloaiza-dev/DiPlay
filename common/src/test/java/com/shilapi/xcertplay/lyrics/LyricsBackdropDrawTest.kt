package com.shilapi.xcertplay.lyrics

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LyricsBackdropDrawTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun render(root: FrameLayout): Bitmap {
        root.measure(View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1200, 400)
        return Bitmap.createBitmap(1200, 400, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
    }

    private fun pillInRoot(): Pair<FrameLayout, LyricsPillView> {
        val pill = LyricsPillView(context).apply {
            setWindowSizeDp(1200f / context.resources.displayMetrics.density, 400f / context.resources.displayMetrics.density)
            show("A line", "The next one", animate = false)
        }
        val root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        root.addView(pill, FrameLayout.LayoutParams(-2, -2, android.view.Gravity.CENTER))
        return root to pill
    }

    private fun solid(color: Int) = Bitmap.createBitmap(8, 2, Bitmap.Config.ARGB_8888).also { it.eraseColor(color) }

    @Test
    fun aBackdropBitmapIsDrawnAsTheGlass() {
        val (root, pill) = pillInRoot()
        render(root)
        pill.onBackdrop(solid(0xFFB03020.toInt()))
        val frame = render(root)
        // Left of the text, inside the glass: the backdrop colour shows, not the dark translucent fill.
        val pixel = frame.getPixel(pill.left + 12, pill.top + pill.height / 2)
        assertTrue("red ${Color.red(pixel)} green ${Color.green(pixel)}", Color.red(pixel) > Color.green(pixel) + 80)
    }

    @Test
    fun withoutABackdropTheGlassIsTheDarkTranslucentFill() {
        val (root, pill) = pillInRoot()
        render(root)
        pill.onBackdrop(solid(0xFFB03020.toInt()))
        render(root)
        pill.onBackdrop(null)
        val frame = render(root)
        val pixel = frame.getPixel(pill.left + 12, pill.top + pill.height / 2)
        assertTrue("dark again ${Color.red(pixel)}", Color.red(pixel) < 70)
    }

    @Test
    fun theBackdropIsClippedToTheRoundedShape() {
        val (root, pill) = pillInRoot()
        render(root)
        pill.onBackdrop(solid(0xFFFFFFFF.toInt()))
        val frame = render(root)
        val corner = frame.getPixel(pill.left + 1, pill.top + 1)
        assertTrue("the corner stays outside the glass: ${Integer.toHexString(corner)}", Color.red(corner) < 60)
    }

    @Test
    fun aViewReportsItsBoundsOnlyWhileShowing() {
        val (root, pill) = pillInRoot()
        render(root)
        val bounds = Rect()
        assertTrue(pill.backdropBounds(bounds))
        assertEquals(Rect(pill.left, pill.top, pill.right, pill.bottom), bounds)
        pill.hide()
        assertFalse(pill.backdropBounds(Rect()))
    }

    @Test
    fun aBackdropSurvivesTheGlassBeingRebuilt() {
        val (root, pill) = pillInRoot()
        render(root)
        pill.onBackdrop(solid(0xFFB03020.toInt()))
        pill.setNight(false) // rebuilds the background drawable
        val frame = render(root)
        val pixel = frame.getPixel(pill.left + 12, pill.top + pill.height / 2)
        assertTrue(Color.red(pixel) > Color.green(pixel) + 80)
    }
}
