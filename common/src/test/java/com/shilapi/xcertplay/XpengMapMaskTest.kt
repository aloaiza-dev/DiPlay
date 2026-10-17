package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class XpengMapMaskTest {
    @Test fun mapFillsTheMiddleButCannotPaintSideCardsOrStatusRows() {
        val app = RuntimeEnvironment.getApplication()
        val root = XpengMapMask(app)
        root.addView(View(app).apply { setBackgroundColor(Color.GREEN) },
            FrameLayout.LayoutParams(-1, -1))
        root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1920, 720)
        val bitmap = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888)
        try {
            root.draw(Canvas(bitmap))
            fun alpha(x: Int, y: Int) = Color.alpha(bitmap.getPixel(x, y))
            // Values follow the OEM tint shapes (see XpengMapMask): clear rectangle, fades, solid sides.
            assertEquals(255, alpha(960, 360))
            assertTrue(alpha(XpengMapMask.CLEAR_LEFT.toInt() + 10, 360) >= 250)
            assertTrue(alpha(1920 - XpengMapMask.CLEAR_LEFT.toInt() - 10, 360) >= 250)
            assertTrue(alpha(960, XpengMapMask.CLEAR_TOP.toInt() + 5) >= 250)
            assertTrue(alpha(960, XpengMapMask.CLEAR_BOTTOM.toInt() - 5) >= 250)
            assertTrue(alpha(600, 360) in 60..200)
            assertTrue(alpha(1320, 360) in 60..200)
            assertTrue(alpha(960, 112) in 90..160)
            for (y in 0 until 720 step 20) {
                assertEquals("left card at $y", 0, alpha(300, y))
                assertEquals("right card at $y", 0, alpha(1620, y))
            }
            for (x in 600 until 1320 step 20) {
                assertTrue("top status at $x", alpha(x, 40) <= 10)
                assertTrue("bottom edge at $x", alpha(x, 710) <= 30)
            }
            // Alpha, rather than theme-colored paint: neither black nor white is added to the map.
            assertEquals(Color.GREEN, bitmap.getPixel(960, 360))
            val directory = File("build/reports/xpeng-map-mask").apply { mkdirs() }
            File(directory, "alpha.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            for ((name, background) in listOf("day" to Color.rgb(223, 233, 245),
                "night" to Color.rgb(9, 11, 15))) {
                val composite = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888)
                try {
                    Canvas(composite).apply { drawColor(background); drawBitmap(bitmap, 0f, 0f, null) }
                    assertEquals(background, composite.getPixel(300, 360))
                    assertEquals(Color.GREEN, composite.getPixel(960, 360))
                    assertNotEquals(background, composite.getPixel(600, 360))
                    assertNotEquals(Color.GREEN, composite.getPixel(600, 360))
                    File(directory, "$name-fixture.png").outputStream().use {
                        assertTrue(composite.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally { composite.recycle() }
            }
        } finally {
            bitmap.recycle()
        }
    }
}
