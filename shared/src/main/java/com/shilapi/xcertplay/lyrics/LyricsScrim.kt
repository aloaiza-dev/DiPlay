package com.shilapi.xcertplay.lyrics

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Darkens a blurred picture just enough for the lyric text to stay readable. Each pixel gets the weakest tint
 * that still holds the text contrast floors over it, so a dark or mid picture keeps its colour and shows
 * through the glass, and only a bright one is pulled down. The strength comes from a table indexed by the
 * pixel's luminance, built once, so shading a pixel is a lookup and a blend.
 */
class LyricsScrim(colors: LyricsColors) {
    private val tint = colors.glassBottom and 0xFFFFFF
    private val alpha256 = IntArray(BUCKETS)

    init {
        for (bucket in 0 until BUCKETS) {
            val luminance = ((bucket + 1).toDouble() / BUCKETS).pow(2)
            val grey = (255 * deLinear(luminance)).toInt().coerceIn(0, 255)
            val backdrop = (0xFF shl 24) or (grey shl 16) or (grey shl 8) or grey
            alpha256[bucket] = strength(colors, backdrop)
        }
    }

    /** [pixel] is opaque ARGB; the result is too. */
    fun shade(pixel: Int): Int {
        val r = (pixel ushr 16) and 0xFF
        val g = (pixel ushr 8) and 0xFF
        val b = pixel and 0xFF
        val luminance = 0.2126f * LINEAR[r] + 0.7152f * LINEAR[g] + 0.0722f * LINEAR[b]
        val bucket = (sqrt(luminance.toDouble()) * BUCKETS).toInt().coerceIn(0, BUCKETS - 1)
        val a = alpha256[bucket]
        val keep = 256 - a
        val outR = (((tint ushr 16) and 0xFF) * a + r * keep) shr 8
        val outG = (((tint ushr 8) and 0xFF) * a + g * keep) shr 8
        val outB = ((tint and 0xFF) * a + b * keep) shr 8
        return (0xFF shl 24) or (outR shl 16) or (outG shl 8) or outB
    }

    // The smallest tint (as 0..256) with every text role above its floor plus a margin, found by bisection:
    // more tint is always darker, so contrast only rises with it.
    private fun strength(colors: LyricsColors, backdrop: Int): Int {
        fun holds(a256: Int): Boolean {
            val shaded = LyricsTheme.over((a256 shl 24) or tint, backdrop)
            return LyricsTheme.contrast(colors.current, shaded) >= CURRENT_CONTRAST + MARGIN &&
                LyricsTheme.contrast(colors.next, shaded) >= NEXT_CONTRAST + MARGIN &&
                LyricsTheme.contrast(colors.far, shaded) >= FAR_CONTRAST + MARGIN
        }
        var low = MIN_ALPHA_256
        var high = MAX_ALPHA_256
        if (holds(low)) return low
        while (high - low > 1) {
            val mid = (low + high) / 2
            if (holds(mid)) high = mid else low = mid
        }
        return high
    }

    private fun deLinear(y: Double): Double = if (y <= 0.0031308) y * 12.92 else 1.055 * y.pow(1 / 2.4) - 0.055

    companion object {
        const val CURRENT_CONTRAST = 8.0
        const val NEXT_CONTRAST = 5.0
        const val FAR_CONTRAST = 4.0
        private const val MARGIN = 0.4
        private const val BUCKETS = 64
        // Never clearer than 30%: below that the text would lose its glass behind it; never above 95%.
        private const val MIN_ALPHA_256 = 77
        private const val MAX_ALPHA_256 = 243

        private val LINEAR = FloatArray(256) { i ->
            val c = i / 255.0
            (if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)).toFloat()
        }
    }
}
