package com.shilapi.xcertplay.lyrics

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Typeface
import android.text.Layout
import android.util.TypedValue
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.TextView

/**
 * Lyrics as a wheel on a floating glass card. Every line has one weight; size and opacity say how far a line
 * is from the current one ([LyricsMotion.railScale], [LyricsMotion.railAlpha]), and the current line sits at
 * the card's vertical centre. When the song moves to its next line every row eases from the pose of the
 * line it shows to the pose of the slot it moves into. It never takes touches.
 */
internal class LyricsRailView(context: Context) : ViewGroup(context) {
    private enum class Motion { NONE, ENTER, ROLL, DIP, EXIT }

    private data class Pose(val centerY: Float, val scale: Float, val alpha: Float)

    private val density = context.resources.displayMetrics.density
    private var colors = LyricsTheme.colors(true)
    private var windowWidthDp = DEFAULT_WINDOW_WIDTH_DP
    private var windowHeightDp = DEFAULT_WINDOW_HEIGHT_DP
    private var dimmed = false
    private var lines: List<String> = emptyList()
    private var index = 0
    private var reach = 0
    private var rows: List<TextView> = emptyList() // the row for slot k is rows[k + reach]
    private var motion = Motion.NONE
    private var progress = 1f
    private var rollFrom: Map<Int, Pose> = emptyMap()
    private var animator: ValueAnimator? = null

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = GONE
        clipChildren = true
        rebuildRows()
        applyTheme()
    }

    fun preferredWidthPx(): Int = (LyricsLayout.railWidthDp(windowWidthDp) * density).toInt()

    fun preferredHeightPx(): Int = (LyricsLayout.railHeightDp(windowHeightDp) * density).toInt()

    fun edgeMarginPx(): Int = (LyricsLayout.railEdgeMarginDp(windowHeightDp) * density).toInt()

    /** The row that shows the line [offset] lines from the current one; for tests and design sheets. */
    internal fun row(offset: Int): TextView = rows[offset + reach]

    internal val slotReach: Int get() = reach

    fun setNight(night: Boolean) {
        colors = LyricsTheme.colors(night)
        applyTheme()
    }

    fun setDimmed(dimmed: Boolean) {
        this.dimmed = dimmed
        applyPose()
    }

    fun setWindowSizeDp(widthDp: Float, heightDp: Float) {
        if (widthDp == windowWidthDp && heightDp == windowHeightDp) return // the host calls this from a layout pass
        windowWidthDp = widthDp
        windowHeightDp = heightDp
        if (rows.size != 2 * slotReachFor(heightDp) + 1) rebuildRows() else applySizes()
        requestLayout()
    }

    /** [currentIndex] is the playing line in [allLines]; -1 before the first line starts. */
    fun show(allLines: List<String>, currentIndex: Int, animate: Boolean = true) {
        if (allLines.isEmpty()) {
            hide()
            return
        }
        val shown = visibility == VISIBLE && motion != Motion.EXIT
        if (shown && allLines == lines && currentIndex == index) return
        val moving = animate && !lyricsReducedMotion(context)
        if (!shown) {
            settle()
            assign(allLines, currentIndex)
            visibility = VISIBLE
            if (moving) run(Motion.ENTER, LyricsMotion.ENTER_MILLIS) else applyPose()
            return
        }
        if (!moving) {
            settle()
            assign(allLines, currentIndex)
            return
        }
        val advancing = allLines == lines && currentIndex == index + 1
        val before = restPoses()
        assign(allLines, currentIndex)
        if (advancing && before.isNotEmpty()) {
            run(Motion.ROLL, LyricsMotion.ROLL_MILLIS, before)
        } else {
            run(Motion.DIP, LyricsMotion.DIP_MILLIS)
        }
    }

    fun hide() {
        settle()
        visibility = GONE
    }

    /** Fades out, then hides. Nothing happens when it is already hidden or on its way out. */
    fun fadeOut() {
        if (visibility != VISIBLE || motion == Motion.EXIT) return
        if (lyricsReducedMotion(context)) hide() else run(Motion.EXIT, LyricsMotion.EXIT_MILLIS)
    }

    /** One frame of the roll to the next line at eased progress [e], without an animator: for design sheets. */
    internal fun freezeRollFrame(e: Float) {
        val before = restPoses()
        settle()
        assign(lines, index + 1)
        rollFrom = before
        motion = Motion.ROLL
        progress = e
        requestLayout()
        applyPose()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = resolveSize(preferredWidthPx(), widthMeasureSpec)
        val height = resolveSize(preferredHeightPx(), heightMeasureSpec)
        val inner = (width - 2 * padH()).toInt().coerceAtLeast(0)
        for (row in rows) {
            if (row.visibility != GONE) row.measure(MeasureSpec.makeMeasureSpec(inner, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val poses = restPoses()
        for (slot in -reach..reach) {
            val row = row(slot)
            if (row.visibility == GONE) continue
            val centre = poses[slot]?.centerY ?: continue
            val left = (width - row.measuredWidth) / 2
            val top = (centre - row.measuredHeight / 2f).toInt()
            row.layout(left, top, left + row.measuredWidth, top + row.measuredHeight)
        }
        applyPose()
    }

    /** Where each slot's line rests: the current line at the card's vertical centre, the others stacked outward. */
    private fun restPoses(): Map<Int, Pose> {
        if (height <= 0 || rows.isEmpty()) return emptyMap()
        val gap = LyricsLayout.RAIL_ROW_GAP_EM * textPx()
        fun scaledHeight(slot: Int): Float = row(slot).let { if (it.visibility == GONE) 0f else it.measuredHeight * LyricsMotion.railScale(slot) }
        val centres = HashMap<Int, Float>()
        centres[0] = height / 2f
        for (slot in 1..reach) {
            val spacing = if (scaledHeight(slot) > 0f && scaledHeight(slot - 1) > 0f) gap else 0f
            centres[slot] = centres.getValue(slot - 1) + scaledHeight(slot - 1) / 2f + spacing + scaledHeight(slot) / 2f
        }
        for (slot in -1 downTo -reach) {
            val spacing = if (scaledHeight(slot) > 0f && scaledHeight(slot + 1) > 0f) gap else 0f
            centres[slot] = centres.getValue(slot + 1) - scaledHeight(slot + 1) / 2f - spacing - scaledHeight(slot) / 2f
        }
        return centres.mapValues { (slot, centre) -> Pose(centre, LyricsMotion.railScale(slot), LyricsMotion.railAlpha(slot)) }
    }

    private fun assign(allLines: List<String>, currentIndex: Int) {
        lines = allLines
        index = currentIndex
        for (slot in -reach..reach) {
            val row = row(slot)
            val text = allLines.getOrNull(currentIndex + slot).orEmpty()
            row.text = text
            row.visibility = if (text.isEmpty()) GONE else VISIBLE
        }
        requestLayout()
    }

    private fun run(mode: Motion, millis: Long, from: Map<Int, Pose> = emptyMap()) {
        settle()
        rollFrom = from
        motion = mode
        progress = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = millis
            interpolator = PathInterpolator(LyricsMotion.EASE_OUT[0], LyricsMotion.EASE_OUT[1], LyricsMotion.EASE_OUT[2], LyricsMotion.EASE_OUT[3])
            addUpdateListener {
                progress = it.animatedValue as Float
                applyPose()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animator === animation) settle()
                }
            })
            start()
        }
        applyPose()
    }

    /** Ends any running motion at its end state. */
    private fun settle() {
        animator?.let {
            it.removeAllListeners()
            it.removeAllUpdateListeners()
            it.cancel()
        }
        animator = null
        if (motion == Motion.EXIT) visibility = GONE
        motion = Motion.NONE
        progress = 1f
        rollFrom = emptyMap()
        applyPose()
    }

    private fun applyPose() {
        val rest = restPoses()
        var wholeAlpha = if (dimmed) DIMMED_ALPHA else 1f
        var wholeShift = 0f
        for (slot in -reach..reach) {
            val row = row(slot)
            val target = rest[slot] ?: Pose(row.top + row.height / 2f, LyricsMotion.railScale(slot), LyricsMotion.railAlpha(slot))
            var shift = 0f
            var scale = target.scale
            var alpha = target.alpha
            when (motion) {
                Motion.ROLL -> {
                    val from = rollFrom[slot + 1] ?: enteringPose(target, rollFrom)
                    shift = (1f - progress) * (from.centerY - target.centerY)
                    scale = lerp(from.scale, target.scale, progress)
                    alpha = lerp(from.alpha, target.alpha, progress)
                }
                Motion.DIP -> alpha = target.alpha * lerp(LyricsMotion.DIP_ALPHA, 1f, progress)
                else -> Unit
            }
            row.translationY = shift
            row.scaleX = scale
            row.scaleY = scale
            row.alpha = alpha
        }
        when (motion) {
            Motion.ENTER -> {
                wholeAlpha *= progress
                wholeShift = (1f - progress) * ENTER_RISE_EM * textPx()
            }
            Motion.EXIT -> {
                wholeAlpha *= 1f - progress
                wholeShift = -progress * EXIT_RISE_EM * textPx()
            }
            else -> Unit
        }
        alpha = wholeAlpha
        translationY = wholeShift
    }

    // A line that was not on the wheel yet enters from just beyond its last slot, unseen.
    private fun enteringPose(target: Pose, from: Map<Int, Pose>): Pose {
        val last = from[reach]
        val before = from[reach - 1]
        val centre = if (last != null && before != null) last.centerY + (last.centerY - before.centerY) else target.centerY + textPx()
        return Pose(centre, target.scale, 0f)
    }

    private fun rebuildRows() {
        removeAllViews()
        reach = slotReachFor(windowHeightDp)
        rows = List(2 * reach + 1) {
            lyricsLine(context).apply { typeface = Typeface.DEFAULT_BOLD; maxLines = 2; breakStrategy = Layout.BREAK_STRATEGY_BALANCED }
        }
        rows.forEach { addView(it) }
        applySizes()
        applyTheme()
        if (lines.isNotEmpty()) assign(lines, index)
    }

    private fun applyTheme() {
        rows.forEach { it.setTextColor(colors.current) }
        applyShape()
    }

    private fun applySizes() {
        rows.forEach { it.setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx()) }
        applyShape()
    }

    private fun applyShape() {
        background = lyricsGlass(colors, LyricsLayout.RAIL_RADIUS_EM * textPx(), LyricsLayout.RIM_DP * density)
        elevation = LyricsLayout.RAIL_ELEVATION_EM * textPx()
    }

    private fun slotReachFor(heightDp: Float) = LyricsLayout.railVisibleLines(heightDp) / 2 + 1

    private fun textPx(): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        LyricsLayout.RAIL_TEXT_SP * LyricsLayout.typeScale(windowHeightDp) * LyricsLayout.widthScale(windowWidthDp),
        resources.displayMetrics,
    )

    private fun padH() = LyricsLayout.RAIL_PADDING_H_EM * textPx()

    private fun lerp(from: Float, to: Float, t: Float) = from + (to - from) * t

    private companion object {
        const val DEFAULT_WINDOW_WIDTH_DP = 1000f
        const val DEFAULT_WINDOW_HEIGHT_DP = 480f
        const val DIMMED_ALPHA = 0.6f
        const val ENTER_RISE_EM = 0.3f
        const val EXIT_RISE_EM = 0.15f
    }
}
