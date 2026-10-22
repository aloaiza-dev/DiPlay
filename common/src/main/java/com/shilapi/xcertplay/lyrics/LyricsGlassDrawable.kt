package com.shilapi.xcertplay.lyrics

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * Frosted glass for a shape that floats over a moving picture: a translucent vertical gradient, lighter at
 * the top, with a hairline rim that fades down the edge. Android cannot blur what is behind a view, so the
 * look comes from the layers, and the translucency keeps the road visible through it.
 */
internal class LyricsGlassDrawable : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shape = RectF()
    private var colors = LyricsTheme.colors(true)
    private var radius = 0f
    private var rimWidth = 1f
    private var shaderHeight = -1f

    fun update(colors: LyricsColors, radiusPx: Float, rimWidthPx: Float) {
        this.colors = colors
        radius = radiusPx
        rimWidth = rimWidthPx
        shaderHeight = -1f
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.isEmpty) return
        val half = rimWidth / 2f
        shape.set(bounds.left + half, bounds.top + half, bounds.right - half, bounds.bottom - half)
        if (shaderHeight != shape.height()) {
            shaderHeight = shape.height()
            fill.shader = LinearGradient(0f, shape.top, 0f, shape.bottom, colors.glassTop, colors.glassBottom, Shader.TileMode.CLAMP)
            rim.strokeWidth = rimWidth
            rim.shader = LinearGradient(0f, shape.top, 0f, shape.bottom, colors.rimTop, colors.rimBottom, Shader.TileMode.CLAMP)
        }
        val corner = minOf(radius, shape.height() / 2f, shape.width() / 2f)
        canvas.drawRoundRect(shape, corner, corner, fill)
        canvas.drawRoundRect(shape, corner, corner, rim)
    }

    // The view's elevation shadow follows this outline: a soft lift off the picture, none inside the glass.
    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, minOf(radius, bounds.height() / 2f, bounds.width() / 2f))
        outline.alpha = SHADOW_STRENGTH
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private companion object {
        const val SHADOW_STRENGTH = 0.55f
    }
}
