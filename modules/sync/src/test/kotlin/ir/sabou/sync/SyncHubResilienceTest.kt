package ir.sabou.sync

import ir.sabou.core.SabouCore
import ir.sabou.kernel.GlobalId
import ir.sabou.persistence.DevDatabases
import ir.sabou.platform.memory.InMemoryAnchorStore
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Audit 2026-10-28, SABOU-AUDIT-007: a stranger on the restaurant's Wi-Fi can neither take every connection of the
 * main phone nor make it set aside large buffers before proving it holds the pairing key.
 */
class SyncHubResilienceTest {
    private class MemoryPeers : PeerStore {
        private val peers = java.util.concurrent.ConcurrentHashMap<String, Peer>()
        override fun all() = peers.values.toList()
        override fun save(peer: Peer) { peers[peer.deviceId] = peer }
        override fun remove(deviceId: String) { peers.remove(deviceId) }
    }

    private val dir = Files.createTempDirectory("sabou-hub")
    private val db = DevDatabases.open(dir.resolve("hub.db"))
    private val core = SabouCore.open(db, InMemoryAnchorStore()).also {
        it.bootstrap("شعبه", "مالک", "owner", "123456".toCharArray()); it.identity.logout()
    }
    private val hub = SyncHub({ core }, MemoryPeers(), { error("no copy") }, "1.0 (7)", "hub-0001", "گوشی اصلی")
    private val address = InetSocketAddress("127.0.0.1", hub.start(0))
    private val open = ArrayList<Socket>()

    @AfterTest fun close() { open.forEach { runCatching { it.close() } }; hub.stop(); db.close(); dir.toFile().deleteRecursively() }

    private fun pair(): Pairing {
        val paired = CountDownLatch(1)
        hub.openPairing(object : PairingListener {
            override fun onCode(code: String, deviceName: String) { hub.confirmPairing(true) }
            override fun onPaired(peer: Peer) { paired.countDown() }
            override fun onFailed(reason: String) {}
        })
        return PairingAttempt.start(address, GlobalId.new().value, "گوشی صندوق").confirm().also { assertTrue(paired.await(5, TimeUnit.SECONDS)) }
    }

    /** A connection from another machine on the network (another loopback address stands for it). */
    private fun stranger(): Socket = Socket().apply { bind(InetSocketAddress("127.0.0.2", 0)); connect(address, 2_000) }.also { open += it }

    @Test fun aStrangerHoldingConnectionsDoesNotKeepThePairedPhoneOut() {
        val phone = pair()
        repeat(SyncHub.MAX_CONNECTIONS) { runCatching { stranger() } }
        Thread.sleep(300)
        HubConnection.open(address, phone, "1.0 (7)").close()          // still served
    }

    @Test fun aSilentConnectionGivesItsSlotBackQuickly() {
        val s = stranger()
        s.soTimeout = 10_000
        val started = System.currentTimeMillis()
        val closedByServer = runCatching { s.getInputStream().read() }.getOrNull() == -1
        assertTrue(closedByServer && System.currentTimeMillis() - started < 8_000, "a silent stranger keeps its slot")
    }

    @Test fun beforeAuthenticationNoLargeFrameIsAccepted() {
        val phone = pair()
        val s = stranger()
        val out = DataOutputStream(s.getOutputStream()); val inp = DataInputStream(s.getInputStream())
        out.write("SABOUSY1".toByteArray()); out.writeByte(2)
        val id = phone.deviceId.toByteArray(); out.writeShort(id.size); out.write(id); out.write(ByteArray(32)); out.flush()
        inp.readFully(ByteArray(32))                                   // the server's nonce
        out.writeInt(SecureChannel.MAX_FRAME); out.write(ByteArray(16)); out.flush()
        s.soTimeout = 5_000
        // Refused at once on its announced size: the connection is closed, nothing waits for 48 MB.
        assertEquals(-1, runCatching { inp.read() }.getOrElse { if (it is EOFException) -1 else throw it })
        assertTrue(SecureChannel.PRE_AUTH_MAX_FRAME < 1_000_000)
    }
}
