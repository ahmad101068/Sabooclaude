package ir.sabou.core

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.Clock
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Scope
import ir.sabou.ledger.ManualLine
import ir.sabou.ledger.PostManualJournal
import ir.sabou.ledger.StandardAccounts
import ir.sabou.persistence.DevDatabases
import ir.sabou.persistence.PostgresSqlDatabase
import ir.sabou.platform.memory.InMemoryAnchorStore
import ir.sabou.platform.StartupVerdict
import java.sql.DriverManager
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-0057 / ADR-0058 on PostgreSQL: each tenant is a schema owned by its own role, and its core runs as that role.
 * Handoff acceptance SEC-001 (another tenant's records by id), SEC-002 (no bypass from the connection) and SYNC-001
 * (one command replayed concurrently posts once). Skipped on SQLite: a phone holds one tenant.
 */
class TenantIsolationTest {
    private val url = System.getProperty("sabou.pg.url")
    private val run = GlobalId.new().value.take(8)
    private val open = mutableListOf<AutoCloseable>()
    private val day = BusinessDate(20_000)
    private fun id() = GlobalId.new()

    @AfterTest fun close() { open.reversed().forEach { runCatching { it.close() } } }

    private fun admin() = DriverManager.getConnection(url).also { open += it }

    /**
     * Creates tenant [name] as the platform would (ADR-0057): a login role and a schema it owns, nothing else. The
     * tenant's core logs in as that role — not as a privileged user that switches role, which `RESET ROLE` undoes.
     */
    private fun tenant(name: String): String {
        val schema = "t_${name}_$run"
        admin().createStatement().use { st ->
            st.execute("CREATE ROLE r_$schema LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS")
            st.execute("CREATE SCHEMA $schema AUTHORIZATION r_$schema")
        }
        return schema
    }

    private fun tenantUrl(schema: String) = url.replace(Regex("user=[^&]*"), "user=r_$schema")

    /** A core of [schema], logged in as the tenant's own role. */
    private fun core(schema: String): Pair<SabouCore, PostgresSqlDatabase> {
        val db = PostgresSqlDatabase(DriverManager.getConnection(tenantUrl(schema)), schema).also { open += it }
        return SabouCore.open(db, InMemoryAnchorStore(), Clock { 1_700_000_000_000L }, emptyList(), null) to db
    }

    private fun journal(core: SabouCore, amount: Long, key: GlobalId = id()) = core.accounting.post(PostManualJournal(key,
        Scope.Branch(core.overview.branches().single().id), day, "تعمیر",
        listOf(ManualLine(StandardAccounts.REPAIRS, Money.of(amount), Money.ZERO), ManualLine(StandardAccounts.LOANS_PAYABLE, Money.ZERO, Money.of(amount)))))

    private fun refused(db: PostgresSqlDatabase, sql: String): String =
        runCatching { db.query(sql) }.exceptionOrNull()?.message ?: error("not refused: $sql")

    /** CI's `postgres` job asks for PostgreSQL through the environment: the suite must not quietly run on SQLite. */
    @Test fun theRequestedDatabaseIsTheOneInUse() {
        if (System.getenv("SABOU_DB") == "postgres") {
            assertTrue(DevDatabases.onPostgres, "SABOU_DB=postgres but the tests run on SQLite")
            DevDatabases.memory().use { assertEquals(ir.sabou.persistence.SqlDialect.POSTGRES, it.dialect) }
        }
    }

    @Test fun aTenantSeesAndReachesNothingOfAnother() {
        org.junit.Assume.assumeTrue("PostgreSQL only", DevDatabases.onPostgres)
        val a = tenant("a"); val b = tenant("b")
        val (coreA, dbA) = core(a); val (coreB, dbB) = core(b)
        coreA.bootstrap("رستوران الف", "مالک الف", "owner", "123456".toCharArray())
        coreB.bootstrap("رستوران ب", "مالک ب", "owner", "654321".toCharArray())
        journal(coreA, 300_000)
        val entryB = journal(coreB, 900_000).resultId

        // Each core sees its own books only, through every read.
        assertEquals(listOf("رستوران الف"), coreA.overview.branches().map { it.name })
        assertEquals(300_000, coreA.ledger.balance(StandardAccounts.REPAIRS).rial)
        assertEquals(900_000, coreB.ledger.balance(StandardAccounts.REPAIRS).rial)
        // B's document id means nothing in A (SEC-001): not found, nothing disclosed.
        assertEquals(null, coreA.journals.byId(entryB))
        assertEquals(1, coreA.journals.all().size)

        // The connection itself cannot reach B's schema, read or write, even fully qualified (SEC-002).
        assertTrue(refused(dbA, "SELECT * FROM $b.journal_entries").contains("permission denied"))
        assertTrue(runCatching { dbA.execute("DELETE FROM $b.journal_lines") }.exceptionOrNull()!!.message!!.contains("permission denied"))
        assertTrue(refused(dbB, "SELECT * FROM $a.users").contains("permission denied"))
        // Nor can it step out of its role: there is nothing to reset to, and no other role to take.
        dbA.execute("RESET ROLE")
        assertEquals("r_$a", dbA.query("SELECT current_user AS u").single().str("u"))
        assertTrue(refused(dbA, "SELECT * FROM $b.journal_entries").contains("permission denied"))
        assertTrue(runCatching { dbA.execute("SET ROLE r_$b") }.exceptionOrNull()!!.message!!.contains("permission denied"))
        assertEquals(1, coreB.journals.all().size)
    }

    @Test fun postedDocumentsAreImmutableInTheDatabase() {
        org.junit.Assume.assumeTrue("PostgreSQL only", DevDatabases.onPostgres)
        val (core, db) = core(tenant("c"))
        core.bootstrap("رستوران ج", "مالک", "owner", "123456".toCharArray())
        journal(core, 300_000)
        val update = runCatching { db.execute("UPDATE journal_lines SET debit = debit + 1") }.exceptionOrNull()?.message.orEmpty()
        assertTrue(update.contains("IMMUTABLE:journal_lines"), update)
        val delete = runCatching { db.execute("DELETE FROM journal_entries") }.exceptionOrNull()?.message.orEmpty()
        assertTrue(delete.contains("IMMUTABLE:journal_entries"), delete)
        assertEquals(300_000, core.ledger.balance(StandardAccounts.REPAIRS).rial)
    }

    /**
     * ADR-0058: two server processes of one tenant (two cores, two connections) posting at once keep the books
     * exactly as if one after the other: every document numbered once, in order, balances exact; and one command
     * replayed concurrently from both posts once (SYNC-001).
     */
    @Test fun writesOfOneTenantAreSerializedAcrossProcesses() {
        org.junit.Assume.assumeTrue("PostgreSQL only", DevDatabases.onPostgres)
        val schema = tenant("d")
        val (first, _) = core(schema)
        first.bootstrap("رستوران د", "مالک", "owner", "123456".toCharArray())
        val (second, _) = core(schema)
        second.identity.login("owner", "123456".toCharArray())
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = listOf(first, second).map { c -> pool.submit(Callable { (1..25).map { journal(c, 1_000) } }) }.flatMap { it.get() }
            assertEquals(50, results.map { it.resultId }.distinct().size)
            assertEquals(50_000, first.ledger.balance(StandardAccounts.REPAIRS).rial)
            val numbers = first.journals.all().map { it.number }.sorted()
            assertEquals((1L..50L).toList(), numbers)

            val key = id()
            val replays = (1..20).map { i -> pool.submit(Callable { journal(if (i % 2 == 0) first else second, 7_000, key) }) }.map { it.get() }
            assertEquals(1, replays.map { it.resultId }.distinct().size)
            assertEquals(1, replays.count { !it.replayed })
            assertEquals(57_000, second.ledger.balance(StandardAccounts.REPAIRS).rial)
            // The audit chain written by both processes is one unbroken chain.
            val (fresh, _) = core(schema)
            fresh.identity.login("owner", "123456".toCharArray())
            assertEquals(StartupVerdict.Healthy, fresh.verifyStartup())
        } finally {
            pool.shutdown()
        }
    }
}
