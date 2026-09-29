package com.ping.app.room

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ping.app.R
import com.ping.app.service.NotificationChannels
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Keeps the process alive while a room is open; the room itself lives in [RoomHub]. */
@AndroidEntryPoint
class RoomHubService : Service() {

    @Inject lateinit var hub: RoomHub

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, NotificationChannels.CHANNEL_EXCHANGE)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_room))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notification)
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
    }

    override fun onDestroy() {
        hub.leave()
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 43

        fun start(context: Context) {
            context.startForegroundService(Intent(context, RoomHubService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RoomHubService::class.java))
        }
    }
}
