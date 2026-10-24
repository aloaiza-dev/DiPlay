package com.shilapi.xcertplay.lyrics

enum class LyricsRailSide(val key: String) {
    AUTO("auto"),
    LEFT("left"),
    RIGHT("right");

    /** AUTO keeps the rail clear of the dock, which sits on the driver's side. */
    fun onLeft(rightHandDrive: Boolean): Boolean = when (this) {
        AUTO -> rightHandDrive
        LEFT -> true
        RIGHT -> false
    }

    companion object {
        fun fromKey(key: String?): LyricsRailSide = entries.firstOrNull { it.key == key } ?: AUTO
    }
}
