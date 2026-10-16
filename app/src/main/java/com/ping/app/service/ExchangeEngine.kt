package com.ping.app.service

import com.ping.app.R
import com.ping.app.model.Contact
import com.ping.app.model.ExchangeSession
import com.ping.app.model.ExchangeSession.Reason
import com.ping.app.model.ExchangeSession.State
import com.ping.app.utils.CryptoUtils
import com.ping.app.utils.Pake
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.security.spec.ECPoint
import java.util.UUID

/**
 * The Ping swap engine.
 *
 * Matchmaking by gesture:
 *  - We advertise [Pairing.advertisement], a 4-bit bucket of the gesture code, never the code.
 *  - We connect to a peer in our bucket and run [Pake] keyed by the gesture code and bound to the link.
 *  - Our sealed card rides with our tag; the peer's is saved only once its tag proves the gesture.
 *    A failed attempt drops that peer and searches on, up to [MAX_ATTEMPTS] per session.
 *  - After saving, each side sends DONE; the link closes once both DONEs crossed or [LINGER_MS]
 *    passed, so the slower phone is never cut off before its own card is out.
 *
 * Every transport callback is re-posted onto [scope], which must be single-threaded: the state
 * below is confined to it. If no peer proves the gesture within the window the session is NO_MATCH.
 */
class ExchangeEngine(
    private val transport: NearbyTransport,
    private val scope: CoroutineScope,
    private val cardProvider: suspend () -> ByteArray,
    private val saveContact: suspend (Contact) -> Contact,
    private val onStop: () -> Unit,
    val session: MutableStateFlow<ExchangeSession?> = MutableStateFlow(null),
    private val windowMs: Long = WINDOW_MS,
) {
    /** Null until the gesture locks; callbacks only buffer endpoints before that. */
    private var gestureCode: String? = null
    private var localName = ""
    private var scanning = false
    /** Every Ping endpoint in range, endpointId to advertisement. */
    private val seen = LinkedHashMap<String, String>()
    /** Endpoints we accepted an incoming connection from during this attempt. */
    private val initiated = HashSet<String>()
    /** Endpoints that failed the exchange this session; never retried. */
    private val tried = HashSet<String>()
    @Volatile internal var failures = 0
        private set

    /** Per-session suffix: keeps the advertisement unique and the initiator tie-break fair. */
    private val sessionNonce = UUID.randomUUID().toString().take(8)

    /** Link tokens reported by the transport before each connection is accepted. */
    private val linkTokens = HashMap<String, ByteArray>()
    private lateinit var generator: ECPoint
    /** Built ahead of the link so a connection only waits on one scalar multiplication. */
    private var attempt: Pairing.Attempt? = null
    /** Belongs to [connectedEndpoint]. */
    private var linkContext = ByteArray(0)
    /** Shares that arrived before our own side of that attempt was set up. */
    private val earlyShares = HashMap<String, ByteArray>()
    /** Our card JSON, read at lock so sealing it never waits on the database. */
    private lateinit var card: Deferred<ByteArray>

    private var connectedEndpoint: String? = null
    /** The matched endpoint we requested, or are waiting on, before any link is up. */
    private var requested: String? = null
    private var windowJob: Job? = null
    /** Last state published, so a screen that cleared [session] can be shown it again. */
    private var current: ExchangeSession? = null

    // Finish handshake for the connected attempt.
    private var cardSent = false
    private var peerDone = false
    private var doneSent = false
    private var linkGone = false
    /** The peer's card was handed to [saveContact]; the session can only end as COMPLETED or ERROR now. */
    private var saving = false
    /** Nothing left for us to receive: wait only for our card to leave, then close. */
    private var localDone = false
    private var saved = false
    private var lingerJob: Job? = null
    private var stopped = false
    /** Replaced by a newer engine: never publish again into the shared [session]. */
    private var detached = false

    private val payloadCallback: (String, ByteArray) -> Unit = { id, data -> scope.launch { handlePayload(id, data) } }

    /** Starts discovery so peers are already known by the time the gesture locks. */
    fun scan() {
        scope.launch {
            if (scanning || stopped) return@launch
            scanning = true
            wireCallbacks()
            transport.startDiscovery(SERVICE_ID)
        }
    }

    fun begin(code: String) {
        scope.launch {
            if (stopped) return@launch
            if (gestureCode != null) {
                if (code == gestureCode) session.value = current
                return@launch
            }
            gestureCode = code
            // The advertised name is readable by any scanner in range, so it carries the
            // token rather than the code, and never the user's display name.
            localName = Pairing.advertisement(code, sessionNonce)
            transport.startAdvertising(localName, SERVICE_ID)
            card = scope.async { cardProvider() }
            generator = Pake.generator(code)
            attempt = Pairing.Attempt(generator)
            publish(ExchangeSession(code, State.SEARCHING))
            Timber.i("Ping searching for a matching gesture")
            Pairing.firstMatch(code, seen, tried)?.let { onMatch(it.key, it.value) }
            updateOthers()

            windowJob = scope.launch {
                delay(windowMs)
                if (current?.state in ACTIVE && !saving) {
                    Timber.i("Pairing window elapsed in %s", current?.state)
                    update { it.copy(state = State.NO_MATCH, reason = Reason.TIMEOUT) }
                    shutdown()
                }
            }
        }
    }

    /** Ends this engine for a newer one: radios and links go, the service stays up. */
    fun abandon() {
        scope.launch {
            detached = true
            shutdown(notify = false)
        }
    }

    /** Releases the transport, unless another feature has since taken it over. */
    fun close() {
        if (transport.onPayloadReceived !== payloadCallback) return
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
    }

    private fun wireCallbacks() {
        transport.onEndpointFound = { endpointId, remoteName ->
            scope.launch {
                seen[endpointId] = remoteName
                val code = gestureCode ?: return@launch
                if (Pairing.matches(code, remoteName) && endpointId !in tried) onMatch(endpointId, remoteName)
                else updateOthers()
            }
        }

        transport.onEndpointLost = { endpointId ->
            scope.launch {
                seen.remove(endpointId)
                updateOthers()
            }
        }

        transport.onAuthToken = { endpointId, token -> scope.launch { linkTokens[endpointId] = token } }

        transport.onConnectionInitiated = { endpointId, remoteName ->
            scope.launch {
                val code = gestureCode
                if (code != null && !stopped && Pairing.matches(code, remoteName) && endpointId !in tried &&
                    connectedEndpoint in listOf(null, endpointId)
                ) {
                    initiated += endpointId
                    transport.acceptConnection(endpointId)
                } else {
                    Timber.w("Rejecting %s: token mismatch or session already taken", endpointId)
                    transport.rejectConnection(endpointId)
                }
            }
        }

        transport.onConnected = { endpointId, remoteName, _ -> scope.launch { onConnected(endpointId, remoteName) } }

        transport.onPayloadReceived = payloadCallback

        transport.onDisconnected = { endpointId -> scope.launch { onDisconnected(endpointId) } }
    }

    private fun onConnected(endpointId: String, remoteName: String) {
        val code = gestureCode
        if (code != null && !stopped && connectedEndpoint == null && Pairing.matches(code, remoteName)) {
            connectedEndpoint = endpointId
            requested = null
            cardSent = false; peerDone = false; doneSent = false; linkGone = false
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

    private fun onDisconnected(endpointId: String) {
        Timber.d("Disconnected %s", endpointId)
        val ours = endpointId == requested || endpointId in initiated
        initiated -= endpointId
        if (stopped) return
        if (endpointId != connectedEndpoint) {
            if (connectedEndpoint == null && ours) requestFailed(endpointId)
            return
        }
        when {
            saving || localDone -> { linkGone = true; maybeFinish() }
            attempt?.verified == true -> {
                update { it.copy(state = State.ERROR) }
                shutdown()
            }
            else -> attemptFailed(endpointId)
        }
    }

    /** Our request never became a link (IO error, collision): search on and match again shortly. */
    private fun requestFailed(endpointId: String) {
        if (requested == endpointId) requested = null
        if (current?.state == State.CONNECTING) update { it.copy(state = State.SEARCHING) }
        // ponytail: retries the same peer until the window ends; cap per peer if radios thrash
        scope.launch {
            delay(REQUEST_GRACE_MS)
            val code = gestureCode ?: return@launch
            if (stopped || connectedEndpoint != null || requested != null) return@launch
            Pairing.firstMatch(code, seen, tried)?.let { onMatch(it.key, it.value) }
        }
    }

    private fun onMatch(endpointId: String, remoteName: String) {
        if (connectedEndpoint != null || stopped) return
        requested = endpointId
        // The smaller name requests at once; the larger only after a grace with no request from
        // them, so a peer slow to discover us cannot stall the pair and collisions stay rare.
        if (Pairing.shouldInitiate(localName, remoteName)) {
            Timber.i("Match found (%s) — requesting connection", endpointId)
            transport.requestConnection(localName, endpointId)
        } else {
            Timber.i("Match found (%s) — waiting briefly for their request", endpointId)
            scope.launch {
                delay(REQUEST_GRACE_MS)
                if (!stopped && connectedEndpoint == null && endpointId !in initiated && endpointId !in tried) {
                    transport.requestConnection(localName, endpointId)
                }
            }
        }
        markConnecting()
    }

    private fun updateOthers() {
        val code = gestureCode ?: return
        val others = Pairing.othersNearby(code, seen)
        val legacy = Pairing.legacyNearby(seen)
        current?.takeIf { it.othersNearby != others || it.legacyNearby != legacy }
            ?.let { publish(it.copy(othersNearby = others, legacyNearby = legacy)) }
    }

    private fun handlePayload(endpointId: String, data: ByteArray) {
        if (data.isEmpty() || data.size > MAX_FRAME || stopped) return
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
                update { it.copy(state = State.EXCHANGING) }
                openCard(endpointId, a)
            }
            TYPE_CARD -> {
                if (saving || localDone) return
                a.onCard(body)
                openCard(endpointId, a)
            }
            TYPE_DONE -> {
                peerDone = true
                maybeFinish()
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
            val json = runCatching { card.await() }.getOrElse {
                Timber.e(it, "Could not read our card")
                if (attempt === a && !stopped) {
                    update { s -> s.copy(state = State.ERROR) }
                    shutdown()
                }
                return@launch
            }
            val sealed = CryptoUtils.encrypt(k.session, json)
            if (attempt !== a || stopped || linkGone) return@launch
            transport.sendBytes(endpointId, frame(TYPE_CARD, sealed))
            cardSent = true
            Timber.i("Card sent to %s", endpointId)
            maybeFinish()
        }
    }

    private fun openCard(endpointId: String, a: Pairing.Attempt) {
        val json = runCatching { a.openCard() }
            .getOrElse { Timber.e(it, "Card decrypt failed"); attemptFailed(endpointId); return } ?: return
        receive(String(json, Charsets.UTF_8))
    }

    private fun receive(json: String) {
        val map = runCatching { Json.decodeFromString<Map<String, String>>(json) }.getOrElse { emptyMap() }
        val contact = Contact.fromMap(map)
        if (contact.isEmptyCard) {
            Timber.w("Rejected an empty card")
            update { it.copy(state = State.ERROR, errorRes = R.string.exchange_peer_empty_card) }
            localDone = true
            maybeFinish()
            return
        }
        saving = true
        scope.launch {
            // The row must land even when the screen closes mid-save, or the peer believes we have it.
            val ok = withContext(NonCancellable) {
                runCatching { saveContact(contact) }
                    .onSuccess { c ->
                        Timber.i("Saved contact: %s", c.displayName)
                        update { it.copy(state = State.COMPLETED, receivedContact = c) }
                    }
                    .onFailure { Timber.e(it, "Could not save the contact") }
                    .isSuccess
            }
            if (!ok) {
                update { it.copy(state = State.ERROR) }
                shutdown()
                return@launch
            }
            saved = true
            localDone = true
            maybeFinish()
        }
    }

    /** Closes once our card is out and the peer said DONE, or after [LINGER_MS] without it. */
    private fun maybeFinish() {
        if (!localDone || stopped) return
        if (linkGone) { shutdown(); return }
        if (!cardSent) return
        val peer = connectedEndpoint ?: return
        if (saved && !doneSent) {
            doneSent = true
            transport.sendBytes(peer, frame(TYPE_DONE, ByteArray(0)))
        }
        if (peerDone) shutdown()
        else if (lingerJob == null) lingerJob = scope.launch { delay(LINGER_MS); shutdown() }
    }

    /** The peer does not hold our gesture, or is guessing: drop it and search on. */
    private fun attemptFailed(endpointId: String) {
        if (endpointId != connectedEndpoint || stopped) return
        tried += endpointId
        failures++
        connectedEndpoint = null
        attempt = Pairing.Attempt(generator)
        earlyShares.clear()
        initiated.clear()
        linkTokens.remove(endpointId)
        runCatching { transport.disconnect(endpointId) }
        val code = gestureCode ?: return
        if (failures >= MAX_ATTEMPTS) {
            Timber.i("Gesture exchange failed %d times; giving up", failures)
            update { it.copy(state = State.NO_MATCH, reason = Reason.GESTURE_MISMATCH) }
            shutdown()
            return
        }
        update { it.copy(state = State.SEARCHING) }
        transport.startAdvertising(localName, SERVICE_ID)
        transport.startDiscovery(SERVICE_ID)
        Pairing.firstMatch(code, seen, tried)?.let { onMatch(it.key, it.value) }
    }

    private fun markConnecting() {
        if (current?.state == State.SEARCHING) update { it.copy(state = State.CONNECTING) }
    }

    private fun publish(s: ExchangeSession) {
        if (detached) return
        current = s
        session.value = s
    }

    private fun update(f: (ExchangeSession) -> ExchangeSession) {
        current?.let { publish(f(it)) }
    }

    private fun shutdown(notify: Boolean = true) {
        if (stopped) return
        stopped = true
        windowJob?.cancel()
        lingerJob?.cancel()
        // A room or rollcall that took the transport since must keep its radios and links.
        if (transport.onPayloadReceived === payloadCallback) runCatching {
            transport.stopAdvertising()
            transport.stopDiscovery()
            transport.stopAllEndpoints()
        }
        if (notify) onStop()
    }

    private fun frame(type: Byte, body: ByteArray): ByteArray = ByteArray(body.size + 1).also {
        it[0] = type
        System.arraycopy(body, 0, it, 1, body.size)
    }

    companion object {
        /** Bumped with the advertisement version so older builds never connect to this one. */
        const val SERVICE_ID = "com.ping.app.swap.v2"
        /** Pairing window — both people must be searching within this window. */
        const val WINDOW_SECONDS = 30
        private const val WINDOW_MS = WINDOW_SECONDS * 1000L

        /** Wrong-gesture peers tolerated per session; each one is an online guess at our gesture. */
        const val MAX_ATTEMPTS = 3

        // ponytail: fixed guess at BLE discovery latency; tune from on-device connect timings
        private const val REQUEST_GRACE_MS = 1500L
        /** How long a finished side keeps the link up for the peer's DONE. */
        const val LINGER_MS = 1500L
        // ponytail: a card is a few hundred bytes; raise if cards ever carry photos
        private const val MAX_FRAME = 64 * 1024

        private val ACTIVE = setOf(State.SEARCHING, State.CONNECTING, State.EXCHANGING)

        // Payload framing: first byte = type.
        internal const val TYPE_CARD: Byte = 2
        internal const val TYPE_SHARE: Byte = 3
        internal const val TYPE_CONFIRM: Byte = 4
        internal const val TYPE_DONE: Byte = 5
    }
}
