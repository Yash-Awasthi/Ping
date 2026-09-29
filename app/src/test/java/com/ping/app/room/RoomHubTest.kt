package com.ping.app.room

import android.content.ContextWrapper
import com.ping.app.data.ProfileRepository
import com.ping.app.data.local.ProfileDao
import com.ping.app.model.Profile
import com.ping.app.room.RoomProtocol.FileMeta
import com.ping.app.service.NearbyTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/** Runs whole rooms in-process: hubs talk through [FakeAir] instead of Nearby Connections. */
class RoomHubTest {

    @get:Rule val tmp = TemporaryFolder()

    private val hubs = mutableListOf<RoomHub>()
    private val radios = mutableMapOf<String, NearbyTransport>()
    private lateinit var air: FakeAir

    @Before fun setUp() { air = FakeAir() }

    @After fun tearDown() { hubs.forEach { it.leave() } }

    private fun newHub(id: String, name: String): RoomHub {
        val root = tmp.newFolder(id)
        val context = object : ContextWrapper(null) {
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getExternalFilesDir(type: String?) = File(root, "ext").apply { mkdirs() }
        }
        val dao = object : ProfileDao {
            override fun observe(id: String): Flow<Profile?> = MutableStateFlow(null)
            override suspend fun get(id: String) = Profile(displayName = name)
            override suspend fun upsert(profile: Profile) {}
        }
        val radio = air.endpoint(id).also { radios[id] = it }
        return RoomHub(context, radio, ProfileRepository(dao)).also { hubs += it }
    }

    private fun await(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    private fun RoomHub.share(name: String, content: String) {
        addShared(FileMeta("", name, content.length.toLong())) { ByteArrayInputStream(content.toByteArray()) }
    }

    private fun admitted(host: RoomHub, guest: RoomHub) {
        await("guest knocks") { host.state.value.pending.isNotEmpty() }
        host.decide(host.state.value.pending.single().endpointId, true)
        await("guest in room") { guest.state.value.phase == RoomPhase.IN_ROOM }
    }

    @Test
    fun `a guest is only listed after the host lets them in`() {
        val host = newHub("h", "Hana")
        val guest = newHub("g", "Gus")
        host.host("F31A0"); Thread.sleep(50)
        host.share("notes.txt", "hello")
        guest.join("F31A0")
        await("guest waits") { guest.state.value.phase == RoomPhase.WAITING_APPROVAL }
        await("guest name") { host.state.value.pending.singleOrNull()?.name == "Gus" }
        assertTrue(guest.state.value.manifest.files.isEmpty())

        admitted(host, guest)
        await("manifest") { guest.state.value.manifest.files.size == 1 }
        assertEquals(listOf("Hana", "Gus"), guest.state.value.manifest.members)
        assertEquals("notes.txt", guest.state.value.manifest.files.single().name)
    }

    @Test
    fun `a guest downloads a host file byte for byte`() {
        val host = newHub("h", "Hana")
        val guest = newHub("g", "Gus")
        host.host("F0A0"); Thread.sleep(50)
        host.share("a.txt", "host bytes")
        guest.join("F0A0")
        admitted(host, guest)
        await("manifest") { guest.state.value.manifest.files.isNotEmpty() }

        val gid = guest.state.value.manifest.files.single().id
        guest.download(gid)
        await("download") { guest.state.value.transfers[gid]?.status == Transfer.Status.DONE }
        assertEquals("host bytes", File(guest.state.value.transfers.getValue(gid).path!!).readText())
    }

    @Test
    fun `a file moves from one guest to another through the host`() {
        val host = newHub("h", "Hana")
        val ada = newHub("a", "Ada")
        val bob = newHub("b", "Bob")
        host.host("F1A1"); Thread.sleep(50)
        ada.join("F1A1"); admitted(host, ada)
        ada.share("ada.bin", "from ada")
        bob.join("F1A1")
        await("bob knocks") { host.state.value.pending.isNotEmpty() }
        host.decide(host.state.value.pending.single().endpointId, true)
        await("bob sees ada's file") { bob.state.value.manifest.files.any { it.name == "ada.bin" } }

        val gid = bob.state.value.manifest.files.single { it.name == "ada.bin" }.id
        bob.download(gid)
        await("bob download") { bob.state.value.transfers[gid]?.status == Transfer.Status.DONE }
        assertEquals("from ada", File(bob.state.value.transfers.getValue(gid).path!!).readText())
    }

    @Test
    fun `the host can pull a guest file too`() {
        val host = newHub("h", "Hana")
        val ada = newHub("a", "Ada")
        host.host("F1A1"); Thread.sleep(50)
        ada.join("F1A1"); admitted(host, ada)
        ada.share("ada.bin", "for the host")
        await("host sees file") { host.state.value.manifest.files.isNotEmpty() }

        val gid = host.state.value.manifest.files.single().id
        host.download(gid)
        await("host download") { host.state.value.transfers[gid]?.status == Transfer.Status.DONE }
        assertEquals("for the host", File(host.state.value.transfers.getValue(gid).path!!).readText())
    }

    @Test
    fun `a denied guest gets nothing`() {
        val host = newHub("h", "Hana")
        val guest = newHub("g", "Gus")
        host.host("F2A2"); Thread.sleep(50)
        host.share("secret.txt", "s")
        guest.join("F2A2")
        await("guest knocks") { host.state.value.pending.isNotEmpty() }
        host.decide(host.state.value.pending.single().endpointId, false)
        await("denied") { guest.state.value.phase == RoomPhase.DENIED }
        assertTrue(guest.state.value.manifest.files.isEmpty())
    }

    @Test
    fun `both sides see the same link code before the host decides`() {
        val host = newHub("h", "Hana")
        val guest = newHub("g", "Gus")
        host.host("F3A3"); Thread.sleep(50)
        guest.join("F3A3")
        await("codes") { host.state.value.pending.singleOrNull()?.digits == "4821" && guest.state.value.linkDigits == "4821" }
    }

    @Test
    fun `guests learn the room closed when the host leaves`() {
        val host = newHub("h", "Hana")
        val guest = newHub("g", "Gus")
        host.host("F4A0"); Thread.sleep(50)
        guest.join("F4A0"); admitted(host, guest)
        host.leave()
        await("closed") { guest.state.value.phase == RoomPhase.CLOSED }
    }

    @Test
    fun `a guest that leaves disappears from the room`() {
        val host = newHub("h", "Hana")
        val ada = newHub("a", "Ada")
        host.host("F4A1"); Thread.sleep(50)
        ada.join("F4A1"); admitted(host, ada)
        ada.share("x.txt", "x")
        await("listed") { host.state.value.manifest.files.size == 1 }
        radios.getValue("a").disconnect("h")
        await("removed") { host.state.value.manifest.files.isEmpty() && host.state.value.manifest.members == listOf("Hana") }
    }

    @Test
    fun `an unapproved guest cannot pull files`() {
        val host = newHub("h", "Hana")
        val guest = newHub("g", "Gus")
        host.host("F5A0"); Thread.sleep(50)
        var opened = 0
        host.addShared(FileMeta("", "s.txt", 1)) { opened++; ByteArrayInputStream(byteArrayOf(1)) }
        guest.join("F5A0")
        await("knock") { host.state.value.pending.isNotEmpty() }
        radios.getValue("g").sendBytes("h", RoomProtocol.frameText(RoomProtocol.T_REQUEST, "host:f1"))
        Thread.sleep(300)
        assertEquals(0, opened)
    }

    @Test
    fun `a stream nobody asked for is dropped`() {
        val host = newHub("h", "Hana")
        val guest = newHub("g", "Gus")
        host.host("F5A1"); Thread.sleep(50)
        host.share("s.txt", "abc")
        guest.join("F5A1"); admitted(host, guest)
        await("manifest") { guest.state.value.manifest.files.isNotEmpty() }
        val forged = RoomProtocol.header("host:f1") + "abc".toByteArray()
        radios.getValue("h").sendStream("g", ByteArrayInputStream(forged), forged.size.toLong())
        Thread.sleep(300)
        assertTrue(guest.state.value.transfers.isEmpty())
    }

    @Test
    fun `a file longer than announced fails and leaves nothing behind`() {
        val host = newHub("h", "Hana")
        val guest = newHub("g", "Gus")
        host.host("F5A2"); Thread.sleep(50)
        host.addShared(FileMeta("", "big.bin", 5)) { ByteArrayInputStream(ByteArray(50)) }
        guest.join("F5A2"); admitted(host, guest)
        await("manifest") { guest.state.value.manifest.files.isNotEmpty() }
        val gid = guest.state.value.manifest.files.single().id
        guest.download(gid)
        await("failed") { guest.state.value.transfers[gid]?.status == Transfer.Status.FAILED }
        assertEquals(0, downloads("g").size)
    }

    @Test
    fun `a file shorter than announced fails`() {
        val host = newHub("h", "Hana")
        val guest = newHub("g", "Gus")
        host.host("F5A3"); Thread.sleep(50)
        host.addShared(FileMeta("", "short.bin", 50)) { ByteArrayInputStream(ByteArray(4)) }
        guest.join("F5A3"); admitted(host, guest)
        await("manifest") { guest.state.value.manifest.files.isNotEmpty() }
        val gid = guest.state.value.manifest.files.single().id
        guest.download(gid)
        await("failed") { guest.state.value.transfers[gid]?.status == Transfer.Status.FAILED }
        assertEquals(0, downloads("g").size)
    }

    @Test
    fun `two downloads of the same name do not overwrite each other`() {
        val host = newHub("h", "Hana")
        val guest = newHub("g", "Gus")
        host.host("F5A4"); Thread.sleep(50)
        host.share("same.txt", "one")
        host.share("same.txt", "two")
        guest.join("F5A4"); admitted(host, guest)
        await("manifest") { guest.state.value.manifest.files.size == 2 }
        guest.state.value.manifest.files.forEach { guest.download(it.id) }
        await("both") { guest.state.value.transfers.values.count { it.status == Transfer.Status.DONE } == 2 }
        assertEquals(setOf("same.txt", "same (2).txt"), downloads("g").map { it.name }.toSet())
    }

    private fun downloads(id: String): List<File> =
        File(tmp.root, "$id/ext/Ping").listFiles().orEmpty().toList()

    @Test
    fun `a different gesture never joins the room`() {
        val host = newHub("h", "Hana")
        val guest = newHub("g", "Gus")
        host.host("F2A2"); Thread.sleep(50)
        guest.join("F9A0")
        Thread.sleep(300)
        assertEquals(RoomPhase.SEARCHING, guest.state.value.phase)
        assertTrue(host.state.value.pending.isEmpty())
    }
}

/** In-memory stand-in for the radio: endpoint ids are the names passed to [endpoint]. */
private class FakeAir {
    private val nodes = mutableMapOf<String, Node>()

    fun endpoint(id: String): NearbyTransport = Node(id).also { nodes[id] = it }

    private inner class Node(val id: String) : NearbyTransport {
        override var onPayloadReceived: ((String, ByteArray) -> Unit)? = null
        override var onConnected: ((String, String, Boolean) -> Unit)? = null
        override var onDisconnected: ((String) -> Unit)? = null
        override var onEndpointFound: ((String, String) -> Unit)? = null
        override var onConnectionInitiated: ((String, String) -> Unit)? = null
        override var onStreamReceived: ((String, InputStream) -> Unit)? = null
        override var onAuthDigits: ((String, String) -> Unit)? = null

        var advertised: String? = null
        var discovering = false
        private val accepted = mutableSetOf<String>()
        private val names = mutableMapOf<String, String>()

        override fun startAdvertising(localName: String, serviceId: String, star: Boolean) {
            advertised = localName
            nodes.values.filter { it !== this && it.discovering }.forEach { it.onEndpointFound?.invoke(id, localName) }
        }

        override fun startDiscovery(serviceId: String, star: Boolean) {
            discovering = true
            nodes.values.filter { it !== this }.forEach { n ->
                n.advertised?.let { onEndpointFound?.invoke(n.id, it) }
            }
        }

        override fun requestConnection(localName: String, endpointId: String) {
            val peer = nodes[endpointId] ?: return
            names[endpointId] = peer.advertised.orEmpty()
            peer.names[id] = localName
            onAuthDigits?.invoke(endpointId, "4821")
            peer.onAuthDigits?.invoke(id, "4821")
            onConnectionInitiated?.invoke(endpointId, peer.advertised.orEmpty())
            peer.onConnectionInitiated?.invoke(id, localName)
        }

        override fun acceptConnection(endpointId: String) {
            accepted += endpointId
            val peer = nodes[endpointId] ?: return
            if (id in peer.accepted) {
                onConnected?.invoke(endpointId, names[endpointId].orEmpty(), false)
                peer.onConnected?.invoke(id, peer.names[id].orEmpty(), true)
            }
        }

        override fun rejectConnection(endpointId: String) {}

        override fun sendBytes(endpointId: String, data: ByteArray) {
            nodes[endpointId]?.onPayloadReceived?.invoke(id, data)
        }

        override fun sendStream(endpointId: String, inputStream: InputStream, lengthHint: Long): Boolean {
            nodes[endpointId]?.onStreamReceived?.invoke(id, inputStream) ?: return false
            return true
        }

        override fun disconnect(endpointId: String) {
            nodes[endpointId]?.onDisconnected?.invoke(id)
            onDisconnected?.invoke(endpointId)
        }

        override fun stopAdvertising() { advertised = null }
        override fun stopDiscovery() { discovering = false }
        override fun stopAllEndpoints() {
            accepted.filter { nodes[it]?.accepted?.contains(id) == true }.forEach { peer ->
                nodes[peer]?.onDisconnected?.invoke(id)
            }
            accepted.clear()
        }
    }
}
