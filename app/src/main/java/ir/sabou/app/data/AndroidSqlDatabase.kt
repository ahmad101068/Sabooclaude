package ir.sabou.app.data

import android.database.Cursor
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import ir.sabou.persistence.SqlDatabase
import ir.sabou.persistence.SqlRow

/**
 * The core's SqlDatabase port over SQLCipher's SupportSQLiteDatabase. Same contract as the JVM
 * JdbcSqlDatabase that the core's end-to-end tests run against.
 */
class AndroidSqlDatabase(private val db: SupportSQLiteDatabase) : SqlDatabase {
    private var lastChanges = 0L

    override fun execute(sql: String, vararg args: Any?) {
        if (sql.trimStart().startsWith("PRAGMA", ignoreCase = true)) {
            db.query(SimpleSQLiteQuery(sql, normalized(args))).use { it.moveToFirst() }
            lastChanges = 0
            return
        }
        db.compileStatement(sql).use { st ->
            normalized(args).forEachIndexed { i, a ->
                when (a) {
                    null -> st.bindNull(i + 1)
                    is String -> st.bindString(i + 1, a)
                    is Long -> st.bindLong(i + 1, a)
                    is ByteArray -> st.bindBlob(i + 1, a)
                    else -> error("sql_arg_unsupported")
                }
            }
            lastChanges = st.executeUpdateDelete().toLong()
        }
    }

    override fun query(sql: String, vararg args: Any?): List<SqlRow> =
        db.query(SimpleSQLiteQuery(sql, normalized(args))).use { c ->
            val rows = ArrayList<SqlRow>(c.count.coerceAtLeast(0))
            while (c.moveToNext()) {
                val m = HashMap<String, Any?>(c.columnCount * 2)
                for (i in 0 until c.columnCount) {
                    m[c.getColumnName(i)] = when (c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                        Cursor.FIELD_TYPE_STRING -> c.getString(i)
                        Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
                        else -> error("sql_column_type_unsupported:${c.getColumnName(i)}")
                    }
                }
                rows += SqlRow(m)
            }
            rows
        }

    override fun begin() = db.beginTransaction()

    override fun commit() {
        db.setTransactionSuccessful()
        db.endTransaction()
    }

    override fun rollback() = db.endTransaction()

    override fun changes(): Long = lastChanges

    private fun normalized(args: Array<out Any?>): Array<Any?> =
        Array(args.size) { i -> args[i].let { if (it is Int) it.toLong() else it } }
}
