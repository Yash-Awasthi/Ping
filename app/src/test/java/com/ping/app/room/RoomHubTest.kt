package com.ping.app.room

import android.content.ContextWrapper
import com.ping.app.data.ProfileRepository
import com.ping.app.data.local.ProfileDao
import com.ping.app.model.Profile
import com.ping.app.room.RoomProtocol.FileMeta
import com.ping.app.service.NearbyTransport
import com.ping.app.service.Pairing
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** Runs whole rooms in-process: hubs talk through [FakeAir] instead of Nearby Connections. */
class RoomHubTest {

    @TempDir lateinit var tmp: File

    private val hubs = mutableListOf<RoomHub>()
    private val radios = mutableMapOf<String, NearbyTransport>()
    private lateinit var air: FakeAir

    @BeforeEach fun setUp() { air = FakeAir() }

    // JUnit 6 fails a test whose TempDir cannot be deleted, so hubs must finish leaving first.
    @AfterEach fun tearDown() {
        hubs.forEach { it.leave() }
        hubs.forEach { hub -> await("leave") { hub.state.value == RoomState() } }
    }

    private fun newHub(id: String, name: String): RoomHub {
        val root = File(tmp, id).apply { mkdirs() }
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

    private fun await(what: String, ms: Long = 5_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    private fun RoomHub.share(name: String, content: String) {
        addShared(FileMeta("", name, content.length.toLong())) { ByteArrayInputStream(content.toByteArray()) }
    }

    private fun hostOpen(host: RoomHub, code: String) {
        host.host(code)
        await("room open") { host.state.value.phase == RoomPhase.OPEN }
    }

    /** Drains every hub's control thread a few times, so frames bounced between them all land. */
    private fun settle(vararg hubs: RoomHub) = repeat(8) { hubs.forEach { it.flush() } }

    private fun admitted(host: RoomHub, guest: RoomHub) {
        await("guest knocks") { host.state.value.pending.isNotEmpty() }
        host.decide(host.state.value.pending.single().endpointId, true)
        await("guest in room") { guest.state.value.phase == RoomPhase.IN_ROOM }
    }

    @Test
    fun `a guest is only listed after the host lets them in`() {
        val host = newHub("h", "Hana")
        val guest = newHub("g", "Gus")
        hostOpen(host, "F31A0")
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
    fun `the host knows its own key so its files are not offered back to it`() {
        val host = newHub("h", "Hana")
        host.host("F6A0")
        await("open") { host.state.value.phase == RoomPhase.OPEN }
        assertEquals(RoomProtocol.HOST_KEY, host.state.value.myKey)
    }

    @Test
    fun `a guest downloads a host file byte for byte`() {
        val host = newHub("h", "Hana")
        val guest = newHub("g", "Gus")
        hostOpen(host, "F0A0")
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
        hostOpen(host, "F1A1")
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
        hostOpen(host, "F1A1")
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
        hostOpen(host, "F2A2")
        host.share("secret.txt", "s")
        guest.join("F2A2")
        await("guest knocks") { host.state.value.pending.isNotEmpty() }
        host.decide(host.state.value.pending.single().endpointId, false)
        await("denied") { guest.state.value.phase == RoomPhase.DENIED }
        assertTrue(guest.state.value.manifest.files.isEmpty())
    }

    @Test
    fun `the same gesture alone gets a guest to the host's decision`() {
        val host = newHub("h", "Hana")
        val guest = newHub("g", "Gus")
        hostOpen(host, "F3A3")
        guest.join("F3A3")
        await("knock") { host.state.value.pending.singleOrNull()?.name == "Gus" }
        assertEquals(RoomPhase.WAITING_APPROVAL, guest.state.value.phase)
    }

    @Test
    fun `guests learn the room closed when the host leaves`() {
        val host = newHub("h", "Hana")
        val guest = newHub("g", "Gus")
        hostOpen(host, "F4A0")
        guest.join("F4A0"); admitted(host, guest)
        host.leave()
        await("closed") { guest.state.value.phase == RoomPhase.CLOSED }
    }

    @Test
    fun `a guest that leaves disappears from the room`() {
        val host = newHub("h", "Hana")
        val ada = newHub("a", "Ada")
        hostOpen(host, "F4A1")
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
        hostOpen(host, "F5A0")
        val opened = AtomicInteger()
        host.addShared(FileMeta("", "s.txt", 1)) { opened.incrementAndGet(); ByteArrayInputStream(byteArrayOf(1)) }
        guest.join("F5A0")
        await("knock") { host.state.value.pending.isNotEmpty() }
        radios.getValue("g").sendBytes("h", RoomProtocol.frameText(RoomProtocol.T_REQUEST, "host:f1"))
        host.flush()
        assertEquals(0, opened.get())
    }

    @Test
    fun `a stream nobody asked for is dropped`() {
        val host = newHub("h", "Hana")
        val guest = newHub("g", "Gus")
        hostOpen(host, "F5A1")
        host.share("s.txt", "abc")
        guest.join("F5A1"); admitted(host, guest)
        await("manifest") { guest.state.value.manifest.files.isNotEmpty() }
        val forged = RoomProtocol.header("host:f1") + "abc".toByteArray()
        radios.getValue("h").sendStream("g", ByteArrayInputStream(forged), forged.size.toLong())
        guest.drain()
        assertTrue(guest.state.value.transfers.isEmpty())
    }

    @Test
    fun `a stream whose checksum does not match fails and leaves nothing behind`() {
        val host = newHub("h", "Hana")
        val guest = newHub("g", "Gus")
        hostOpen(host, "F5A4")
        host.addShared(FileMeta("", "s.txt", 3)) { null }
        air.drop("h", RoomProtocol.T_FAILED)
        guest.join("F5A4"); admitted(host, guest)
        await("manifest") { guest.state.value.manifest.files.isNotEmpty() }
        val gid = guest.state.value.manifest.files.single().id
        guest.download(gid)
        // download() registers the request asynchronously; a stream arriving earlier is ignored.
        await("queued") { guest.state.value.transfers[gid] != null }
        val forged = RoomProtocol.header(gid) + "abc".toByteArray() + ByteArray(32)
        radios.getValue("h").sendStream("g", ByteArrayInputStream(forged), 3)
        await("failed") { guest.state.value.transfers[gid]?.status == Transfer.Status.FAILED }
        assertEquals(0, downloads("g").size)
    }

    @Test
    fun `a file longer than announced fails and leaves nothing behind`() {
        val host = newHub("h", "Hana")
        val guest = newHub("g", "Gus")
        hostOpen(host, "F5A2")
        host.addShared(FileMeta("", "big.bin", 5)) { ByteArrayInputStream(ByteArray(50)) }
        guest.join("F5A2"); admitted(host, guest)
        await("manifest") { guest.state.value.manifest.files.isNotEmpty() }
        val gid = guest.state.value.manifest.files.single().id
        guest.download(gid)
        await("failed") { guest.state.value.transfers[gid]?.status == Transfer.Status.FAILED }
        assertEquals(0, downloads("g").size)
    }

    @Test
    fun `a file shorter than announced fails and only its part is kept`() {
        val host = newHub("h", "Hana")
        val guest = newHub("g", "Gus")
        hostOpen(host, "F5A3")
        host.addShared(FileMeta("", "short.bin", 50)) { ByteArrayInputStream(ByteArray(4)) }
        guest.join("F5A3"); admitted(host, guest)
        await("manifest") { guest.state.value.manifest.files.isNotEmpty() }
        val gid = guest.state.value.manifest.files.single().id
        guest.download(gid)
        await("failed") { guest.state.value.transfers[gid]?.status == Transfer.Status.FAILED }
        assertEquals(listOf("short.bin.part"), downloads("g").map { it.name })
    }

    @Test
    fun `an interrupted download resumes from its offset and matches byte for byte`() {
        val host = newHub("h", "Hana")
        val guest = newHub("g", "Gus")
        val data = Random(7).nextBytes(200_000)
        var opens = 0
        hostOpen(host, "F6A1")
        host.addShared(FileMeta("", "big.bin", data.size.toLong())) {
            if (opens++ == 0) CutAfter(data, 70_000) else ByteArrayInputStream(data)
        }
        guest.join("F6A1"); admitted(host, guest)
        await("manifest") { guest.state.value.manifest.files.isNotEmpty() }
        val gid = guest.state.value.manifest.files.single().id

        guest.download(gid)
        await("cut") { guest.state.value.transfers[gid]?.status == Transfer.Status.FAILED }
        assertEquals(70_000L, guest.state.value.transfers.getValue(gid).bytes)
        assertArrayEquals(data.copyOf(70_000), File(downloads("g").single().path).readBytes())

        guest.download(gid)
        await("resumed") { guest.state.value.transfers[gid]?.status == Transfer.Status.DONE }
        assertArrayEquals(data, File(guest.state.value.transfers.getValue(gid).path!!).readBytes())
        assertEquals(listOf(gid, "$gid 70000"), air.texts("g", "h", RoomProtocol.T_REQUEST))
    }

    @Test
    fun `a file that changed since the cut restarts from zero once`() {
        val host = newHub("h", "Hana")
        val guest = newHub("g", "Gus")
        val before = Random(1).nextBytes(100_000)
        val after = Random(2).nextBytes(100_000)
        var opens = 0
        hostOpen(host, "F6A2")
        host.addShared(FileMeta("", "doc.bin", before.size.toLong())) {
            if (opens++ == 0) CutAfter(before, 40_000) else ByteArrayInputStream(after)
        }
        guest.join("F6A2"); admitted(host, guest)
        await("manifest") { guest.state.value.manifest.files.isNotEmpty() }
        val gid = guest.state.value.manifest.files.single().id

        guest.download(gid)
        await("cut") { guest.state.value.transfers[gid]?.status == Transfer.Status.FAILED }
        guest.download(gid)
        await("restarted") { guest.state.value.transfers[gid]?.status == Transfer.Status.DONE }
        assertArrayEquals(after, File(guest.state.value.transfers.getValue(gid).path!!).readBytes())
        assertEquals(listOf(gid, "$gid 40000", gid), air.texts("g", "h", RoomProtocol.T_REQUEST))
    }

    @Test
    fun `a relayed file resumes into the host cache from where it stopped`() {
        val host = newHub("h", "Hana")
        val ada = newHub("a", "Ada")
        val bob = newHub("b", "Bob")
        val data = Random(3).nextBytes(150_000)
        var opens = 0
        hostOpen(host, "F6A3")
        ada.join("F6A3"); admitted(host, ada)
        ada.addShared(FileMeta("", "ada.bin", data.size.toLong())) {
            if (opens++ == 0) CutAfter(data, 50_000) else ByteArrayInputStream(data)
        }
        bob.join("F6A3"); admitted(host, bob)
        await("bob sees file") { bob.state.value.manifest.files.isNotEmpty() }
        val gid = bob.state.value.manifest.files.single().id

        bob.download(gid)
        await("cut") { bob.state.value.transfers[gid]?.status == Transfer.Status.FAILED }
        bob.download(gid)
        await("done") { bob.state.value.transfers[gid]?.status == Transfer.Status.DONE }
        assertArrayEquals(data, File(bob.state.value.transfers.getValue(gid).path!!).readBytes())
        val local = RoomProtocol.localIdOf(gid)
        assertEquals(listOf(local, "$local 50000"), air.texts("h", "a", RoomProtocol.T_PUSH))
    }

    @Test
    fun `two downloads of the same name do not overwrite each other`() {
        val host = newHub("h", "Hana")
        val guest = newHub("g", "Gus")
        hostOpen(host, "F5A4")
        host.share("same.txt", "one")
        host.share("same.txt", "two")
        guest.join("F5A4"); admitted(host, guest)
        await("manifest") { guest.state.value.manifest.files.size == 2 }
        guest.state.value.manifest.files.forEach { guest.download(it.id) }
        await("both") { guest.state.value.transfers.values.count { it.status == Transfer.Status.DONE } == 2 }
        assertEquals(setOf("same.txt", "same (2).txt"), downloads("g").map { it.name }.toSet())
    }

    @Test
    fun `folder files land in their subfolders and single files stay flat`() {
        val host = newHub("h", "Hana")
        val guest = newHub("g", "Gus")
        hostOpen(host, "F7A1")
        host.addShared(FileMeta("", "a.txt", 1, "Trip/day1")) { ByteArrayInputStream("a".toByteArray()) }
        host.addShared(FileMeta("", "b.txt", 1, "Trip")) { ByteArrayInputStream("b".toByteArray()) }
        host.share("c.txt", "c")
        guest.join("F7A1"); admitted(host, guest)
        await("manifest") { guest.state.value.manifest.files.size == 3 }
        guest.downloadAll()
        await("all") { guest.state.value.transfers.values.count { it.status == Transfer.Status.DONE } == 3 }
        val root = File(tmp, "g/ext/Ping")
        assertEquals("a", File(root, "Trip/day1/a.txt").readText())
        assertEquals("b", File(root, "Trip/b.txt").readText())
        assertEquals("c", File(root, "c.txt").readText())
    }

    @Test
    fun `a path that climbs out of the download folder lands flat`() {
        val host = newHub("h", "Hana")
        val guest = newHub("g", "Gus")
        hostOpen(host, "F7A2")
        // The host's own list is not cleaned, so this is what a hostile host would broadcast.
        host.addShared(FileMeta("", "evil.txt", 1, "../../../escape")) { ByteArrayInputStream("e".toByteArray()) }
        guest.join("F7A2"); admitted(host, guest)
        await("manifest") { guest.state.value.manifest.files.isNotEmpty() }
        val gid = guest.state.value.manifest.files.single().id
        guest.download(gid)
        await("done") { guest.state.value.transfers[gid]?.status == Transfer.Status.DONE }
        assertEquals(File(tmp, "g/ext/Ping/evil.txt").canonicalFile, File(guest.state.value.transfers.getValue(gid).path!!).canonicalFile)
        assertTrue(File(tmp, "escape").let { !it.exists() } && File(tmp, "g/escape").let { !it.exists() })
    }

    @Test
    fun `a full room of guests all see each other's files and one more is turned away`() {
        val host = newHub("h", "Hana")
        hostOpen(host, "F8A1")
        host.share("host.txt", "h")
        val guests = (1..RoomProtocol.MAX_GUESTS).map { n ->
            newHub("g$n", "Guest$n").also { g ->
                g.join("F8A1"); admitted(host, g)
                g.share("from$n.txt", "$n")
            }
        }
        val last = guests.last()
        await("everyone listed") { last.state.value.manifest.files.size == RoomProtocol.MAX_GUESTS + 1 }
        assertEquals(RoomProtocol.MAX_GUESTS + 1, last.state.value.manifest.members.size)

        val gid = last.state.value.manifest.files.single { it.name == "from1.txt" }.id
        last.download(gid)
        await("relay") { last.state.value.transfers[gid]?.status == Transfer.Status.DONE }
        assertEquals("1", File(last.state.value.transfers.getValue(gid).path!!).readText())

        val extra = newHub("x", "Extra")
        extra.join("F8A1")
        await("extra searching") { extra.state.value.phase == RoomPhase.SEARCHING }
        settle(extra, host)
        assertEquals(RoomPhase.SEARCHING, extra.state.value.phase)
        assertTrue(host.state.value.pending.isEmpty())
        assertTrue(air.largestFrame <= RoomProtocol.MAX_FRAME)
    }

    @Test
    fun `download all keeps a few files in flight and gets every one`() {
        val host = newHub("h", "Hana")
        val guest = newHub("g", "Gus")
        val gate = CountDownLatch(1)
        hostOpen(host, "F9A8")
        repeat(5) { n -> host.addShared(FileMeta("", "f$n.txt", 1)) { Gated(byteArrayOf(n.toByte()), gate) } }
        guest.join("F9A8"); admitted(host, guest)
        await("manifest") { guest.state.value.manifest.files.size == 5 }
        try {
            guest.downloadAll()
            await("first requests") { air.texts("g", "h", RoomProtocol.T_REQUEST).size == 3 }
            guest.flush()
            assertEquals(3, air.texts("g", "h", RoomProtocol.T_REQUEST).size)
            assertEquals(2, guest.state.value.transfers.values.count { it.status == Transfer.Status.QUEUED })
        } finally {
            gate.countDown()
        }
        await("all") { guest.state.value.transfers.values.count { it.status == Transfer.Status.DONE } == 5 }
        assertEquals(5, air.texts("g", "h", RoomProtocol.T_REQUEST).size)
    }

    @Test
    fun `an unreadable file fails the download instead of holding its slot`() {
        val host = newHub("h", "Hana")
        val guest = newHub("g", "Gus")
        hostOpen(host, "F9A7")
        host.addShared(FileMeta("", "gone.txt", 3)) { null }
        guest.join("F9A7"); admitted(host, guest)
        await("manifest") { guest.state.value.manifest.files.isNotEmpty() }
        val gid = guest.state.value.manifest.files.single().id
        guest.download(gid)
        await("failed") { guest.state.value.transfers[gid]?.status == Transfer.Status.FAILED }
    }

    @Test
    fun `a relayed file reaches the requester while it is still landing at the host`() {
        val host = newHub("h", "Hana")
        val ada = newHub("a", "Ada")
        val bob = newHub("b", "Bob")
        val data = Random(4).nextBytes(1 shl 20)
        val gate = CountDownLatch(1)
        hostOpen(host, "F9A6")
        ada.join("F9A6"); admitted(host, ada)
        ada.addShared(FileMeta("", "ada.bin", data.size.toLong())) { HalfGated(data, gate) }
        bob.join("F9A6"); admitted(host, bob)
        await("bob sees file") { bob.state.value.manifest.files.isNotEmpty() }
        val gid = bob.state.value.manifest.files.single().id
        try {
            bob.download(gid)
            await("first half at bob") { downloads("b").singleOrNull()?.length() == data.size / 2L }
        } finally {
            gate.countDown()
        }
        await("done") { bob.state.value.transfers[gid]?.status == Transfer.Status.DONE }
        assertArrayEquals(data, File(bob.state.value.transfers.getValue(gid).path!!).readBytes())
    }

    @Test
    fun `megabytes move host to guest and guest to guest byte for byte`() {
        val host = newHub("h", "Hana")
        val ada = newHub("a", "Ada")
        val bob = newHub("b", "Bob")
        val data = Random(11).nextBytes(16 shl 20)
        hostOpen(host, "F9A9")
        host.addShared(FileMeta("", "host.bin", data.size.toLong())) { ByteArrayInputStream(data) }
        ada.join("F9A9"); admitted(host, ada)
        ada.addShared(FileMeta("", "ada.bin", data.size.toLong())) { ByteArrayInputStream(data) }
        bob.join("F9A9"); admitted(host, bob)
        await("listed") { bob.state.value.manifest.files.size == 2 }

        for (name in listOf("host.bin", "ada.bin")) {
            val gid = bob.state.value.manifest.files.single { it.name == name }.id
            val started = System.nanoTime()
            bob.download(gid)
            await(name, 60_000) { bob.state.value.transfers[gid]?.status == Transfer.Status.DONE }
            val secs = (System.nanoTime() - started) / 1e9
            println("Room %s: %.1f MB/s".format(name, data.size / 1e6 / secs))
            assertArrayEquals(data, File(bob.state.value.transfers.getValue(gid).path!!).readBytes())
        }
    }

    /** Host, one guest in the room, and the guest's id for the host's single file [name]. */
    private fun oneFileRoom(code: String, name: String = "s.txt", opener: () -> InputStream?): Triple<RoomHub, RoomHub, String> {
        val host = newHub("h", "Hana")
        val guest = newHub("g", "Gus")
        hostOpen(host, code)
        host.addShared(FileMeta("", name, 3), opener)
        guest.join(code); admitted(host, guest)
        await("manifest") { guest.state.value.manifest.files.isNotEmpty() }
        return Triple(host, guest, guest.state.value.manifest.files.single().id)
    }

    @Test
    fun `a request for a file the host does not list is answered with a failure`() {
        val (host, _, _) = oneFileRoom("FAA1") { ByteArrayInputStream("abc".toByteArray()) }
        radios.getValue("g").sendBytes("h", RoomProtocol.frameText(RoomProtocol.T_REQUEST, "host:nope"))
        host.flush()
        assertEquals(listOf("host:nope"), air.texts("h", "g", RoomProtocol.T_FAILED))
    }

    @Test
    fun `a guest that cannot open its file fails the relay and a retry asks again`() {
        val host = newHub("h", "Hana")
        val ada = newHub("a", "Ada")
        val bob = newHub("b", "Bob")
        hostOpen(host, "FAA2")
        ada.join("FAA2"); admitted(host, ada)
        ada.addShared(FileMeta("", "gone.bin", 5)) { null }
        bob.join("FAA2"); admitted(host, bob)
        await("bob sees file") { bob.state.value.manifest.files.isNotEmpty() }
        val gid = bob.state.value.manifest.files.single().id
        bob.download(gid)
        await("failed") { bob.state.value.transfers[gid]?.status == Transfer.Status.FAILED }
        bob.download(gid)
        await("asked again") { air.texts("h", "a", RoomProtocol.T_PUSH).size == 2 }
        await("failed again") { bob.state.value.transfers[gid]?.status == Transfer.Status.FAILED }
    }

    @Test
    fun `a push for a file the guest no longer shares is answered with a failure`() {
        val host = newHub("h", "Hana")
        val ada = newHub("a", "Ada")
        hostOpen(host, "FAA3")
        ada.join("FAA3"); admitted(host, ada)
        radios.getValue("h").sendBytes("a", RoomProtocol.frameText(RoomProtocol.T_PUSH, "f9"))
        ada.flush()
        assertEquals(listOf("a:f9"), air.texts("a", "h", RoomProtocol.T_FAILED))
    }

    @Test
    fun `a stream that cannot start fails the download`() {
        val (_, guest, gid) = oneFileRoom("FAA4") { ByteArrayInputStream("abc".toByteArray()) }
        air.failStreams("h")
        guest.download(gid)
        await("failed") { guest.state.value.transfers[gid]?.status == Transfer.Status.FAILED }
    }

    @Test
    fun `a stream for a file that left the room fails the download`() {
        val (host, guest, gid) = oneFileRoom("FAA5") { null }
        air.drop("h", RoomProtocol.T_FAILED)
        guest.download(gid)
        await("queued") { guest.state.value.transfers[gid] != null }
        host.removeShared(host.state.value.shared.single().id)
        await("gone") { guest.state.value.manifest.files.isEmpty() }
        val forged = RoomProtocol.header(gid) + "abc".toByteArray()
        radios.getValue("h").sendStream("g", ByteArrayInputStream(forged), 3)
        await("failed") { guest.state.value.transfers[gid]?.status == Transfer.Status.FAILED }
    }

    @Test
    fun `a download that hears nothing gives its slot back`() {
        val (_, guest, gid) = oneFileRoom("FAA6") { null }
        air.drop("h", RoomProtocol.T_FAILED)
        guest.idleMs = 200
        guest.download(gid)
        await("failed") { guest.state.value.transfers[gid]?.status == Transfer.Status.FAILED }
    }

    @Test
    fun `a relay that would overfill the host cache is refused`() {
        val host = newHub("h", "Hana")
        val ada = newHub("a", "Ada")
        val bob = newHub("b", "Bob")
        host.relayCap = 10
        hostOpen(host, "FAA7")
        ada.join("FAA7"); admitted(host, ada)
        ada.share("big.txt", "more than ten bytes")
        bob.join("FAA7"); admitted(host, bob)
        await("bob sees file") { bob.state.value.manifest.files.isNotEmpty() }
        val gid = bob.state.value.manifest.files.single().id
        bob.download(gid)
        await("failed") { bob.state.value.transfers[gid]?.status == Transfer.Status.FAILED }
        assertTrue(air.texts("h", "a", RoomProtocol.T_PUSH).isEmpty())
    }

    @Test
    fun `a transfer still running when the guest leaves does not touch the next room`() {
        val gate = CountDownLatch(1)
        val (_, guest, gid) = oneFileRoom("FAA8") { Gated("abc".toByteArray(), gate) }
        guest.download(gid)
        await("asked") { air.texts("g", "h", RoomProtocol.T_REQUEST).isNotEmpty() }
        guest.leave()
        await("left") { guest.state.value == RoomState() }
        gate.countDown()
        guest.drain()
        assertEquals(RoomState(), guest.state.value)
    }

    private fun downloads(id: String): List<File> =
        File(tmp, "$id/ext/Ping").listFiles().orEmpty().toList()

    @Test
    fun `a different gesture never joins the room`() {
        val host = newHub("h", "Hana")
        val guest = newHub("g", "Gus")
        hostOpen(host, "F2A2")
        guest.join("F9A0")
        await("searching") { guest.state.value.phase == RoomPhase.SEARCHING }
        settle(guest, host)
        assertEquals(RoomPhase.SEARCHING, guest.state.value.phase)
        assertTrue(host.state.value.pending.isEmpty())
    }

    @Test
    fun `a different gesture in the same advertised bucket fails the gesture check`() {
        val host = newHub("h", "Hana")
        val guest = newHub("g", "Gus")
        val other = (0 until 64).map { "F${it}A3" }.first { it != "F2A2" && Pairing.token(it) == Pairing.token("F2A2") }
        hostOpen(host, "F2A2")
        guest.join(other)
        await("searching") { guest.state.value.phase == RoomPhase.SEARCHING }
        settle(guest, host)
        assertEquals(RoomPhase.SEARCHING, guest.state.value.phase)
        assertTrue(host.state.value.pending.isEmpty())
        assertTrue(guest.state.value.manifest.files.isEmpty())
    }

    @Test
    fun `a link whose two ends see different tokens never gets in`() {
        val host = newHub("h", "Hana")
        val guest = newHub("g", "Gus")
        air.splitLink("g")
        hostOpen(host, "F2A1")
        guest.join("F2A1")
        await("searching") { guest.state.value.phase == RoomPhase.SEARCHING }
        settle(guest, host)
        assertEquals(RoomPhase.SEARCHING, guest.state.value.phase)
        assertTrue(host.state.value.pending.isEmpty())
    }
}

/** [data], but only once [gate] opens. */
private class Gated(data: ByteArray, private val gate: CountDownLatch) : InputStream() {
    private val inner = ByteArrayInputStream(data)
    override fun read(): Int = gate.await().let { inner.read() }
    override fun read(b: ByteArray, off: Int, len: Int): Int = gate.await().let { inner.read(b, off, len) }
}

/** The first half of [data] at once, the rest once [gate] opens. */
private class HalfGated(private val data: ByteArray, private val gate: CountDownLatch) : InputStream() {
    private var pos = 0
    override fun read(): Int = ByteArray(1).let { if (read(it, 0, 1) < 0) -1 else it[0].toInt() and 0xff }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (pos >= data.size) return -1
        if (pos >= data.size / 2) gate.await()
        val end = if (pos < data.size / 2) data.size / 2 else data.size
        val n = minOf(len, end - pos)
        System.arraycopy(data, pos, b, off, n)
        pos += n
        return n
    }
}

/** Hands out the first [cut] bytes of [data], then fails like a dropped link. */
private class CutAfter(private val data: ByteArray, private val cut: Int) : InputStream() {
    private var pos = 0
    override fun read(): Int = ByteArray(1).let { if (read(it, 0, 1) < 0) -1 else it[0].toInt() and 0xff }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (pos >= cut) throw IOException("link dropped")
        val n = minOf(len, cut - pos)
        System.arraycopy(data, pos, b, off, n)
        pos += n
        return n
    }
}

/** In-memory stand-in for the radio: endpoint ids are the names passed to [endpoint]. */
private class FakeAir {
    private val nodes = mutableMapOf<String, Node>()
    private val frames: MutableList<Triple<String, String, ByteArray>> = Collections.synchronizedList(mutableListOf())
    @Volatile var largestFrame = 0

    /** Bodies of the [type] frames [from] sent [to], in order. */
    fun texts(from: String, to: String, type: Byte): List<String> = synchronized(frames) {
        frames.filter { it.first == from && it.second == to && it.third.firstOrNull() == type }
            .map { it.third.copyOfRange(1, it.third.size).toString(Charsets.UTF_8) }
    }

    fun endpoint(id: String): NearbyTransport = Node(id).also { nodes[id] = it }

    fun splitLink(id: String) { nodes.getValue(id).tokenOverride = byteArrayOf(6, 6, 6) }

    /** [id] stops sending frames of [type], as a peer that never answers would. */
    fun drop(id: String, type: Byte) { nodes.getValue(id).dropped += type }

    /** [id]'s streams fail to start, as a link that just dropped would. */
    fun failStreams(id: String) { nodes.getValue(id).streamsFail = true }

    inner class Node(val id: String) : NearbyTransport {
        override var onPayloadReceived: ((String, ByteArray) -> Unit)? = null
        override var onConnected: ((String, String, Boolean) -> Unit)? = null
        override var onDisconnected: ((String) -> Unit)? = null
        override var onEndpointFound: ((String, String) -> Unit)? = null
        override var onConnectionInitiated: ((String, String) -> Unit)? = null
        override var onStreamReceived: ((String, InputStream) -> Unit)? = null
        override var onAuthToken: ((String, ByteArray) -> Unit)? = null
        /** Set to give the two legs different link tokens, as a relay in the middle would. */
        var tokenOverride: ByteArray? = null
        val dropped: MutableSet<Byte> = Collections.synchronizedSet(mutableSetOf())
        @Volatile var streamsFail = false

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
            onAuthToken?.invoke(endpointId, tokenOverride ?: byteArrayOf(4, 8, 2, 1))
            peer.onAuthToken?.invoke(id, byteArrayOf(4, 8, 2, 1))
            onConnectionInitiated?.invoke(endpointId, peer.advertised.orEmpty())
            peer.onConnectionInitiated?.invoke(id, localName)
        }

        // Both ends accept on their own threads; unlocked, each can miss the other's accept.
        override fun acceptConnection(endpointId: String) {
            synchronized(this@FakeAir) {
                accepted += endpointId
                val peer = nodes[endpointId] ?: return
                if (id in peer.accepted) {
                    onConnected?.invoke(endpointId, names[endpointId].orEmpty(), false)
                    peer.onConnected?.invoke(id, peer.names[id].orEmpty(), true)
                }
            }
        }

        override fun rejectConnection(endpointId: String) {}

        override fun sendBytes(endpointId: String, data: ByteArray) {
            if (data.firstOrNull() in dropped) return
            frames += Triple(id, endpointId, data)
            largestFrame = maxOf(largestFrame, data.size)
            nodes[endpointId]?.onPayloadReceived?.invoke(id, data)
        }

        override fun sendStream(endpointId: String, inputStream: InputStream, lengthHint: Long): Boolean {
            if (streamsFail) return false
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
