package ir.sabou.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import ir.sabou.core.SabouCore
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.sync.HubLocator
import ir.sabou.sync.Pairing
import ir.sabou.sync.PairingAttempt
import ir.sabou.sync.PairingListener
import ir.sabou.sync.Peer
import ir.sabou.sync.PeerStore
import ir.sabou.sync.SyncHub
import ir.sabou.sync.SyncReplica
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.util.Base64

/**
 * This phone's part in "two phones, one set of books" (ADR-0042):
 * - NONE: a phone of its own (the default);
 * - HUB: the main phone — it keeps the books and serves a paired second phone over the restaurant's Wi-Fi;
 * - REPLICA: the second phone — it reads its own copy and sends every change to the main phone.
 * The role and the pairing keys belong to this phone, not to the books, so they live outside the database, sealed
 * with this phone's Keystore key.
 */
class TwoPhones(private val context: Context, private val container: AppContainer) {
    enum class Role { NONE, HUB, REPLICA }

    data class Status(
        val role: Role = Role.NONE,
        val hubName: String = "",
        val connected: Boolean = false,
        val peers: List<Peer> = emptyList(),
        val connectedPeers: Set<String> = emptySet(),
        val address: String? = null,
        val problem: String? = null,
        /** On the main phone while a phone asks to pair: the code to compare, and that phone's name. */
        val pairingCode: String? = null,
        val pairingDevice: String? = null,
        val pairingOpen: Boolean = false,
        val pairingResult: String? = null,
        /** This phone holds a second phone's copy but its pairing was lost: nothing can be recorded on it. */
        val orphaned: Boolean = false,
    )

    private val file = File(context.noBackupFilesDir, "two-phones.cfg")
    private val mutableStatus = MutableStateFlow(Status())
    val status: StateFlow<Status> = mutableStatus

    /** Bumped whenever the copy changed (second phone): every screen on display reloads. */
    private val mutableChanges = MutableStateFlow(0L)
    val changes: StateFlow<Long> = mutableChanges

    @Volatile private var config: Config = load()
    @Volatile private var replica: SyncReplica? = null
    @Volatile private var finder: HubFinder? = null
    @Volatile private var refreshing = false

    val isReplica: Boolean get() = config.role == Role.REPLICA
    val role: Role get() = config.role

    init {
        mutableStatus.value = Status(role = config.role, hubName = config.hubName, peers = config.peers)
    }

    // ------------------------------------------------------------------ persistence

    private data class Config(
        val role: Role = Role.NONE,
        val deviceId: String = java.util.UUID.randomUUID().toString(),
        val hubId: String = "",
        val hubName: String = "",
        val key: ByteArray? = null,
        val lastAddress: String? = null,
        val peers: List<Peer> = emptyList(),
    )

    private fun load(): Config = runCatching {
        if (!file.exists()) return@runCatching Config()
        val j = JSONObject(String(container.unseal(file.readBytes()), Charsets.UTF_8))
        Config(
            role = Role.valueOf(j.getString("role")),
            deviceId = j.getString("deviceId"),
            hubId = j.optString("hubId"),
            hubName = j.optString("hubName"),
            key = j.optString("key").takeIf { it.isNotEmpty() }?.let { Base64.getDecoder().decode(it) },
            lastAddress = j.optString("lastAddress").takeIf { it.isNotEmpty() },
            peers = j.optJSONArray("peers")?.let { a ->
                (0 until a.length()).map { i ->
                    val p = a.getJSONObject(i)
                    Peer(p.getString("id"), p.getString("name"), Base64.getDecoder().decode(p.getString("key")), p.getLong("cursor"), p.getLong("seen"))
                }
            }.orEmpty(),
        )
    }.getOrElse { Config() }

    @Synchronized
    private fun save(c: Config) {
        val j = JSONObject()
            .put("role", c.role.name).put("deviceId", c.deviceId).put("hubId", c.hubId).put("hubName", c.hubName)
            .put("key", c.key?.let { Base64.getEncoder().encodeToString(it) } ?: "")
            .put("lastAddress", c.lastAddress ?: "")
            .put("peers", JSONArray(c.peers.map { p ->
                JSONObject().put("id", p.deviceId).put("name", p.name).put("key", Base64.getEncoder().encodeToString(p.key))
                    .put("cursor", p.cursor).put("seen", p.lastSeen)
            }))
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(container.seal(j.toString().toByteArray(Charsets.UTF_8)))
        check(tmp.renameTo(file)) { "TWO_PHONES_SAVE_FAILED" }
        config = c
        mutableStatus.value = mutableStatus.value.copy(role = c.role, hubName = c.hubName, peers = c.peers)
    }

    private val peerStore = object : PeerStore {
        override fun all(): List<Peer> = config.peers
        override fun save(peer: Peer) = synchronized(this@TwoPhones) { this@TwoPhones.save(config.copy(peers = config.peers.filter { it.deviceId != peer.deviceId } + peer)) }
        override fun remove(deviceId: String) = synchronized(this@TwoPhones) { this@TwoPhones.save(config.copy(peers = config.peers.filter { it.deviceId != deviceId })) }
    }

    // ------------------------------------------------------------------ lifecycle

    /** Called by the container whenever a healthy core is open. */
    fun attach(core: SabouCore) {
        // Fail closed: a copy of the main phone's books never turns into books of its own because a file was lost.
        if (core.sync.isCopy() && (config.role != Role.REPLICA || config.key == null)) {
            core.sync.lockOrphanedCopy()
            mutableStatus.value = mutableStatus.value.copy(orphaned = true,
                problem = "اتصال این گوشی به گوشی اصلی از بین رفته؛ تا مالک دوباره آن را وصل یا جدا نکند، ثبتی انجام نمی‌شود.")
            return
        }
        when (config.role) {
            Role.NONE -> Unit
            Role.HUB -> SyncService.start(context)
            Role.REPLICA -> startReplica(core)
        }
    }

    /** Called before the database is closed or replaced. */
    fun detach() {
        replica?.stop(); replica = null
        finder?.stop(); finder = null
    }

    fun appVersion(): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        return "${info.versionName} ($code)"
    }

    private fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(40)

    // ------------------------------------------------------------------ main phone

    @Volatile private var hub: SyncHub? = null

    /** The server, created on first use (started by [SyncService]). */
    @Synchronized
    fun hub(): SyncHub = hub ?: SyncHub(
        core = { (container.state.value as? AppState.Ready)?.core },
        peers = peerStore,
        snapshots = { core -> container.exportSyncCopy(core) },
        appVersion = appVersion(),
        hubId = config.deviceId,
        hubName = deviceName(),
    ).also { hub = it }

    /** Makes this phone the main phone (the owner decides; checked by the screen). */
    fun becomeHub() {
        if (config.role == Role.REPLICA) throw DomainException(DomainError.InvalidState("DEVICE", "HUB_ONLY"))
        save(config.copy(role = Role.HUB))
        SyncService.start(context)
    }

    /** Stops serving and forgets every paired phone; they must be paired again. */
    fun stopHub() {
        save(config.copy(role = Role.NONE, peers = emptyList()))
        SyncService.stop(context)
        hub?.stop()
        hub = null
        mutableStatus.value = mutableStatus.value.copy(connectedPeers = emptySet(), address = null)
    }

    /** Called by the service once the server listens. */
    fun hubStarted() {
        mutableStatus.value = mutableStatus.value.copy(address = localAddress()?.let { "$it · درگاه ${SyncHub.DEFAULT_PORT}" })
        hub?.pruneForPeers()
    }

    fun refreshHubStatus() {
        val h = hub ?: return
        mutableStatus.value = mutableStatus.value.copy(connectedPeers = h.connectedDevices(), peers = config.peers,
            address = localAddress()?.let { "$it · درگاه ${SyncHub.DEFAULT_PORT}" })
    }

    fun openPairing() {
        mutableStatus.value = mutableStatus.value.copy(pairingOpen = true, pairingCode = null, pairingDevice = null, pairingResult = null)
        hub().openPairing(object : PairingListener {
            override fun onCode(code: String, deviceName: String) {
                mutableStatus.value = mutableStatus.value.copy(pairingCode = code, pairingDevice = deviceName)
            }
            override fun onPaired(peer: Peer) {
                mutableStatus.value = mutableStatus.value.copy(pairingOpen = false, pairingCode = null, pairingResult = "«${peer.name}» وصل شد.", peers = config.peers)
            }
            override fun onFailed(reason: String) {
                mutableStatus.value = mutableStatus.value.copy(pairingOpen = false, pairingCode = null,
                    pairingResult = if (reason == "OWNER_REJECTED") "اتصال رد شد." else "گوشی دوم اتصال را تأیید نکرد.")
            }
        })
    }

    fun answerPairing(same: Boolean) = hub?.confirmPairing(same)

    fun cancelPairing() {
        hub?.closePairing()
        mutableStatus.value = mutableStatus.value.copy(pairingOpen = false, pairingCode = null)
    }

    fun removePeer(deviceId: String) {
        hub?.removePeer(deviceId) ?: peerStore.remove(deviceId)
        refreshHubStatus()
    }

    /** This phone's address on the Wi-Fi, for typing it into the second phone when discovery does not work. */
    fun localAddress(): String? = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val props: LinkProperties = cm.getLinkProperties(cm.activeNetwork) ?: return@runCatching null
        props.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }.getOrNull()

    // ------------------------------------------------------------------ second phone

    private fun startReplica(core: SabouCore) {
        val key = checkNotNull(config.key) { "REPLICA_WITHOUT_KEY" }   // attach() locks such a copy first
        val pairing = Pairing(config.deviceId, key, config.hubId, config.hubName)
        val f = HubFinder(context, config.hubId) { address -> save(config.copy(lastAddress = "${address.hostString}:${address.port}")) }
        finder = f
        f.start()
        val r = SyncReplica(core, locator(f), pairing, appVersion(), object : SyncReplica.Listener {
            override fun onApplied() {
                mutableChanges.value = mutableChanges.value + 1
                if (mutableStatus.value.problem == BEHIND) mutableStatus.value = mutableStatus.value.copy(problem = null)
            }
            override fun onBehind() { mutableStatus.value = mutableStatus.value.copy(problem = BEHIND) }
            override fun onConnection(connected: Boolean) {
                mutableStatus.value = mutableStatus.value.copy(connected = connected, problem = null)
            }
            override fun onNeedsFullCopy(reason: String) = refreshCopy(reason)
        })
        replica = r
        r.start()
    }

    private fun locator(f: HubFinder) = HubLocator {
        f.latest ?: config.lastAddress?.let(::parseAddress)
    }

    /** Ends the person's session on the main phone too (signing out on the second phone). */
    fun signedOut() {
        val r = replica ?: return
        kotlin.concurrent.thread(isDaemon = true) { r.logout() }
    }

    /** The copy cannot continue from the log: take a new full copy (the app then reopens on it). */
    private fun refreshCopy(reason: String) {
        if (refreshing) return
        refreshing = true
        mutableStatus.value = mutableStatus.value.copy(problem = "در حال گرفتن نسخه‌ی تازه از گوشی اصلی…")
        kotlin.concurrent.thread(name = "sabou-sync-copy", isDaemon = true) {
            try {
                var delay = 2_000L
                while (true) {
                    val outcome = runCatching {
                        val address = finder?.latest ?: config.lastAddress?.let(::parseAddress) ?: throw DomainException(ir.sabou.sync.SyncErrors.HUB_UNREACHABLE)
                        val target = container.syncCandidateFile()
                        val header = SyncReplica.downloadCopy(address, Pairing(config.deviceId, config.key!!, config.hubId, config.hubName), appVersion(), target)
                        container.installSyncCopy(target, header.key, header.epoch, header.cursor, firstTime = false)
                    }
                    if (outcome.isSuccess) break
                    mutableStatus.value = mutableStatus.value.copy(problem = "نسخه‌ی تازه گرفته نشد؛ دوباره تلاش می‌شود. (${reason.take(40)})")
                    Thread.sleep(delay)
                    delay = (delay * 2).coerceAtMost(60_000)
                    if (config.role != Role.REPLICA) break
                }
            } finally {
                refreshing = false
            }
        }
    }

    /** Step 1 of pairing on this (future second) phone: connect to [address] and get the code to compare. */
    fun startPairing(address: InetSocketAddress): PairingAttempt = PairingAttempt.start(address, config.deviceId, deviceName())

    /**
     * Step 2, after the person saw the same code on both phones: wait for the owner's confirmation on the main
     * phone, download the books and replace this phone's data with them. [progress]: bytes done and total.
     * The role is saved before the data is replaced: from then on this phone only ever takes copies.
     */
    fun finishPairing(attempt: PairingAttempt, address: InetSocketAddress, progress: (Long, Long) -> Unit) {
        val pairing = attempt.confirm()
        val target = container.syncCandidateFile()
        val header = SyncReplica.downloadCopy(address, pairing, appVersion(), target, progress)
        save(config.copy(role = Role.REPLICA, hubId = pairing.hubId, hubName = pairing.hubName, key = pairing.key,
            lastAddress = "${address.hostString}:${address.port}", peers = emptyList()))
        try {
            container.installSyncCopy(target, header.key, header.epoch, header.cursor, firstTime = true)
        } catch (e: Throwable) {
            // This phone keeps its own data and stays a phone of its own.
            save(config.copy(role = Role.NONE, key = null, hubId = "", hubName = ""))
            throw e
        }
    }

    /** After the container replaced this phone's copy with an empty database. */
    fun forgetRole() {
        detach()
        save(config.copy(role = Role.NONE, key = null, hubId = "", hubName = "", lastAddress = null))
        mutableStatus.value = Status()
    }

    /** Phones offering themselves as the main phone on this network, found so far (for the pairing screen). */
    fun discover(onFound: (name: String, address: InetSocketAddress) -> Unit): HubFinder =
        HubFinder(context, hubId = null) { }.also { it.onAny = onFound; it.start() }

    companion object {
        fun parseAddress(text: String): InetSocketAddress? {
            val t = text.trim()
            val host = t.substringBeforeLast(':', t).trim()
            val port = t.substringAfterLast(':', "").trim().toIntOrNull() ?: SyncHub.DEFAULT_PORT
            if (host.isEmpty() || !host.matches(Regex("[0-9.]{7,15}"))) return null
            return InetSocketAddress(host, port)
        }

        const val SERVICE_TYPE = "_sabou._tcp."
        /** Shown on the second phone after a command whose effects have not reached it yet. */
        const val BEHIND = "در گوشی اصلی ثبت شد؛ این گوشی هنوز به‌روز نشده و تا چند لحظه‌ی دیگر نشان داده می‌شود."

        fun serviceName(hubId: String) = "sabou-${hubId.take(12)}"
    }
}

/**
 * Finds the main phone on the local network by its service name (Android network service discovery, mDNS), so a
 * changed Wi-Fi address does not break the link. [hubId] null: report every main phone found ([onAny]).
 */
class HubFinder(private val context: Context, private val hubId: String?, private val onResolved: (InetSocketAddress) -> Unit) {
    @Volatile var latest: InetSocketAddress? = null
        private set
    @Volatile var onAny: ((String, InetSocketAddress) -> Unit)? = null
    private val nsd = context.getSystemService(NsdManager::class.java)
    private var lock: WifiManager.MulticastLock? = null
    @Volatile private var listener: NsdManager.DiscoveryListener? = null
    private val resolving = java.util.concurrent.atomic.AtomicBoolean(false)
    private val queue = java.util.concurrent.ConcurrentLinkedQueue<NsdServiceInfo>()

    fun start() {
        if (listener != null) return
        runCatching {
            lock = context.applicationContext.getSystemService(WifiManager::class.java).createMulticastLock("sabou-sync").apply { setReferenceCounted(false); acquire() }
        }
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val wanted = hubId?.let { TwoPhones.serviceName(it) }
                if (wanted != null && serviceInfo.serviceName != wanted) return
                if (!serviceInfo.serviceName.startsWith("sabou-")) return
                queue += serviceInfo
                resolveNext()
            }
        }
        listener = l
        runCatching { nsd.discoverServices(TwoPhones.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    // Older Android versions resolve one service at a time.
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (!resolving.compareAndSet(false, true)) return
        val info = queue.poll() ?: run { resolving.set(false); return }
        runCatching {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { resolving.set(false); resolveNext() }
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val host = serviceInfo.host
                    if (host is Inet4Address) {
                        val address = InetSocketAddress(host, serviceInfo.port)
                        latest = address
                        onResolved(address)
                        onAny?.invoke(serviceInfo.serviceName, address)
                    }
                    resolving.set(false)
                    resolveNext()
                }
            })
        }.onFailure { resolving.set(false) }
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        runCatching { lock?.release() }
        lock = null
    }
}
