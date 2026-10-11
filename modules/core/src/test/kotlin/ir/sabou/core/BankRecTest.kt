package ir.sabou.core

import ir.sabou.kernel.Clock
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.JalaliCalendar
import ir.sabou.kernel.Money
import ir.sabou.kernel.Scope
import ir.sabou.ledger.StandardAccounts
import ir.sabou.persistence.JdbcSqlDatabase
import ir.sabou.platform.memory.InMemoryAnchorStore
import ir.sabou.treasury.MatchStatementLine
import ir.sabou.treasury.OpenTreasuryAccount
import ir.sabou.treasury.PaymentPurpose
import ir.sabou.treasury.ReceiptPurpose
import ir.sabou.treasury.RecordPayment
import ir.sabou.treasury.RecordReceipt
import ir.sabou.treasury.TreasuryKind
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A bank statement imported, matched with the books, its own lines recorded, the difference explained (ADR-0035). */
class BankRecTest {
    private val dir = Files.createTempDirectory("bankrec")
    private val db = JdbcSqlDatabase(DriverManager.getConnection("jdbc:sqlite:${dir.resolve("b.db")}"))
    private val core = SabouCore.open(db, InMemoryAnchorStore(), Clock { 1_790_000_000_000L })
    private fun d(day: Int) = JalaliCalendar.date(1405, 7, day)
    private fun id() = GlobalId.new()

    @AfterTest fun close() { db.close(); dir.toFile().deleteRecursively() }

    @Test fun statementIsMatchedExplainedAndCompleted() {
        core.bootstrap("شعبه یک", "مالک", "owner", "123456".toCharArray())
        val branch = Scope.Branch(core.overview.branches().single().id)
        val bank = core.treasury.openAccount(OpenTreasuryAccount(id(), branch, "بانک ملت", TreasuryKind.BANK)).resultId
        val terminal = core.treasury.openAccount(OpenTreasuryAccount(id(), branch, "کارت‌خوان", TreasuryKind.CARD_TERMINAL)).resultId
        core.treasury.receipt(RecordReceipt(id(), branch, terminal, ReceiptPurpose.OTHER_INCOME, Money.of(30_000_000), d(3), "فروش کارتی"))
        // Books: capital in on the 1st, rent out on the 2nd, a payment on the 3rd the bank has not seen yet.
        core.treasury.receipt(RecordReceipt(id(), branch, bank, ReceiptPurpose.OWNER_CAPITAL, Money.of(100_000_000), d(1), "آورده"))
        core.treasury.payment(RecordPayment(id(), branch, bank, PaymentPurpose.RENT, Money.of(20_000_000), d(2), "اجاره مهر"))
        core.treasury.payment(RecordPayment(id(), branch, bank, PaymentPurpose.OTHER_EXPENSE, Money.of(5_000_000), d(3), "تعمیرات"))
        // Statement: rent cleared a day late, a fee, and the terminal's settlement — closing 109,950,000.
        val csv = "تاریخ,شرح,شماره پیگیری,برداشت,واریز,مانده\n" +
            "1405/07/01,واریز نقدی,1,,\"100,000,000\",\"100,000,000\"\n" +
            "1405/07/03,حواله,2,\"20,000,000\",,\"80,000,000\"\n" +
            "1405/07/03,کارمزد,3,\"50,000\",,\"79,950,000\"\n" +
            "1405/07/04,تسویه شاپرک,4,,\"30,000,000\",\"109,950,000\"\n"
        val file = PosFile("mellat.csv", csv.toByteArray(Charsets.UTF_8))
        assertTrue(core.bankRec.preview(file).ready)
        val statement = core.bankRec.import(id(), bank, file).resultId
        assertEquals("INVALID_STATE:BANK_STATEMENT:ALREADY_IMPORTED", assertFailsWith<DomainException> { core.bankRec.import(id(), bank, file) }.error.code)

        val r = core.bankRec.reconciliation(statement)
        assertEquals(2, r.matched)
        assertEquals(listOf("کارمزد", "تسویه شاپرک"), r.statementOnly.map { it.description })
        assertEquals(listOf("تعمیرات"), r.bookOnly.map { it.description })
        assertEquals(75_000_000L, r.bookBalance)
        assertEquals(0L, r.unexplained)                  // 75,000,000 + 5,000,000 − 50,000 + 30,000,000 = 109,950,000

        // Record the two statement-only lines: they match themselves.
        core.bankRec.record(id(), statement, 2, StatementAction.Pay(PaymentPurpose.BANK_FEES))
        core.bankRec.record(id(), statement, 3, StatementAction.Transfer(terminal))
        val after = core.bankRec.reconciliation(statement)
        assertEquals(4, after.matched)
        assertEquals(emptyList(), after.statementOnly)
        assertEquals(0L, after.unexplained)
        assertEquals(50_000L, core.ledger.balance(StandardAccounts.BANK_CHARGES).rial)
        assertEquals(0L, core.overview.treasury().single { it.account.id == terminal }.balance)

        // A match can be undone and made by hand, never twice.
        val rent = core.bankStatementStore.byId(statement)!!.lines[1].movementId!!
        core.bankStatements.match(MatchStatementLine(id(), branch, statement, 1, null))
        assertEquals(listOf(rent), core.bankRec.candidates(statement, 1).map { it.movement.id })
        core.bankStatements.match(MatchStatementLine(id(), branch, statement, 1, rent))
        assertEquals("INVALID_STATE:TREASURY_MOVEMENT:ALREADY_MATCHED", assertFailsWith<DomainException> {
            core.bankStatements.match(MatchStatementLine(id(), branch, statement, 1, rent))
        }.error.code)
    }
}
