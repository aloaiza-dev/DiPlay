package com.shilapi.xcertplay.lyrics

import android.content.Context
import com.shilapi.xcertplay.host.R
import java.text.NumberFormat
import kotlin.math.abs

/** "On time", "0.5 s earlier", "1 s later": the number follows the locale. Used by Settings and the rail. */
internal fun lyricsTimingLabel(context: Context, millis: Int): String {
    if (millis == 0) return context.getString(R.string.settings_lyrics_timing_on_time)
    val seconds = NumberFormat.getInstance().apply { maximumFractionDigits = 2 }.format(abs(millis) / 1000.0)
    return context.getString(if (millis > 0) R.string.settings_lyrics_timing_earlier else R.string.settings_lyrics_timing_later, seconds)
}
