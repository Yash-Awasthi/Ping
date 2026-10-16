package com.ping.app.auth

import android.content.res.Resources
import com.ping.app.R.string as S
import com.ping.app.auth.GestureFingerprint.Motion
import com.ping.app.auth.GestureFingerprint.Palm

/**
 * Best-effort emoji and name for a [GestureFingerprint], shown to the user while they hold a
 * pose. Matching never uses this text — only the fingerprint code — so a wrong guess here
 * cannot break a swap, and translating it cannot either.
 */
object GestureNames {

    private const val T = 0b00001
    private const val I = 0b00010
    private const val M = 0b00100
    private const val R = 0b01000
    private const val P = 0b10000
    private const val ALL = 0b11111
    private const val NON_THUMB_ALL = I or M or R or P

    /** Formats a string resource with its arguments, e.g. `res.getString(id, *args)`. */
    fun interface Text {
        fun get(id: Int, args: Array<out Any>): String
        operator fun invoke(id: Int, vararg args: Any) = get(id, args)
    }

    /** Set from the application context so [GestureFingerprint.label] can read localized names. */
    @Volatile var appResources: Resources? = null

    /** One entry of the in-app gesture guide. */
    data class Entry(val emoji: String, val name: String)

    fun label(fp: GestureFingerprint, res: Resources): String = label(fp) { id, args -> res.getString(id, *args) }

    fun label(fp: GestureFingerprint, t: Text): String {
        val motion = fp.motion
        val base = if (motion != null) motionLabel(fp, motion, t) else poseLabel(fp, t)
        val sided = if (fp.hand != null) t(S.gesture_sided, base, t(fp.hand.text)) else base
        return if (fp.partner != null) "👏 " + t(S.gesture_both_hands, sided) else sided
    }

    private fun motionLabel(fp: GestureFingerprint, motion: Motion, t: Text): String {
        val name = t(motion.text)
        return when (motion) {
            Motion.WAVE -> "👋 $name"
            Motion.CIRCLE_CW -> "${fingerEmoji(fp.fingerMask)} $name ↻"
            Motion.CIRCLE_CCW -> "${fingerEmoji(fp.fingerMask)} $name ↺"
            Motion.SWIPE_LEFT -> "${fingerEmoji(fp.fingerMask)} $name ←"
            Motion.SWIPE_RIGHT -> "${fingerEmoji(fp.fingerMask)} $name →"
            Motion.SWIPE_UP -> "${fingerEmoji(fp.fingerMask)} $name ↑"
            Motion.SWIPE_DOWN -> "${fingerEmoji(fp.fingerMask)} $name ↓"
        }
    }

    private fun fingerEmoji(mask: Int) = when (mask) {
        I -> "☝️"
        0 -> "✊"
        ALL -> "✋"
        else -> "🖐️"
    }

    private fun poseLabel(fp: GestureFingerprint, t: Text): String {
        val mask = fp.fingerMask
        val dir = fp.angleBucket
        val toward = t(
            when (dir) { 0 -> S.gesture_dir_up; 1 -> S.gesture_dir_right; 2 -> S.gesture_dir_down; else -> S.gesture_dir_left },
        )
        if (fp.pinch) {
            return when {
                mask and (M or R or P) == (M or R or P) -> "👌 " + t(S.gesture_ok)
                mask and (M or R or P) == 0 -> "🤏 " + t(S.gesture_pinch)
                else -> "🤌 " + t(S.gesture_pinched_fingers)
            }
        }
        return when (mask) {
            0 -> when (dir) {
                1 -> "🤜 " + t(S.gesture_fist_toward, toward)
                3 -> "🤛 " + t(S.gesture_fist_toward, toward)
                2 -> "👊 " + t(S.gesture_fist_toward, toward)
                else -> "✊ " + t(S.gesture_fist)
            }
            T -> when (dir) {
                2 -> "👎 " + t(S.gesture_thumbs_down)
                0 -> "👍 " + t(S.gesture_thumbs_up)
                else -> "👍 " + t(S.gesture_thumb_toward, toward)
            }
            I -> (when (dir) { 2 -> "👇 "; 1 -> "👉 "; 3 -> "👈 "; else -> "☝️ " }) + t(S.gesture_pointing, toward)
            I or M -> if (fp.crossed) "🤞 " + t(S.gesture_crossed) else "✌️ " + t(S.gesture_peace, toward)
            I or P -> "🤘 " + t(S.gesture_rock_on)
            T or I or P -> "🤟 " + t(S.gesture_love_you)
            T or P -> "🤙 " + t(S.gesture_call_me)
            T or I -> "🫰 " + t(S.gesture_finger_gun)
            I or M or R -> "3️⃣ " + t(S.gesture_three_fingers, toward)
            NON_THUMB_ALL -> "4️⃣ " + t(S.gesture_four_fingers, toward)
            ALL -> openHand(fp, dir, t)
            else -> "🖐️ " + t(S.gesture_fingers_out, fp.openFingers, toward)
        }
    }

    private fun openHand(fp: GestureFingerprint, dir: Int, t: Text): String {
        val (emoji, id) = when {
            fp.gaps == 0b010 -> "🖖" to S.gesture_vulcan
            fp.gaps == 0b111 -> "🖐️" to S.gesture_spread
            fp.gaps != 0 -> "🖐️" to S.gesture_some_apart
            fp.palm == Palm.AWAY -> "🤚" to S.gesture_back_of_hand
            fp.palm == Palm.EDGE && dir == 1 -> "🫱" to S.gesture_hand_out_right
            fp.palm == Palm.EDGE && dir == 3 -> "🫲" to S.gesture_hand_out_left
            dir == 1 -> "🫸" to S.gesture_push_right
            dir == 3 -> "🫷" to S.gesture_push_left
            dir == 2 -> "🫳" to S.gesture_palm_down
            else -> "✋" to S.gesture_open_palm
        }
        return "$emoji " + t(id)
    }

    /** What the gesture guide lists: the poses and motions Ping tells apart. */
    val guide: List<Entry> = listOf(
        Entry("✊", "Fist (also 🤛 left, 🤜 right, 👊 down)"),
        Entry("✋", "Open palm, fingers together"),
        Entry("🖐️", "Open hand, fingers spread"),
        Entry("🖖", "Vulcan salute: gap between middle and ring"),
        Entry("🤚", "Back of the hand"),
        Entry("🫷 🫸", "Push left / push right"),
        Entry("🫱 🫲", "Hand out to the side"),
        Entry("🫳", "Palm down"),
        Entry("☝️ 👇 👈 👉", "One finger up, down, left, right"),
        Entry("✌️", "Peace: index and middle"),
        Entry("🤞", "Fingers crossed"),
        Entry("🤘", "Rock on"),
        Entry("🤟", "Love you"),
        Entry("🤙", "Call me"),
        Entry("👍 👎", "Thumbs up / down"),
        Entry("🫰", "Thumb and index out"),
        Entry("👌", "OK: thumb and index touching, others out"),
        Entry("🤏 🤌", "Pinch"),
        Entry("3️⃣ 4️⃣", "Three or four fingers"),
        Entry("👋", "Wave: open hand side to side"),
        Entry("↻ ↺", "Draw a circle in the air, either way round"),
        Entry("← → ↑ ↓", "Swipe: one sweep left, right, up or down, then stop"),
        Entry("👏 🙏 🤝 🫶", "Both hands together, with any of the poses above"),
    )
}
