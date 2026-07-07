package com.ping.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat

/**
 * Notification-channel registry. Services call [ensureChannels] in onCreate()
 * and reference [CHANNEL_EXCHANGE] for the foreground swap notification.
 */
object NotificationChannels {

    /** Foreground swap service — active while a swap is in progress. */
    const val CHANNEL_EXCHANGE = "ping_exchange_channel"

    /** Idempotent — safe to call on every service start. */
    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_EXCHANGE,
                "Ping Exchange",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active while a Ping contact swap is in progress."
                lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
                setShowBadge(false)
            }
        )
    }
}
