package com.ping.app.auth

import com.ping.app.auth.GestureFingerprint.Motion
import com.ping.app.auth.GestureFingerprint.Palm

/**
 * Best-effort emoji and name for a [GestureFingerprint], shown to the user while they hold a
 * pose. Matching never uses this text — only the fingerprint code — so a wrong guess here
 * cannot break a swap.
 */
object GestureNames {

    private const val T = 0b00001
    private const val I = 0b00010
    private const val M = 0b00100
    private const val R = 0b01000
    private const val P = 0b10000
    private const val ALL = 0b11111

    /** One entry of the in-app gesture guide. */
    data class Entry(val emoji: String, val name: String)

    fun label(fp: GestureFingerprint): String {
        val motion = fp.motion
        val base = if (motion != null) motionLabel(fp, motion) else poseLabel(fp)
        val sided = if (fp.hand != null) "$base (${fp.hand.text})" else base
        return if (fp.partner != null) "👏 both hands · $sided" else sided
    }

    private fun motionLabel(fp: GestureFingerprint, motion: Motion): String = when (motion) {
        Motion.WAVE -> "👋 wave"
        Motion.CIRCLE_CW -> "${fingerEmoji(fp.fingerMask)} circle clockwise ↻"
        Motion.CIRCLE_CCW -> "${fingerEmoji(fp.fingerMask)} circle counter-clockwise ↺"
    }

    private fun fingerEmoji(mask: Int) = when (mask) {
        I -> "☝️"
        0 -> "✊"
        ALL -> "✋"
        else -> "🖐️"
    }

    private fun poseLabel(fp: GestureFingerprint): String {
        val mask = fp.fingerMask
        val dir = fp.angleBucket
        val toward = when (dir) { 0 -> "up"; 1 -> "right"; 2 -> "down"; else -> "left" }
        if (fp.pinch) {
            return when {
                mask and (M or R or P) == (M or R or P) -> "👌 OK"
                mask and (M or R or P) == 0 -> "🤏 pinch"
                else -> "🤌 pinched fingers"
            }
        }
        return when (mask) {
            0 -> when (dir) { 1 -> "🤜 fist right"; 3 -> "🤛 fist left"; 2 -> "👊 fist down"; else -> "✊ fist" }
            T -> when (dir) { 2 -> "👎 thumbs down"; 0 -> "👍 thumbs up"; else -> "👍 thumb $toward" }
            I -> when (dir) { 2 -> "👇 pointing down"; 1 -> "👉 pointing right"; 3 -> "👈 pointing left"; else -> "☝️ pointing up" }
            I or M -> if (fp.crossed) "🤞 fingers crossed" else "✌️ peace $toward"
            I or P -> "🤘 rock on"
            T or I or P -> "🤟 love you"
            T or P -> "🤙 call me"
            T or I -> "🫰 finger gun"
            I or M or R -> "3️⃣ three fingers $toward"
            NON_THUMB_ALL -> "4️⃣ four fingers $toward"
            ALL -> openHand(fp, dir)
            else -> "🖐️ ${fp.openFingers} fingers out, $toward"
        }
    }

    private const val NON_THUMB_ALL = I or M or R or P

    private fun openHand(fp: GestureFingerprint, dir: Int): String {
        if (fp.gaps == 0b010) return "🖖 vulcan salute"
        if (fp.gaps == 0b111) return "🖐️ fingers spread"
        if (fp.gaps != 0) return "🖐️ open hand, some fingers apart"
        return when {
            fp.palm == Palm.AWAY -> "🤚 back of hand"
            fp.palm == Palm.EDGE && dir == 1 -> "🫱 hand out to the right"
            fp.palm == Palm.EDGE && dir == 3 -> "🫲 hand out to the left"
            dir == 1 -> "🫸 push right"
            dir == 3 -> "🫷 push left"
            dir == 2 -> "🫳 palm down"
            else -> "✋ open palm"
        }
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
        Entry("👏 🙏 🤝 🫶", "Both hands together, with any of the poses above"),
    )
}
