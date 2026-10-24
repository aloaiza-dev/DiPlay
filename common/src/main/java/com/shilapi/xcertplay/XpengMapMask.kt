package com.shilapi.xcertplay

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.widget.FrameLayout

/** Full OEM-size canvas that cuts the map out with the instrument app's own center-mask shape.
 * The OEM `MaskFullView` (map mode, 1920x720) tints these shapes over its map. Here the same shapes
 * are removed from the video with DST_OUT, so the theme background shows through and no day/night
 * color is needed. Shapes overlap by union, as the OEM tint does.
 * A hardware-accelerated window is required for the child TextureView.
 */
internal class XpengMapMask(context: Context) : FrameLayout(context) {
    private val cut = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }
    private val topBlur = BlurMaskFilter(TOP_BLUR, BlurMaskFilter.Blur.NORMAL)
    private val topPath = Path().apply {
        moveTo(-150f, -150f)
        lineTo(-150f, TOP_EDGE)
        cubicTo(100f, TOP_EDGE - 40f, 560f, TOP_EDGE - 40f, 660f, TOP_EDGE)
        lineTo(1260f, TOP_EDGE)
        cubicTo(1360f, TOP_EDGE - 40f, 1820f, TOP_EDGE - 40f, 2070f, TOP_EDGE)
        lineTo(2070f, -150f)
        close()
    }
    private val sideColors = intArrayOf(OPAQUE, OPAQUE, CLEAR)
    private val sideStops = floatArrayOf(0f, 0.8f, 1f)
    private val topShader = LinearGradient(0f, 0f, 0f, TOP_GRADIENT, intArrayOf(OPAQUE, 0xE6000000.toInt()),
        floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
    private val bottomShader = LinearGradient(0f, CANVAS_HEIGHT, 0f, CANVAS_HEIGHT - BOTTOM_FADE,
        intArrayOf(OPAQUE, 0xCC000000.toInt(), CLEAR), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    private val leftShader = RadialGradient(0f, SIDE_CENTER_Y, SIDE_RADIUS, sideColors, sideStops, Shader.TileMode.CLAMP)
    private val rightShader = RadialGradient(CANVAS_WIDTH, SIDE_CENTER_Y, SIDE_RADIUS, sideColors, sideStops, Shader.TileMode.CLAMP)

    override fun dispatchDraw(canvas: Canvas) {
        // One isolated GPU layer: DST_OUT must affect the map, never the instrument underneath.
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        super.dispatchDraw(canvas)
        cut.shader = topShader
        cut.maskFilter = topBlur
        canvas.drawPath(topPath, cut)
        cut.maskFilter = null
        cut.shader = bottomShader
        canvas.drawRect(0f, CANVAS_HEIGHT - BOTTOM_FADE, CANVAS_WIDTH, CANVAS_HEIGHT, cut)
        cut.shader = leftShader
        canvas.drawCircle(0f, SIDE_CENTER_Y, SIDE_RADIUS, cut)
        cut.shader = rightShader
        canvas.drawCircle(CANVAS_WIDTH, SIDE_CENTER_Y, SIDE_RADIUS, cut)
        canvas.restoreToCount(layer)
    }

    companion object {
        const val CANVAS_WIDTH = 1920f
        const val CANVAS_HEIGHT = 720f
        // Values read from the instrument app's decompiled MaskFullView / MaskLeftAndRightView
        // for the non-F30 (720px) layout. Not measured on the car yet.
        private const val SIDE_RADIUS = 690f
        private const val SIDE_CENTER_Y = 280f
        private const val TOP_EDGE = 112f
        private const val TOP_BLUR = 40f
        private const val TOP_GRADIENT = 174f
        private const val BOTTOM_FADE = 85f
        private const val OPAQUE = Color.BLACK
        private const val CLEAR = 0x00000000

        // Centered rectangle where the OEM tint is below 2% alpha: the map is fully visible here.
        const val CLEAR_LEFT = 690f
        const val CLEAR_TOP = 160f
        const val CLEAR_BOTTOM = 635f
    }
}
