package com.shilapi.xcertplay.lyrics

import kotlin.math.abs

/**
 * How much earlier the lyrics show than their timestamps say. Community timestamps are often late, and the
 * iPhone's position reaches the car a little after it was true. Positive means earlier.
 */
object LyricsTiming {
    const val MIN_MILLIS = -2000
    const val MAX_MILLIS = 2000
    const val DEFAULT_MILLIS = 500

    val PRESETS_MILLIS = listOf(-1000, -500, -250, 0, 250, 500, 750, 1000, 1250, 1500, 2000)

    fun clamp(millis: Int): Int = millis.coerceIn(MIN_MILLIS, MAX_MILLIS)

    fun nearestPresetIndex(millis: Int): Int = PRESETS_MILLIS.indices.minBy { abs(PRESETS_MILLIS[it] - clamp(millis)) }
}
