package com.shilapi.xcertplay.lyrics

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/** Several lyric lines with the current one kept in the vertical centre. It never takes touches. */
internal class LyricsRailView(context: Context) : LinearLayout(context) {
    private val density = context.resources.displayMetrics.density
    private var colors = LyricsTheme.colors(true)
    private var windowWidthDp = DEFAULT_WINDOW_WIDTH_DP
    private var windowHeightDp = DEFAULT_WINDOW_HEIGHT_DP
    private var dimmed = false
    private var lines: List<TextView> = emptyList()
    private var shownIndex = Int.MIN_VALUE

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = GONE
        setPadding(dp(LyricsLayout.RAIL_PADDING_H_DP), 0, dp(LyricsLayout.RAIL_PADDING_H_DP), 0)
        rebuildLines()
        applyTheme()
    }

    fun preferredWidthPx(): Int = (LyricsLayout.railWidthDp(windowWidthDp) * density).toInt()

    fun setNight(night: Boolean) {
        colors = LyricsTheme.colors(night)
        applyTheme()
    }

    fun setDimmed(dimmed: Boolean) {
        this.dimmed = dimmed
        alpha = if (dimmed) DIMMED_ALPHA else 1f
    }

    fun setWindowSizeDp(widthDp: Float, heightDp: Float) {
        if (widthDp == windowWidthDp && heightDp == windowHeightDp) return // the host calls this from a layout pass
        windowWidthDp = widthDp
        windowHeightDp = heightDp
        if (lines.size != LyricsLayout.railVisibleLines(heightDp)) rebuildLines() else applyStyles()
        requestLayout()
    }

    /** [currentIndex] is the playing line in [allLines]; -1 before the first line starts. */
    fun show(allLines: List<String>, currentIndex: Int) {
        if (allLines.isEmpty()) {
            hide()
            return
        }
        val centre = lines.size / 2
        lines.forEachIndexed { slot, view ->
            view.text = allLines.getOrNull(currentIndex + slot - centre).orEmpty()
        }
        val wasVisible = visibility == VISIBLE
        visibility = VISIBLE
        if (wasVisible && currentIndex == shownIndex + 1 && !lyricsReducedMotion(context)) scrollUpOneLine()
        shownIndex = currentIndex
    }

    fun hide() {
        animate().cancel()
        translationY = 0f
        visibility = GONE
        shownIndex = Int.MIN_VALUE
    }

    private fun scrollUpOneLine() {
        animate().cancel()
        translationY = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, LyricsLayout.RAIL_OTHER_SP * typeScale(), resources.displayMetrics,
        ) * LyricsLayout.LINE_SPACING_MULTIPLIER
        animate().translationY(0f).setDuration(SCROLL_MILLIS).setInterpolator(DecelerateInterpolator())
    }

    private fun rebuildLines() {
        removeAllViews()
        lines = List(LyricsLayout.railVisibleLines(windowHeightDp)) { lyricsLine(context) }
        lines.forEach { addView(it, LayoutParams(-1, -2)) }
        applyStyles()
    }

    private fun applyTheme() {
        background = lyricsBackground(colors, 0f, dp(LyricsLayout.EDGE_STROKE_DP))
        applyStyles()
    }

    private fun typeScale() = LyricsLayout.typeScale(windowHeightDp) * LyricsLayout.widthScale(windowWidthDp)

    private fun applyStyles() {
        val centre = lines.size / 2
        val scale = typeScale()
        lines.forEachIndexed { slot, view ->
            val distance = abs(slot - centre)
            val sp = when (distance) {
                0 -> LyricsLayout.RAIL_CURRENT_SP
                1 -> LyricsLayout.RAIL_OTHER_SP
                else -> LyricsLayout.RAIL_FAR_SP
            }
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * scale)
            view.typeface = if (distance == 0) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            view.maxLines = if (distance == 0) 2 else 1
            view.setTextColor(when (slot - centre) {
                0 -> colors.current
                1 -> colors.next
                else -> colors.far
            })
        }
    }

    private fun dp(value: Int) = (value * density).toInt()

    private companion object {
        const val DEFAULT_WINDOW_WIDTH_DP = 1000f
        const val DEFAULT_WINDOW_HEIGHT_DP = 480f
        const val SCROLL_MILLIS = 250L
        const val DIMMED_ALPHA = 0.6f
    }
}
