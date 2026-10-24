package com.shilapi.xcertplay.lyrics

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R

/**
 * Lyrics as a wheel on a floating glass card. Every line has one weight; size and opacity say how far a line
 * is from the current one ([LyricsMotion.railScale], [LyricsMotion.railAlpha]), and the current line sits at
 * the card's vertical centre. When the song moves to its next line every row eases from the pose of the
 * line it shows to the pose of the slot it moves into.
 *
 * The card takes no touches except on its timing control: a small gear in the corner that opens a strip with
 * Later and Earlier buttons. Everywhere else a tap goes through to CarPlay.
 */
internal class LyricsRailView(context: Context) : ViewGroup(context), BackdropTarget {
    private enum class Motion { NONE, ENTER, ROLL, DIP, EXIT }

    private data class Pose(val centerY: Float, val scale: Float, val alpha: Float)

    private val density = context.resources.displayMetrics.density
    private var colors = LyricsTheme.railColors(true)
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

    // The timing control. It is hidden until [setTimingControl].
    private val handler = Handler(Looper.getMainLooper())
    private var timingMillis = LyricsTiming.DEFAULT_MILLIS
    private var onTimingChange: ((Int) -> Unit)? = null
    private var controlConfigured = false
    private val collapse = Runnable { closeStrip() }

    internal val gearButton = ImageView(context).apply {
        setImageResource(R.drawable.ic_dp_controls)
        scaleType = ImageView.ScaleType.FIT_CENTER
        isClickable = true
        isFocusable = false
        contentDescription = context.getString(R.string.settings_lyrics_timing)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        visibility = GONE
        setOnClickListener { if (timingStrip.visibility == VISIBLE) closeStrip() else openStrip() }
    }
    // Goes back to the pill view. Shown while there is an action.
    private var switchAction: (() -> Unit)? = null
    internal val pillButton = ImageView(context).apply {
        setImageResource(R.drawable.ic_lyrics_pill)
        scaleType = ImageView.ScaleType.FIT_CENTER
        isClickable = true
        isFocusable = false
        contentDescription = context.getString(R.string.lyrics_control_pill)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        visibility = GONE
        setOnClickListener { switchAction?.invoke() }
    }
    // Hides the lyrics of this song. Shown while there is an action.
    private var hideAction: (() -> Unit)? = null
    internal val hideButton = ImageView(context).apply {
        setImageResource(R.drawable.ic_lyrics_hide)
        scaleType = ImageView.ScaleType.FIT_CENTER
        isClickable = true
        isFocusable = false
        contentDescription = context.getString(R.string.lyrics_control_hide)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        visibility = GONE
        setOnClickListener { hideAction?.invoke() }
    }
    internal val laterButton = controlButton(R.string.lyrics_control_later, earlier = false)
    internal val earlierButton = controlButton(R.string.lyrics_control_earlier, earlier = true)
    internal val timingValue = lyricsLine(context).apply { maxLines = 1; gravity = Gravity.CENTER }
    internal val timingStrip = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true // a tap on the strip must not fall through to CarPlay
        isFocusable = false
        visibility = GONE
    }

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = GONE
        clipChildren = true
        timingStrip.addView(laterButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        timingStrip.addView(timingValue, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.5f))
        timingStrip.addView(earlierButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        addView(gearButton)
        addView(pillButton)
        addView(hideButton)
        addView(timingStrip)
        rebuildRows()
        applyTheme()
    }

    /** Enables the control. [millis] is the timing now; [onChange] gets each new value after a tap. */
    fun setTimingControl(millis: Int, onChange: (Int) -> Unit) {
        timingMillis = millis
        onTimingChange = onChange
        controlConfigured = true
        refreshControls()
    }

    /** The timing changed somewhere else, for example in Settings. */
    fun setTiming(millis: Int) {
        timingMillis = millis
        refreshStrip()
    }

    /** The action of the pill icon in the corner: switch to the pill view. */
    fun setSwitchAction(action: (() -> Unit)?) {
        switchAction = action
        refreshControls()
    }

    /** The action of the eye icon in the top middle: hide the lyrics of this song. */
    fun setHideAction(action: (() -> Unit)?) {
        hideAction = action
        refreshControls()
    }

    private fun refreshControls() {
        hideButton.visibility = if (hideAction != null) VISIBLE else GONE
        pillButton.visibility = if (switchAction != null) VISIBLE else GONE
        gearButton.visibility = if (controlConfigured) VISIBLE else GONE
        if (gearButton.visibility == GONE) closeStrip()
        refreshStrip()
    }

    private fun refreshStrip() {
        timingValue.text = lyricsTimingLabel(context, timingMillis)
        for (earlier in listOf(true, false)) {
            val button = if (earlier) earlierButton else laterButton
            val enabled = LyricsTiming.canStep(timingMillis, earlier)
            button.isEnabled = enabled
            button.alpha = if (enabled) 1f else DISABLED_ALPHA
        }
    }

    private fun openStrip() {
        timingStrip.visibility = VISIBLE
        refreshStrip()
        gearButton.setColorFilter(colors.current)
        scheduleCollapse()
        applyPose()
    }

    private fun closeStrip() {
        handler.removeCallbacks(collapse)
        timingStrip.visibility = GONE
        gearButton.setColorFilter(colors.next)
        applyPose()
    }

    private fun scheduleCollapse() {
        handler.removeCallbacks(collapse)
        handler.postDelayed(collapse, COLLAPSE_MILLIS)
    }

    private fun step(earlier: Boolean) {
        if (!LyricsTiming.canStep(timingMillis, earlier)) return
        timingMillis = LyricsTiming.step(timingMillis, earlier)
        refreshStrip()
        onTimingChange?.invoke(timingMillis)
        scheduleCollapse()
    }

    private fun controlButton(label: Int, earlier: Boolean) = TextView(context).apply {
        text = context.getString(label)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        maxLines = 1
        isClickable = true
        isFocusable = false
        setOnClickListener { step(earlier) }
    }

    fun preferredWidthPx(): Int = (LyricsLayout.railWidthDp(windowWidthDp) * density).toInt()

    fun preferredHeightPx(): Int = (LyricsLayout.railHeightDp(windowHeightDp) * density).toInt()

    fun edgeMarginPx(): Int = (LyricsLayout.railEdgeMarginDp(windowHeightDp) * density).toInt()

    /** The row that shows the line [offset] lines from the current one; for tests and design sheets. */
    internal fun row(offset: Int): TextView = rows[offset + reach]

    internal val slotReach: Int get() = reach

    fun setNight(night: Boolean) {
        colors = LyricsTheme.railColors(night)
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
        closeStrip()
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
        for (button in listOf(gearButton, pillButton, hideButton)) {
            button.measure(MeasureSpec.makeMeasureSpec(gearSize(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(gearSize(), MeasureSpec.EXACTLY))
        }
        timingStrip.measure(MeasureSpec.makeMeasureSpec(inner, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(stripHeight(), MeasureSpec.EXACTLY))
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
        // The gear in the upper right corner; the strip along the bottom, above the faintest lines.
        val edge = (CONTROL_EDGE_EM * textPx()).toInt()
        val gear = gearSize()
        gearButton.layout(width - edge - gear, edge, width - edge, edge + gear)
        pillButton.layout(edge, edge, edge + gear, edge + gear)
        hideButton.layout((width - gear) / 2, edge, (width + gear) / 2, edge + gear)
        val stripBottom = (b - t) - (STRIP_BOTTOM_EM * textPx()).toInt()
        timingStrip.layout(padH().toInt(), stripBottom - stripHeight(), width - padH().toInt(), stripBottom)
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
        if (motion == Motion.EXIT) {
            closeStrip()
            visibility = GONE
        }
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
                    val p = LyricsMotion.staggered(progress, slot)
                    shift = (1f - p) * (from.centerY - target.centerY)
                    scale = lerp(from.scale, target.scale, p)
                    alpha = lerp(from.alpha, target.alpha, p)
                }
                Motion.DIP -> alpha = target.alpha * lerp(LyricsMotion.DIP_ALPHA, 1f, progress)
                else -> Unit
            }
            // While the strip is open, a line that would reach under it is not drawn: only a sliver would show.
            if (timingStrip.visibility == VISIBLE && row.visibility == VISIBLE &&
                (row.top + row.bottom) / 2f + row.height * scale / 2f + shift > timingStrip.top) alpha = 0f
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
        rows.forEach { removeView(it) }
        reach = slotReachFor(windowHeightDp)
        rows = List(2 * reach + 1) {
            lyricsLine(context).apply { typeface = Typeface.DEFAULT_BOLD; maxLines = MAX_ROWS_PER_LINE; breakStrategy = Layout.BREAK_STRATEGY_BALANCED }
        }
        // Rows go under the gear and the strip.
        rows.forEachIndexed { position, row -> addView(row, position) }
        applySizes()
        applyTheme()
        if (lines.isNotEmpty()) assign(lines, index)
    }

    private fun applyTheme() {
        rows.forEach { it.setTextColor(colors.current) }
        applyControlTheme()
        applyShape()
    }

    private fun applySizes() {
        rows.forEach { it.setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx()) }
        applyControlSizes()
        applyShape()
    }

    private fun applyControlTheme() {
        val ink = colors.current and 0x00FFFFFF
        // The glass's own colour made opaque and lifted a little: the faint lines behind it must not show through.
        val stripFill = LyricsTheme.over(ink or STRIP_LIFT, colors.glassBottom or OPAQUE)
        timingStrip.background = GradientDrawable().apply {
            setColor(stripFill)
            cornerRadius = stripHeight() / 2f
        }
        for (button in listOf(laterButton, earlierButton)) {
            button.setTextColor(colors.current)
            button.background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply {
                    setColor(ink or PRESSED_FILL)
                    cornerRadius = stripHeight() / 2f
                })
            }
        }
        timingValue.setTextColor(colors.next)
        for (button in listOf(gearButton, pillButton, hideButton)) {
            button.background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply {
                    setColor(ink or PRESSED_FILL)
                    shape = GradientDrawable.OVAL
                })
            }
        }
        pillButton.setColorFilter(colors.next)
        hideButton.setColorFilter(colors.next)
        gearButton.setColorFilter(if (timingStrip.visibility == VISIBLE) colors.current else colors.next)
    }

    private fun applyControlSizes() {
        val label = CONTROL_TEXT_EM * textPx()
        for (view in listOf<TextView>(laterButton, earlierButton, timingValue)) view.setTextSize(TypedValue.COMPLEX_UNIT_PX, label)
        val pad = (GEAR_PADDING_FRACTION * gearSize()).toInt()
        gearButton.setPadding(pad, pad, pad, pad)
        pillButton.setPadding(pad, pad, pad, pad)
        hideButton.setPadding(pad, pad, pad, pad)
        applyControlTheme()
    }

    private fun gearSize(): Int = maxOf(MIN_TARGET_DP * density, GEAR_EM * textPx()).toInt()

    private fun stripHeight(): Int = maxOf(MIN_TARGET_DP * density, STRIP_EM * textPx()).toInt()

    private var backdrop: android.graphics.Bitmap? = null

    override val opaque: Boolean get() = true

    override fun backdropBounds(out: android.graphics.Rect): Boolean {
        if (visibility != VISIBLE || width <= 0 || height <= 0) return false
        out.set(left, top, right, bottom)
        return true
    }

    override fun onBackdrop(bitmap: android.graphics.Bitmap?) {
        backdrop = bitmap
        (background as? LyricsGlassDrawable)?.setBackdrop(bitmap)
    }

    private fun applyShape() {
        background = lyricsGlass(colors, LyricsLayout.RAIL_RADIUS_EM * textPx(), LyricsLayout.RIM_DP * density)
        (background as? LyricsGlassDrawable)?.setBackdrop(backdrop)
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
        // The slim rail wraps a long lyric to three rows before it ends in an ellipsis.
        const val MAX_ROWS_PER_LINE = 3
        const val ENTER_RISE_EM = 0.3f
        const val EXIT_RISE_EM = 0.15f

        // The control: touch targets are at least 48 dp, and otherwise follow the text size.
        const val MIN_TARGET_DP = 48f
        const val GEAR_EM = 1.15f
        const val STRIP_EM = 1.3f
        const val GEAR_PADDING_FRACTION = 0.2f
        const val CONTROL_EDGE_EM = 0.3f
        const val STRIP_BOTTOM_EM = 0.45f
        const val CONTROL_TEXT_EM = 0.62f
        const val STRIP_LIFT = 0x1F000000
        const val OPAQUE = 0xFF000000.toInt()
        const val PRESSED_FILL = 0x38000000
        const val DISABLED_ALPHA = 0.35f
        const val COLLAPSE_MILLIS = 4_000L
    }
}
