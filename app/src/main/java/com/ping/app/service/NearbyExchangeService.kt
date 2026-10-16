package com.ping.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ping.app.R
import com.ping.app.data.ContactRepository
import com.ping.app.data.ProfileRepository
import com.ping.app.model.Contact
import com.ping.app.model.ExchangeSession
import com.ping.app.utils.CryptoUtils
import com.ping.app.utils.Pake
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.security.spec.ECPoint
import javax.inject.Inject

/**
 * The Ping swap engine.
 *
 * Matchmaking by gesture:
 *  - We advertise `<codeToken>|<sessionNonce>`, a hash of the gesture code, never the code.
 *  - The token is a 4-bit bucket, so it only narrows who we try; we connect to a peer in
 *    our bucket and run [Pake] keyed by the gesture code and bound to the link.
 *  - Our card leaves sealed with our confirmation tag; the peer's card is saved only after its
 *    tag proves the same gesture. A failed attempt drops that peer and searches on, up to
 *    [MAX_ATTEMPTS] per session.
 *
 * Discovery starts when the swap screen opens ([prewarm]) so peers are already known by the
 * time the gesture locks. If no matching peer appears within [WINDOW_MS] of the lock the
 * session ends as NO_MATCH.
 * Everything is offline — Nearby Connections uses BLE + Wi-Fi Direct directly.
 */
@AndroidEntryPoint
class NearbyExchangeService : Service() {

    @Inject lateinit var transport: NearbyTransport
    @Inject lateinit var profileRepo: ProfileRepository
    @Inject lateinit var contactRepo: ContactRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Null until the gesture locks; callbacks only buffer endpoints before that. */
    private var gestureCode: String? = null
    private lateinit var localName: String
    private var scanning = false
    /** Every Ping endpoint in range, endpointId to advertisement. Main thread only. */
    private val seen = LinkedHashMap<String, String>()
    /** Endpoints with a connection in progress, either direction. Main thread only. */
    private val initiated = HashSet<String>()
    /** Endpoints that failed the exchange this session; never retried. */
    private val tried = HashSet<String>()
    private var failures = 0

    /** Per-session suffix: keeps the advertisement unique and the initiator tie-break fair. */
    private val sessionNonce = UUID.randomUUID().toString().take(8)

    /** Link tokens reported by the transport before each connection is accepted. */
    private val linkTokens = ConcurrentHashMap<String, ByteArray>()
    private lateinit var generator: ECPoint
    /** Built ahead of the link so a connection only waits on one scalar multiplication. */
    @Volatile private var attempt: Pairing.Attempt? = null
    /** Belongs to [connectedEndpoint]. */
    @Volatile private var linkContext = ByteArray(0)
    /** Shares that arrived before our own side of that attempt was set up. */
    private val earlyShares = ConcurrentHashMap<String, ByteArray>()
    /** Our card JSON, read at lock so sealing it never waits on the database. */
    private lateinit var card: Deferred<ByteArray>

    @Volatile private var connectedEndpoint: String? = null
    private var windowJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification(getString(R.string.notif_starting)))
        if (!scanning) {
            scanning = true
            wireCallbacks()
            transport.startDiscovery(SERVICE_ID)
        }
        intent?.getStringExtra(EXTRA_GESTURE_CODE)?.let(::begin)
        return START_NOT_STICKY
    }

    private fun begin(code: String) {
        if (gestureCode != null) return
        gestureCode = code
        // The advertised name is readable by any scanner in range, so it carries the
        // token rather than the code, and never the user's display name.
        localName = Pairing.advertisement(code, sessionNonce)
        transport.startAdvertising(localName, SERVICE_ID)
        card = scope.async { Json.encodeToString(profileRepo.getOrCreate().toShareableMap()).toByteArray() }
        generator = Pake.generator(code)
        attempt = Pairing.Attempt(generator)
        _session.value = ExchangeSession(code, ExchangeSession.State.SEARCHING)
        Timber.i("Ping searching for a matching gesture")
        Pairing.firstMatch(code, seen, tried)?.let { onMatch(it.key, it.value) }
        updateOthers()

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
            seen[endpointId] = remoteName
            val code = gestureCode ?: return@onEndpointFound
            if (Pairing.matches(code, remoteName) && endpointId !in tried) onMatch(endpointId, remoteName)
            else updateOthers()
        }

        transport.onEndpointLost = { endpointId ->
            seen.remove(endpointId)
            updateOthers()
        }

        transport.onAuthToken = { endpointId, token -> linkTokens[endpointId] = token }

        transport.onConnectionInitiated = { endpointId, remoteName ->
            val code = gestureCode
            if (code != null && Pairing.matches(code, remoteName) && endpointId !in tried &&
                connectedEndpoint in listOf(null, endpointId)
            ) {
                initiated += endpointId
                transport.acceptConnection(endpointId)
            } else {
                Timber.w("Rejecting %s: token mismatch or session already taken", endpointId)
                transport.rejectConnection(endpointId)
            }
        }

        transport.onConnected = { endpointId, remoteName, _ ->
            val code = gestureCode
            if (code != null && connectedEndpoint == null) {
                connectedEndpoint = endpointId
                markConnecting()
                transport.stopAdvertising()
                transport.stopDiscovery()
                linkContext = Pake.context(localName, remoteName, linkTokens[endpointId])
                val a = attempt ?: Pairing.Attempt(generator).also { attempt = it }
                transport.sendBytes(endpointId, frame(TYPE_SHARE, a.pake.share))
                earlyShares.remove(endpointId)?.let { onShare(endpointId, it) }
                Timber.i("Connected to %s — sent key share", endpointId)
            } else if (endpointId != connectedEndpoint) {
                transport.disconnect(endpointId)
            }
        }

        transport.onPayloadReceived = { endpointId, data -> handlePayload(endpointId, data) }

        transport.onDisconnected = { endpointId ->
            Timber.d("Disconnected %s", endpointId)
            if (endpointId == connectedEndpoint && _session.value?.state != ExchangeSession.State.COMPLETED) {
                if (attempt?.verified == true) {
                    _session.value = _session.value?.copy(state = ExchangeSession.State.ERROR)
                    shutdown()
                } else {
                    attemptFailed(endpointId)
                }
            }
        }
    }

    private fun onMatch(endpointId: String, remoteName: String) {
        if (connectedEndpoint != null) return
        // The smaller name requests at once; the larger only after a grace with no request from
        // them, so a peer slow to discover us cannot stall the pair and collisions stay rare.
        if (Pairing.shouldInitiate(localName, remoteName)) {
            Timber.i("Match found (%s) — requesting connection", endpointId)
            transport.requestConnection(localName, endpointId)
        } else {
            Timber.i("Match found (%s) — waiting briefly for their request", endpointId)
            scope.launch(Dispatchers.Main) {
                delay(REQUEST_GRACE_MS)
                if (connectedEndpoint == null && endpointId !in initiated && endpointId !in tried) {
                    transport.requestConnection(localName, endpointId)
                }
            }
        }
        markConnecting()
    }

    private fun updateOthers() {
        val code = gestureCode ?: return
        val others = Pairing.othersNearby(code, seen)
        _session.value?.takeIf { it.othersNearby != others }?.let { _session.value = it.copy(othersNearby = others) }
    }

    private fun handlePayload(endpointId: String, data: ByteArray) {
        if (data.isEmpty()) return
        val body = data.copyOfRange(1, data.size)
        if (endpointId != connectedEndpoint) {
            if (data[0] == TYPE_SHARE && connectedEndpoint == null && endpointId !in tried) earlyShares[endpointId] = body
            return
        }
        val a = attempt ?: return
        when (data[0]) {
            TYPE_SHARE -> onShare(endpointId, body)
            TYPE_CONFIRM -> {
                if (a.keys == null || a.verified) return
                if (!a.onConfirm(body)) {
                    Timber.w("Confirmation from %s failed", endpointId)
                    attemptFailed(endpointId)
                    return
                }
                _session.value = _session.value?.copy(state = ExchangeSession.State.EXCHANGING)
                openCard(endpointId, a)
            }
            TYPE_CARD -> {
                a.onCard(body)
                openCard(endpointId, a)
            }
        }
    }

    private fun onShare(endpointId: String, share: ByteArray) {
        val a = attempt ?: return
        if (a.keys != null) return
        val k = a.onShare(share, linkContext) ?: run { attemptFailed(endpointId); return }
        transport.sendBytes(endpointId, frame(TYPE_CONFIRM, k.myTag))
        // Only a phone that derived this key can open the card, and our tag already lets it test
        // a guess, so the card rides with the tag instead of waiting a round trip for theirs.
        scope.launch {
            val sealed = CryptoUtils.encrypt(k.session, card.await())
            if (attempt !== a) return@launch
            transport.sendBytes(endpointId, frame(TYPE_CARD, sealed))
            Timber.i("Card sent to %s", endpointId)
        }
    }

    private fun openCard(endpointId: String, a: Pairing.Attempt) {
        val json = runCatching { a.openCard() }
            .getOrElse { Timber.e(it, "Card decrypt failed"); attemptFailed(endpointId); return } ?: return
        saveContact(String(json))
    }

    /** The peer does not hold our gesture, or is guessing: drop it and search on. */
    private fun attemptFailed(endpointId: String) {
        if (endpointId != connectedEndpoint) return
        tried += endpointId
        failures++
        connectedEndpoint = null
        attempt = Pairing.Attempt(generator)
        earlyShares.clear()
        linkTokens.remove(endpointId)
        runCatching { transport.disconnect(endpointId) }
        val code = gestureCode ?: return
        if (failures >= MAX_ATTEMPTS) {
            Timber.i("Gesture exchange failed %d times; giving up", failures)
            _session.value = _session.value?.copy(state = ExchangeSession.State.NO_MATCH)
            shutdown()
            return
        }
        _session.value = _session.value?.copy(state = ExchangeSession.State.SEARCHING)
        transport.startAdvertising(localName, SERVICE_ID)
        transport.startDiscovery(SERVICE_ID)
        Pairing.firstMatch(code, seen, tried)?.let { onMatch(it.key, it.value) }
    }

    private fun saveContact(json: String) {
        scope.launch {
            val map = runCatching { Json.decodeFromString<Map<String, String>>(json) }
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
            transport.onAuthToken = null
            transport.onEndpointLost = null
            transport.onConnected = null
            transport.onPayloadReceived = null
            transport.onDisconnected = null
            transport.stopAdvertising()
            transport.stopDiscovery()
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
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()

    companion object {
        private const val SERVICE_ID = "com.ping.app.swap"
        private const val NOTIF_ID = 42
        private const val EXTRA_GESTURE_CODE = "gesture_code"
        /** Pairing window — both people must be searching within this window. */
        const val WINDOW_SECONDS = 30
        private const val WINDOW_MS = WINDOW_SECONDS * 1000L

        /** Wrong-gesture peers tolerated per session; each one is an online guess at our gesture. */
        private const val MAX_ATTEMPTS = 3

        // ponytail: fixed guess at BLE discovery latency; tune from on-device connect timings
        private const val REQUEST_GRACE_MS = 1500L

        // Payload framing: first byte = type.
        private const val TYPE_CARD: Byte = 2
        private const val TYPE_SHARE: Byte = 3
        private const val TYPE_CONFIRM: Byte = 4

        private val _session = MutableStateFlow<ExchangeSession?>(null)
        /** Latest session state, observed by the Exchange screen. */
        val session: StateFlow<ExchangeSession?> = _session

        /** Start discovering before any gesture is locked, so the later match is quick. */
        fun prewarm(context: Context) {
            context.startForegroundService(Intent(context, NearbyExchangeService::class.java))
        }

        /** Start a swap keyed to the locked [gestureCode]. */
        fun start(context: Context, gestureCode: String) {
            val intent = Intent(context, NearbyExchangeService::class.java)
                .putExtra(EXTRA_GESTURE_CODE, gestureCode)
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
