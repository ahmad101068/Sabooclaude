package ir.sabou.platform.memory

import ir.sabou.platform.Actor
import ir.sabou.platform.AnchorStore
import ir.sabou.platform.AuditEvent
import ir.sabou.platform.AuditStore
import ir.sabou.platform.DomainEvent
import ir.sabou.platform.EventLog
import ir.sabou.platform.IdempotencyRecord
import ir.sabou.platform.IdempotencyStore
import ir.sabou.platform.IntegrityAnchor
import ir.sabou.platform.SessionPort
import ir.sabou.platform.UnitOfWork

/**
 * Reference in-memory adapters. They implement the same contracts as the Room adapters and are
 * used by domain tests; [InMemoryUnitOfWork] really rolls back every participant on failure.
 */
interface Transactional {
    fun snapshot(): Any
    fun restore(snapshot: Any)
}

class InMemoryUnitOfWork : UnitOfWork {
    private val participants = mutableListOf<Transactional>()
    private var depth = 0

    fun register(vararg stores: Transactional) {
        participants += stores
    }

    @Synchronized
    override fun <T> transaction(block: () -> T): T {
        if (depth > 0) return block()
        val snapshots = participants.map { it to it.snapshot() }
        depth++
        try {
            return block()
        } catch (error: Throwable) {
            snapshots.forEach { (store, snapshot) -> store.restore(snapshot) }
            throw error
        } finally {
            depth--
        }
    }
}

/** Small helper: a transactional map-backed table. */
open class Table<K, V> : Transactional {
    protected val rows = LinkedHashMap<K, V>()
    fun get(key: K): V? = rows[key]
    fun put(key: K, value: V) { rows[key] = value }
    fun values(): List<V> = rows.values.toList()
    override fun snapshot(): Any = LinkedHashMap(rows)
    @Suppress("UNCHECKED_CAST")
    override fun restore(snapshot: Any) {
        rows.clear(); rows.putAll(snapshot as Map<K, V>)
    }
}

class InMemoryIdempotencyStore : Table<String, IdempotencyRecord>(), IdempotencyStore {
    override fun find(commandId: String): IdempotencyRecord? = get(commandId)
    override fun save(record: IdempotencyRecord) {
        check(get(record.commandId) == null) { "duplicate_command_record" }
        put(record.commandId, record)
    }
}

class InMemoryAuditStore : AuditStore, Transactional {
    val events = mutableListOf<AuditEvent>()
    override fun head(): AuditEvent? = events.lastOrNull()
    override fun append(event: AuditEvent) { events += event }
    override fun page(afterPosition: Long, limit: Int): List<AuditEvent> =
        events.drop(afterPosition.toInt()).take(limit)
    override fun contains(epoch: String, sequence: Long, hash: String): Boolean =
        events.any { it.epoch == epoch && it.sequence == sequence && it.hash == hash }
    override fun snapshot(): Any = events.toList()
    @Suppress("UNCHECKED_CAST")
    override fun restore(snapshot: Any) { events.clear(); events.addAll(snapshot as List<AuditEvent>) }
}

class InMemoryEventLog : EventLog, Transactional {
    val events = mutableListOf<DomainEvent>()
    override fun append(event: DomainEvent) { events += event }
    override fun snapshot(): Any = events.toList()
    @Suppress("UNCHECKED_CAST")
    override fun restore(snapshot: Any) { events.clear(); events.addAll(snapshot as List<DomainEvent>) }
}

/** Anchors live outside the database, so they are intentionally NOT transactional. */
class InMemoryAnchorStore : AnchorStore {
    val history = mutableListOf<IntegrityAnchor>()
    override fun latest(): IntegrityAnchor? = history.lastOrNull()
    override fun record(anchor: IntegrityAnchor) { history += anchor }
}

class MutableSession(var actor: Actor? = null) : SessionPort {
    override fun currentActor(): Actor? = actor
}

class InMemoryUserStore : Table<ir.sabou.kernel.GlobalId, ir.sabou.platform.User>(), ir.sabou.platform.UserStore {
    override fun byId(id: ir.sabou.kernel.GlobalId) = get(id)
    override fun byUsername(username: String) = values().firstOrNull { it.username == username }
    override fun all() = values()
    override fun save(user: ir.sabou.platform.User) = put(user.id, user)
}

class InMemoryBranchStore : Table<ir.sabou.kernel.BranchId, ir.sabou.platform.Branch>(), ir.sabou.platform.BranchStore {
    override fun byId(id: ir.sabou.kernel.BranchId) = get(id)
    override fun all() = values()
    override fun save(branch: ir.sabou.platform.Branch) = put(branch.id, branch)
}
