package com.ping.app.auth

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A named gesture the user recorded: one or two poses captured from the camera, kept as their
 * [GestureFingerprint] codes. [labels] are only for showing the steps again.
 */
@Serializable
data class CustomGesture(val name: String, val steps: List<String>, val labels: List<String> = emptyList()) {

    /** Same key as holding the steps live with the two-gesture password, so no one else needs it saved. */
    val code: String get() = GestureFingerprint.sequenceCode(steps)

    companion object {
        /** The swap screen chains at most two poses, so every saved gesture can also be made live. */
        const val MAX_STEPS = 2
        private const val PREFS = "custom_gestures"
        private const val KEY = "gestures"
        private val json = Json { ignoreUnknownKeys = true }

        fun encode(gestures: List<CustomGesture>): String = json.encodeToString(gestures)

        fun decode(text: String?): List<CustomGesture> =
            text?.let { runCatching { json.decodeFromString<List<CustomGesture>>(it) }.getOrNull() }.orEmpty()

        fun load(context: Context): List<CustomGesture> =
            decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

        fun save(context: Context, gestures: List<CustomGesture>) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY, encode(gestures)) }
    }
}
