package ir.sabou.sync

import ir.sabou.core.SabouCore
import ir.sabou.kernel.GlobalId
import ir.sabou.persistence.DevDatabases
import ir.sabou.platform.memory.InMemoryAnchorStore
import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Independent audit (ChatGPT, 1405/08/06), findings 004 and 005: a paired phone cannot move its recorded position
 * past the main phone's log, and one message cannot make the reader build arbitrarily large arrays.
 */
class SyncProtocolLimitsTest {
    private class MemoryPeers : PeerStore {
        val peers = java.util.concurrent.ConcurrentHashMap<String, Peer>()
        override fun all() = peers.values.toList()
        override fun save(peer: Peer) { peers[peer.deviceId] = peer }
        override fun remove(deviceId: String) { peers.remove(deviceId) }
    }

    private val dir = Files.createTempDirectory("sabou-proto")
    private val db = DevDatabases.open(dir.resolve("hub.db"))
    private val core = SabouCore.open(db, InMemoryAnchorStore()).also {
        it.bootstrap("شعبه", "مالک", "owner", "123456".toCharArray()); it.identity.logout()
    }
    private val peers = MemoryPeers()
    private val hub = SyncHub({ core }, peers, { error("no copy") }, "1.0 (7)", "hub-0001", "گوشی اصلی")
    private val address = InetSocketAddress("127.0.0.1", hub.start(0))

    @AfterTest fun close() { hub.stop(); db.close(); dir.toFile().deleteRecursively() }

    private fun pair(): Pairing {
        val paired = CountDownLatch(1)
        hub.openPairing(object : PairingListener {
            override fun onCode(code: String, deviceName: String) { hub.confirmPairing(true) }
            override fun onPaired(peer: Peer) { paired.countDown() }
            override fun onFailed(reason: String) {}
        })
        return PairingAttempt.start(address, GlobalId.new().value, "گوشی صندوق").confirm().also { assertTrue(paired.await(5, TimeUnit.SECONDS)) }
    }

    @Test fun aPositionAheadOfTheLogIsRefusedAndNotRecorded() {
        val phone = pair()
        val before = peers.peers.getValue(phone.deviceId).cursor
        HubConnection.open(address, phone, "1.0 (7)").use { c ->
            val reply = c.request(Message.Pull(Long.MAX_VALUE, 0))
            assertEquals("INVALID_STATE:SYNC:CURSOR_AHEAD", (reply as Message.Failure).error.code)
            assertTrue(c.request(Message.Pull(-1, 0)) is Message.Failure)
            assertEquals(before, peers.peers.getValue(phone.deviceId).cursor)
            assertTrue(c.request(Message.Pull(core.sync.head(), 0)) is Message.Changes)   // a real position still works
        }
    }

    @Test fun aMessageCannotAskForAHugeArray() {
        assertTrue(Wire.limitsEnforced, "the JVM has the serialization filter")
        val bytes = ByteArrayOutputStream().also { b -> ObjectOutputStream(b).use { it.writeObject(LongArray(3)) } }.toByteArray()
        // The array's length sits just before its 24 bytes of data: announce 2^31-1 elements (16 GB) instead of 3.
        val at = bytes.size - 24 - 4
        assertEquals(listOf<Byte>(0, 0, 0, 3), bytes.copyOfRange(at, at + 4).toList())
        byteArrayOf(0x7f, -1, -1, -1).copyInto(bytes, at)
        val failure = runCatching { Wire.decode(bytes) }.exceptionOrNull()
        assertTrue(failure is java.io.InvalidClassException, "refused before allocating, got $failure")
    }
}
