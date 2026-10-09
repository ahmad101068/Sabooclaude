package ir.sabou.persistence

import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PersistenceTest {
    private fun memoryDb() = JdbcSqlDatabase(DriverManager.getConnection("jdbc:sqlite::memory:"))

    @Test fun jsonRoundTripsPersianTextEscapesAndNesting() {
        val value = mapOf(
            "name" to "پیتزا \"مخصوص\"\n\t\\ ۱۲۳", "n" to -9_000_000_000_000_000L, "ok" to true, "none" to null,
            "list" to listOf(1L, mapOf("x" to "y"), emptyList<Any>()), "ctl" to "\u0001",
        )
        assertEquals(value, Json.decode(Json.encode(value)))
        assertFailsWith<IllegalArgumentException> { Json.decode("{\"a\":1} x") }
    }

    @Test fun migrationIsIdempotentAndRefusesANewerDatabase() {
        val db = memoryDb()
        Schema.migrate(db); Schema.migrate(db)
        assertEquals(Schema.latestVersion.toLong(), db.query("SELECT MAX(version) AS v FROM schema_version").single().long("v"))
        db.execute("INSERT INTO schema_version(version) VALUES (?)", Schema.latestVersion + 1)
        val e = assertFailsWith<IllegalStateException> { Schema.migrate(db) }
        assertTrue(e.message!!.startsWith("DATABASE_NEWER_THAN_APP"))
    }

    @Test fun postedRecordsAreImmutableAtTheDatabaseLevel() {
        val db = memoryDb(); Schema.migrate(db)
        db.execute("INSERT INTO accounts(code, doc) VALUES ('1101', '{}')")
        db.execute("INSERT INTO journal_entries(id, number, date, scope, source_module, source_type, source_id, doc) VALUES ('e', 1, 1, 'ORG', 'X', 'T', 's', '{}')")
        db.execute("INSERT INTO journal_lines(entry_id, line_no, account, scope, date, debit, credit, contributor, memo) VALUES ('e', 0, '1101', 'ORG', 1, 5, 0, 'X', '')")
        listOf(
            "UPDATE journal_lines SET debit = 6", "DELETE FROM journal_lines", "UPDATE journal_entries SET number = 2", "DELETE FROM journal_entries",
        ).forEach { sql -> assertTrue(assertFailsWith<Exception> { db.execute(sql) }.message!!.contains("IMMUTABLE")) }
        // A line must be one-sided and non-negative.
        assertFailsWith<Exception> { db.execute("INSERT INTO journal_lines VALUES ('e', 1, '1101', 'ORG', 1, 5, 5, 'X', '')") }
        assertFailsWith<Exception> { db.execute("INSERT INTO journal_lines VALUES ('e', 2, '1101', 'ORG', 1, -5, 0, 'X', '')") }
        // Unknown account is rejected by the foreign key.
        assertFailsWith<Exception> { db.execute("INSERT INTO journal_lines VALUES ('e', 3, '9999', 'ORG', 1, 5, 0, 'X', '')") }
    }

    @Test fun unitOfWorkRollsBackEverythingAndNestedCallsJoin() {
        val db = memoryDb(); Schema.migrate(db)
        val uow = SqlUnitOfWork(db)
        assertFailsWith<IllegalStateException> {
            uow.transaction {
                db.execute("INSERT INTO meta(key, value) VALUES ('a', '1')")
                uow.transaction { db.execute("INSERT INTO meta(key, value) VALUES ('b', '2')") }
                error("boom")
            }
        }
        assertEquals(0, db.query("SELECT COUNT(*) AS n FROM meta").single().long("n"))
        uow.transaction { db.execute("INSERT INTO meta(key, value) VALUES ('a', '1')") }
        assertEquals(1, db.query("SELECT COUNT(*) AS n FROM meta").single().long("n"))
    }

    @Test fun stockBalanceCannotGoNegativeEvenIfCodeTried() {
        val db = memoryDb(); Schema.migrate(db)
        db.execute("INSERT INTO items(id, doc) VALUES ('i', '{}')"); db.execute("INSERT INTO locations(id, doc) VALUES ('l', '{}')")
        assertFailsWith<Exception> { db.execute("INSERT INTO stock_balances VALUES ('i', 'l', -1, 0)") }
    }

    @Test fun employmentDatesPartialMonthsAndProrationSurviveTheDatabase() {
        val db = memoryDb(); Schema.migrate(db)
        val branch = ir.sabou.kernel.Scope.Branch(ir.sabou.kernel.BranchId(ir.sabou.kernel.GlobalId.new()))
        val people = SqlPersonnelStore(db)
        val e = ir.sabou.payroll.Employee(ir.sabou.kernel.GlobalId.new(), "گارسون", "0499370899", branch, ir.sabou.kernel.Money.of(1_000),
            isActive = false, startDate = ir.sabou.kernel.BusinessDate(20_010), endDate = ir.sabou.kernel.BusinessDate(20_020))
        people.saveEmployee(e)
        assertEquals(e, people.employee(e.id))
        val plain = e.copy(id = ir.sabou.kernel.GlobalId.new(), nationalId = "0084575948", isActive = true, startDate = null, endDate = null)
        people.saveEmployee(plain)
        assertEquals(plain, people.employee(plain.id))

        val policy = ir.sabou.payroll.StatutoryPolicy(
            version = "T", from = ir.sabou.kernel.BusinessDate(20_000), to = ir.sabou.kernel.BusinessDate(20_100), standardMonthlyMinutes = 11_520,
            overtimeMultiplierPercent = 140, employeeInsuranceBp = 700, employerInsuranceBp = 2_000, unemploymentInsuranceBp = 300,
            maxInsurableMonthly = ir.sabou.kernel.Money.of(1_000_000), insuranceTaxExemptNumerator = 2, insuranceTaxExemptDenominator = 7,
            taxBrackets = listOf(ir.sabou.payroll.TaxBracket(null, 0)), prorationDays = 31,
        )
        SqlPolicyStore(db).save(policy)
        assertEquals(listOf(policy), SqlPolicyStore(db).all())

        val runs = SqlPayrollStore(db)
        val slips = listOf(policy.calculate(e.id, ir.sabou.kernel.Money.of(31_000), 0, 0, payableDays = 10), policy.calculate(plain.id, ir.sabou.kernel.Money.of(1_000), 0, 0))
        val run = ir.sabou.payroll.PayrollRun(ir.sabou.kernel.GlobalId.new(), branch, ir.sabou.kernel.BusinessDate(20_000), ir.sabou.kernel.BusinessDate(20_029), "T",
            slips, ir.sabou.payroll.RunStatus.DRAFT, ir.sabou.kernel.GlobalId.new(), null, null)
        runs.saveRun(run)
        assertEquals(run, runs.run(run.id))
        assertEquals(10, runs.run(run.id)!!.payslips.first().payableDays)
    }
}
