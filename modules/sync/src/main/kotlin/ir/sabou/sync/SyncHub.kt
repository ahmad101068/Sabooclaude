package ir.sabou.sync

import ir.sabou.core.SabouCore
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/** A paired second phone. [key] is the pairing key; [cursor] how far it has copied the log (for pruning). */
data class Peer(val deviceId: String, val name: String, val key: ByteArray, val cursor: Long, val lastSeen: Long) {
    override fun equals(other: Any?) = other is Peer && other.deviceId == deviceId
    override fun hashCode() = deviceId.hashCode()
}

/** The main phone's list of paired phones, kept outside the database (it belongs to this phone, not the books). */
interface PeerStore {
    fun all(): List<Peer>
    fun save(peer: Peer)
    fun remove(deviceId: String)
}

/** A consistent full copy of the books taken at log position [cursor], as a SQLCipher file under [key]. */
class Snapshot(val file: File, val key: String, val cursor: Long, val epoch: String)

fun interface SnapshotSource {
    fun export(core: SabouCore): Snapshot
}

/** What the main phone's owner sees while a phone asks to pair. */
interface PairingListener {
    fun onCode(code: String, deviceName: String)
    fun onPaired(peer: Peer)
    fun onFailed(reason: String)
}

/**
 * The main phone's side (ADR-0042): a small server on the restaurant's network. A paired phone can sign people in,
 * run commands for them, copy changes and take a full copy; nothing else. Each connection has its own thread; the
 * core's single unit of work serializes them with this phone's own use.
 */
class SyncHub(
    private val core: () -> SabouCore?,
    private val peers: PeerStore,
    private val snapshots: SnapshotSource,
    private val appVersion: String,
    /** This phone's id (also in its network service name, so the second phone finds this one and no other). */
    val hubId: String,
    private val hubName: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile private var server: ServerSocket? = null
    private val connections = ConcurrentHashMap.newKeySet<Socket>()
    private val sessions = ConcurrentHashMap<String, RemoteSession>()
    private val random = SecureRandom()
    private val slots = java.util.concurrent.Semaphore(MAX_CONNECTIONS)

    private class RemoteSession(val userId: GlobalId, val deviceId: String, @Volatile var lastUse: Long)

    /** Starts listening on [port] (0: any free port); returns the port. Idempotent. */
    @Synchronized
    fun start(port: Int = DEFAULT_PORT): Int {
        server?.let { return it.localPort }
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(port))
        server = s
        pruneForPeers()
        Thread({ acceptLoop(s) }, "sabou-sync-accept").apply { isDaemon = true }.start()
        return s.localPort
    }

    @Synchronized
    fun stop() {
        server?.close()
        server = null
        connections.forEach { runCatching { it.close() } }
        connections.clear()
        sessions.clear()
        closePairing()
    }

    val isRunning: Boolean get() = server?.isClosed == false

    /** Phones currently connected (device ids). */
    fun connectedDevices(): Set<String> = sessionsByDevice.keys.toSet()
    private val sessionsByDevice = ConcurrentHashMap<String, Int>()

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val socket = try { s.accept() } catch (_: IOException) { break }
            // Bounded (audit F11): a flood of connections from the network cannot exhaust the phone. Paired phones use
            // two connections each; anything beyond the limit is closed at once, before any work is done for it.
            if (!slots.tryAcquire()) { runCatching { socket.close() }; continue }
            connections += socket
            if (s.isClosed) { connections -= socket; slots.release(); runCatching { socket.close() }; break }
            try {
                Thread({ try { serve(socket) } finally { slots.release() } }, "sabou-sync-conn").apply { isDaemon = true }.start()
            } catch (e: Throwable) {
                connections -= socket; slots.release(); runCatching { socket.close() }
            }
        }
    }

    private fun serve(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            if (!input.readBytes(MAGIC.size).contentEquals(MAGIC)) return
            when (input.readUnsignedByte()) {
                MODE_PAIR -> pair(socket, input, output)
                MODE_SESSION -> session(socket, input, output)
            }
        } catch (_: IOException) {
            // A dropped connection, a stranger or a failed authentication: nothing to tell anyone.
        } finally {
            connections -= socket
            runCatching { socket.close() }
        }
    }

    // ------------------------------------------------------------------ pairing

    private class PairingWindow(val listener: PairingListener, val until: Long) {
        @Volatile var decision: Boolean? = null
        val lock = Object()
        /** One phone per window: a second request at the same moment is turned away, so the code the owner sees is that phone's. */
        val claimed = java.util.concurrent.atomic.AtomicBoolean(false)
    }

    @Volatile private var pairing: PairingWindow? = null

    /** Accepts one pairing request during the next [millis]; the owner confirms the code with [confirmPairing]. */
    fun openPairing(listener: PairingListener, millis: Long = PAIRING_WINDOW_MS) {
        pairing = PairingWindow(listener, clock() + millis)
    }

    fun closePairing() {
        pairing?.let { w -> synchronized(w.lock) { if (w.decision == null) w.decision = false; w.lock.notifyAll() } }
        pairing = null
    }

    /** The owner compared the codes on both screens: [same] = they match. */
    fun confirmPairing(same: Boolean) {
        val w = pairing ?: return
        synchronized(w.lock) { w.decision = same; w.lock.notifyAll() }
    }

    private fun pair(socket: Socket, input: DataInputStream, output: DataOutputStream) {
        val window = pairing?.takeIf { clock() < it.until && it.claimed.compareAndSet(false, true) } ?: return
        val clientPub = input.readShortBytes()
        val clientNonce = input.readBytes(32)
        val deviceName = String(input.readShortBytes(), Charsets.UTF_8).take(60)
        val deviceId = String(input.readShortBytes(), Charsets.UTF_8)
        if (!deviceId.matches(Regex("[A-Za-z0-9-]{8,64}"))) return
        val mine = Crypto.Ephemeral()
        val serverNonce = Crypto.randomBytes(32)
        output.writeShortBytes(mine.publicKey); output.write(serverNonce); output.writeShortBytes(hubName.toByteArray(Charsets.UTF_8)); output.flush()
        val (key, code) = Crypto.pairing(mine.agree(clientPub), clientPub, mine.publicKey, clientNonce, serverNonce)
        val (c2s, s2c) = Crypto.sessionKeys(key, clientNonce, serverNonce)
        val channel = SecureChannel(input, output, s2c, c2s)
        window.listener.onCode(code, deviceName)
        // The second phone confirms on its side by sending an encrypted frame (it also proves the same key).
        socket.soTimeout = (window.until - clock()).coerceAtLeast(1_000L).toInt()
        val confirmed = runCatching { channel.receive() == Message.PairConfirm }.getOrDefault(false)
        val decision = synchronized(window.lock) {
            while (window.decision == null && clock() < window.until) window.lock.wait(500)
            window.decision == true
        }
        pairing = null
        if (!confirmed || !decision) {
            window.listener.onFailed(if (!confirmed) "CLIENT_CANCELLED" else "OWNER_REJECTED")
            return
        }
        val peer = Peer(deviceId, deviceName, key, cursor = 0, lastSeen = clock())
        peers.save(peer)
        channel.send(Message.PairAccepted(hubId, hubName))
        window.listener.onPaired(peer)
    }

    // ------------------------------------------------------------------ sessions

    private fun session(socket: Socket, input: DataInputStream, output: DataOutputStream) {
        val deviceId = String(input.readShortBytes(), Charsets.UTF_8)
        val clientNonce = input.readBytes(32)
        val peer = peers.all().firstOrNull { it.deviceId == deviceId } ?: return
        val serverNonce = Crypto.randomBytes(32)
        output.write(serverNonce); output.flush()
        val (c2s, s2c) = Crypto.sessionKeys(peer.key, clientNonce, serverNonce)
        val channel = SecureChannel(input, output, s2c, c2s)
        val hello = channel.receive() as? Message.Hello ?: return
        if (hello.appVersion != appVersion) { channel.send(Message.Failure(SyncErrors.VERSION)); return }
        val c = core() ?: run { channel.send(Message.Failure(SyncErrors.HUB_NOT_READY)); return }
        channel.send(Message.HelloOk(c.epoch))
        socket.soTimeout = IDLE_TIMEOUT_MS
        sessionsByDevice.merge(deviceId, 1, Int::plus)
        try {
            while (true) {
                val request = channel.receive()
                // Stopped meanwhile (a connection accepted just as the server closed escapes the closing loop), or
                // the peer was removed: this connection stops answering.
                if (!isRunning || peers.all().none { it.deviceId == deviceId }) return
                handle(request, peer, channel)
            }
        } finally {
            sessionsByDevice.computeIfPresent(deviceId) { _, n -> if (n <= 1) null else n - 1 }
        }
    }

    private fun handle(request: Message, peer: Peer, channel: SecureChannel) {
        if (request is Message.CopyRequest) { sendCopy(peer, channel); return }
        val reply = try {
            val c = core() ?: throw DomainException(SyncErrors.HUB_NOT_READY)
            when (request) {
                is Message.Login -> {
                    val user = try { c.sync.verifyLogin(request.username, request.pin, peer.name) } finally { request.pin.fill('0') }
                    val token = newToken()
                    sessions[token] = RemoteSession(user.id, peer.deviceId, clock())
                    Message.LoginOk(token, user.id, c.sync.head())
                }
                is Message.ConfirmPin -> {
                    val s = sessionOf(request.token, peer)
                    try { c.sync.confirmPin(s.userId, request.pin) } finally { request.pin.fill('0') }
                    Message.Ok
                }
                is Message.Logout -> { sessions.remove(request.token); Message.Ok }
                is Message.Execute -> {
                    val s = sessionOf(request.token, peer)
                    val outcome = c.sync.execute(s.userId, request.command)
                    Message.Executed(outcome, c.sync.head())
                }
                is Message.Pull -> pull(c, peer, request)
                else -> throw DomainException(DomainError.InvalidState("SYNC", "UNEXPECTED"))
            }
        } catch (e: DomainException) {
            Message.Failure(e.error)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            // Anything else is an internal failure of this phone: the second phone shows the generic message.
            Message.Failure(DomainError.IntegrityViolation("HUB:${e.javaClass.simpleName}"))
        }
        channel.send(reply)
    }

    private fun sessionOf(token: String, peer: Peer): RemoteSession {
        val s = sessions[token]?.takeIf { it.deviceId == peer.deviceId } ?: throw DomainException(DomainError.AuthenticationRequired)
        val now = clock()
        if (now - s.lastUse > SESSION_IDLE_MS) { sessions.remove(token); throw DomainException(DomainError.AuthenticationRequired) }
        s.lastUse = now
        return s
    }

    private fun newToken(): String = ByteArray(24).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    private fun pull(c: SabouCore, peer: Peer, request: Message.Pull): Message {
        recordCursor(peer.deviceId, request.cursor)
        val deadline = clock() + request.waitMillis.coerceIn(0, MAX_WAIT_MS)
        while (c.sync.head() <= request.cursor && clock() < deadline && isRunning) Thread.sleep(POLL_MS)
        return Message.Changes(c.sync.changesSince(request.cursor))
    }

    private fun recordCursor(deviceId: String, cursor: Long) {
        val current = peers.all().firstOrNull { it.deviceId == deviceId } ?: return
        if (current.cursor != cursor || clock() - current.lastSeen > 60_000) {
            peers.save(current.copy(cursor = cursor, lastSeen = clock()))
            pruneForPeers()
        }
    }

    /** Keeps only the log entries some paired phone still needs (none when no phone is paired). */
    fun pruneForPeers() {
        val c = core() ?: return
        runCatching {
            val all = peers.all()
            c.sync.prune(if (all.isEmpty()) c.sync.head() else all.minOf { it.cursor })
        }
    }

    /** Forgets a paired phone; its connections stop at their next request. */
    fun removePeer(deviceId: String) {
        peers.remove(deviceId)
        sessions.entries.removeIf { it.value.deviceId == deviceId }
        pruneForPeers()
    }

    private fun sendCopy(peer: Peer, channel: SecureChannel) {
        val c = core() ?: run { channel.send(Message.Failure(SyncErrors.HUB_NOT_READY)); return }
        val snapshot = try {
            snapshots.export(c)
        } catch (e: DomainException) {
            channel.send(Message.Failure(e.error)); return
        }
        try {
            recordCursor(peer.deviceId, snapshot.cursor)
            channel.send(Message.CopyHeader(snapshot.epoch, snapshot.cursor, snapshot.key, snapshot.file.length()))
            snapshot.file.inputStream().use { i ->
                val buffer = ByteArray(CHUNK)
                while (true) {
                    val n = i.read(buffer)
                    if (n < 0) break
                    channel.sendBytes(buffer.copyOf(n))
                }
            }
            channel.sendBytes(ByteArray(0))
        } finally {
            snapshot.file.delete()
        }
    }

    companion object {
        const val DEFAULT_PORT = 47_612
        /** Connections served at once: a few phones × (requests + replication), and a pairing. */
        const val MAX_CONNECTIONS = 12
        internal val MAGIC = "SABOUSY1".toByteArray(Charsets.US_ASCII)
        internal const val MODE_PAIR = 1
        internal const val MODE_SESSION = 2
        const val PAIRING_WINDOW_MS = 5 * 60_000L
        internal const val HANDSHAKE_TIMEOUT_MS = 15_000
        internal const val IDLE_TIMEOUT_MS = 10 * 60_000
        /** Like the sign-in on the phone itself: 30 minutes without a command ends the person's session. */
        const val SESSION_IDLE_MS = 30 * 60_000L
        internal const val MAX_WAIT_MS = 25_000L
        private const val POLL_MS = 250L
        internal const val CHUNK = 256 * 1024
    }
}
