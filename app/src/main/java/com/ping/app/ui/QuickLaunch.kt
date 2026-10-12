package com.ping.app.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.ping.app.R
import com.ping.app.ui.navigation.Routes

/** Launcher shortcuts and the Quick Settings tile open a screen directly through [EXTRA_ROUTE]. */
object QuickLaunch {
    const val EXTRA_ROUTE = "com.ping.app.extra.ROUTE"

    /** MainActivity is exported, so only these routes may be opened from outside. */
    private val allowed = setOf(Routes.EXCHANGE, Routes.ROOM)

    fun routeOf(intent: Intent?): String? = intent?.getStringExtra(EXTRA_ROUTE)?.takeIf { it in allowed }

    fun intent(context: Context, route: String): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(EXTRA_ROUTE, route)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    /** Dynamic rather than static shortcuts, so the debug build's package suffix needs no extra XML. */
    fun publishShortcuts(context: Context) {
        val shortcuts = listOf(
            Triple("swap", Routes.EXCHANGE, R.string.shortcut_swap),
            Triple("room", Routes.ROOM, R.string.shortcut_room),
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
