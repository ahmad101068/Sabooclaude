package ir.sabou.persistence

import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager

/**
 * Opens a database for JVM tests and tools: SQLite at [path] by default, or — with the system property
 * `sabou.db=postgres` — a PostgreSQL schema derived from [path] on the server at `sabou.pg.url` (ADR-0060). The same
 * path always gives the same schema, so a test that closes and reopens its database sees its data again, as with a
 * file. The driver for the chosen database must be on the classpath; this code depends on neither.
 */
object DevDatabases {
    val onPostgres: Boolean get() = System.getProperty("sabou.db") == "postgres"

    fun open(path: Path): ClosableSqlDatabase = if (!onPostgres) {
        JdbcSqlDatabase(DriverManager.getConnection("jdbc:sqlite:$path"))
    } else {
        val url = System.getProperty("sabou.pg.url") ?: error("sabou.pg.url not set")
        PostgresSqlDatabase(DriverManager.getConnection(url), schemaFor(path.toAbsolutePath().toString()))
    }

    fun open(file: java.io.File): ClosableSqlDatabase = open(file.toPath())

    /** An in-memory SQLite database, or a fresh PostgreSQL schema. */
    fun memory(): ClosableSqlDatabase = if (!onPostgres) JdbcSqlDatabase(DriverManager.getConnection("jdbc:sqlite::memory:"))
        else open(Path.of("memory-" + java.util.UUID.randomUUID()))

    fun schemaFor(key: String): String =
        "t_" + MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
}
