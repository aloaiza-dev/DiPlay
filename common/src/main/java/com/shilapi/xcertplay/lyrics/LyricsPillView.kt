package com.shilapi.xcertplay.lyrics

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import kotlin.math.max
import kotlin.math.min

/** The current and the next lyric line in a rounded pill. It never takes touches. */
internal class LyricsPillView(context: Context) : LinearLayout(context) {
    private val density = context.resources.displayMetrics.density
    private val current = lyricsLine(context).apply { typeface = Typeface.DEFAULT_BOLD; maxLines = 1 }
    private val next = lyricsLine(context).apply { maxLines = 1 }
    private var colors = LyricsTheme.colors(true)
    private var windowWidthDp = 0f
    private var windowHeightDp = LyricsLayout.NARROW_HEIGHT_DP
    private var dimmed = false

    init {
        orientation = VERTICAL
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = GONE
        setPadding(dp(LyricsLayout.PILL_PADDING_H_DP), dp(LyricsLayout.PILL_PADDING_V_DP),
            dp(LyricsLayout.PILL_PADDING_H_DP), dp(LyricsLayout.PILL_PADDING_V_DP))
        // Full-width rows, so each line is centred inside the pill whatever its script.
        addView(current, LayoutParams(-1, -2))
        addView(next, LayoutParams(-1, -2))
        applyTheme()
        applySizes()
    }

    fun setNight(night: Boolean) {
        colors = LyricsTheme.colors(night)
        applyTheme()
    }

    fun setDimmed(dimmed: Boolean) {
        this.dimmed = dimmed
        alpha = targetAlpha()
    }

    fun setWindowSizeDp(widthDp: Float, heightDp: Float) {
        windowWidthDp = widthDp
        windowHeightDp = heightDp
        applySizes()
        requestLayout()
    }

    fun show(currentLine: String?, nextLine: String?) {
        val now = currentLine?.trim().orEmpty()
        val later = nextLine?.trim().orEmpty()
        if (now.isEmpty() && later.isEmpty()) {
            hide()
            return
        }
        if (visibility != VISIBLE) {
            animate().cancel()
            apply(now, later)
            visibility = VISIBLE
            alpha = targetAlpha()
            return
        }
        if (current.text.toString() == now && next.text.toString() == later) return
        if (lyricsReducedMotion(context)) {
            animate().cancel()
            apply(now, later)
            alpha = targetAlpha()
            return
        }
        animate().cancel()
        animate().alpha(0f).setDuration(FADE_HALF_MILLIS).withEndAction {
            apply(now, later)
            animate().alpha(targetAlpha()).setDuration(FADE_HALF_MILLIS)
        }
    }

    fun hide() {
        animate().cancel()
        visibility = GONE
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val limit = if (View.MeasureSpec.getMode(widthMeasureSpec) == View.MeasureSpec.UNSPECIFIED) Int.MAX_VALUE
        else View.MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(View.MeasureSpec.makeMeasureSpec(min(limit, maxWidthPx()), View.MeasureSpec.AT_MOST), heightMeasureSpec)
    }

    private fun apply(now: String, later: String) {
        current.text = now
        current.visibility = if (now.isEmpty()) GONE else VISIBLE
        next.text = later
        next.visibility = if (later.isEmpty()) GONE else VISIBLE
        fitCurrentLine()
    }

    // A long line shrinks to 80% of its size, then ellipsizes at the end.
    private fun fitCurrentLine() {
        val base = currentBasePx()
        val available = maxWidthPx() - paddingLeft - paddingRight
        val needed = Paint(current.paint).also { it.textSize = base }.measureText(current.text.toString())
        val size = if (available > 0 && needed > available) max(base * LyricsLayout.PILL_MIN_TEXT_SCALE, base * available / needed) else base
        current.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
    }

    private fun applyTheme() {
        background = lyricsBackground(colors, dp(LyricsLayout.PILL_RADIUS_DP).toFloat(), dp(LyricsLayout.EDGE_STROKE_DP))
        current.setTextColor(colors.current)
        next.setTextColor(colors.next)
    }

    private fun applySizes() {
        val scale = LyricsLayout.typeScale(windowHeightDp)
        next.setTextSize(TypedValue.COMPLEX_UNIT_SP, LyricsLayout.PILL_NEXT_SP * scale)
        fitCurrentLine()
    }

    private fun currentBasePx(): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, LyricsLayout.PILL_CURRENT_SP * LyricsLayout.typeScale(windowHeightDp), resources.displayMetrics)

    private fun maxWidthPx(): Int =
        if (windowWidthDp > 0f) (windowWidthDp * LyricsLayout.PILL_MAX_WIDTH_FRACTION * density).toInt() else Int.MAX_VALUE

    private fun targetAlpha() = if (dimmed) DIMMED_ALPHA else 1f

    private fun dp(value: Int) = (value * density).toInt()

    private companion object {
        const val FADE_HALF_MILLIS = 75L
        const val DIMMED_ALPHA = 0.6f
    }
}
