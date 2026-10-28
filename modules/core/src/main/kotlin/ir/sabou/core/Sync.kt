package ir.sabou.core

import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.persistence.SyncLog
import ir.sabou.platform.AuditEvent
import ir.sabou.platform.AuditStore
import ir.sabou.platform.Command
import ir.sabou.platform.CommandForwarder
import ir.sabou.platform.CommandOutcome
import ir.sabou.platform.RemoteIdentity
import ir.sabou.platform.User
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Two phones, one set of books (ADR-0042). The main phone owns the database: every command runs there, in its
 * single pipeline (permissions, idempotency, numbering, audit). The second phone keeps an exact copy, refreshed by
 * row replication ([SyncLog]); its reads are local and its commands are forwarded. It never writes business data
 * or audit records of its own, so its audit chain is always a prefix of the main phone's and verifies as such.
 */
class Sync internal constructor(private val core: SabouCore) {
    // ------------------------------------------------------------------ main phone

    /** The current end of the change log (a new second phone starts from a full copy taken at this point). */
    fun head(): Long = core.unitOfWork.transaction { SyncLog.head(core.db) }

    /** Everything after [cursor], as one consistent moment of the books. */
    fun changesSince(cursor: Long): SyncLog.Batch = core.unitOfWork.transaction { SyncLog.since(core.db, cursor) }

    /** Drops log entries up to [upTo] (the second phone has them, or there is no second phone). */
    fun prune(upTo: Long) = core.unitOfWork.transaction { SyncLog.prune(core.db, upTo) }

    /** Checks a sign-in made on the second phone [device]; returns the person (nobody is signed in here). */
    fun verifyLogin(username: String, pin: CharArray, device: String): User = core.identity.verifyRemoteLogin(username, pin, device)

    fun confirmPin(userId: GlobalId, pin: CharArray) = core.identity.confirmRemotePin(userId, pin)

    /**
     * Runs a command sent by the second phone for [userId] (verified by [verifyLogin] earlier). The person's current
     * role and branches apply, so deactivating them or changing their access takes effect on the next command.
     */
    fun execute(userId: GlobalId, command: Command): CommandOutcome {
        val actor = core.users.byId(userId)?.takeIf { it.isActive }?.toActor() ?: throw DomainException(DomainError.AuthenticationRequired)
        val route = dispatcher.route(command)
        return core.session.actAs(actor) { route(command) }
    }

    internal val dispatcher by lazy { CommandDispatcher(core) }

    // ------------------------------------------------------------------ second phone

    /** True once [becomeReplica] was called on this core. */
    @Volatile var isReplica: Boolean = false
        private set

    /**
     * Makes this core a copy of the main phone: commands go to [forwarder], sign-in to [identity], and nothing may
     * append to the local audit chain. The local change log is cleared (it only ever records replication here).
     */
    fun becomeReplica(forwarder: CommandForwarder, identity: RemoteIdentity) {
        isReplica = true
        core.auditGuard.replica = true
        core.bus.forwarder = forwarder
        core.identity.remote = identity
        core.unitOfWork.transaction { core.db.execute("DELETE FROM ${SyncLog.CHANGES}") }
    }

    /**
     * True when this database is a second phone's copy: only a copy ever records how far it applied the main phone's
     * log. The marker lives in the database itself, so it survives losing the phone's pairing file.
     */
    fun isCopy(): Boolean = core.unitOfWork.transaction {
        core.db.query("SELECT 1 AS n FROM ${SyncLog.STATE} WHERE key = ?", SyncLog.CURSOR).isNotEmpty()
    }

    /**
     * A copy whose pairing was lost (its file is missing, unreadable or has no key): it must not become a second set
     * of books (independent audit, 1405/08/06, finding 001). Every command is refused until the owner pairs this phone
     * again or detaches it (which sets the copy aside); what it already holds can still be read.
     */
    fun lockOrphanedCopy() {
        isReplica = true
        core.bus.forwarder = CommandForwarder { _, _ -> throw DomainException(DomainError.InvalidState("DEVICE", "PAIRING_LOST")) }
    }

    /** How far this copy has applied the main phone's log. */
    fun cursor(): Long = core.unitOfWork.transaction { SyncLog.cursor(core.db) }

    /** After a full copy taken at [cursor]: start applying from there. */
    fun startAt(cursor: Long) = core.unitOfWork.transaction {
        core.db.execute("DELETE FROM ${SyncLog.CHANGES}")
        core.db.execute("DELETE FROM ${SyncLog.STATE}")   // the copy carries the main phone's own bookkeeping
        SyncLog.setCursor(core.db, cursor)
    }

    /** Applies one batch from the main phone atomically; a failure leaves this copy as it was. */
    fun apply(batch: SyncLog.Batch) {
        check(isReplica) { "NOT_A_REPLICA" }
        core.unitOfWork.transaction { SyncLog.apply(core.db, batch) }
    }
}

/** Refuses any local audit record on a second phone: its chain must stay a copy of the main phone's. */
internal class ReplicaAuditGuard(private val delegate: AuditStore) : AuditStore by delegate {
    @Volatile var replica = false
    override fun append(event: AuditEvent) {
        if (replica) throw DomainException(DomainError.InvalidState("DEVICE", "HUB_ONLY"))
        delegate.append(event)
    }
}

/**
 * Finds, for each command type, the one public entry point that runs it: a method of one of the core's public
 * services taking exactly that command and returning [CommandOutcome]. Where a core-level service wraps a module's
 * entry point with extra checks (e.g. the month-end checklist), the core-level one is the route.
 */
internal class CommandDispatcher(core: SabouCore) {
    private val routes: Map<Class<*>, (Command) -> CommandOutcome>
    private val owners = HashMap<Class<*>, Class<*>>()

    init {
        val found = HashMap<Class<*>, MutableList<Pair<Any, Method>>>()
        SabouCore::class.java.methods
            .filter { it.parameterCount == 0 && it.name.startsWith("get") && '$' !in it.name && Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers) }
            .forEach { getter ->
                val service = runCatching { getter.invoke(core) }.getOrNull() ?: return@forEach
                if (service === core) return@forEach
                service.javaClass.methods
                    .filter { m ->
                        m.parameterCount == 1 && Command::class.java.isAssignableFrom(m.parameterTypes[0]) &&
                            m.returnType == CommandOutcome::class.java && '$' !in m.name && Modifier.isPublic(m.modifiers)
                    }
                    .forEach { m -> found.getOrPut(m.parameterTypes[0]) { ArrayList() } += service to m }
            }
        routes = found.mapValues { (type, candidates) ->
            val chosen = candidates.distinctBy { it.second }.let { all ->
                if (all.size == 1) all.single()
                else all.filter { it.second.declaringClass.name.substringBeforeLast('.') == SabouCore::class.java.name.substringBeforeLast('.') }.singleOrNull()
                    ?: error("AMBIGUOUS_COMMAND_ROUTE:${type.name}")
            }
            val (service, method) = chosen
            owners[type] = method.declaringClass
            val route: (Command) -> CommandOutcome = { command ->
                try {
                    method.invoke(service, command) as CommandOutcome
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            }
            route
        }
    }

    val commandTypes: Set<Class<*>> get() = routes.keys

    /** The class whose method runs [type] (for tests). */
    fun routeOwner(type: Class<*>): Class<*>? = owners[type]

    fun route(command: Command): (Command) -> CommandOutcome =
        routes[command.javaClass] ?: throw DomainException(DomainError.InvalidState("COMMAND", "UNKNOWN:${command.javaClass.simpleName}"))
}
