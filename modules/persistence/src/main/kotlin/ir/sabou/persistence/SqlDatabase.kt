package ir.sabou.persistence

import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.platform.UnitOfWork

/**
 * The smallest SQL surface the stores need. On Android it wraps SQLCipher's
 * SupportSQLiteDatabase; in JVM tests it wraps sqlite-jdbc. Arguments are String, Long, Int, ByteArray or null.
 */
interface SqlDatabase {
    fun execute(sql: String, vararg args: Any?)
    fun query(sql: String, vararg args: Any?): List<SqlRow>
    fun begin()
    fun commit()
    fun rollback()

    /** Rows changed by the last statement on this connection. */
    fun changes(): Long = query("SELECT changes() AS n").single().long("n")
}

class SqlRow(private val values: Map<String, Any?>) {
    fun str(column: String): String = values[column] as String
    fun strOrNull(column: String): String? = values[column] as String?
    fun long(column: String): Long = (values[column] as Number).toLong()
    fun longOrNull(column: String): Long? = (values[column] as Number?)?.toLong()
    fun bytes(column: String): ByteArray = values[column] as ByteArray
}

/**
 * One transaction per command; nested calls join it. Every write of the system passes through here, so this
 * is also the single barrier for replacing the database: [exclusive] waits for the transaction in progress and
 * keeps new ones out while it runs; [seal] closes the gate for good (the database is about to be replaced),
 * so no write can land in a file that is being copied or swapped.
 */
class SqlUnitOfWork(private val db: SqlDatabase) : UnitOfWork {
    private val depth = ThreadLocal.withInitial { 0 }
    @Volatile private var sealedReason: String? = null

    @Synchronized
    override fun <T> transaction(block: () -> T): T {
        if (depth.get() > 0) return block()
        requireOpen()
        db.begin()
        depth.set(1)
        try {
            val result = block()
            db.commit()
            return result
        } catch (error: Throwable) {
            db.rollback()
            throw error
        } finally {
            depth.set(0)
        }
    }

    /** Runs [block] with no other transaction in progress or starting; transactions inside [block] still work. */
    @Synchronized
    fun <T> exclusive(block: () -> T): T {
        requireOpen()
        return block()
    }

    /** From now on every transaction is refused. Waits for the one in progress (same monitor). */
    @Synchronized
    fun seal(reason: String) {
        sealedReason = reason
    }

    val isSealed: Boolean get() = sealedReason != null

    private fun requireOpen() {
        sealedReason?.let { throw DomainException(DomainError.InvalidState("DATABASE", "SEALED:$it")) }
    }
}

/**
 * Database identity. The epoch names this database's history: it is created once with the
 * database and changes only through a recorded rebase (restore or factory reset, ADR-0004).
 */
class DatabaseMeta(private val db: SqlDatabase) {
    fun get(key: String): String? = db.query("SELECT value FROM meta WHERE key = ?", key).firstOrNull()?.str("value")
    fun put(key: String, value: String) =
        db.execute("INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", key, value)

    /** The stored epoch; a new database takes [initial] (announced beforehand by a rebase anchor) or a random one. */
    fun epoch(initial: String? = null): String = get(EPOCH) ?: (initial ?: java.util.UUID.randomUUID().toString()).also { put(EPOCH, it) }

    companion object { const val EPOCH = "audit_epoch" }
}
