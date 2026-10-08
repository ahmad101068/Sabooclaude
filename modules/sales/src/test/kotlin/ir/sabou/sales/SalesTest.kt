package ir.sabou.sales

import ir.sabou.inventory.CreateItem
import ir.sabou.inventory.CreateLocation
import ir.sabou.inventory.DefineMenuItem
import ir.sabou.inventory.InventoryGateway
import ir.sabou.inventory.InventoryOperations
import ir.sabou.inventory.PublishRecipe
import ir.sabou.inventory.ReceiptLine
import ir.sabou.inventory.RecipeBook
import ir.sabou.inventory.RecipeLine
import ir.sabou.inventory.RecordOpeningStock
import ir.sabou.inventory.StockUnit
import ir.sabou.inventory.memory.InMemoryItemStore
import ir.sabou.inventory.memory.InMemoryLocationStore
import ir.sabou.inventory.memory.InMemoryRecipeStore
import ir.sabou.inventory.memory.InMemoryStockStore
import ir.sabou.kernel.BranchId
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.Clock
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Scope
import ir.sabou.ledger.Ledger
import ir.sabou.ledger.LedgerAccessRegistry
import ir.sabou.ledger.StandardAccounts
import ir.sabou.ledger.memory.InMemoryAccountStore
import ir.sabou.ledger.memory.InMemoryJournalStore
import ir.sabou.ledger.memory.InMemoryPeriodStore
import ir.sabou.platform.Actor
import ir.sabou.platform.CommandBus
import ir.sabou.platform.ModuleId
import ir.sabou.platform.Role
import ir.sabou.platform.memory.InMemoryAuditStore
import ir.sabou.platform.memory.InMemoryEventLog
import ir.sabou.platform.memory.InMemoryIdempotencyStore
import ir.sabou.platform.memory.InMemoryUnitOfWork
import ir.sabou.platform.memory.MutableSession
import ir.sabou.sales.memory.InMemoryCustomerStore
import ir.sabou.sales.memory.InMemorySalesStore
import ir.sabou.treasury.OpenTreasuryAccount
import ir.sabou.treasury.PaymentPurpose
import ir.sabou.treasury.RecordPayment
import ir.sabou.treasury.TreasuryGateway
import ir.sabou.treasury.TreasuryKind
import ir.sabou.treasury.TreasuryOperations
import ir.sabou.treasury.memory.InMemoryMovementStore
import ir.sabou.treasury.memory.InMemoryTreasuryAccountStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SalesTest {
    private val branch = Scope.Branch(BranchId(GlobalId.new()))
    private val owner = Actor(GlobalId.new(), "owner", Role.OWNER, emptySet())
    private val manager = Actor(GlobalId.new(), "manager", Role.MANAGER, setOf(branch.branchId))
    private val session = MutableSession(owner)
    private val uow = InMemoryUnitOfWork()
    private val journals = InMemoryJournalStore()
    private val items = InMemoryItemStore(); private val locations = InMemoryLocationStore()
    private val stock = InMemoryStockStore(); private val recipes = InMemoryRecipeStore()
    private val tAccounts = InMemoryTreasuryAccountStore(); private val movements = InMemoryMovementStore()
    private val customers = InMemoryCustomerStore(); private val salesStore = InMemorySalesStore()
    private val bus = CommandBus(session, uow, InMemoryIdempotencyStore().also { uow.register(it) },
        InMemoryAuditStore().also { uow.register(it) }, InMemoryEventLog(), Clock { 1L }) { "e1" }
    private val registry = LedgerAccessRegistry()
    private val ledger = Ledger(registry, InMemoryAccountStore(StandardAccounts.chart()), journals, InMemoryPeriodStore())
    private val inventory = InventoryGateway(ledger, registry.issue(ModuleId.INVENTORY), items, locations, stock)
    private val inventoryOps = InventoryOperations(bus, inventory, items, locations, recipes)
    private val treasuryCap = registry.issue(ModuleId.TREASURY)
    private val treasury = TreasuryGateway(ledger, treasuryCap, tAccounts, movements)
    private val treasuryOps = TreasuryOperations(bus, treasury, treasuryCap, tAccounts)
    private val ops = SalesOperations(bus, ledger, registry.issue(ModuleId.SALES), inventory, RecipeBook(recipes), treasury, customers, salesStore)
    private val day = BusinessDate(20_000)

    init { uow.register(journals, items, locations, stock, recipes, tAccounts, movements, customers, salesStore) }

    private fun code(block: () -> Unit) = assertFailsWith<DomainException> { block() }.error.code
    private fun rial(v: Long) = Money.of(v)
    private val dough = inventoryOps.createItem(CreateItem(GlobalId.new(), "خمیر", StockUnit.KILOGRAM, Quantity.units(1))).resultId
    private val kitchen = inventoryOps.createLocation(CreateLocation(GlobalId.new(), branch, "آشپزخانه")).resultId
    private val pizza = inventoryOps.defineMenuItem(DefineMenuItem(GlobalId.new(), "پیتزا")).resultId
    private val cash = treasuryOps.openAccount(OpenTreasuryAccount(GlobalId.new(), branch, "صندوق", TreasuryKind.CASH)).resultId
    private val card = treasuryOps.openAccount(OpenTreasuryAccount(GlobalId.new(), branch, "کارت‌خوان", TreasuryKind.CARD_TERMINAL)).resultId
    private val company = ops.registerCustomer(RegisterCustomer(GlobalId.new(), branch, "شرکت آرین", CustomerType.COMPANY, "021", rial(5_000_000))).resultId

    init {
        inventoryOps.publishRecipe(PublishRecipe(GlobalId.new(), pizza, BusinessDate(19_000), listOf(RecipeLine(dough, Quantity.of(250_000)))))
        inventoryOps.openingStock(RecordOpeningStock(GlobalId.new(), branch, kitchen, listOf(ReceiptLine(dough, Quantity.units(10), rial(1_000_000))), day))
    }

    /** 20 pizzas, 1,000,000 gross, 100,000 discount, 50,000 service, 90,000 tax = 1,040,000 payable. */
    private fun draft(settlements: List<Settlement>, portions: Long = 20, date: BusinessDate = day) =
        ops.saveDraft(SaveSaleDraft(GlobalId.new(), branch, date, kitchen, listOf(SaleLine(pizza, Quantity.units(portions), rial(1_000_000))),
            rial(100_000), rial(50_000), rial(90_000), settlements)).resultId

    private fun standardSettlements() = listOf(
        Settlement.Liquid(cash, rial(400_000)), Settlement.Liquid(card, rial(440_000)),
        Settlement.Credit(company, rial(200_000), day.plusDays(30)),
    )

    private fun post(id: GlobalId) = ops.post(PostDailySale(GlobalId.new(), branch, id))

    private fun receivableBooksMatch() =
        assertEquals(ops.customerBalance(company).rial, ledger.balance(StandardAccounts.RECEIVABLE, branch).rial)

    @Test fun postingTheDayMovesStockMoneyRevenueAndReceivablesTogether() {
        val sale = draft(standardSettlements())
        post(sale)
        assertEquals(900_000, -ledger.balance(StandardAccounts.FOOD_SALES, branch).rial)
        assertEquals(50_000, -ledger.balance(StandardAccounts.SERVICE_INCOME, branch).rial)
        assertEquals(90_000, -ledger.balance(StandardAccounts.SALES_TAX_PAYABLE, branch).rial)
        assertEquals(500_000, ledger.balance(StandardAccounts.COGS, branch).rial)          // 5 kg of 10 kg @ 1,000,000
        assertEquals(Quantity.units(5), inventory.balance(dough, kitchen).quantity)
        assertEquals(400_000, treasury.balance(cash))
        assertEquals(440_000, treasury.balance(card))
        assertEquals(0, ledger.balance(StandardAccounts.SALES_CLEARING).rial)
        assertEquals(200_000, ops.customerBalance(company).rial)
        receivableBooksMatch()
    }

    @Test fun unsettledDayCannotBePosted() {
        val sale = draft(listOf(Settlement.Liquid(cash, rial(1_000_000))))
        assertEquals("INVALID_STATE:DAILY_SALE:SETTLEMENT_MISMATCH:40000", code { post(sale) })
        assertEquals(0, treasury.balance(cash))
    }

    @Test fun ingredientShortageRollsBackTheWholeDay() {
        val sale = draft(listOf(Settlement.Liquid(cash, rial(1_040_000))), portions = 41)   // needs 10.25 kg
        assertTrue(code { post(sale) }.startsWith("INSUFFICIENT_STOCK"))
        assertEquals(0, treasury.balance(cash))
        assertEquals(0, ledger.balance(StandardAccounts.FOOD_SALES).rial)
        assertEquals(SaleStatus.DRAFT, salesStore.sale(sale)!!.status)
    }

    @Test fun creditLimitIsEnforcedUnlessTheOwnerOverrides() {
        session.actor = manager
        val sale = draft(listOf(Settlement.Liquid(cash, rial(40_000)), Settlement.Credit(company, rial(1_000_000), day)))
        ops.post(PostDailySale(GlobalId.new(), branch, sale))
        val next = draft(listOf(Settlement.Credit(company, rial(1_040_000), day.plusDays(1))), date = day.plusDays(1))
        // Already owes 1,000,000; limit 5,000,000 → still fine. Lower the limit to force the rule.
        customers.save(customers.byId(company)!!.copy(creditLimit = rial(1_500_000)))
        assertEquals("INVALID_STATE:CUSTOMER:CREDIT_LIMIT_EXCEEDED", code { ops.post(PostDailySale(GlobalId.new(), branch, next)) })
        session.actor = owner
        ops.post(PostDailySale(GlobalId.new(), branch, next))
        receivableBooksMatch()
    }

    @Test fun collectedSaleCannotBeReversedUntilTheCollectionIs() {
        val sale = draft(standardSettlements())
        post(sale)
        val receivable = salesStore.receivablesOfSale(sale).single().id
        val collection = ops.collect(CollectReceivable(GlobalId.new(), branch, receivable, cash, rial(200_000), day)).resultId
        assertEquals("INVALID_STATE:RECEIVABLE:AMOUNT_EXCEEDS_OUTSTANDING", code {
            ops.collect(CollectReceivable(GlobalId.new(), branch, receivable, cash, rial(1), day))
        })
        assertEquals("INVALID_STATE:DAILY_SALE:HAS_COLLECTIONS", code { ops.reverse(ReverseDailySale(GlobalId.new(), branch, sale, day, "ثبت اشتباه")) })
        ops.reverseCollection(ReverseCollection(GlobalId.new(), branch, collection, day, "وصول اشتباه"))
        ops.reverse(ReverseDailySale(GlobalId.new(), branch, sale, day, "ثبت اشتباه"))
        assertEquals(0, treasury.balance(cash)); assertEquals(0, treasury.balance(card))
        assertEquals(Quantity.units(10), inventory.balance(dough, kitchen).quantity)
        assertEquals(1_000_000, inventory.balance(dough, kitchen).value.rial)
        listOf(StandardAccounts.FOOD_SALES, StandardAccounts.COGS, StandardAccounts.RECEIVABLE, StandardAccounts.SALES_TAX_PAYABLE)
            .forEach { assertEquals(0, ledger.balance(it).rial, "$it") }
        receivableBooksMatch()
    }

    @Test fun reversingASaleWhoseCashWasSpentIsRefused() {
        val sale = draft(listOf(Settlement.Liquid(cash, rial(1_040_000))))
        post(sale)
        treasuryOps.payment(RecordPayment(GlobalId.new(), branch, cash, PaymentPurpose.RENT, rial(1_000_000), day, "اجاره"))
        assertTrue(code { ops.reverse(ReverseDailySale(GlobalId.new(), branch, sale, day, "اشتباه")) }.startsWith("INSUFFICIENT_FUNDS"))
        assertEquals(SaleStatus.POSTED, salesStore.sale(sale)!!.status)
    }

    @Test fun closedDayIsFrozenAndOnlyTheOwnerReopensIt() {
        session.actor = manager
        val sale = draft(standardSettlements())
        assertEquals("INVALID_STATE:DAILY_SALE:NOT_POSTED", code { ops.closeDay(CloseSalesDay(GlobalId.new(), branch, day, rial(400_000))) })
        ops.post(PostDailySale(GlobalId.new(), branch, sale))
        ops.closeDay(CloseSalesDay(GlobalId.new(), branch, day, rial(400_000)))
        assertEquals("INVALID_STATE:SALES_DAY:CLOSED", code { ops.reverse(ReverseDailySale(GlobalId.new(), branch, sale, day, "اشتباه")) })
        assertEquals("PERMISSION_DENIED:SALES_DAY_REOPEN", code { ops.reopenDay(ReopenSalesDay(GlobalId.new(), branch, day, "اصلاح")) })
        session.actor = owner
        ops.reopenDay(ReopenSalesDay(GlobalId.new(), branch, day, "اصلاح فروش"))
        ops.reverse(ReverseDailySale(GlobalId.new(), branch, sale, day, "اشتباه"))
    }

    @Test fun cashierRecordsButCannotPost() {
        session.actor = Actor(GlobalId.new(), "cashier", Role.CASHIER, setOf(branch.branchId))
        val sale = draft(standardSettlements())
        assertEquals("PERMISSION_DENIED:SALES_POST", code { post(sale) })
    }
}
