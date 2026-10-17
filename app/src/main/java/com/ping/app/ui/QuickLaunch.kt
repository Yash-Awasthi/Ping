package com.ping.app.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.ping.app.R
import androidx.navigation3.runtime.NavKey
import com.ping.app.ui.navigation.Exchange
import com.ping.app.ui.navigation.RollCall
import com.ping.app.ui.navigation.Room

/** Launcher shortcuts and the Quick Settings tile open a screen directly through [EXTRA_ROUTE]. */
object QuickLaunch {
    const val EXTRA_ROUTE = "com.ping.app.extra.ROUTE"

    // Extra values are kept from the string-route era so already pinned shortcuts still open.
    const val SWAP = "exchange"
    private const val ROOM = "room"
    private const val ROLL_CALL = "roll_call"
    private const val MY_CARD = "my_card"

    /** Opens Profile and shares the saved card; never put on the back stack, so not a Screen key. */
    data object ShareMyCard : NavKey

    /** MainActivity is exported, so only these screens may be opened from outside. */
    private val allowed = mapOf(SWAP to Exchange, ROOM to Room, ROLL_CALL to RollCall, MY_CARD to ShareMyCard)

    fun keyOf(intent: Intent?): NavKey? = intent?.getStringExtra(EXTRA_ROUTE)?.let(allowed::get)

    fun intent(context: Context, route: String): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(EXTRA_ROUTE, route)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    /** Dynamic rather than static shortcuts, so the debug build's package suffix needs no extra XML. */
    fun publishShortcuts(context: Context) {
        val shortcuts = listOf(
            Triple("swap", SWAP, R.string.shortcut_swap),
            Triple("room", ROOM, R.string.shortcut_room),
            Triple("roll", ROLL_CALL, R.string.home_roll_button),
            Triple("card", MY_CARD, R.string.shortcut_my_card),
        ).map { (id, route, label) ->
            ShortcutInfoCompat.Builder(context, id)
                .setShortLabel(context.getString(label))
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_launcher))
                .setIntent(intent(context, route))
                .build()
        }
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
    }
}
