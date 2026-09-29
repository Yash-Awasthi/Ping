package com.ping.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.ping.app.R
import com.ping.app.data.ContactRepository
import com.ping.app.data.ProfileRepository
import com.ping.app.model.Contact
import com.ping.app.model.ExchangeSession
import com.ping.app.utils.CryptoUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.security.KeyPair
import java.security.PublicKey
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey
import javax.inject.Inject

/**
 * The Ping swap engine.
 *
 * Matchmaking by gesture:
 *  - We advertise `<codeToken>|<sessionNonce>`, a hash of the gesture code, never the code.
 *  - When we discover a peer we only request a connection if its token equals ours,
 *    and we only accept an incoming connection whose token equals ours.
 *  - Once connected we swap ephemeral ECDH keys and both users compare a six-digit
 *    code. No card leaves this phone until its user confirms the codes match.
 *
 * If no matching peer appears within [WINDOW_MS] the session ends as NO_MATCH.
 * Everything is offline — Nearby Connections uses BLE + Wi-Fi Direct directly.
 */
@AndroidEntryPoint
class NearbyExchangeService : Service() {

    @Inject lateinit var transport: NearbyTransport
    @Inject lateinit var profileRepo: ProfileRepository
    @Inject lateinit var contactRepo: ContactRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gson = Gson()

    private lateinit var gestureCode: String
    private lateinit var localName: String
    private lateinit var keyPair: KeyPair

    /** Per-session suffix: keeps the advertisement unique and the initiator tie-break fair. */
    private val sessionNonce = UUID.randomUUID().toString().take(8)

    /** Per-endpoint negotiated AES key, set after we receive the peer's pubkey. */
    private val sessionKeys = ConcurrentHashMap<String, SecretKey>()
    /** Endpoints we've already sent our card to, so we don't double-send. */
    private val cardSent = ConcurrentHashMap.newKeySet<String>()
    /** Set once the local user has confirmed the short authentication string. */
    @Volatile private var confirmed = false
    /** Peer card that arrived before the local user confirmed. */
    @Volatile private var pendingCardJson: String? = null

    @Volatile private var connectedEndpoint: String? = null
    private var windowJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONFIRM -> { onUserConfirmed(); return START_NOT_STICKY }
            ACTION_REJECT -> { onUserRejected(); return START_NOT_STICKY }
        }
        val code = intent?.getStringExtra(EXTRA_GESTURE_CODE)
        if (code == null) {
            Timber.w("No gesture code supplied — stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification(getString(R.string.notif_starting)))
        begin(code)
        return START_NOT_STICKY
    }

    private fun begin(code: String) {
        gestureCode = code
        keyPair = CryptoUtils.generateEphemeralKeyPair()
        _session.value = ExchangeSession(gestureCode, ExchangeSession.State.SEARCHING)

        scope.launch {
            // The advertised name is readable by any scanner in range, so it carries the
            // token rather than the code, and never the user's display name.
            localName = Pairing.advertisement(code, sessionNonce)
            wireCallbacks()
            transport.startAdvertising(localName, SERVICE_ID)
            transport.startDiscovery(SERVICE_ID)
            Timber.i("Ping searching for a matching gesture")
        }

        // Pairing window: if nothing connects in time, give up.
        windowJob = scope.launch {
            delay(WINDOW_MS)
            if (_session.value?.state == ExchangeSession.State.SEARCHING) {
                Timber.i("Pairing window elapsed with no match")
                _session.value = _session.value?.copy(state = ExchangeSession.State.NO_MATCH)
                shutdown()
            }
        }
    }

    private fun wireCallbacks() {
        transport.onEndpointFound = onEndpointFound@{ endpointId, remoteName ->
            if (!Pairing.matches(gestureCode, remoteName)) {
                Timber.d("Ignoring %s — advertisement does not match this gesture", endpointId)
                return@onEndpointFound
            }
            // Deterministic tie-break: only the lexicographically-smaller name
            // initiates, so both sides don't request each other simultaneously.
            if (Pairing.shouldInitiate(localName, remoteName)) {
                Timber.i("Match found (%s) — requesting connection", endpointId)
                transport.requestConnection(localName, endpointId)
            } else {
                Timber.i("Match found (%s) — waiting for their request", endpointId)
            }
            markConnecting()
        }

        transport.onConnectionInitiated = { endpointId, remoteName ->
            if (Pairing.matches(gestureCode, remoteName) && connectedEndpoint in listOf(null, endpointId)) {
                transport.acceptConnection(endpointId)
            } else {
                Timber.w("Rejecting %s: token mismatch or session already taken", endpointId)
                transport.rejectConnection(endpointId)
            }
        }

        transport.onConnected = { endpointId, _, _ ->
            if (connectedEndpoint == null) {
                connectedEndpoint = endpointId
                markConnecting()
                transport.stopAdvertising()
                transport.stopDiscovery()
                // Send our public key first; card follows once we have theirs.
                transport.sendBytes(endpointId, frame(TYPE_KEY, CryptoUtils.encodePublicKey(keyPair.public)))
                Timber.i("Connected to %s — sent public key", endpointId)
            } else if (endpointId != connectedEndpoint) {
                transport.rejectConnection(endpointId)
            }
        }

        transport.onPayloadReceived = { endpointId, data -> handlePayload(endpointId, data) }

        transport.onDisconnected = { endpointId ->
            Timber.d("Disconnected %s", endpointId)
            if (endpointId == connectedEndpoint &&
                _session.value?.state != ExchangeSession.State.COMPLETED
            ) {
                _session.value = _session.value?.copy(state = ExchangeSession.State.ERROR)
                shutdown()
            }
        }
    }

    private fun handlePayload(endpointId: String, data: ByteArray) {
        if (data.isEmpty()) return
        val type = data[0]
        val body = data.copyOfRange(1, data.size)
        when (type) {
            TYPE_KEY -> {
                if (endpointId != connectedEndpoint || sessionKeys.containsKey(endpointId)) return
                val peerPub: PublicKey = runCatching { CryptoUtils.decodePublicKey(body) }
                    .getOrElse { Timber.e(it, "Bad peer key"); return }
                val key = CryptoUtils.deriveSharedKey(keyPair.private, peerPub)
                sessionKeys[endpointId] = key
                val sas = CryptoUtils.shortAuthString(CryptoUtils.encodePublicKey(keyPair.public), body)
                _session.value = _session.value?.copy(
                    state = ExchangeSession.State.AWAITING_CONFIRM,
                    sas = sas,
                )
            }
            TYPE_CARD -> {
                val key = sessionKeys[endpointId] ?: run { Timber.w("Card before key"); return }
                val json = runCatching { String(CryptoUtils.decrypt(key, body)) }
                    .getOrElse { Timber.e(it, "Card decrypt failed"); return }
                if (confirmed) saveContact(json) else pendingCardJson = json
            }
        }
    }

    private fun onUserConfirmed() {
        val endpoint = connectedEndpoint ?: return
        val key = sessionKeys[endpoint] ?: return
        if (_session.value?.state != ExchangeSession.State.AWAITING_CONFIRM) return
        confirmed = true
        _session.value = _session.value?.copy(state = ExchangeSession.State.EXCHANGING)
        sendCard(endpoint, key)
        pendingCardJson?.let { saveContact(it) }
    }

    private fun onUserRejected() {
        _session.value = _session.value?.copy(state = ExchangeSession.State.CANCELLED)
        shutdown()
    }

    private fun sendCard(endpointId: String, key: SecretKey) {
        if (!cardSent.add(endpointId)) return
        scope.launch {
            val map = profileRepo.getOrCreate().toShareableMap()
            val sealed = CryptoUtils.encrypt(key, gson.toJson(map).toByteArray())
            transport.sendBytes(endpointId, frame(TYPE_CARD, sealed))
            Timber.i("Card sent to %s", endpointId)
        }
    }

    private fun saveContact(json: String) {
        scope.launch {
            @Suppress("UNCHECKED_CAST")
            val map = runCatching { gson.fromJson(json, Map::class.java) as Map<String, String> }
                .getOrElse { emptyMap() }
            val contact = Contact.fromMap(map)
            if (contact.isEmptyCard) {
                Timber.w("Rejected an empty card")
                _session.value = _session.value?.copy(
                    state = ExchangeSession.State.ERROR,
                    errorMessage = "The other person has not set up their card.",
                )
                shutdown()
                return@launch
            }
            val saved = contactRepo.saveReceived(contact)
            Timber.i("Saved contact: %s", saved.displayName)
            _session.value = _session.value?.copy(
                state = ExchangeSession.State.COMPLETED,
                receivedContact = saved
            )
            shutdown()
        }
    }

    private fun markConnecting() {
        if (_session.value?.state == ExchangeSession.State.SEARCHING) {
            _session.value = _session.value?.copy(state = ExchangeSession.State.CONNECTING)
        }
    }

    private fun shutdown() {
        windowJob?.cancel()
        runCatching {
            transport.stopAdvertising()
            transport.stopDiscovery()
            transport.stopAllEndpoints()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        runCatching {
            transport.onEndpointFound = null
            transport.onConnectionInitiated = null
            transport.onConnected = null
            transport.onPayloadReceived = null
            transport.onDisconnected = null
            transport.stopAllEndpoints()
        }
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private fun frame(type: Byte, body: ByteArray): ByteArray = ByteArray(body.size + 1).also {
        it[0] = type
        System.arraycopy(body, 0, it, 1, body.size)
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, NotificationChannels.CHANNEL_EXCHANGE)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()

    companion object {
        private const val SERVICE_ID = "com.ping.app.swap"
        private const val NOTIF_ID = 42
        private const val EXTRA_GESTURE_CODE = "gesture_code"
        private const val ACTION_CONFIRM = "com.ping.app.action.CONFIRM"
        private const val ACTION_REJECT = "com.ping.app.action.REJECT"
        /** Pairing window — both people must be searching within this window. */
        const val WINDOW_SECONDS = 10
        private const val WINDOW_MS = WINDOW_SECONDS * 1000L

        // Payload framing: first byte = type.
        private const val TYPE_KEY: Byte = 1
        private const val TYPE_CARD: Byte = 2

        private val _session = MutableStateFlow<ExchangeSession?>(null)
        /** Latest session state, observed by the Exchange screen. */
        val session: StateFlow<ExchangeSession?> = _session

        /** Start a swap keyed to the locked [gestureCode]. */
        fun start(context: Context, gestureCode: String) {
            val intent = Intent(context, NearbyExchangeService::class.java)
                .putExtra(EXTRA_GESTURE_CODE, gestureCode)
            context.startForegroundService(intent)
        }

        /** The user saw the same six digits on both phones. */
        fun confirm(context: Context) {
            context.startService(
                Intent(context, NearbyExchangeService::class.java).setAction(ACTION_CONFIRM)
            )
        }

        /** The digits differ, or the user does not recognise the peer. */
        fun reject(context: Context) {
            context.startService(
                Intent(context, NearbyExchangeService::class.java).setAction(ACTION_REJECT)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NearbyExchangeService::class.java))
            _session.value = null
        }

        /** Reset the shared session state (e.g. when leaving the exchange screen). */
        fun clearSession() { _session.value = null }
    }
}
