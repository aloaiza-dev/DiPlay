package com.shilapi.xcertplay.lyrics

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/**
 * The current and the next lyric line on frosted glass. It never takes touches.
 *
 * Four text roles share one layout. The two real rows hold the lines now shown. During a roll, two ghost
 * rows carry the lines that were shown: the old current line leaves upward and the old next line rises into
 * the current slot as the new current line fades in over it (see [LyricsMotion]). The glass follows the
 * size of the text, so its width and height ease from one line to the next.
 */
internal class LyricsPillView(context: Context) : ViewGroup(context) {
    private enum class Motion { NONE, ENTER, ROLL, DIP, EXIT }

    private val density = context.resources.displayMetrics.density
    // The current line may take two balanced rows; the preview line stays on one.
    private val outgoing = lyricsLine(context).apply { typeface = Typeface.DEFAULT_BOLD; maxLines = 2; breakStrategy = Layout.BREAK_STRATEGY_BALANCED; visibility = GONE }
    private val morph = lyricsLine(context).apply { maxLines = 1; visibility = GONE }
    private val current = lyricsLine(context).apply { typeface = Typeface.DEFAULT_BOLD; maxLines = 2; breakStrategy = Layout.BREAK_STRATEGY_BALANCED }
    private val next = lyricsLine(context).apply { maxLines = 1 }
    private var colors = LyricsTheme.colors(true)
    private var windowWidthDp = 0f
    private var windowHeightDp = LyricsLayout.NARROW_HEIGHT_DP
    private var dimmed = false
    private var motion = Motion.NONE
    private var progress = 1f
    private var fromWidth = 0
    private var fromHeight = 0
    private var rowDistance = 0f
    private var animator: ValueAnimator? = null

    internal val currentRow: TextView get() = current
    internal val nextRow: TextView get() = next
    internal val outgoingRow: TextView get() = outgoing
    internal val morphRow: TextView get() = morph

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = GONE
        // Rows are clipped to the glass: the old line leaves through the top edge, the new one rises inside.
        clipChildren = true
        addView(outgoing)
        addView(morph)
        addView(current)
        addView(next)
        applyTheme()
        applySizes()
    }

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
        applySizes()
        requestLayout()
    }

    /** [animate] false changes the line at once, for a seek or a screenshot. */
    fun show(currentLine: String?, nextLine: String?, animate: Boolean = true) {
        val now = currentLine?.trim().orEmpty()
        val later = nextLine?.trim().orEmpty()
        if (now.isEmpty() && later.isEmpty()) {
            hide()
            return
        }
        val shown = visibility == VISIBLE && motion != Motion.EXIT
        val oldCurrent = current.text.toString()
        val oldNext = next.text.toString()
        if (shown && now == oldCurrent && later == oldNext) return
        val moving = animate && !lyricsReducedMotion(context)
        if (!shown) {
            settle()
            apply(now, later)
            visibility = VISIBLE
            if (moving) run(Motion.ENTER, LyricsMotion.ENTER_MILLIS) else applyPose()
            return
        }
        if (!moving) {
            settle()
            apply(now, later)
            return
        }
        fromWidth = measuredWidth
        fromHeight = measuredHeight
        if (oldCurrent.isNotEmpty() && oldNext.isNotEmpty() && now == oldNext) {
            outgoing.text = oldCurrent
            outgoing.setTextSize(TypedValue.COMPLEX_UNIT_PX, current.textSize)
            morph.text = oldNext
            morph.setTextSize(TypedValue.COMPLEX_UNIT_PX, next.textSize)
            apply(now, later)
            run(Motion.ROLL, LyricsMotion.ROLL_MILLIS)
        } else {
            apply(now, later)
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

    /**
     * One frame of the roll at eased progress [e], without an animator: for design sheets. The pill must
     * show a current and a next line and have been laid out once.
     */
    internal fun freezeRollFrame(newNextLine: String?, e: Float) {
        val oldCurrent = current.text.toString()
        val oldNext = next.text.toString()
        settle()
        fromWidth = measuredWidth
        fromHeight = measuredHeight
        outgoing.text = oldCurrent
        outgoing.setTextSize(TypedValue.COMPLEX_UNIT_PX, current.textSize)
        morph.text = oldNext
        morph.setTextSize(TypedValue.COMPLEX_UNIT_PX, next.textSize)
        apply(oldNext, newNextLine?.trim().orEmpty())
        motion = Motion.ROLL
        progress = e
        requestLayout()
        applyPose()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val spec = MeasureSpec.getSize(widthMeasureSpec)
        val limit = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else spec
        val inner = (min(limit, maxWidthPx()) - 2 * padH()).toInt().coerceAtLeast(0)
        for (row in listOf(outgoing, morph, current, next)) {
            if (row.visibility != GONE) row.measure(MeasureSpec.makeMeasureSpec(inner, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        }
        val rows = listOf(current, next).filter { it.visibility != GONE }
        val contentWidth = rows.maxOfOrNull { it.measuredWidth } ?: 0
        val contentHeight = rows.sumOf { it.measuredHeight } + (if (rows.size == 2) gap().toInt() else 0)
        val naturalWidth = contentWidth + (2 * padH()).toInt()
        val naturalHeight = contentHeight + (2 * padV()).toInt()
        val easing = (motion == Motion.ROLL || motion == Motion.DIP) && fromWidth > 0
        setMeasuredDimension(
            if (easing) lerp(fromWidth, naturalWidth, progress) else naturalWidth,
            if (easing) lerp(fromHeight, naturalHeight, progress) else naturalHeight,
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        var top = padV().toInt()
        val currentShown = current.visibility != GONE
        val nextShown = next.visibility != GONE
        if (currentShown) {
            placeCentred(current, width, top)
            top += current.measuredHeight + gap().toInt()
        }
        if (nextShown) placeCentred(next, width, top)
        val currentCentre = if (currentShown) current.top + current.measuredHeight / 2f else padV() + currentPx() * 0.6f
        val nextCentre = if (nextShown) next.top + next.measuredHeight / 2f else currentCentre + currentPx() * 1.2f
        rowDistance = max(nextCentre - currentCentre, 1f)
        if (outgoing.visibility != GONE) placeCentred(outgoing, width, (currentCentre - outgoing.measuredHeight / 2f).toInt())
        if (morph.visibility != GONE) placeCentred(morph, width, (nextCentre - morph.measuredHeight / 2f).toInt())
        applyPose()
    }

    private fun placeCentred(row: TextView, width: Int, top: Int) {
        val left = (width - row.measuredWidth) / 2
        row.layout(left, top, left + row.measuredWidth, top + row.measuredHeight)
    }

    private fun apply(now: String, later: String) {
        current.text = now
        current.visibility = if (now.isEmpty()) GONE else VISIBLE
        next.text = later
        next.visibility = if (later.isEmpty()) GONE else VISIBLE
        fitCurrentLine()
        requestLayout()
    }

    // A line a little too long shrinks (down to 80%) to stay on one row. A longer one keeps its full size and
    // wraps to a second row; only text beyond two rows ends in an ellipsis.
    private fun fitCurrentLine() {
        val base = currentPx()
        val available = maxWidthPx() - 2 * padH()
        val needed = Paint(current.paint).also { it.textSize = base }.measureText(current.text.toString())
        val shrinks = available > 0 && needed > available && needed <= available / LyricsLayout.PILL_MIN_TEXT_SCALE
        current.setTextSize(TypedValue.COMPLEX_UNIT_PX, if (shrinks) base * available / needed else base)
    }

    private fun run(mode: Motion, millis: Long) {
        settle()
        motion = mode
        progress = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = millis
            interpolator = PathInterpolator(LyricsMotion.EASE_OUT[0], LyricsMotion.EASE_OUT[1], LyricsMotion.EASE_OUT[2], LyricsMotion.EASE_OUT[3])
            addUpdateListener {
                progress = it.animatedValue as Float
                requestLayout()
                applyPose()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animator === animation) settle()
                }
            })
            start()
        }
        requestLayout()
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
        outgoing.visibility = GONE
        morph.visibility = GONE
        applyPose()
        requestLayout()
    }

    private fun applyPose() {
        val d = rowDistance
        val ratio = nextPx() / currentPx()
        var wholeAlpha = if (dimmed) DIMMED_ALPHA else 1f
        var wholeShift = 0f
        resetRow(current)
        resetRow(next)
        when (motion) {
            Motion.ROLL -> {
                outgoing.visibility = VISIBLE
                morph.visibility = VISIBLE
                pose(outgoing, LyricsMotion.outgoing(progress), d)
                pose(morph, LyricsMotion.morph(progress, ratio), d)
                pose(current, LyricsMotion.incomingCurrent(progress, ratio), d)
                pose(next, LyricsMotion.incomingNext(progress), d)
            }
            Motion.DIP -> {
                val dip = LyricsMotion.DIP_ALPHA + (1f - LyricsMotion.DIP_ALPHA) * progress
                current.alpha = dip
                next.alpha = dip
            }
            Motion.ENTER -> {
                wholeAlpha *= progress
                wholeShift = (1f - progress) * ENTER_RISE_EM * currentPx()
            }
            Motion.EXIT -> {
                wholeAlpha *= 1f - progress
                wholeShift = -progress * EXIT_RISE_EM * currentPx()
            }
            Motion.NONE -> Unit
        }
        alpha = wholeAlpha
        translationY = wholeShift
    }

    private fun pose(row: TextView, pose: LyricsMotion.Pose, distance: Float) {
        row.alpha = pose.alpha
        row.translationY = pose.translateY * distance
        row.scaleX = pose.scale
        row.scaleY = pose.scale
    }

    private fun resetRow(row: TextView) {
        row.alpha = 1f
        row.translationY = 0f
        row.scaleX = 1f
        row.scaleY = 1f
    }

    private fun applyTheme() {
        current.setTextColor(colors.current)
        outgoing.setTextColor(colors.current)
        next.setTextColor(colors.next)
        morph.setTextColor(colors.next)
        applyShape()
    }

    private fun applySizes() {
        next.setTextSize(TypedValue.COMPLEX_UNIT_PX, nextPx())
        morph.setTextSize(TypedValue.COMPLEX_UNIT_PX, nextPx())
        outgoing.setTextSize(TypedValue.COMPLEX_UNIT_PX, currentPx())
        fitCurrentLine()
        applyShape()
    }

    private fun applyShape() {
        background = lyricsGlass(colors, LyricsLayout.PILL_RADIUS_EM * currentPx(), rimPx())
        elevation = LyricsLayout.PILL_ELEVATION_EM * currentPx()
    }

    private fun rimPx() = LyricsLayout.RIM_DP * density

    private fun typeScale() = LyricsLayout.typeScale(windowHeightDp) * LyricsLayout.widthScale(windowWidthDp)

    private fun currentPx(): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, LyricsLayout.PILL_CURRENT_SP * typeScale(), resources.displayMetrics)

    private fun nextPx(): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, LyricsLayout.PILL_NEXT_SP * typeScale(), resources.displayMetrics)

    private fun padH() = LyricsLayout.PILL_PADDING_H_EM * currentPx()
    private fun padV() = LyricsLayout.PILL_PADDING_V_EM * currentPx()
    private fun gap() = LyricsLayout.PILL_ROW_GAP_EM * currentPx()

    private fun maxWidthPx(): Int =
        if (windowWidthDp > 0f) (windowWidthDp * LyricsLayout.PILL_MAX_WIDTH_FRACTION * density).toInt() else Int.MAX_VALUE

    private fun lerp(from: Int, to: Int, t: Float) = (from + (to - from) * t).toInt()

    private companion object {
        const val DIMMED_ALPHA = 0.6f
        const val ENTER_RISE_EM = 0.3f
        const val EXIT_RISE_EM = 0.15f
    }
}
