package com.shilapi.xcertplay.lyrics

/**
 * The lyric roll. When the song moves to its next line the old current line rises and fades out, the old
 * next line rises into the current slot and grows into the bold current line, and the line after it fades
 * up from below. All poses are functions of one eased progress `e` from 0 to 1, so a preview can draw any
 * frame. `translateY` is a fraction of the distance between the current and the next row; `scale` and
 * `alpha` are multipliers on the role's resting look. Only transform and opacity move.
 */
object LyricsMotion {
    const val ROLL_MILLIS = 520L
    const val ENTER_MILLIS = 360L
    const val EXIT_MILLIS = 260L
    const val DIP_MILLIS = 320L
    const val DIP_ALPHA = 0.55f

    /** `cubic-bezier(0.32, 0.72, 0, 1)`, the iOS sheet curve: quick to start, a long gentle settle, no bounce. */
    val EASE_OUT = floatArrayOf(0.32f, 0.72f, 0f, 1f)

    // A rail row starts this share of the roll later for each slot it is from the current line, up to three.
    private const val STAGGER_PER_SLOT = 0.06f

    data class Pose(val alpha: Float, val translateY: Float, val scale: Float)

    fun outgoing(e: Float) = Pose(alpha = 1f - smooth(0f, 0.55f, e), translateY = -0.55f * e, scale = 1f - 0.03f * e)

    /** The old next line, moving from its own slot to the current slot. [ratio] is next size / current size. */
    fun morph(e: Float, ratio: Float) = Pose(alpha = 1f - smooth(0.25f, 0.85f, e), translateY = -e, scale = 1f + (1f / ratio - 1f) * e)

    /** The new current line, which starts in the next slot at the next line's size and settles in its own. */
    fun incomingCurrent(e: Float, ratio: Float) = Pose(alpha = smooth(0.2f, 0.9f, e), translateY = 1f - e, scale = ratio + (1f - ratio) * e)

    fun incomingNext(e: Float) = Pose(alpha = smooth(0.3f, 1f, e), translateY = 0.45f * (1f - e), scale = 1f)

    // The rail is a wheel of lines, all one weight. Size and opacity say how far a line is from the current
    // one: [offset] 0 is the current line, positive offsets are still to come. It flattens beyond three lines.
    private val railAlphas = floatArrayOf(1f, 0.72f, 0.42f, 0.2f)
    private val railPastAlphas = floatArrayOf(1f, 0.5f, 0.28f, 0.14f)
    private val railScales = floatArrayOf(1f, 0.76f, 0.62f, 0.52f)

    fun railAlpha(offset: Int): Float {
        val steps = minOf(kotlin.math.abs(offset), 3)
        return if (offset >= 0) railAlphas[steps] else railPastAlphas[steps]
    }

    fun railScale(offset: Int): Float = railScales[minOf(kotlin.math.abs(offset), 3)]

    /** The eased progress [e] of the rail row [offset] slots from the current line, which waits a little. */
    fun staggered(e: Float, offset: Int): Float {
        val delay = minOf(kotlin.math.abs(offset), 3) * STAGGER_PER_SLOT
        return ((e - delay) / (1f - delay)).coerceIn(0f, 1f)
    }

    fun smooth(low: Float, high: Float, x: Float): Float {
        val t = ((x - low) / (high - low)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
