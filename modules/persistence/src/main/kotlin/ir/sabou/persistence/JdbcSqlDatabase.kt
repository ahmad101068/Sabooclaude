package ir.sabou.persistence

import java.sql.Connection
import java.sql.PreparedStatement

/**
 * SqlDatabase over a JDBC connection to SQLite (sqlite-jdbc). Used by JVM tests and tools; the
 * Android app uses its own SQLCipher adapter with the same contract. One connection, one thread
 * at a time (the unit of work is synchronized).
 */
class JdbcSqlDatabase(private val connection: Connection) : SqlDatabase, AutoCloseable {
    private var lastChanges = 0L

    init {
        connection.autoCommit = true
        execute("PRAGMA foreign_keys = ON")
    }

    private fun bind(st: PreparedStatement, args: Array<out Any?>) = args.forEachIndexed { i, a ->
        when (a) {
            null -> st.setObject(i + 1, null)
            is String -> st.setString(i + 1, a)
            is Long -> st.setLong(i + 1, a)
            is Int -> st.setLong(i + 1, a.toLong())
            else -> error("sql_arg_unsupported:${a::class.simpleName}")
        }
    }

    override fun execute(sql: String, vararg args: Any?) {
        connection.prepareStatement(sql).use { st ->
            bind(st, args)
            val hasResult = st.execute()
            lastChanges = if (hasResult) 0L else st.updateCount.toLong().coerceAtLeast(0)
        }
    }

    override fun query(sql: String, vararg args: Any?): List<SqlRow> = connection.prepareStatement(sql).use { st ->
        bind(st, args)
        st.executeQuery().use { rs ->
            val md = rs.metaData
            val rows = ArrayList<SqlRow>()
            while (rs.next()) {
                val m = HashMap<String, Any?>(md.columnCount * 2)
                for (c in 1..md.columnCount) {
                    val v = rs.getObject(c)
                    m[md.getColumnLabel(c)] = if (v is Int) v.toLong() else v
                }
                rows += SqlRow(m)
            }
            rows
        }
    }

    override fun begin() { connection.autoCommit = false }
    override fun commit() { connection.commit(); connection.autoCommit = true }
    override fun rollback() { connection.rollback(); connection.autoCommit = true }
    override fun changes(): Long = lastChanges
    override fun close() = connection.close()
}
