package com.ping.app.service

import com.ping.app.R
import com.ping.app.model.Contact
import com.ping.app.model.ExchangeSession.Reason
import com.ping.app.model.ExchangeSession.State
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Runs whole swaps in-process: engines talk through [FakeAir] instead of Nearby Connections. */
class ExchangeEngineTest {

    private val air = FakeAir()
    private val executors = mutableListOf<ExecutorService>()
    private val code = "F3A0"

    private class Peer(val engine: ExchangeEngine, val scope: CoroutineScope) {
        val saved: MutableList<Contact> = Collections.synchronizedList(mutableListOf())
        val stopped = AtomicBoolean()
        val state get() = engine.session.value?.state
    }

    @AfterEach fun tearDown() { executors.forEach { it.shutdownNow() } }

    private fun peer(
        id: String,
        card: String = """{"name":"User $id"}""",
        saveDelayMs: Long = 0,
        windowMs: Long = 30_000,
    ): Peer {
        val exec = Executors.newSingleThreadExecutor().also { executors += it }
        val scope = CoroutineScope(SupervisorJob() + exec.asCoroutineDispatcher())
        lateinit var p: Peer
        val engine = ExchangeEngine(
            transport = air.endpoint(id),
            scope = scope,
            cardProvider = { card.toByteArray() },
            saveContact = { delay(saveDelayMs); p.saved += it; it },
            onStop = { p.stopped.set(true) },
            windowMs = windowMs,
        )
        p = Peer(engine, scope)
        return p
    }

    private fun Peer.go(gesture: String = code) = apply { engine.scan(); engine.begin(gesture) }

    private fun await(what: String, ms: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    @Test
    fun `the same gesture completes on both phones`() {
        val a = peer("a").go()
        val b = peer("b").go()
        await("both completed") { a.state == State.COMPLETED && b.state == State.COMPLETED }
        assertEquals(listOf("User b"), a.saved.map { it.displayName })
        assertEquals(listOf("User a"), b.saved.map { it.displayName })
        await("both stopped") { a.stopped.get() && b.stopped.get() }
    }

    @Test
    fun `wrong gestures up to the attempt limit end as a gesture mismatch`() {
        val a = peer("a").go()
        val wrong = (0 until 500).map { "F${it}A1" }
            .filter { it != code && Pairing.token(it) == Pairing.token(code) }
            .take(ExchangeEngine.MAX_ATTEMPTS)
        wrong.forEachIndexed { i, gesture ->
            val p = peer("w$i").go(gesture)
            await("attempt ${i + 1} failed") { a.engine.failures == i + 1 }
            p.engine.close()
            p.scope.cancel()
            assertTrue(p.saved.isEmpty())
        }
        await("no match") { a.state == State.NO_MATCH }
        assertEquals(Reason.GESTURE_MISMATCH, a.engine.session.value?.reason)
        assertTrue(a.saved.isEmpty())
        await("a stopped") { a.stopped.get() }
    }

    @Test
    fun `a share that beats our onConnected is still used`() {
        val a = peer("a")
        air.lateConnect("a", 300)
        a.go()
        val b = peer("b").go()
        await("both completed") { a.state == State.COMPLETED && b.state == State.COMPLETED }
    }

    @Test
    fun `a verified peer that leaves without its card is an error`() {
        val a = peer("a").go()
        val b = peer("b")
        air.drop("b", ExchangeEngine.TYPE_CARD)
        b.go()
        await("a errored") { a.state == State.ERROR }
        assertTrue(a.saved.isEmpty())
        assertEquals(State.COMPLETED, b.state)
    }

    @Test
    fun `an empty card is rejected while ours still reaches the peer`() {
        val a = peer("a").go()
        val b = peer("b", card = "{}").go()
        await("a errored, b completed") { a.state == State.ERROR && b.state == State.COMPLETED }
        assertEquals(R.string.exchange_peer_empty_card, a.engine.session.value?.errorRes)
        assertTrue(a.saved.isEmpty())
        await("both stopped") { a.stopped.get() && b.stopped.get() }
    }

    @Test
    fun `a saver slower than the linger still completes`() {
        val a = peer("a", saveDelayMs = ExchangeEngine.LINGER_MS + 1_000).go()
        val b = peer("b").go()
        await("b completed") { b.state == State.COMPLETED }
        await("b stopped") { b.stopped.get() }
        assertTrue(a.state != State.ERROR)
        await("a completed") { a.state == State.COMPLETED }
        assertEquals(listOf("User b"), a.saved.map { it.displayName })
    }

    @Test
    fun `both sides wait for each other's done before closing`() {
        val a = peer("a", saveDelayMs = 300).go()
        val b = peer("b").go()
        await("both stopped") { a.stopped.get() && b.stopped.get() }
        assertEquals(State.COMPLETED, a.state)
        assertTrue(ExchangeEngine.TYPE_DONE in air.types("a", "b"))
        assertTrue(ExchangeEngine.TYPE_DONE in air.types("b", "a"))
    }

    @Test
    fun `a card that arrives without its tag is never saved`() {
        val a = peer("a").go()
        val b = peer("b")
        air.drop("b", ExchangeEngine.TYPE_CONFIRM)
        b.go()
        await("b sent its card") { ExchangeEngine.TYPE_CARD in air.types("b", "a") }
        Thread.sleep(ExchangeEngine.LINGER_MS + 500)
        assertTrue(a.saved.isEmpty())
        assertTrue(a.state != State.COMPLETED)
    }

    @Test
    fun `a link stuck connecting times out`() {
        val a = peer("a", windowMs = 1_000).go()
        val b = peer("b")
        air.drop("b", ExchangeEngine.TYPE_SHARE)
        b.go()
        await("a connecting") { a.state == State.CONNECTING }
        await("a timed out") { a.state == State.NO_MATCH }
        assertEquals(Reason.TIMEOUT, a.engine.session.value?.reason)
        await("a stopped") { a.stopped.get() }
    }

    @Test
    fun `beginning again republishes a cleared session`() {
        val a = peer("a").go()
        await("searching") { a.state == State.SEARCHING }
        a.engine.session.value = null
        a.engine.begin(code)
        await("republished") { a.engine.session.value != null }
        assertNotNull(a.state)
    }

    @Test
    fun `a failed connection request is retried`() {
        val a = peer("a")
        val b = peer("b")
        air.failRequests("a", 1)
        air.failRequests("b", 1)
        a.go(); b.go()
        await("both completed") { a.state == State.COMPLETED && b.state == State.COMPLETED }
    }

    @Test
    fun `a timeout leaves a transport another feature took over alone`() {
        val a = peer("a", windowMs = 500).go()
        await("searching") { a.state == State.SEARCHING }
        val node = air.node("a")
        a.scope.launch {
            node.onPayloadReceived = { _, _ -> }
            node.startAdvertising("room", "room")
        }
        await("a timed out") { a.stopped.get() }
        assertEquals("room", node.advertised)
    }

    @Test
    fun `an abandoned engine stops its radios but not the service`() {
        val a = peer("a").go()
        await("searching") { a.state == State.SEARCHING }
        a.engine.abandon()
        await("advertising stopped") { air.node("a").advertised == null }
        Thread.sleep(200)
        assertFalse(a.stopped.get())
    }
}
