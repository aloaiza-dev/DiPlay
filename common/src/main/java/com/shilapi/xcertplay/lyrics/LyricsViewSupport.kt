package com.shilapi.xcertplay.lyrics

import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.TextView

/** The system "remove animations" switch: a fade or a scroll becomes an instant change. */
internal fun lyricsReducedMotion(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

internal fun lyricsGlass(colors: LyricsColors, radiusPx: Float, rimPx: Float) =
    LyricsGlassDrawable().apply { update(colors, radiusPx, rimPx) }

/** A touch-free, centred line that picks its direction from its own first strong character. */
internal fun lyricsLine(context: Context) = TextView(context).apply {
    textDirection = View.TEXT_DIRECTION_FIRST_STRONG
    gravity = Gravity.CENTER_HORIZONTAL
    ellipsize = TextUtils.TruncateAt.END
    includeFontPadding = false
    setLineSpacing(0f, LyricsLayout.LINE_SPACING_MULTIPLIER)
    isClickable = false
    isFocusable = false
}
