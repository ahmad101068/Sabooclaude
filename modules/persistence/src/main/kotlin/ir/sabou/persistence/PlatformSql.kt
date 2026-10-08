package ir.sabou.persistence

import ir.sabou.kernel.BranchId
import ir.sabou.kernel.GlobalId
import ir.sabou.platform.AuditEvent
import ir.sabou.platform.AuditStore
import ir.sabou.platform.Branch
import ir.sabou.platform.BranchStore
import ir.sabou.platform.DomainEvent
import ir.sabou.platform.EventLog
import ir.sabou.platform.IdempotencyRecord
import ir.sabou.platform.IdempotencyStore
import ir.sabou.platform.ModuleId
import ir.sabou.platform.Role
import ir.sabou.platform.User
import ir.sabou.platform.UserStore

class SqlIdempotencyStore(private val db: SqlDatabase) : IdempotencyStore {
    override fun find(commandId: String): IdempotencyRecord? =
        db.query("SELECT * FROM idempotency WHERE command_id = ?", commandId).firstOrNull()?.let {
            IdempotencyRecord(it.str("command_id"), it.str("command_type"), it.str("fingerprint"), it.str("result_id"), it.long("recorded_at"))
        }

    override fun save(record: IdempotencyRecord) = db.execute(
        "INSERT INTO idempotency (command_id, command_type, fingerprint, result_id, recorded_at) VALUES (?, ?, ?, ?, ?)",
        record.commandId, record.commandType, record.fingerprint, record.resultId, record.recordedAtEpochMillis,
    )
}

/** Audit rows are immutable (trigger). Positions are contiguous because AUTOINCREMENT rolls back with the transaction. */
class SqlAuditStore(private val db: SqlDatabase) : AuditStore {
    private fun read(r: SqlRow) = AuditEvent(
        r.str("epoch"), r.long("sequence"), r.str("previous_hash"), r.str("hash"), r.long("occurred_at"), r.str("actor_id"),
        r.str("actor_name"), ModuleId.valueOf(r.str("module")), r.str("action"), r.str("entity_type"), r.str("entity_id"),
        r.str("scope"), r.str("command_id"), r.str("detail"),
    )

    override fun head(): AuditEvent? = db.query("SELECT * FROM audit_events ORDER BY position DESC LIMIT 1").firstOrNull()?.let(::read)

    override fun append(event: AuditEvent) = db.execute(
        "INSERT INTO audit_events (epoch, sequence, previous_hash, hash, occurred_at, actor_id, actor_name, module, action, entity_type, entity_id, scope, command_id, detail) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        event.epoch, event.sequence, event.previousHash, event.hash, event.occurredAtEpochMillis, event.actorId, event.actorName,
        event.module.name, event.action, event.entityType, event.entityId, event.scope, event.commandId, event.detail,
    )

    override fun page(afterPosition: Long, limit: Int): List<AuditEvent> =
        db.query("SELECT * FROM audit_events WHERE position > ? ORDER BY position LIMIT ?", afterPosition, limit).map(::read)

    override fun contains(epoch: String, sequence: Long, hash: String): Boolean =
        db.query("SELECT 1 AS x FROM audit_events WHERE epoch = ? AND sequence = ? AND hash = ?", epoch, sequence, hash).isNotEmpty()
}

class SqlEventLog(private val db: SqlDatabase) : EventLog {
    override fun append(event: DomainEvent) = db.execute(
        "INSERT INTO domain_events (event_id, command_id, module, type, scope, occurred_at, payload) VALUES (?, ?, ?, ?, ?, ?, ?)",
        event.eventId, event.commandId, event.module.name, event.type, event.scope, event.occurredAtEpochMillis, Json.encode(event.payload),
    )

    /** Events not yet delivered to a server (ADR-0001). */
    fun unsynced(limit: Int): List<DomainEvent> =
        db.query("SELECT * FROM domain_events WHERE synced = 0 ORDER BY rowid LIMIT ?", limit).map {
            DomainEvent(it.str("event_id"), it.str("command_id"), ModuleId.valueOf(it.str("module")), it.str("type"), it.str("scope"),
                it.long("occurred_at"), Doc.parse("{\"p\":" + it.str("payload") + "}").strMap("p"))
        }
}

class SqlUserStore(db: SqlDatabase) : SqlTable(db), UserStore {
    private fun read(d: Doc) = User(
        Codec.id(d.str("id")), d.str("username"), d.str("displayName"), Role.valueOf(d.str("role")),
        d.strs("grants").map { BranchId(Codec.id(it)) }.toSet(), d.str("pinHash"), d.int("failedAttempts"),
        d.long("lockedUntil"), d.bool("active"),
    )

    override fun byId(id: GlobalId) = doc("SELECT doc FROM users WHERE id = ?", id.value)?.let(::read)
    override fun byUsername(username: String) = doc("SELECT doc FROM users WHERE username = ?", username)?.let(::read)
    override fun all() = docs("SELECT doc FROM users ORDER BY rowid").map(::read)
    override fun save(user: User) = upsert(
        "users", "id",
        mapOf(
            "id" to user.id.value, "username" to user.username,
            "doc" to Json.encode(
                mapOf(
                    "id" to user.id.value, "username" to user.username, "displayName" to user.displayName, "role" to user.role.name,
                    "grants" to user.branchGrants.map { it.value.value }.sorted(), "pinHash" to user.pinHash,
                    "failedAttempts" to user.failedAttempts, "lockedUntil" to user.lockedUntilEpochMillis, "active" to user.isActive,
                ),
            ),
        ),
    )
}

class SqlBranchStore(db: SqlDatabase) : SqlTable(db), BranchStore {
    private fun read(d: Doc) = Branch(BranchId(Codec.id(d.str("id"))), d.str("name"), d.bool("active"))
    override fun byId(id: BranchId) = doc("SELECT doc FROM branches WHERE id = ?", id.value.value)?.let(::read)
    override fun all() = docs("SELECT doc FROM branches ORDER BY rowid").map(::read)
    override fun save(branch: Branch) = upsert(
        "branches", "id",
        mapOf("id" to branch.id.value.value, "doc" to Json.encode(mapOf("id" to branch.id.value.value, "name" to branch.name, "active" to branch.isActive))),
    )
}
