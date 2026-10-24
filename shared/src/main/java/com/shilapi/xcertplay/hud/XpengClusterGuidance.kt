package com.shilapi.xcertplay.hud

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * One update for the XPeng instrument cluster's directions card (`onAndroidAutoInfo`). The card has no
 * field for the next-maneuver distance, so [roadName] carries it as text. [remainingMeters] is the
 * whole route, not the next turn.
 */
internal data class XpengClusterGuidance(
    val roadType: Int,
    val roadName: String,
    val remainingMeters: Int,
    val remainingSeconds: Int,
    val arrivalTime: String,
) {
    /** The card's own JSON, built here so the app and the shell helper cannot disagree on it. */
    fun toJson(): String = org.json.JSONObject()
        .put("roadType", roadType)
        .put("roadName", roadName)
        .put("resDistance", remainingMeters)
        .put("resTime", remainingSeconds)
        .put("arrivalTime", arrivalTime)
        .toString()

    /** Words for the maneuvers the card has no arrow for; the card shows [roadName] as plain text. */
    class Labels(
        val roundabout: String,
        val roundaboutExit: (Int) -> String,
        val arrive: String,
        val left: String = "Left",
    )

    companion object {
        // Arrow ids come from the instrument app's `android_auto_direction_<hex roadType>` drawables.
        // Live-tested on a G9: 1, 5, 6, 9, 16, 17, 18 in both themes. 7 (left) and 8 (right) draw at
        // night only: the day images are named `7_` and `8_`, which the app never looks up. In the day
        // theme a right turn uses 0 and a left turn uses 9 (sharp left) with the word "Left" in the text.
        // The card has no roundabout or arrival glyph.
        private const val STRAIGHT = 1
        private const val SLIGHT_LEFT = 5
        private const val SLIGHT_RIGHT = 6
        private const val LEFT = 7
        private const val RIGHT = 8
        private const val SHARP_LEFT = 9
        private const val SHARP_RIGHT = 16
        private const val U_TURN_LEFT = 17
        private const val U_TURN_RIGHT = 18
        private const val RIGHT_DAY = 0
        private const val LEFT_DAY = SHARP_LEFT

        /** Every arrow id [roadType] can return; the helper's protocol accepts exactly these. */
        val ARROW_IDS = setOf(
            RIGHT_DAY, STRAIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, LEFT, RIGHT, SHARP_LEFT, SHARP_RIGHT, U_TURN_LEFT, U_TURN_RIGHT,
        )

        /** Null for a type that is unknown: the caller clears the card instead of drawing a false arrow. */
        fun roadType(appleType: Int, drivingSide: Int = 0, night: Boolean = true): Int? = when (appleType) {
            1, 20 -> if (night) LEFT else LEFT_DAY
            2, 21 -> if (night) RIGHT else RIGHT_DAY
            13, 22, 49, 52 -> SLIGHT_LEFT
            14, 23, 50, 53 -> SLIGHT_RIGHT
            47 -> SHARP_LEFT
            48 -> SHARP_RIGHT
            4, 18, 19, 26 -> if (drivingSide == 1) U_TURN_RIGHT else U_TURN_LEFT
            // No glyph exists for these: a straight arrow, with the distance and road in the text.
            3, 5, 6, 7, 8, 9, 10, 11, 12, 24, 25, 27, in 28..46, 51 -> STRAIGHT
            else -> null
        }

        /** Metres below 1 km, then kilometres: one decimal below 10 km, whole above. */
        fun distanceText(meters: Int): String = when {
            meters < 1000 -> "$meters m"
            meters < 10_000 -> String.format(Locale.getDefault(), "%.1f km", meters / 1000.0)
            else -> String.format(Locale.getDefault(), "%d km", (meters + 500) / 1000)
        }

        private fun label(appleType: Int, labels: Labels?, night: Boolean): String? = when {
            labels == null -> null
            appleType in LEFT_TYPES && !night -> labels.left
            appleType in 28..46 -> labels.roundaboutExit(appleType - 27)
            appleType == 6 || appleType == 7 -> labels.roundabout
            appleType in ARRIVAL_TYPES -> labels.arrive
            else -> null
        }

        private val ARRIVAL_TYPES = setOf(10, 12, 24, 25, 27)
        private val LEFT_TYPES = setOf(1, 20)

        /** [night] is the head unit's theme: it decides which arrow images exist, see the note above. */
        fun from(
            maneuver: BydAppleManeuver,
            labels: Labels? = null,
            night: Boolean = true,
            arrivalTime: (Long) -> String,
        ): XpengClusterGuidance? {
            val roadType = roadType(maneuver.type, maneuver.drivingSide, night) ?: return null
            val arrival = maneuver.arrivalEpochSeconds ?: return null
            val distance = maneuver.distanceMeters.coerceAtLeast(0)
            val text = listOfNotNull(label(maneuver.type, labels, night), distanceText(distance), maneuver.road.ifEmpty { null })
                .joinToString(" · ")
            return XpengClusterGuidance(
                roadType = roadType,
                roadName = text,
                remainingMeters = (maneuver.remainingMeters ?: return null).coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
                remainingSeconds = (maneuver.remainingSeconds ?: return null).coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
                arrivalTime = arrivalTime(arrival),
            )
        }

        /** The head unit's own clock format and zone. */
        fun clockFormatter(is24Hour: Boolean, zone: TimeZone = TimeZone.getDefault()): (Long) -> String {
            val format = SimpleDateFormat(if (is24Hour) "HH:mm" else "h:mm a", Locale.getDefault()).apply { timeZone = zone }
            return { epochSeconds -> format.format(Date(epochSeconds * 1000)) }
        }
    }
}
