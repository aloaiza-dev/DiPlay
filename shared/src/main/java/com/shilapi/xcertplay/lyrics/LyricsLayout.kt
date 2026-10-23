package com.shilapi.xcertplay.lyrics

/** Sizes of the lyrics overlay. Starting values: none is measured on a car yet. */
object LyricsLayout {
    const val PILL_TOP_MARGIN_DP = 12
    // The pill keeps this width whatever the line: a width that follows the text keeps moving and distracts.
    const val PILL_WIDTH_FRACTION = 0.55f
    const val PILL_MIN_TEXT_SCALE = 0.8f
    const val RAIL_WIDTH_FRACTION = 0.36f
    const val RAIL_MIN_DP = 240f
    const val RAIL_MAX_DP = 480f
    const val RAIL_MAX_FRACTION = 0.45f
    const val NARROW_HEIGHT_DP = 360f
    const val NARROW_TYPE_SCALE = 0.8f
    const val TALL_HEIGHT_DP = 600f

    // Shapes are measured in the size of their own text, so they keep their proportions on any screen.
    const val PILL_PADDING_H_EM = 0.62f
    const val PILL_PADDING_V_EM = 0.34f
    const val PILL_ROW_GAP_EM = 0.12f
    const val PILL_RADIUS_EM = 0.5f
    const val PILL_ELEVATION_EM = 0.2f
    const val RAIL_PADDING_H_EM = 0.45f
    const val RAIL_PADDING_V_EM = 0.6f
    const val RAIL_ROW_GAP_EM = 0.22f
    const val RAIL_RADIUS_EM = 0.7f
    const val RAIL_ELEVATION_EM = 0.25f
    const val RIM_DP = 1.2f
    const val LINE_SPACING_MULTIPLIER = 1.15f

    // The rail is a card that floats at the edge: the full window height less the same margin above and below.
    const val RAIL_EDGE_MARGIN_FRACTION = 0.012f

    const val PILL_CURRENT_SP = 28f
    const val PILL_NEXT_SP = 20f
    const val RAIL_TEXT_SP = 32f

    // Head units differ a lot in density. XPENG runs 2400 px at 144 dpi, which is 2666 dp wide: type sized
    // for a 1280 dp window would be tiny there, so type and the rail limits grow with the window, up to 2x.
    const val WIDTH_REFERENCE_DP = 1280f
    const val MAX_WIDTH_SCALE = 2f

    fun widthScale(windowWidthDp: Float): Float = (windowWidthDp / WIDTH_REFERENCE_DP).coerceIn(1f, MAX_WIDTH_SCALE)

    fun railWidthDp(windowWidthDp: Float): Float {
        val scale = widthScale(windowWidthDp)
        return (windowWidthDp * RAIL_WIDTH_FRACTION).coerceIn(RAIL_MIN_DP * scale, RAIL_MAX_DP * scale)
            .coerceAtMost(windowWidthDp * RAIL_MAX_FRACTION)
    }

    fun railHeightDp(windowHeightDp: Float): Float = windowHeightDp * (1f - 2 * RAIL_EDGE_MARGIN_FRACTION)

    fun railEdgeMarginDp(windowHeightDp: Float): Float = windowHeightDp * RAIL_EDGE_MARGIN_FRACTION

    fun typeScale(windowHeightDp: Float): Float = if (windowHeightDp < NARROW_HEIGHT_DP) NARROW_TYPE_SCALE else 1f

    fun railVisibleLines(windowHeightDp: Float): Int = when {
        windowHeightDp < NARROW_HEIGHT_DP -> 3
        windowHeightDp < TALL_HEIGHT_DP -> 5
        else -> 7
    }
}
