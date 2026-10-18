package ir.sabou.persistence

/**
 * Row-level replication from the main phone to the second phone (ADR-0042).
 *
 * On every database, a trigger on each table logs the primary key of every inserted, updated or deleted row into
 * [CHANGES] (an ever-growing sequence). The main phone answers "what changed since N" with the CURRENT state of each
 * logged key (or "gone"), read in one transaction, so the batch is one consistent moment of its books. The second
 * phone applies such a batch in one transaction, with foreign keys checked at commit, and records how far it got in
 * [STATE] (never replicated). Rows are matched by primary key, never by rowid, so the two files need not agree on
 * physical layout; tables whose rows may never change (immutability triggers) only ever receive inserts.
 */
object SyncLog {
    const val CHANGES = "sync_changes"
    const val STATE = "sync_state"
    /** The schema version that introduced the log; migrations from this one on ensure the triggers. */
    const val SINCE_VERSION = 18
    const val CURSOR = "cursor"

    /** Tables that are never replicated: bookkeeping of the schema and of replication itself. */
    val EXCLUDED = setOf("schema_version", CHANGES, STATE, "sqlite_sequence")

    /** Separates the quoted key columns in [CHANGES].pk (a unit separator; quoted text may contain it). */
    private const val SEP = "char(31)"

    /** Replicated tables, by name. */
    fun tables(db: SqlDatabase): List<String> =
        db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
            .map { it.str("name") }.filter { it !in EXCLUDED }

    data class Column(val name: String, val pkOrder: Int)

    fun columns(db: SqlDatabase, table: String): List<Column> {
        require(table.matches(NAME)) { "bad table name" }
        return db.query("PRAGMA table_info($table)").map { Column(it.str("name"), it.long("pk").toInt()) }
    }

    fun keyColumns(columns: List<Column>): List<String> = columns.filter { it.pkOrder > 0 }.sortedBy { it.pkOrder }.map { it.name }

    /**
     * Creates the three logging triggers of every replicated table that does not have them yet. Phones only: a
     * server database (PostgreSQL) has no LAN second phone to replicate to (ADR-0060).
     */
    fun ensureTriggers(db: SqlDatabase) {
        if (db.dialect != SqlDialect.SQLITE) return
        tables(db).forEach { t ->
            val key = keyColumns(columns(db, t))
            check(key.isNotEmpty()) { "SYNC_TABLE_WITHOUT_PRIMARY_KEY:$t" }
            fun pk(row: String) = key.joinToString(" || $SEP || ") { "quote($row.$it)" }
            db.execute("CREATE TRIGGER IF NOT EXISTS ${t}_sync_i AFTER INSERT ON $t BEGIN INSERT INTO $CHANGES(tbl, pk) VALUES ('$t', ${pk("NEW")}); END")
            // An update may change the key: both the old and the new key are logged (the old one then reads as gone).
            db.execute(
                "CREATE TRIGGER IF NOT EXISTS ${t}_sync_u AFTER UPDATE ON $t BEGIN " +
                    "INSERT INTO $CHANGES(tbl, pk) SELECT '$t', ${pk("OLD")} WHERE ${pk("OLD")} IS NOT ${pk("NEW")}; " +
                    "INSERT INTO $CHANGES(tbl, pk) VALUES ('$t', ${pk("NEW")}); END",
            )
            db.execute("CREATE TRIGGER IF NOT EXISTS ${t}_sync_d AFTER DELETE ON $t BEGIN INSERT INTO $CHANGES(tbl, pk) VALUES ('$t', ${pk("OLD")}); END")
        }
    }

    private val NAME = Regex("[a-z_][a-z0-9_]*")

    // ------------------------------------------------------------------ SQL literals (the output of quote())

    /** Parses the values written by quote(...) || char(31) || quote(...): NULL, integers, 'text' and X'hex'. */
    fun parseKey(text: String): List<Any?> {
        val out = ArrayList<Any?>()
        var i = 0
        while (i <= text.length) {
            require(i < text.length) { "bad key" }
            when {
                text.startsWith("NULL", i) -> { out += null; i += 4 }
                text[i] == '\'' -> {
                    val sb = StringBuilder(); i++
                    while (true) {
                        require(i < text.length) { "bad key" }
                        val c = text[i]
                        if (c == '\'') {
                            if (i + 1 < text.length && text[i + 1] == '\'') { sb.append('\''); i += 2 } else { i++; break }
                        } else { sb.append(c); i++ }
                    }
                    out += sb.toString()
                }
                text[i] == 'X' && i + 1 < text.length && text[i + 1] == '\'' -> {
                    val end = text.indexOf('\'', i + 2)
                    require(end > 0) { "bad key" }
                    val hex = text.substring(i + 2, end)
                    require(hex.length % 2 == 0) { "bad key" }
                    out += ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
                    i = end + 1
                }
                else -> {
                    var j = i
                    if (j < text.length && text[j] == '-') j++
                    while (j < text.length && text[j].isDigit()) j++
                    require(j > i && (j < text.length && text[j] == '\u001F' || j == text.length)) { "bad key" }
                    out += text.substring(i, j).toLong()
                    i = j
                }
            }
            if (i == text.length) break
            require(text[i] == '\u001F') { "bad key" }
            i++
        }
        return out
    }

    // ------------------------------------------------------------------ main phone: the change feed

    /** One key's current state: [row] (column → value) or null when the row no longer exists. */
    data class RowChange(val table: String, val key: List<Any?>, val row: Map<String, Any?>?) : java.io.Serializable

    /** Changes after a cursor, up to [upTo]. [tooMany]: the second phone should take a full copy instead. */
    data class Batch(val changes: List<RowChange>, val upTo: Long, val tooMany: Boolean) : java.io.Serializable

    /** The last position of the log, even when every entry has been pruned. */
    fun head(db: SqlDatabase): Long = maxOf(
        db.query("SELECT COALESCE(MAX(seq), 0) AS s FROM $CHANGES").single().long("s"),
        state(db, PRUNED) ?: 0L,
    )

    private const val PRUNED = "pruned"

    private fun state(db: SqlDatabase, key: String): Long? =
        db.query("SELECT value FROM $STATE WHERE key = ?", key).firstOrNull()?.str("value")?.toLong()

    private fun setState(db: SqlDatabase, key: String, value: Long) =
        db.execute("INSERT INTO $STATE(key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", key, value.toString())

    /**
     * Everything that changed after [cursor]. Call inside a transaction (one consistent moment). Each key appears
     * once, at the position of its first change in this range, with its state now.
     */
    fun since(db: SqlDatabase, cursor: Long, limit: Int = 50_000): Batch {
        // A cursor from before what was pruned, or beyond this log (the main phone's books were replaced by a backup):
        // the gap cannot be filled from the log.
        if (cursor < (state(db, PRUNED) ?: 0L) || cursor > head(db)) return Batch(emptyList(), cursor, tooMany = true)
        val logged = db.query("SELECT seq, tbl, pk FROM $CHANGES WHERE seq > ? ORDER BY seq LIMIT ?", cursor, limit + 1L)
        if (logged.size > limit) return Batch(emptyList(), cursor, tooMany = true)
        if (logged.isEmpty()) return Batch(emptyList(), cursor, tooMany = false)
        val first = LinkedHashMap<Pair<String, String>, Unit>()
        logged.forEach { first.putIfAbsent(it.str("tbl") to it.str("pk"), Unit) }
        val cache = HashMap<String, List<Column>>()
        val changes = first.keys.map { (table, pk) ->
            val cols = cache.getOrPut(table) { columns(db, table) }
            val keyCols = keyColumns(cols)
            val key = parseKey(pk)
            check(key.size == keyCols.size) { "SYNC_KEY_SHAPE:$table" }
            val row = db.query("SELECT * FROM $table WHERE " + keyCols.joinToString(" AND ") { "$it = ?" }, *key.toTypedArray()).singleOrNull()
            RowChange(table, key, row?.let { r -> cols.associate { c -> c.name to r.raw(c.name) } })
        }
        return Batch(changes, logged.last().long("seq"), tooMany = false)
    }

    /** Forgets log entries the second phone already has (or all of them, when no second phone is paired). */
    fun prune(db: SqlDatabase, upTo: Long) {
        val target = minOf(upTo, head(db))
        if (target <= (state(db, PRUNED) ?: 0L)) return
        db.execute("DELETE FROM $CHANGES WHERE seq <= ?", target)
        setState(db, PRUNED, target)
    }

    // ------------------------------------------------------------------ second phone: applying

    fun cursor(db: SqlDatabase): Long = state(db, CURSOR) ?: 0L

    fun setCursor(db: SqlDatabase, value: Long) = setState(db, CURSOR, value)

    /**
     * Applies [batch] (call inside a transaction). Every table and column is checked against this phone's own
     * schema (both phones run the same version), and the result must satisfy every constraint at commit.
     */
    fun apply(db: SqlDatabase, batch: Batch) {
        require(!batch.tooMany) { "full copy needed" }
        db.execute("PRAGMA defer_foreign_keys = ON")
        val known = tables(db).toSet()
        val cache = HashMap<String, List<Column>>()
        batch.changes.forEach { c ->
            require(c.table in known) { "SYNC_UNKNOWN_TABLE:${c.table}" }
            val cols = cache.getOrPut(c.table) { columns(db, c.table) }
            val keyCols = keyColumns(cols)
            require(c.key.size == keyCols.size) { "SYNC_KEY_SHAPE:${c.table}" }
            val where = keyCols.joinToString(" AND ") { "$it = ?" }
            val existing = db.query("SELECT * FROM ${c.table} WHERE $where", *c.key.toTypedArray()).singleOrNull()
            val row = c.row
            if (row == null) {
                if (existing != null) db.execute("DELETE FROM ${c.table} WHERE $where", *c.key.toTypedArray())
                return@forEach
            }
            require(row.keys == cols.map { it.name }.toSet()) { "SYNC_COLUMNS:${c.table}" }
            if (existing == null) {
                val names = cols.map { it.name }
                db.execute("INSERT INTO ${c.table} (${names.joinToString()}) VALUES (${names.joinToString { "?" }})", *names.map { row[it] }.toTypedArray())
            } else {
                val changed = cols.filter { it.pkOrder == 0 && !same(existing.raw(it.name), row[it.name]) }
                if (changed.isNotEmpty()) {
                    db.execute(
                        "UPDATE ${c.table} SET ${changed.joinToString { "${it.name} = ?" }} WHERE $where",
                        *(changed.map { row[it.name] } + c.key).toTypedArray(),
                    )
                }
            }
        }
        // This phone's own triggers logged what was just applied; that log has no reader here.
        db.execute("DELETE FROM $CHANGES")
        setCursor(db, batch.upTo)
    }

    private fun same(a: Any?, b: Any?): Boolean = when {
        a is ByteArray && b is ByteArray -> a.contentEquals(b)
        a is Number && b is Number -> a.toLong() == b.toLong()
        else -> a == b
    }
}
