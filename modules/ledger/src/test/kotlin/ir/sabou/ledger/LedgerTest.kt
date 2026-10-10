package ir.sabou.ledger

import ir.sabou.kernel.BranchId
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.Clock
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Scope
import ir.sabou.ledger.memory.InMemoryAccountStore
import ir.sabou.ledger.memory.InMemoryJournalStore
import ir.sabou.ledger.memory.InMemoryPeriodStore
import ir.sabou.platform.Actor
import ir.sabou.platform.Command
import ir.sabou.platform.CommandBus
import ir.sabou.platform.ModuleId
import ir.sabou.platform.Permission
import ir.sabou.platform.Role
import ir.sabou.platform.memory.InMemoryAuditStore
import ir.sabou.platform.memory.InMemoryEventLog
import ir.sabou.platform.memory.InMemoryDocumentNumberStore
import ir.sabou.platform.memory.InMemoryIdempotencyStore
import ir.sabou.platform.memory.InMemoryUnitOfWork
import ir.sabou.platform.memory.MutableSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LedgerTest {
    private val branchA = BranchId(GlobalId.new())
    private val session = MutableSession(Actor(GlobalId.new(), "owner", Role.OWNER, emptySet()))
    private val uow = InMemoryUnitOfWork()
    private val accounts = InMemoryAccountStore(StandardAccounts.chart())
    private val journals = InMemoryJournalStore()
    private val periods = InMemoryPeriodStore()
    private val audit = InMemoryAuditStore()
    private val numbers = InMemoryDocumentNumberStore().also { uow.register(it) }
    private val bus = CommandBus(session, uow, InMemoryIdempotencyStore().also { uow.register(it) }, audit, InMemoryEventLog(), Clock { 1L }, numbers) { "e1" }
    private val registry = LedgerAccessRegistry()
    private val ledger = Ledger(registry, accounts, journals, periods)
    private val manualCap = registry.issue(ModuleId.LEDGER_MANUAL)
    private val inventoryCap = registry.issue(ModuleId.INVENTORY)
    private val manual = ManualAccounting(bus, ledger, manualCap, periods)
    private val day = BusinessDate(20_000)

    init { uow.register(journals, periods, audit) }

    private fun rial(v: Long) = Money.of(v)
    private fun code(block: () -> Unit) = assertFailsWith<DomainException> { block() }.error.code

    private fun manualPost(lines: List<ManualLine>, scope: Scope = Scope.Branch(branchA), date: BusinessDate = day) =
        manual.post(PostManualJournal(GlobalId.new(), scope, date, "اجاره", lines))

    @Test fun balancedManualJournalOnOpenAccountsPosts() {
        manualPost(listOf(ManualLine(StandardAccounts.RENT, rial(500), Money.ZERO), ManualLine(StandardAccounts.CAPITAL, Money.ZERO, rial(500))))
        assertEquals(500, ledger.balance(StandardAccounts.RENT, Scope.Branch(branchA)).rial)
    }

    @Test fun unbalancedJournalIsRejectedAndNothingIsWritten() {
        assertEquals("UNBALANCED_JOURNAL", code {
            manualPost(listOf(ManualLine(StandardAccounts.RENT, rial(500), Money.ZERO), ManualLine(StandardAccounts.CAPITAL, Money.ZERO, rial(499))))
        })
        assertTrue(journals.all().isEmpty())
        assertTrue(audit.events.isEmpty())
    }

    @Test fun manualJournalCannotTouchControlAccounts() {
        // AUD-010: cash, AP, AR, inventory... are owned by their modules.
        for (control in listOf(StandardAccounts.CASH, StandardAccounts.PAYABLE, StandardAccounts.RECEIVABLE, StandardAccounts.INVENTORY)) {
            assertEquals("CONTROL_ACCOUNT:${control.value}", code {
                manualPost(listOf(ManualLine(control, rial(1), Money.ZERO), ManualLine(StandardAccounts.CAPITAL, Money.ZERO, rial(1))))
            })
        }
    }

    @Test fun aModuleCannotPostAnotherModulesControlAccount() {
        assertEquals("CONTROL_ACCOUNT:2101", code {
            bus.execute(ModuleId.INVENTORY, Dummy()) { _, ctx ->
                ledger.post(ctx, inventoryCap, JournalDraft(day, Scope.Organization, "X", GlobalId.new(), "x", listOf(
                    LineDraft(StandardAccounts.INVENTORY, debit = rial(1), by = inventoryCap),
                    LineDraft(StandardAccounts.PAYABLE, credit = rial(1), by = inventoryCap),
                ))).id
            }
        })
    }

    @Test fun aModuleCannotPostUnderAnotherModulesIdentity() {
        assertTrue(code {
            bus.execute(ModuleId.SALES, Dummy()) { _, ctx ->
                ledger.post(ctx, inventoryCap, JournalDraft(day, Scope.Organization, "X", GlobalId.new(), "x", listOf(
                    LineDraft(StandardAccounts.COGS, debit = rial(1), by = inventoryCap),
                    LineDraft(StandardAccounts.INVENTORY, credit = rial(1), by = inventoryCap),
                ))).id
            }
        }.startsWith("OWNED_BY"))
    }

    @Test fun capabilitiesCannotBeIssuedTwice() {
        assertFailsWith<IllegalStateException> { registry.issue(ModuleId.LEDGER_MANUAL) }
    }

    @Test fun reversalRulesOwnerOnlyOnceAndNeverReversalOfReversal() {
        val id = manualPost(listOf(ManualLine(StandardAccounts.RENT, rial(500), Money.ZERO), ManualLine(StandardAccounts.CAPITAL, Money.ZERO, rial(500)))).resultId
        val entry = journals.all().single()
        val rev = manual.reverse(ReverseManualJournal(GlobalId.new(), Scope.Branch(branchA), entry.id, day, "اشتباه ثبت")).resultId
        assertEquals(0, ledger.balance(StandardAccounts.RENT).rial)
        assertEquals("INVALID_STATE:JOURNAL:ALREADY_REVERSED", code { manual.reverse(ReverseManualJournal(GlobalId.new(), Scope.Branch(branchA), entry.id, day, "دوباره")) })
        assertEquals("INVALID_STATE:JOURNAL:IS_REVERSAL", code { manual.reverse(ReverseManualJournal(GlobalId.new(), Scope.Branch(branchA), rev, day, "برگشتِ برگشت")) })
        // An inventory-owned journal cannot be reversed from manual accounting.
        val invJournal = bus.execute(ModuleId.INVENTORY, Dummy()) { _, ctx ->
            ledger.post(ctx, inventoryCap, JournalDraft(day, Scope.Organization, "WASTE", GlobalId.new(), "ضایعات", listOf(
                LineDraft(StandardAccounts.WASTE, debit = rial(9), by = inventoryCap),
                LineDraft(StandardAccounts.INVENTORY, credit = rial(9), by = inventoryCap),
            ))).id
        }.resultId
        assertTrue(code { manual.reverse(ReverseManualJournal(GlobalId.new(), Scope.Organization, invJournal, day, "تلاش")) }.startsWith("OWNED_BY"))
        assertTrue(id.value.isNotEmpty())
    }

    @Test fun closedPeriodBlocksPostingAndOnlyOwnerReopens() {
        val lock = manual.closePeriod(ClosePeriod(GlobalId.new(), BusinessDate(19_990), BusinessDate(20_010))).resultId
        assertEquals("PERIOD_CLOSED", code {
            manualPost(listOf(ManualLine(StandardAccounts.RENT, rial(5), Money.ZERO), ManualLine(StandardAccounts.CAPITAL, Money.ZERO, rial(5))))
        })
        session.actor = Actor(GlobalId.new(), "manager", Role.MANAGER, setOf(branchA))
        assertEquals("PERMISSION_DENIED:PERIOD_REOPEN", code { manual.reopenPeriod(ReopenPeriod(GlobalId.new(), lock, "اصلاح")) })
        // A branch manager cannot lock the whole organization's books either.
        assertEquals("PERMISSION_DENIED:PERIOD_CLOSE", code { manual.closePeriod(ClosePeriod(GlobalId.new(), BusinessDate(30_000), BusinessDate(30_010))) })
        session.actor = Actor(GlobalId.new(), "owner", Role.OWNER, emptySet())
        manual.reopenPeriod(ReopenPeriod(GlobalId.new(), lock, "اصلاح سند"))
        manualPost(listOf(ManualLine(StandardAccounts.RENT, rial(5), Money.ZERO), ManualLine(StandardAccounts.CAPITAL, Money.ZERO, rial(5))))
    }

    private class Dummy(override val commandId: GlobalId = GlobalId.new()) : Command {
        override val requiredPermission = Permission.LEDGER_VIEW
        override val scope: Scope = Scope.Organization
        override fun fingerprint() = "dummy"
    }
}
