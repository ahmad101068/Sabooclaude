package ir.sabou.sync

import ir.sabou.core.SabouCore
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.platform.Command
import ir.sabou.platform.CommandForwarder
import ir.sabou.platform.CommandOutcome
import ir.sabou.platform.ModuleId
import ir.sabou.platform.RemoteIdentity
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/** Where the main phone can be reached now (found on the network, or typed in), or null. */
fun interface HubLocator {
    fun locate(): InetSocketAddress?
}

/** The second phone's identity towards the main phone: its id and the pairing key, and which main phone. */
class Pairing(val deviceId: String, val key: ByteArray, val hubId: String = "", val hubName: String = "")

/** One authenticated connection to the main phone. */
internal class HubConnection private constructor(private val socket: Socket, val channel: SecureChannel, val epoch: String) : AutoCloseable {
    fun request(message: Message): Message {
        channel.send(message)
        return channel.receive()
    }

    var readTimeout: Int
        get() = socket.soTimeout
        set(value) { socket.soTimeout = value }

    override fun close() = runCatching { socket.close() }.let { }

    companion object {
        fun open(address: InetSocketAddress, pairing: Pairing, appVersion: String): HubConnection {
            val socket = Socket()
            try {
                socket.connect(address, CONNECT_TIMEOUT_MS)
                socket.tcpNoDelay = true
                socket.soTimeout = SyncHub.HANDSHAKE_TIMEOUT_MS
                val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                val nonce = Crypto.randomBytes(32)
                output.write(SyncHub.MAGIC); output.writeByte(SyncHub.MODE_SESSION)
                output.writeShortBytes(pairing.deviceId.toByteArray(Charsets.UTF_8)); output.write(nonce); output.flush()
                val serverNonce = input.readBytes(32)
                val (c2s, s2c) = Crypto.sessionKeys(pairing.key, nonce, serverNonce)
                val channel = SecureChannel(input, output, c2s, s2c)
                channel.send(Message.Hello(appVersion))
                return when (val reply = channel.receive()) {
                    is Message.HelloOk -> HubConnection(socket, channel, reply.epoch).also { socket.soTimeout = REQUEST_TIMEOUT_MS }
                    is Message.Failure -> throw DomainException(reply.error)
                    else -> throw IOException("unexpected hello")
                }
            } catch (e: Throwable) {
                runCatching { socket.close() }
                throw e
            }
        }

        const val CONNECT_TIMEOUT_MS = 4_000
        const val REQUEST_TIMEOUT_MS = 60_000
    }
}

/**
 * The second phone's side (ADR-0042). It forwards commands and sign-ins to the main phone and keeps this phone's
 * copy current: a background loop asks for changes (the main phone answers as soon as there are any, or after a
 * while), and after each command the copy is brought up to that command's effects before the screen refreshes.
 */
class SyncReplica(
    private val core: SabouCore,
    private val locator: HubLocator,
    private val pairing: Pairing,
    private val appVersion: String,
    private val listener: Listener,
) : CommandForwarder, RemoteIdentity {
    interface Listener {
        /** New data was applied: refresh what is on screen. */
        fun onApplied()
        /** Connected or not, for the status line. */
        fun onConnection(connected: Boolean)
        /** The copy cannot continue from the log (gap, replaced books, or a batch that did not apply): take a full copy. */
        fun onNeedsFullCopy(reason: String)
        /**
         * A command was recorded on the main phone but this copy could not yet bring in its effects: until the next
         * [onApplied], what is on screen may not show it. The command itself stands (independent audit, finding 002).
         */
        fun onBehind() {}
    }

    @Volatile private var token: String? = null
    @Volatile private var running = false
    private var control: HubConnection? = null
    private val controlLock = Any()
    private val applyLock = Any()
    @Volatile var connected: Boolean = false
        private set

    fun start() {
        if (running) return
        running = true
        core.sync.becomeReplica(this, this)
        Thread({ pullLoop() }, "sabou-sync-pull").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        synchronized(controlLock) { control?.close(); control = null }
    }

    private fun setConnected(value: Boolean) {
        if (connected != value) { connected = value; listener.onConnection(value) }
    }

    /** Opens (or reuses) the control connection and checks it still belongs to the same books. */
    private fun connection(): HubConnection {
        control?.let { return it }
        val address = locator.locate() ?: throw DomainException(SyncErrors.HUB_UNREACHABLE)
        val c = try {
            HubConnection.open(address, pairing, appVersion)
        } catch (e: IOException) {
            setConnected(false)
            throw DomainException(SyncErrors.HUB_UNREACHABLE)
        }
        if (c.epoch != core.epoch) {
            c.close()
            listener.onNeedsFullCopy("EPOCH")
            throw DomainException(SyncErrors.HUB_UNREACHABLE)
        }
        control = c
        setConnected(true)
        return c
    }

    /**
     * One request on the control connection. A broken connection is retried once on a fresh one: every request is
     * safe to repeat (commands carry their own id, so a command that already ran answers with its first result).
     */
    private fun request(message: Message): Message = synchronized(controlLock) {
        var attempt = 0
        while (true) {
            try {
                return when (val reply = connection().request(message)) {
                    is Message.Failure -> throw DomainException(reply.error)
                    else -> reply
                }
            } catch (e: IOException) {
                control?.close(); control = null
                if (++attempt >= 2) { setConnected(false); throw DomainException(SyncErrors.HUB_UNREACHABLE) }
            }
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    // ------------------------------------------------------------------ commands and sign-in

    override fun forward(module: ModuleId, command: Command): CommandOutcome {
        val t = token ?: throw DomainException(DomainError.AuthenticationRequired)
        val reply = try {
            request(Message.Execute(t, module, command))
        } catch (e: DomainException) {
            if (e.error == DomainError.AuthenticationRequired) token = null
            throw e
        }
        val executed = reply as? Message.Executed ?: throw DomainException(SyncErrors.HUB_UNREACHABLE)
        // Recorded on the main phone, which keeps the books: that is the outcome. A copy left behind is said so, never
        // reported as a failure (the person would enter it again) nor silently shown as up to date.
        if (!runCatching { catchUp(executed.cursor) }.getOrDefault(false)) listener.onBehind()
        return executed.outcome
    }

    override fun login(username: String, pin: CharArray): GlobalId {
        val reply = request(Message.Login(username, pin.copyOf())) as? Message.LoginOk ?: throw DomainException(SyncErrors.HUB_UNREACHABLE)
        token = reply.token
        if (!runCatching { catchUp(reply.cursor) }.getOrDefault(false)) listener.onBehind()
        return reply.userId
    }

    override fun confirmPin(userId: GlobalId, pin: CharArray) {
        val t = token ?: throw DomainException(DomainError.AuthenticationRequired)
        request(Message.ConfirmPin(t, pin.copyOf()))
    }

    /** Ends the person's session on the main phone too (best effort: it also expires by itself). */
    fun logout() {
        val t = token ?: return
        token = null
        runCatching { request(Message.Logout(t)) }
    }

    // ------------------------------------------------------------------ replication

    /** Brings this copy at least to [target] (the effects of a command or sign-in just made); true once it is there. */
    private fun catchUp(target: Long): Boolean {
        var rounds = 0
        while (core.sync.cursor() < target && rounds++ < 20) {
            val reply = request(Message.Pull(core.sync.cursor(), 0)) as? Message.Changes ?: return false
            if (!applyBatch(reply)) return false
        }
        return core.sync.cursor() >= target
    }

    /** Applies one batch; false when the copy must be replaced instead. */
    private fun applyBatch(changes: Message.Changes): Boolean = synchronized(applyLock) {
        val batch = changes.batch
        if (batch.tooMany) {
            // Asked from an older position while the other connection already moved this copy on (and the main
            // phone forgot what lies before it): not a gap — ask again from where this copy is now.
            if (batch.upTo < core.sync.cursor()) return true
            listener.onNeedsFullCopy("GAP"); running = false; return false
        }
        if (batch.upTo <= core.sync.cursor()) return true   // the other loop got here first
        try {
            core.sync.apply(batch)
        } catch (e: Exception) {
            listener.onNeedsFullCopy("APPLY:${e.message ?: e.javaClass.simpleName}")
            running = false
            return false
        }
        if (batch.changes.isNotEmpty()) listener.onApplied()
        true
    }

    private fun pullLoop() {
        var connection: HubConnection? = null
        var backoff = 1_000L
        while (running) {
            try {
                val c = connection ?: run {
                    val address = locator.locate() ?: throw IOException("no hub")
                    HubConnection.open(address, pairing, appVersion).also { opened ->
                        if (opened.epoch != core.epoch) { opened.close(); listener.onNeedsFullCopy("EPOCH"); running = false; return }
                        opened.readTimeout = (SyncHub.MAX_WAIT_MS + 15_000).toInt()
                    }
                }
                connection = c
                setConnected(true)
                backoff = 1_000L
                val reply = c.request(Message.Pull(core.sync.cursor(), PULL_WAIT_MS))
                when (reply) {
                    is Message.Changes -> if (!applyBatch(reply)) break
                    is Message.Failure -> throw DomainException(reply.error)
                    else -> throw IOException("unexpected reply")
                }
            } catch (e: DomainException) {
                connection?.close(); connection = null
                if (e.error == SyncErrors.VERSION) setConnected(false)
                sleep(backoff); backoff = (backoff * 2).coerceAtMost(15_000)
            } catch (e: Exception) {
                connection?.close(); connection = null
                setConnected(false)
                sleep(backoff); backoff = (backoff * 2).coerceAtMost(15_000)
            }
        }
        connection?.close()
    }

    private fun sleep(ms: Long) = runCatching { Thread.sleep(ms) }.let { }

    companion object {
        const val PULL_WAIT_MS = 20_000L
        /** A restaurant's books stay far below this; a larger announced copy is refused before it is downloaded. */
        const val MAX_COPY_BYTES = 4L * 1024 * 1024 * 1024
        /** Room left on the phone after the copy (the app and the old copy still need space). */
        const val FREE_MARGIN_BYTES = 64L * 1024 * 1024

        /** Downloads a full copy of the main phone's books into [target]; returns its header. */
        fun downloadCopy(address: InetSocketAddress, pairing: Pairing, appVersion: String, target: File, progress: (Long, Long) -> Unit = { _, _ -> }): Message.CopyHeader {
            HubConnection.open(address, pairing, appVersion).use { c ->
                c.readTimeout = 120_000
                val header = when (val reply = c.request(Message.CopyRequest)) {
                    is Message.CopyHeader -> reply
                    is Message.Failure -> throw DomainException(reply.error)
                    else -> throw IOException("unexpected reply")
                }
                // Size and room are checked before anything is written, and the stream may not run past the size it
                // announced; a copy that fails half way leaves no partial file behind (audit finding 007).
                if (header.size !in 1..MAX_COPY_BYTES) throw DomainException(DomainError.InvalidState("SYNC", "COPY_SIZE"))
                val room = (target.absoluteFile.parentFile ?: target.absoluteFile).usableSpace
                if (room < header.size + FREE_MARGIN_BYTES) throw DomainException(DomainError.InvalidState("SYNC", "NO_ROOM"))
                try {
                    target.outputStream().use { out: OutputStream ->
                        var done = 0L
                        while (true) {
                            val chunk = c.channel.receiveBytes()
                            if (chunk.isEmpty()) break
                            if (done + chunk.size > header.size) throw IOException("copy longer than announced")
                            out.write(chunk)
                            done += chunk.size
                            progress(done, header.size)
                        }
                        if (done != header.size) throw IOException("copy truncated")
                    }
                } catch (e: Throwable) {
                    target.delete()
                    throw e
                }
                return header
            }
        }
    }
}

/**
 * The second phone's side of pairing: connect, show [code] to the user, and after they confirm it matches the main
 * phone's screen, [confirm] waits for the owner's confirmation there and returns the pairing.
 */
class PairingAttempt private constructor(
    private val socket: Socket,
    private val channel: SecureChannel,
    private val key: ByteArray,
    private val deviceId: String,
    val code: String,
    val hubName: String,
) : AutoCloseable {
    fun confirm(): Pairing {
        socket.soTimeout = SyncHub.PAIRING_WINDOW_MS.toInt()
        channel.send(Message.PairConfirm)
        val reply = channel.receive() as? Message.PairAccepted ?: throw IOException("not accepted")
        close()
        return Pairing(deviceId, key, reply.hubId, reply.hubName)
    }

    override fun close() = runCatching { socket.close() }.let { }

    companion object {
        fun start(address: InetSocketAddress, deviceId: String, deviceName: String): PairingAttempt {
            val socket = Socket()
            try {
                socket.connect(address, HubConnection.CONNECT_TIMEOUT_MS)
                socket.soTimeout = SyncHub.HANDSHAKE_TIMEOUT_MS
                val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                val mine = Crypto.Ephemeral()
                val nonce = Crypto.randomBytes(32)
                output.write(SyncHub.MAGIC); output.writeByte(SyncHub.MODE_PAIR)
                output.writeShortBytes(mine.publicKey); output.write(nonce)
                output.writeShortBytes(deviceName.toByteArray(Charsets.UTF_8)); output.writeShortBytes(deviceId.toByteArray(Charsets.UTF_8))
                output.flush()
                val serverPub = input.readShortBytes()
                val serverNonce = input.readBytes(32)
                val hubName = String(input.readShortBytes(), Charsets.UTF_8)
                val (key, code) = Crypto.pairing(mine.agree(serverPub), mine.publicKey, serverPub, nonce, serverNonce)
                val (c2s, s2c) = Crypto.sessionKeys(key, nonce, serverNonce)
                return PairingAttempt(socket, SecureChannel(input, output, c2s, s2c), key, deviceId, code, hubName)
            } catch (e: Throwable) {
                runCatching { socket.close() }
                throw e
            }
        }
    }
}
