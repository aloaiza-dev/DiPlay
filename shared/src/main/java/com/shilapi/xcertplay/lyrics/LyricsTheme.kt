package com.shilapi.xcertplay.lyrics

import kotlin.math.pow
import kotlin.math.roundToInt

/** ARGB colours of one theme. Hierarchy comes from opacity, not hue. */
data class LyricsColors(val scrim: Int, val edge: Int, val current: Int, val next: Int, val far: Int)

object LyricsTheme {
    private val night = LyricsColors(
        scrim = 0xCC000000.toInt(),
        edge = 0x00000000,
        current = 0xFFFFFFFF.toInt(),
        next = 0xB3FFFFFF.toInt(),
        far = 0x99FFFFFF.toInt(),
    )
    private val day = LyricsColors(
        scrim = 0xD9FFFFFF.toInt(),
        edge = 0x1F000000,
        current = 0xFF000000.toInt(),
        next = 0xB3000000.toInt(),
        far = 0x99000000.toInt(),
    )

    fun colors(night: Boolean): LyricsColors = if (night) this.night else day

    /** [top] drawn over the opaque colour [bottom]. */
    fun over(top: Int, bottom: Int): Int {
        val alpha = (top ushr 24) / 255.0
        fun channel(shift: Int): Int {
            val t = (top ushr shift) and 0xFF
            val b = (bottom ushr shift) and 0xFF
            return (alpha * t + (1 - alpha) * b).roundToInt()
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    /** WCAG contrast ratio of [foreground] (any alpha) drawn over the opaque colour [background]. */
    fun contrast(foreground: Int, background: Int): Double {
        val a = luminance(over(foreground, background))
        val b = luminance(background)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    private fun luminance(color: Int): Double {
        fun linear(shift: Int): Double {
            val c = ((color ushr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(16) + 0.7152 * linear(8) + 0.0722 * linear(0)
    }
}
