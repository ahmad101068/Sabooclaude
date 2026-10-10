package ir.sabou.ledger

import ir.sabou.kernel.BranchId
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.Clock
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Scope
import ir.sabou.ledger.memory.InMemoryAccountStore
import ir.sabou.ledger.memory.InMemoryBudgetStore
import ir.sabou.platform.Actor
import ir.sabou.platform.CommandBus
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

class BudgetTest {
    private val branch = Scope.Branch(BranchId(GlobalId.new()))
    private val session = MutableSession(Actor(GlobalId.new(), "owner", Role.OWNER, emptySet()))
    private val uow = InMemoryUnitOfWork()
    private val budgets = InMemoryBudgetStore()
    private val numbers = InMemoryDocumentNumberStore().also { uow.register(it) }
    private val bus = CommandBus(session, uow, InMemoryIdempotencyStore().also { uow.register(it) },
        InMemoryAuditStore().also { uow.register(it) }, InMemoryEventLog(), Clock { 1L }, numbers) { "e1" }
    private val ops = BudgetOperations(bus, InMemoryAccountStore(StandardAccounts.chart()), budgets)
    private val d = BusinessDate(20_000)

    init { uow.register(budgets) }

    private fun code(block: () -> Unit) = assertFailsWith<DomainException> { block() }.error.code
    private fun months(vararg amounts: Long) = amounts.mapIndexed { i, a -> BudgetPeriod(d.plusDays(i * 30L), d.plusDays(i * 30L + 29), Money.of(a)) }

    @Test fun budgetsAreSetPerAccountAndPeriodAndReplacedInPlace() {
        ops.set(SetBudget(GlobalId.new(), branch, StandardAccounts.RENT, months(10, 20, 30)))
        ops.set(SetBudget(GlobalId.new(), branch, StandardAccounts.RENT, months(11)))
        assertEquals(listOf(11L, 20L, 30L), budgets.entries().sortedBy { it.from }.map { it.amount.rial })
        assertEquals("INVALID_INPUT:account", code { ops.set(SetBudget(GlobalId.new(), branch, StandardAccounts.CASH, months(1))) })
        assertEquals("INVALID_INPUT:periods", code {
            ops.set(SetBudget(GlobalId.new(), branch, StandardAccounts.RENT, listOf(BudgetPeriod(d.plusDays(10), d.plusDays(40), Money.of(1)))))
        })
        session.actor = Actor(GlobalId.new(), "m", Role.CASHIER, setOf(branch.branchId))
        assertEquals("PERMISSION_DENIED:BUDGET_MANAGE", code { ops.set(SetBudget(GlobalId.new(), branch, StandardAccounts.RENT, months(1))) })
    }
}
