package com.ping.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat

/**
 * Centralized notification-channel registry with hardened privacy settings.
 *
 * All channels are created here; services call [ensureChannels] in onCreate() and
 * reference the [CHANNEL_*] constants directly so channel definitions are never
 * duplicated across services.
 *
 * Visibility model
 * | Channel          | Importance | Lock-screen visibility |
 * |------------------|------------|------------------------|
 * | EXCHANGE         | LOW        | PRIVATE (title only)   |
 * | SECURITY_ALERT   | HIGH       | SECRET (hidden)        |
 *
 * SECURITY_ALERT is VISIBILITY_SECRET: the SAS code or identity-mismatch warning
 * MUST NOT appear on the lock screen — an observer shoulder-surfing the lock screen
 * could capture the SAS PIN or learn that an exchange is in progress.
 */
object NotificationChannels {

    // Channel IDs — reference these from services, never use raw strings

    /** Foreground exchange service — active during an AURA session. */
    const val CHANNEL_EXCHANGE = "aura_exchange_channel"

    /**
     * Security alerts — SAS mismatch, MITM detected, identity key rotation.
     * VISIBILITY_SECRET: never shown on lock screen.
     */
    const val CHANNEL_SECURITY_ALERT = "aura_security_alert_channel"

    // Bootstrap

    /**
     * Create all AURA notification channels.
     *
     * Safe to call multiple times — NotificationManager.createNotificationChannel is idempotent.
     * Call from Service.onCreate() before the first startForeground().
     */
    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Exchange foreground service — low importance, no sound, lock-screen shows title only
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_EXCHANGE,
                "AURA Exchange",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description         = "Active while an AURA contact exchange is in progress."
                lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
                setShowBadge(false)
            }
        )

        // Security alerts — high importance so they surface, but SECRET on lock screen
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SECURITY_ALERT,
                "AURA Security Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description          = "Security events: identity mismatch, MITM warnings, SAS failures."
                lockscreenVisibility = NotificationCompat.VISIBILITY_SECRET
                setShowBadge(true)
            }
        )

    }

    // Security-alert helper

    /**
     * Post a security alert notification.
     *
     * Content is intentionally generic — no SAS PIN, no endpoint ID, no profile data.
     * The user taps to open the app and see details in-context.
     */
    fun postSecurityAlert(
        context: Context,
        notificationId: Int,
        title: String,
        body: String
    ) {
        val nm    = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(context, CHANNEL_SECURITY_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .build()
        nm.notify(notificationId, notif)
    }
}
