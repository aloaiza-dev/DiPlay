package com.shilapi.xcertplay.lyrics

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
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
        // Wrap-content rows centred by gravity. A match-parent row would not ask for a new layout when its
        // text changes at the same height, so the pill would keep the width of the previous line.
        addView(current, LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        addView(next, LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
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
        if (widthDp == windowWidthDp && heightDp == windowHeightDp) return // the host calls this from a layout pass
        windowWidthDp = widthDp
        windowHeightDp = heightDp
        applySizes()
        requestLayout()
    }

    /** [animate] false changes the line at once, for a seek. */
    fun show(currentLine: String?, nextLine: String?, animate: Boolean = true) {
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
        if (!animate || lyricsReducedMotion(context)) {
            animate().cancel()
            apply(now, later)
            alpha = targetAlpha()
            return
        }
        // The text changes at once; only the opacity dips and recovers, so a cancelled animation cannot hold an old line.
        animate().cancel()
        apply(now, later)
        alpha = DIP_ALPHA
        animate().alpha(targetAlpha()).setDuration(RECOVER_MILLIS)
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

    private fun typeScale() = LyricsLayout.typeScale(windowHeightDp) * LyricsLayout.widthScale(windowWidthDp)

    private fun applySizes() {
        val scale = typeScale()
        next.setTextSize(TypedValue.COMPLEX_UNIT_SP, LyricsLayout.PILL_NEXT_SP * scale)
        fitCurrentLine()
    }

    private fun currentBasePx(): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, LyricsLayout.PILL_CURRENT_SP * typeScale(), resources.displayMetrics)

    private fun maxWidthPx(): Int =
        if (windowWidthDp > 0f) (windowWidthDp * LyricsLayout.PILL_MAX_WIDTH_FRACTION * density).toInt() else Int.MAX_VALUE

    private fun targetAlpha() = if (dimmed) DIMMED_ALPHA else 1f

    private fun dp(value: Int) = (value * density).toInt()

    private companion object {
        const val RECOVER_MILLIS = 150L
        const val DIP_ALPHA = 0.35f
        const val DIMMED_ALPHA = 0.6f
    }
}
