package com.ping.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ping.app.R
import com.ping.app.data.ContactRepository
import com.ping.app.data.ProfileRepository
import com.ping.app.model.ExchangeSession
import com.ping.app.model.Profile
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import javax.inject.Inject

/**
 * Foreground shell around [ExchangeEngine]: keeps the swap alive while the screen is away.
 *
 * Discovery starts when the swap screen opens ([prewarm]) so peers are already known by the
 * time the gesture locks. Everything is offline — the transport uses BLE + Wi-Fi Direct directly.
 */
@AndroidEntryPoint
class NearbyExchangeService : Service() {

    @Inject lateinit var transport: NearbyTransport
    @Inject lateinit var profileRepo: ProfileRepository
    @Inject lateinit var contactRepo: ContactRepository

    // Main is single-threaded, which the engine's state confinement relies on.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var engine: ExchangeEngine? = null
    private var fields: Set<String> = Profile.FIELDS

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification(getString(R.string.notif_starting)))
        val e = engine ?: ExchangeEngine(
            transport = transport,
            scope = scope,
            cardProvider = { Json.encodeToString(profileRepo.getOrCreate().toShareableMap(fields)).toByteArray() },
            saveContact = contactRepo::saveReceived,
            onStop = {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            },
            session = _session,
        ).also { engine = it }
        e.scan()
        intent?.getStringExtra(EXTRA_GESTURE_CODE)?.let { code ->
            intent.getStringArrayExtra(EXTRA_FIELDS)?.let { fields = it.toSet() }
            e.begin(code)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        engine?.close()
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, NotificationChannels.CHANNEL_EXCHANGE)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()

    companion object {
        private const val NOTIF_ID = 42
        private const val EXTRA_GESTURE_CODE = "gesture_code"
        private const val EXTRA_FIELDS = "fields"
        const val WINDOW_SECONDS = ExchangeEngine.WINDOW_SECONDS

        private val _session = MutableStateFlow<ExchangeSession?>(null)
        /** Latest session state, observed by the Exchange screen. */
        val session: StateFlow<ExchangeSession?> = _session

        /** Start discovering before any gesture is locked, so the later match is quick. */
        fun prewarm(context: Context) {
            context.startForegroundService(Intent(context, NearbyExchangeService::class.java))
        }

        /** Start a swap keyed to the locked [gestureCode], sharing only the card [fields]. */
        fun start(context: Context, gestureCode: String, fields: Set<String> = Profile.FIELDS) {
            val intent = Intent(context, NearbyExchangeService::class.java)
                .putExtra(EXTRA_GESTURE_CODE, gestureCode)
                .putExtra(EXTRA_FIELDS, fields.toTypedArray())
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NearbyExchangeService::class.java))
            _session.value = null
        }

        /** Reset the shared session state (e.g. when leaving the exchange screen). */
        fun clearSession() { _session.value = null }
    }
}
