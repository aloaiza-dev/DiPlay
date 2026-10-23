package com.shilapi.xcertplay.lyrics

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * ARGB colours of one theme. The glass is a vertical gradient with a bright specular rim that fades down the
 * edge and a sheen that lifts its top. Hierarchy of the text comes from opacity, not hue.
 */
data class LyricsColors(
    val glassTop: Int,
    val glassBottom: Int,
    val rimTop: Int,
    val rimBottom: Int,
    val sheen: Int,
    val current: Int,
    val next: Int,
    val far: Int,
)

object LyricsTheme {
    // Dark glass with white text in both themes, like CarPlay's own overlays: a pale glass over a bright map
    // would turn the lyrics grey. Day is a little bluer and denser, as the picture behind it is brighter.
    private val night = LyricsColors(
        glassTop = 0xC81A1D25.toInt(),
        glassBottom = 0xD00E1016.toInt(),
        rimTop = 0x66FFFFFF,
        rimBottom = 0x1AFFFFFF,
        sheen = 0x24FFFFFF,
        current = 0xFFFFFFFF.toInt(),
        next = 0xD1FFFFFF.toInt(),
        far = 0xB3FFFFFF.toInt(),
    )
    private val day = LyricsColors(
        glassTop = 0xCC1E2430.toInt(),
        glassBottom = 0xD6121820.toInt(),
        rimTop = 0x70FFFFFF,
        rimBottom = 0x20FFFFFF,
        sheen = 0x28FFFFFF,
        current = 0xFFFFFFFF.toInt(),
        next = 0xD1FFFFFF.toInt(),
        far = 0xB3FFFFFF.toInt(),
    )

    fun colors(night: Boolean): LyricsColors = if (night) this.night else day

    // The small pill borrows the picture's colour; a quiet rim and sheen keep it frosted rather than glossy.
    // Keep the dense fallback fill for devices where frame copying is unavailable.
    fun pillColors(night: Boolean): LyricsColors = colors(night).copy(
        rimTop = 0x40FFFFFF,
        rimBottom = 0x10FFFFFF,
        sheen = 0x14FFFFFF,
    )

    // Match the pill's quiet rim and sheen, with a denser fallback for the rail's many text rows.
    // Sampled glass uses its own stronger minimum tint and keeps all three text contrast floors.
    private const val RAIL_GLASS_ALPHA = 0xE6

    fun railColors(night: Boolean): LyricsColors = pillColors(night).let {
        it.copy(glassTop = withAlpha(it.glassTop), glassBottom = withAlpha(it.glassBottom))
    }

    private fun withAlpha(color: Int) = (RAIL_GLASS_ALPHA shl 24) or (color and 0xFFFFFF)

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
