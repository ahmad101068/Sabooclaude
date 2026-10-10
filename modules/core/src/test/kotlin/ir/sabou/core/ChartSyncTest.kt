package ir.sabou.core

import ir.sabou.kernel.Clock
import ir.sabou.ledger.StandardAccounts
import ir.sabou.persistence.JdbcSqlDatabase
import ir.sabou.platform.ModuleId
import ir.sabou.platform.memory.InMemoryAnchorStore
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Posting rights of system accounts follow the code's chart, also in a database created by an older version. */
class ChartSyncTest {
    private val dir = Files.createTempDirectory("chart")
    private val file = dir.resolve("c.db")
    private val open = mutableListOf<JdbcSqlDatabase>()
    private val anchors = InMemoryAnchorStore()

    private fun boot() = SabouCore.open(JdbcSqlDatabase(DriverManager.getConnection("jdbc:sqlite:$file")).also { open += it }, anchors, Clock { 1_790_000_000_000L })

    @AfterTest fun close() { open.forEach { it.close() }; dir.toFile().deleteRecursively() }

    @Test fun anOlderDatabaseGetsTheCurrentPostingRights() {
        val core = boot()
        core.bootstrap("شعبه یک", "مالک", "owner", "123456".toCharArray())
        // As an older version stored it: only treasury may post cash over/short, and the owner renamed it.
        val code = StandardAccounts.CASH_OVER_SHORT.value
        val doc = core.db.query("SELECT doc FROM accounts WHERE code = ?", code).single().str("doc")
            .replace("\"SALES\",", "").replace(",\"SALES\"", "").replace("کسر و اضافه صندوق", "کسری صندوق")
        core.db.execute("UPDATE accounts SET doc = ? WHERE code = ?", doc, code)
        assertEquals(setOf(ModuleId.TREASURY), core.accounts.byCode(StandardAccounts.CASH_OVER_SHORT)!!.postingModules)

        open.forEach { it.close() }; open.clear()
        val account = boot().accounts.byCode(StandardAccounts.CASH_OVER_SHORT)!!
        assertEquals(setOf(ModuleId.TREASURY, ModuleId.SALES), account.postingModules)
        assertEquals("کسری صندوق", account.name)                       // what the owner kept stays
    }
}
