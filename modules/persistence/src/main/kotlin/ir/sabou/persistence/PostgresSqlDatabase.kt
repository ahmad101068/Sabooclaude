package ir.sabou.persistence

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Types

/**
 * SqlDatabase over a JDBC connection to PostgreSQL, bound to one tenant's schema (ADR-0057, ADR-0060).
 *
 * The stores and migrations are written once, for SQLite (phones); this adapter runs them unchanged on PostgreSQL by
 * translating the few SQLite-specific constructs ([PostgresDialect]). Every table gets a `rowid` identity column so
 * that the stores' insertion order (`ORDER BY rowid`) means what it means on SQLite.
 *
 * [schema] is the tenant's schema; the connection's `search_path` is that schema only, so no statement of the core
 * can name another tenant's tables without qualifying them — and a tenant role ([role]) that owns only its schema
 * makes even a qualified name fail (ADR-0057). [writeLockKey]: commands of one tenant are serialized across
 * processes by a transaction advisory lock (ADR-0058).
 */
class PostgresSqlDatabase(
    private val connection: Connection,
    val schema: String,
    role: String? = null,
    private val writeLockKey: Long = schema.hashCode().toLong(),
) : ClosableSqlDatabase {
    private var lastChanges = 0L

    init {
        require(SCHEMA_NAME.matches(schema)) { "schema_name_invalid" }
        connection.autoCommit = true
        connection.createStatement().use { st ->
            role?.let { require(SCHEMA_NAME.matches(it)) { "role_name_invalid" }; st.execute("SET ROLE $it") }
            // A tenant's role has no right to create schemas (the platform creates them, ADR-0057); only a
            // missing schema is created here (development and tests run with a privileged user).
            val exists = st.executeQuery("SELECT 1 FROM pg_namespace WHERE nspname = '$schema'").use { it.next() }
            if (!exists) st.execute("CREATE SCHEMA $schema")
            st.execute("SET search_path TO $schema")
            st.execute(PostgresDialect.IMMUTABLE_FUNCTION)
        }
    }

    override val dialect: SqlDialect get() = SqlDialect.POSTGRES

    private fun bind(st: PreparedStatement, args: Array<out Any?>) = args.forEachIndexed { i, a ->
        when (a) {
            null -> st.setNull(i + 1, Types.NULL)
            is String -> st.setString(i + 1, a)
            is Long -> st.setLong(i + 1, a)
            is Int -> st.setLong(i + 1, a.toLong())
            is ByteArray -> st.setBytes(i + 1, a)
            else -> error("sql_arg_unsupported:${a::class.simpleName}")
        }
    }

    override fun execute(sql: String, vararg args: Any?) {
        val statements = PostgresDialect.translateStatement(sql) ?: run { lastChanges = 0; return }
        statements.forEach { s ->
            connection.prepareStatement(s).use { st ->
                bind(st, args)
                val hasResult = st.execute()
                lastChanges = if (hasResult) 0L else st.updateCount.toLong().coerceAtLeast(0)
            }
        }
    }

    override fun query(sql: String, vararg args: Any?): List<SqlRow> {
        PostgresDialect.answer(sql)?.let { return it }
        val translated = PostgresDialect.translateQuery(sql)
        return connection.prepareStatement(translated).use { st ->
            bind(st, args)
            st.executeQuery().use { rs ->
                val md = rs.metaData
                val rows = ArrayList<SqlRow>()
                while (rs.next()) {
                    val m = HashMap<String, Any?>(md.columnCount * 2)
                    for (c in 1..md.columnCount) {
                        m[md.getColumnLabel(c)] = when (val v = rs.getObject(c)) {
                            is Int -> v.toLong()
                            is Short -> v.toLong()
                            is java.math.BigDecimal -> v.longValueExact()      // SUM(bigint) is numeric in PostgreSQL
                            is Boolean -> if (v) 1L else 0L
                            else -> v
                        }
                    }
                    rows += SqlRow(m)
                }
                rows
            }
        }
    }

    override fun begin() {
        connection.autoCommit = false
        connection.createStatement().use { it.execute("SELECT pg_advisory_xact_lock($writeLockKey)") }
    }

    override fun commit() { connection.commit(); connection.autoCommit = true }
    override fun rollback() { connection.rollback(); connection.autoCommit = true }
    override fun changes(): Long = lastChanges
    override fun close() = connection.close()

    companion object {
        /** Lower-case identifiers only: schema and role names are interpolated into SQL. */
        val SCHEMA_NAME = Regex("[a-z][a-z0-9_]{0,62}")
    }
}

/**
 * SQLite → PostgreSQL for the statements this code base writes (ADR-0060). Deliberately narrow: anything it does
 * not know passes unchanged, and the whole test suite runs on PostgreSQL to prove the translation.
 */
object PostgresDialect {
    /** One trigger function for the immutable tables; the message is the trigger's argument («IMMUTABLE:table»). */
    const val IMMUTABLE_FUNCTION =
        "CREATE OR REPLACE FUNCTION sabou_immutable() RETURNS trigger LANGUAGE plpgsql AS \$\$ " +
            "BEGIN RAISE EXCEPTION '%', TG_ARGV[0]; END \$\$"

    private val immutableTrigger = Regex(
        """CREATE TRIGGER (\w+) BEFORE (UPDATE|DELETE) ON (\w+) BEGIN SELECT RAISE\(ABORT, '([^']+)'\); END""")
    private val createTable = Regex("""^\s*CREATE TABLE (IF NOT EXISTS )?(\w+)\s*\((.*)\)\s*$""", RegexOption.DOT_MATCHES_ALL)
    private val integerWord = Regex("""\bINTEGER\b""")

    /** The PostgreSQL statements for [sql], or null when it has no meaning there (PRAGMA settings). */
    fun translateStatement(sql: String): List<String>? {
        val s = sql.trim()
        if (s.startsWith("PRAGMA", ignoreCase = true)) return null
        immutableTrigger.matchEntire(s)?.let { m ->
            val (name, op, table, message) = m.destructured
            return listOf("CREATE TRIGGER $name BEFORE $op ON $table FOR EACH ROW EXECUTE FUNCTION sabou_immutable('$message')")
        }
        // The phone-sync triggers (SyncLog) are never created on PostgreSQL: the server has no LAN replica.
        require(!s.startsWith("CREATE TRIGGER", ignoreCase = true)) { "trigger_not_translated:${s.take(60)}" }
        createTable.matchEntire(s)?.let { m ->
            val (ifNot, name, body) = m.destructured
            val columns = types(body)
                .replace(Regex("""\bBIGINT PRIMARY KEY AUTOINCREMENT\b"""), "BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY")
            // SQLite's implicit insertion order, which the stores rely on (ORDER BY rowid).
            return listOf("CREATE TABLE $ifNot$name ($columns, rowid BIGINT GENERATED ALWAYS AS IDENTITY)")
        }
        if (s.startsWith("ALTER TABLE", ignoreCase = true)) return listOf(types(s))
        return listOf(translateQuery(s))
    }

    private fun types(sql: String) = sql.replace(integerWord, "BIGINT").replace(Regex("""\bBLOB\b"""), "BYTEA")

    /** Expressions that differ: `CAST(x AS INTEGER)` (32-bit in PostgreSQL). */
    fun translateQuery(sql: String): String = sql.replace(Regex("""\bAS INTEGER\b"""), "AS BIGINT")

    /** Catalogue questions answered for PostgreSQL in SQLite's shape. */
    fun answer(sql: String): List<SqlRow>? {
        val s = sql.trim()
        if (s.equals("PRAGMA foreign_keys", ignoreCase = true)) return listOf(SqlRow(mapOf("foreign_keys" to 1L)))   // always enforced
        return null
    }

    /** `SELECT name FROM sqlite_master WHERE type = 'table' …` and `PRAGMA table_info(t)` in PostgreSQL terms. */
    const val TABLES = "SELECT table_name AS name FROM information_schema.tables WHERE table_schema = current_schema() " +
        "AND table_type = 'BASE TABLE' ORDER BY table_name"

    fun columns(table: String) =
        "SELECT c.column_name AS name, CASE WHEN EXISTS (SELECT 1 FROM information_schema.key_column_usage k " +
            "JOIN information_schema.table_constraints t ON t.constraint_name = k.constraint_name AND t.table_schema = k.table_schema " +
            "WHERE t.constraint_type = 'PRIMARY KEY' AND k.table_schema = c.table_schema AND k.table_name = c.table_name " +
            "AND k.column_name = c.column_name) THEN 1 ELSE 0 END AS pk FROM information_schema.columns c " +
            "WHERE c.table_schema = current_schema() AND c.table_name = '$table' AND c.column_name <> 'rowid' ORDER BY c.ordinal_position"
}
