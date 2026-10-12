package ir.sabou.sync

import ir.sabou.core.SabouCore
import ir.sabou.inventory.CreateItem
import ir.sabou.inventory.StockUnit
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Scope
import ir.sabou.persistence.JdbcSqlDatabase
import ir.sabou.persistence.SyncLog
import ir.sabou.platform.Role
import ir.sabou.platform.StartupVerdict
import ir.sabou.platform.memory.InMemoryAnchorStore
import ir.sabou.treasury.OpenTreasuryAccount
import ir.sabou.treasury.ReceiptPurpose
import ir.sabou.treasury.RecordReceipt
import ir.sabou.treasury.TreasuryKind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.sql.DriverManager
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The two phones over a real socket on this machine (ADR-0042). */
class SyncLinkTest {
    private val dir = Files.createTempDirectory("sabou-link")
    private val open = CopyOnWriteArrayList<JdbcSqlDatabase>()
    private val hubs = CopyOnWriteArrayList<SyncHub>()
    private val replicas = CopyOnWriteArrayList<SyncReplica>()

    @AfterTest fun close() {
        replicas.forEach { it.stop() }
        hubs.forEach { it.stop() }
        Thread.sleep(100)
        open.forEach { runCatching { it.close() } }
        dir.toFile().deleteRecursively()
    }

    private fun db(name: String) = JdbcSqlDatabase(DriverManager.getConnection("jdbc:sqlite:${dir.resolve(name)}")).also { open += it }

    private class MemoryPeers : PeerStore {
        private val peers = java.util.concurrent.ConcurrentHashMap<String, Peer>()
        override fun all() = peers.values.toList()
        override fun save(peer: Peer) { peers[peer.deviceId] = peer }
        override fun remove(deviceId: String) { peers.remove(deviceId) }
    }

    private inner class Hub {
        val database = db("hub.db")
        val core: SabouCore = SabouCore.open(database, InMemoryAnchorStore())
        val peers = MemoryPeers()
        val server: SyncHub
        val port: Int
        init {
            core.bootstrap("شعبه ونک", "مالک", "owner", "123456".toCharArray())
            core.identity.createUser("cashier", "صندوقدار", Role.CASHIER, setOf(core.overview.branches().single().id), "222222".toCharArray())
            core.identity.logout()
            server = SyncHub({ core }, peers, ::snapshot, "1.0 (7)", "hub-0001", "گوشی اصلی")
            hubs += server
            port = server.start(0)
        }

        val address get() = InetSocketAddress("127.0.0.1", port)

        private fun snapshot(c: SabouCore): Snapshot {
            val file = Files.createTempFile(dir, "copy", ".db").toFile().also { it.delete() }
            val cursor = c.exclusive {
                val head = c.sync.head()
                database.execute("VACUUM INTO '${file.absolutePath}'")
                head
            }
            return Snapshot(file, "", cursor, c.epoch)
        }

        /** Pairs a new phone: both screens show the same code and both people confirm. */
        fun pair(name: String = "گوشی صندوق"): Pairing {
            val codes = CopyOnWriteArrayList<String>()
            val paired = CountDownLatch(1)
            server.openPairing(object : PairingListener {
                override fun onCode(code: String, deviceName: String) { codes += code; server.confirmPairing(true) }
                override fun onPaired(peer: Peer) { paired.countDown() }
                override fun onFailed(reason: String) {}
            })
            val attempt = PairingAttempt.start(address, GlobalId.new().value, name)
            assertEquals("گوشی اصلی", attempt.hubName)
            val pairing = attempt.confirm()
            assertEquals("hub-0001", pairing.hubId)
            assertTrue(paired.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(attempt.code), codes.toList())
            return pairing
        }
    }

    private class Events : SyncReplica.Listener {
        val applied = java.util.concurrent.atomic.AtomicInteger()
        val needsCopy = CopyOnWriteArrayList<String>()
        override fun onApplied() { applied.incrementAndGet() }
        override fun onConnection(connected: Boolean) {}
        override fun onNeedsFullCopy(reason: String) { needsCopy += reason }
    }

    private inner class Phone(val hub: Hub, val pairing: Pairing = hub.pair(), version: String = "1.0 (7)") {
        val file = dir.resolve("phone-${GlobalId.new().value}.db").toFile()
        val header = SyncReplica.downloadCopy(hub.address, pairing, version, file)
        val database = JdbcSqlDatabase(DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")).also { open += it }
        val core: SabouCore = SabouCore.open(database, InMemoryAnchorStore())
        val events = Events()
        val replica: SyncReplica
        init {
            core.sync.startAt(header.cursor)
            replica = SyncReplica(core, { hub.address }, pairing, version, events)
            replicas += replica
            replica.start()
        }
    }

    private fun dump(db: JdbcSqlDatabase) = SyncLog.tables(db).associateWith { t ->
        db.query("SELECT * FROM $t").map { r -> SyncLog.columns(db, t).joinToString("|") { "${r.raw(it.name)}" } }.sorted()
    }

    // The JDBC connection of these tests is not safe for use by two threads at once (Android's is): reads made here
    // while the replication thread may be writing are taken under the core's own lock.
    private fun <T> SabouCore.locked(block: SabouCore.() -> T): T = exclusive { block() }

    private fun eventually(what: String, check: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < until) { if (check()) return; Thread.sleep(50) }
        throw AssertionError("not reached: $what")
    }

    @Test fun pairedPhoneWorksThroughTheMainPhoneAndStaysIdentical() {
        val hub = Hub()
        val phone = Phone(hub)
        assertEquals(hub.core.epoch, phone.core.epoch)

        val actor = phone.core.identity.login("owner", "123456".toCharArray())
        assertEquals(Role.OWNER, actor.role)
        val branch = Scope.Branch(phone.core.overview.branches().single().id)
        val cash = phone.core.overview.treasury().single().account.id
        phone.core.treasury.receipt(RecordReceipt(GlobalId.new(), branch, cash, ReceiptPurpose.OWNER_CAPITAL, Money.of(5_000_000), BusinessDate(20_000), "آورده"))
        // Read back at once on the second phone: the command's effects are already in its copy.
        assertEquals(5_000_000, phone.core.locked { overview.treasury().single().balance })

        // A change made on the main phone reaches the second phone by itself.
        hub.core.identity.login("owner", "123456".toCharArray())
        hub.core.inventory.createItem(CreateItem(GlobalId.new(), "برنج", StockUnit.KILOGRAM, Quantity.units(10)))
        eventually("item copied") { phone.core.locked { overview.items().any { it.name == "برنج" } } }
        assertTrue(phone.events.applied.get() > 0)

        eventually("identical books") { hub.core.locked { dump(hub.database) } == phone.core.locked { dump(phone.database) } }
        assertEquals(StartupVerdict.Healthy, phone.core.locked { verifyAuditFull() })
        assertEquals(emptyList(), phone.events.needsCopy.toList())
    }

    @Test fun secondPhoneCannotDoAnythingWithoutTheMainPhone() {
        val hub = Hub()
        val phone = Phone(hub)
        phone.core.identity.login("cashier", "222222".toCharArray())
        hub.server.stop()
        val branch = Scope.Branch(phone.core.overview.branches().single().id)
        val e = assertFailsWith<DomainException> {
            phone.core.salesOps.registerCustomer(
                ir.sabou.sales.RegisterCustomer(GlobalId.new(), branch, "مشتری", ir.sabou.sales.CustomerType.PERSON, "021", Money.of(1)),
            )
        }
        assertTrue(e.error == SyncErrors.HUB_UNREACHABLE || e.error is DomainError.PermissionDenied)
        val e2 = assertFailsWith<DomainException> { phone.core.identity.login("owner", "123456".toCharArray()) }
        assertEquals(SyncErrors.HUB_UNREACHABLE.code, e2.error.code)
    }

    @Test fun commandsAfterTheMainPhoneComesBackSucceedOnce() {
        val hub = Hub()
        val phone = Phone(hub)
        phone.core.identity.login("owner", "123456".toCharArray())
        val branch = Scope.Branch(phone.core.overview.branches().single().id)
        val command = OpenTreasuryAccount(GlobalId.new(), branch, "بانک ملت", TreasuryKind.BANK)
        val first = phone.core.treasury.openAccount(command)
        // The answer was lost (connection dropped): the same command again answers with the first result.
        val again = phone.core.treasury.openAccount(command)
        assertEquals(first.resultId, again.resultId)
        assertTrue(again.replayed)
    }

    @Test fun differentAppVersionsAreRefused() {
        val hub = Hub()
        val pairing = hub.pair()
        val e = assertFailsWith<DomainException> { SyncReplica.downloadCopy(hub.address, pairing, "1.1 (8)", dir.resolve("x.db").toFile()) }
        assertEquals(SyncErrors.VERSION.code, e.error.code)
    }

    @Test fun strangersAndRemovedPhonesGetNothing() {
        val hub = Hub()
        // Not paired: the main phone hangs up before anything is said.
        assertFailsWith<IOException> { SyncReplica.downloadCopy(hub.address, Pairing("stranger-0001", ByteArray(32)), "1.0 (7)", dir.resolve("s.db").toFile()) }
        // A known id with the wrong key: the first frame fails authentication on the main phone.
        val pairing = hub.pair()
        assertFailsWith<IOException> { SyncReplica.downloadCopy(hub.address, Pairing(pairing.deviceId, ByteArray(32)), "1.0 (7)", dir.resolve("k.db").toFile()) }
        // Removed: refused like a stranger.
        hub.server.removePeer(pairing.deviceId)
        assertFailsWith<IOException> { SyncReplica.downloadCopy(hub.address, pairing, "1.0 (7)", dir.resolve("r.db").toFile()) }
        // Pairing outside an open window is refused.
        assertFailsWith<IOException> { PairingAttempt.start(hub.address, GlobalId.new().value, "x").confirm() }
    }

    @Test fun anOwnerWhoRejectsTheCodeLeavesThePhoneUnpaired() {
        val hub = Hub()
        val failed = CountDownLatch(1)
        hub.server.openPairing(object : PairingListener {
            override fun onCode(code: String, deviceName: String) { hub.server.confirmPairing(false) }
            override fun onPaired(peer: Peer) {}
            override fun onFailed(reason: String) { failed.countDown() }
        })
        val attempt = PairingAttempt.start(hub.address, GlobalId.new().value, "x")
        assertFailsWith<IOException> { attempt.confirm() }
        assertTrue(failed.await(5, TimeUnit.SECONDS))
        assertEquals(emptyList(), hub.peers.all())
    }

    @Test fun onlyOnePhoneCanAnswerAPairingWindow() {
        val hub = Hub()
        val seen = CountDownLatch(1)
        hub.server.openPairing(object : PairingListener {
            override fun onCode(code: String, deviceName: String) = seen.countDown()
            override fun onPaired(peer: Peer) {}
            override fun onFailed(reason: String) {}
        })
        val first = PairingAttempt.start(hub.address, GlobalId.new().value, "اول")
        assertTrue(seen.await(5, TimeUnit.SECONDS))
        // Another phone at the same moment is turned away: the owner only ever sees one phone's code.
        assertFailsWith<IOException> { PairingAttempt.start(hub.address, GlobalId.new().value, "دوم") }
        hub.server.confirmPairing(false)
        assertFailsWith<IOException> { first.confirm() }
        assertEquals(emptyList(), hub.peers.all())
    }

    @Test fun alteredOrReplayedFramesAreRejected() {
        val key = ByteArray(32) { it.toByte() }
        val out = ByteArrayOutputStream()
        val sender = SecureChannel(DataInputStream(ByteArrayInputStream(ByteArray(0))), DataOutputStream(out), key, key)
        sender.send(Message.Ok)
        sender.send(Message.Ok)
        val frames = out.toByteArray()
        val firstLength = 4 + DataInputStream(ByteArrayInputStream(frames)).readInt()
        // Altered: one bit flipped.
        val altered = frames.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
        assertFailsWith<IOException> { SecureChannel(DataInputStream(ByteArrayInputStream(altered)), DataOutputStream(ByteArrayOutputStream()), key, key).receive() }
        // Replayed: the first frame where the second is expected.
        val replayed = frames.copyOfRange(0, firstLength) + frames.copyOfRange(0, firstLength)
        val receiver = SecureChannel(DataInputStream(ByteArrayInputStream(replayed)), DataOutputStream(ByteArrayOutputStream()), key, key)
        assertEquals(Message.Ok, receiver.receive())
        assertFailsWith<IOException> { receiver.receive() }
    }

    @Test fun onlyTheAppsOwnTypesAreReadFromTheWire() {
        assertTrue(Wire.allowed("ir.sabou.treasury.OpenTreasuryAccount"))
        assertTrue(Wire.allowed("java.util.ArrayList"))
        assertTrue(Wire.allowed("[B"))
        assertTrue(!Wire.allowed("java.util.PriorityQueue"))
        assertTrue(!Wire.allowed("javax.management.BadAttributeValueExpException"))
        assertTrue(!Wire.allowed("java.lang.reflect.Proxy"))
    }

    @Test fun aSecondPhoneThatFellBehindThePrunedLogIsToldToTakeAFullCopy() {
        val hub = Hub()
        val phone = Phone(hub)
        phone.replica.stop()
        hub.core.identity.login("owner", "123456".toCharArray())
        hub.core.inventory.createItem(CreateItem(GlobalId.new(), "روغن", StockUnit.LITER, Quantity.units(1)))
        hub.core.sync.prune(hub.core.sync.head())
        val events = Events()
        val again = SyncReplica(phone.core, { hub.address }, phone.pairing, "1.0 (7)", events).also { replicas += it }
        again.start()
        eventually("full copy asked") { events.needsCopy.isNotEmpty() }
        assertNotNull(events.needsCopy.firstOrNull { it == "GAP" })
    }
}
