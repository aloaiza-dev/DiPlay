package com.shilapi.xcertplay.lyrics

enum class LyricsPlacement(val key: String) {
    OFF("off"),
    TOP_PILL("top_pill"),
    SIDE_RAIL("side_rail"),
    ;

    companion object {
        fun fromKey(key: String?): LyricsPlacement = entries.firstOrNull { it.key == key } ?: OFF
    }
}
