package com.shilapi.xcertplay.lyrics

/** Sizes of the lyrics overlay. Starting values: none is measured on a car yet. */
object LyricsLayout {
    const val PILL_TOP_MARGIN_DP = 12
    const val PILL_MAX_WIDTH_FRACTION = 0.55f
    const val PILL_MIN_TEXT_SCALE = 0.8f
    const val RAIL_WIDTH_FRACTION = 0.32f
    const val RAIL_MIN_DP = 240f
    const val RAIL_MAX_DP = 420f
    const val RAIL_MAX_FRACTION = 0.45f
    const val NARROW_HEIGHT_DP = 360f
    const val NARROW_TYPE_SCALE = 0.8f

    const val PILL_PADDING_H_DP = 20
    const val PILL_PADDING_V_DP = 10
    const val PILL_RADIUS_DP = 16
    const val RAIL_PADDING_H_DP = 16
    const val EDGE_STROKE_DP = 1
    const val LINE_SPACING_MULTIPLIER = 1.15f

    const val PILL_CURRENT_SP = 28f
    const val PILL_NEXT_SP = 20f
    const val RAIL_CURRENT_SP = 30f
    const val RAIL_OTHER_SP = 22f
    const val RAIL_FAR_SP = 18f

    fun railWidthDp(windowWidthDp: Float): Float =
        (windowWidthDp * RAIL_WIDTH_FRACTION).coerceIn(RAIL_MIN_DP, RAIL_MAX_DP)
            .coerceAtMost(windowWidthDp * RAIL_MAX_FRACTION)

    fun typeScale(windowHeightDp: Float): Float = if (windowHeightDp < NARROW_HEIGHT_DP) NARROW_TYPE_SCALE else 1f

    fun railVisibleLines(windowHeightDp: Float): Int = if (windowHeightDp < NARROW_HEIGHT_DP) 3 else 5
}
