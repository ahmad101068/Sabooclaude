package ir.sabou.core

import ir.sabou.inventory.Item
import ir.sabou.inventory.Location
import ir.sabou.inventory.MenuItem
import ir.sabou.inventory.RecipeVersion
import ir.sabou.inventory.StockBalance
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Scope
import ir.sabou.ledger.Account
import ir.sabou.payroll.AttendanceRecord
import ir.sabou.payroll.Employee
import ir.sabou.payroll.LiabilityKind
import ir.sabou.payroll.PayrollRun
import ir.sabou.payroll.RunStatus
import ir.sabou.payroll.StatutoryPolicy
import ir.sabou.platform.Actor
import ir.sabou.platform.Branch
import ir.sabou.platform.Permission
import ir.sabou.platform.User
import ir.sabou.purchasing.InvoiceStatus
import ir.sabou.purchasing.PurchaseInvoice
import ir.sabou.purchasing.PurchaseReturn
import ir.sabou.purchasing.Supplier
import ir.sabou.purchasing.SupplierPayment
import ir.sabou.sales.Customer
import ir.sabou.sales.DailySale
import ir.sabou.sales.Receivable
import ir.sabou.sales.SalesDay
import ir.sabou.treasury.TreasuryAccount
import ir.sabou.treasury.TreasuryMovement

data class AccountBalance(val account: TreasuryAccount, val balance: Long)
data class LowStock(val item: Item, val location: Location, val quantity: Quantity)
data class BranchDay(val branch: Branch, val sale: DailySale?, val closed: Boolean)
data class MenuEntry(val item: MenuItem, val latest: RecipeVersion?)
data class SalesDayView(val sale: DailySale?, val day: SalesDay?, val customers: List<Customer>, val accounts: List<TreasuryAccount>)
data class CustomerBalance(val customer: Customer, val owed: Money)
data class OpenReceivable(val receivable: Receivable, val customer: String, val outstanding: Money)
data class InvoiceRow(val invoice: PurchaseInvoice, val supplier: String, val outstanding: Money)
data class InvoiceView(val invoice: PurchaseInvoice, val supplier: String, val outstanding: Money, val payments: List<SupplierPayment>, val returns: List<PurchaseReturn>)
data class SupplierBalance(val supplier: Supplier, val owed: Money)
data class PayrollView(
    val runs: List<PayrollRun>,
    val names: Map<GlobalId, String>,
    val unpaid: Map<GlobalId, Map<GlobalId, Money>>,
    val insurance: Money,
    val tax: Money,
)
data class SetupStatus(val hasAccounts: Boolean, val hasItems: Boolean, val hasMenu: Boolean)

/**
 * The only read path for screens. Every method checks the signed-in actor, the permission and the
 * branch scope — the same rules as commands (AUD-004, AUD-012). The raw stores are internal to the
 * core module, so the app cannot bypass this. Nothing here writes.
 */
class Overview internal constructor(private val core: SabouCore) {
    private fun actor(): Actor = core.session.currentActor() ?: throw DomainException(DomainError.AuthenticationRequired)

    /** The actor if they hold at least one of [anyOf]. */
    private fun actor(vararg anyOf: Permission): Actor {
        val a = actor()
        if (anyOf.none { a.role.allows(it) }) throw DomainException(DomainError.PermissionDenied(anyOf.first().name))
        return a
    }

    private fun Actor.require(scope: Scope) {
        if (!canAccess(scope)) throw DomainException(DomainError.ScopeDenied(ir.sabou.platform.CommandContext.scopeLabel(scope)))
    }

    private fun location(id: GlobalId): Location = core.locations.byId(id) ?: throw DomainException(DomainError.NotFound("LOCATION"))

    // ------------------------------------------------------------ Identity and setup

    fun me(): Actor = actor()

    fun branches(): List<Branch> = core.identity.accessibleBranches(actor())

    fun users(): List<User> = core.identity.listUsers()

    fun allBranches(): List<Branch> {
        actor(Permission.USER_MANAGE)
        return core.branches.all()
    }

    fun setupStatus(): SetupStatus {
        actor()
        return SetupStatus(core.treasuryAccounts.all().isNotEmpty(), core.items.all().isNotEmpty(), core.recipes.menuItems().isNotEmpty())
    }

    // ------------------------------------------------------------ Treasury

    fun treasury(): List<AccountBalance> {
        val a = actor(Permission.TREASURY_VIEW)
        return core.treasuryAccounts.all().filter { it.isActive && a.canAccess(it.scope) }
            .map { AccountBalance(it, core.treasuryGateway.balance(it.id)) }
    }

    /** Names of the accounts an operation may use (no balances): for sales settlement, payments, collections. */
    fun paymentAccounts(scope: Scope? = null): List<TreasuryAccount> {
        val a = actor(
            Permission.TREASURY_VIEW, Permission.SALES_RECORD, Permission.PURCHASE_PAY, Permission.PAYROLL_PAY, Permission.RECEIVABLE_COLLECT,
        )
        return core.treasuryAccounts.all().filter { it.isActive && a.canAccess(it.scope) && (scope == null || it.scope == scope) }
    }

    fun recentMovements(limit: Int = 20): List<TreasuryMovement> {
        val a = actor(Permission.TREASURY_VIEW)
        return core.treasuryAccounts.all().filter { a.canAccess(it.scope) }
            .flatMap { core.treasuryMovements.byAccount(it.id, limit) }
            .sortedByDescending { it.recordedAtEpochMillis }.take(limit)
    }

    fun accountHistory(accountId: GlobalId, limit: Int = 200): Pair<AccountBalance, List<TreasuryMovement>> {
        val a = actor(Permission.TREASURY_VIEW)
        val account = core.treasuryAccounts.byId(accountId) ?: throw DomainException(DomainError.NotFound("TREASURY_ACCOUNT"))
        a.require(account.scope)
        return AccountBalance(account, core.treasuryGateway.balance(account.id)) to core.treasuryMovements.byAccount(account.id, limit)
    }

    // ------------------------------------------------------------ Catalog (shared reference data)

    fun items(): List<Item> {
        actor(Permission.INVENTORY_VIEW, Permission.PURCHASE_VIEW, Permission.RECIPE_MANAGE, Permission.INVENTORY_ITEM_MANAGE, Permission.SALES_VIEW)
        return core.items.all()
    }

    fun locations(branch: Scope.Branch): List<Location> {
        val a = actor(
            Permission.INVENTORY_VIEW, Permission.PURCHASE_RECORD, Permission.SALES_RECORD, Permission.INVENTORY_LOCATION_MANAGE,
        )
        a.require(branch)
        return core.locations.all().filter { it.scope == branch }
    }

    /** Locations the actor may move stock into (any granted branch). */
    fun transferTargets(): List<Location> {
        val a = actor(Permission.INVENTORY_TRANSFER)
        return core.locations.all().filter { it.isActive && a.canAccess(it.scope) }
    }

    fun menu(): List<MenuEntry> {
        actor(Permission.SALES_VIEW, Permission.RECIPE_MANAGE, Permission.INVENTORY_VIEW)
        return core.recipes.menuItems().map { m -> MenuEntry(m, core.recipes.versions(m.id).maxByOrNull { it.version }) }
    }

    // ------------------------------------------------------------ Inventory

    fun stock(locationId: GlobalId): List<StockBalance> {
        val a = actor(Permission.INVENTORY_VIEW, Permission.INVENTORY_COUNT, Permission.INVENTORY_OPENING)
        a.require(location(locationId).scope)
        return core.stock.balances(locationId)
    }

    fun lowStock(): List<LowStock> {
        val a = actor(Permission.INVENTORY_VIEW)
        val items = core.items.all().associateBy { it.id }
        return core.locations.all().filter { it.isActive && a.canAccess(it.scope) }.flatMap { location ->
            val stocked = core.stock.balances(location.id).associateBy { it.itemId }
            items.values.filter { it.isActive && !it.minimumStock.isZero }.mapNotNull { item ->
                val q = stocked[item.id]?.quantity ?: Quantity.ZERO
                if (q < item.minimumStock) LowStock(item, location, q) else null
            }
        }
    }

    // ------------------------------------------------------------ Sales

    fun today(date: BusinessDate): List<BranchDay> {
        actor(Permission.SALES_VIEW)
        return branches().map { b ->
            val scope = Scope.Branch(b.id)
            BranchDay(b, core.sales.activeSale(scope, date), core.sales.day(scope, date)?.closed == true)
        }
    }

    fun salesDay(branch: Scope.Branch, date: BusinessDate): SalesDayView {
        val a = actor(Permission.SALES_VIEW)
        a.require(branch)
        return SalesDayView(
            sale = core.sales.activeSale(branch, date),
            day = core.sales.day(branch, date),
            customers = core.customers.all().filter { it.isActive && it.registeredIn == branch },
            accounts = core.treasuryAccounts.all().filter { it.isActive && it.scope == branch },
        )
    }

    fun customers(branch: Scope.Branch): List<CustomerBalance> {
        val a = actor(Permission.SALES_VIEW, Permission.CUSTOMER_MANAGE)
        a.require(branch)
        return core.customers.all().filter { it.registeredIn == branch }.map { CustomerBalance(it, core.salesOps.customerBalance(it.id)) }
    }

    fun openReceivables(): List<OpenReceivable> {
        val a = actor(Permission.SALES_VIEW)
        return core.customers.all().filter { a.canAccess(it.registeredIn) }.flatMap { c ->
            core.sales.receivablesOfCustomer(c.id).filter { !it.voided && a.canAccess(it.scope) }
                .map { OpenReceivable(it, c.name, core.salesOps.outstanding(it.id)) }
        }.filter { !it.outstanding.isZero }.sortedBy { it.receivable.dueDate }
    }

    fun receivable(id: GlobalId): OpenReceivable {
        val a = actor(Permission.SALES_VIEW)
        val r = core.sales.receivable(id) ?: throw DomainException(DomainError.NotFound("RECEIVABLE"))
        a.require(r.scope)
        return OpenReceivable(r, core.customers.byId(r.customerId)?.name.orEmpty(), core.salesOps.outstanding(r.id))
    }

    /** What customers of the visible branches owe (equals GL 1201 for those branches). */
    fun receivables(): Money = Money.sum(openReceivables().map { it.outstanding })

    // ------------------------------------------------------------ Purchasing

    fun invoices(): List<InvoiceRow> {
        val a = actor(Permission.PURCHASE_VIEW)
        val names = core.suppliers.all().associate { it.id to it.name }
        return core.purchases.invoices().filter { a.canAccess(it.scope) }.sortedByDescending { it.date }
            .map { InvoiceRow(it, names[it.supplierId].orEmpty(), core.purchasing.outstanding(it.id)) }
    }

    fun invoice(id: GlobalId): InvoiceView {
        val a = actor(Permission.PURCHASE_VIEW)
        val inv = core.purchases.invoice(id) ?: throw DomainException(DomainError.NotFound("PURCHASE_INVOICE"))
        a.require(inv.scope)
        return InvoiceView(
            inv, core.suppliers.byId(inv.supplierId)?.name.orEmpty(), core.purchasing.outstanding(inv.id),
            core.purchases.payments(inv.id), core.purchases.returns(inv.id),
        )
    }

    /** Suppliers with what the visible branches owe them (never other branches' debts). */
    fun suppliers(): List<SupplierBalance> {
        val a = actor(Permission.PURCHASE_VIEW, Permission.SUPPLIER_MANAGE)
        val owed = core.purchases.invoices().filter { it.status == InvoiceStatus.POSTED && a.canAccess(it.scope) }
            .groupBy { it.supplierId }.mapValues { (_, l) -> Money.sum(l.map { core.purchasing.outstanding(it.id) }) }
        return core.suppliers.all().map { SupplierBalance(it, owed[it.id] ?: Money.ZERO) }
    }

    /** What the visible branches owe suppliers (equals GL 2101 for those branches). */
    fun payables(): Money = Money.sum(invoices().filter { it.invoice.status == InvoiceStatus.POSTED }.map { it.outstanding })

    // ------------------------------------------------------------ Personnel and payroll

    fun employees(branch: Scope.Branch): List<Employee> {
        val a = actor(Permission.PERSONNEL_VIEW, Permission.PERSONNEL_MANAGE)
        a.require(branch)
        return core.personnel.employees(branch)
    }

    /** Attendance sheet for one day: names only, no salaries. */
    fun attendance(branch: Scope.Branch, date: BusinessDate): List<Pair<Employee, AttendanceRecord?>> {
        val a = actor(Permission.ATTENDANCE_RECORD, Permission.PERSONNEL_VIEW)
        a.require(branch)
        return core.personnel.employees(branch).filter { it.isActive }
            .map { it.copy(monthlySalary = Money.ZERO, nationalId = "") to core.personnel.attendanceOn(it.id, date) }
    }

    fun payroll(branch: Scope.Branch): PayrollView {
        val a = actor(Permission.PAYROLL_CALCULATE, Permission.PAYROLL_APPROVE, Permission.PAYROLL_PAY)
        a.require(branch)
        val runs = core.payrollStore.runs(branch).sortedByDescending { it.from }
        return PayrollView(
            runs = runs,
            names = core.personnel.employees(branch).associate { it.id to it.name },
            unpaid = runs.filter { it.status == RunStatus.APPROVED }
                .associate { r -> r.id to r.payslips.associate { it.employeeId to core.payroll.unpaidNet(r.id, it.employeeId) } },
            insurance = core.payroll.outstandingLiability(branch, LiabilityKind.INSURANCE),
            tax = core.payroll.outstandingLiability(branch, LiabilityKind.INCOME_TAX),
        )
    }

    fun policies(): List<StatutoryPolicy> {
        actor(Permission.PAYROLL_APPROVE, Permission.PAYROLL_CALCULATE)
        return core.payrollPolicies.policies()
    }

    // ------------------------------------------------------------ Ledger

    /**
     * Trial balance over the scopes the actor may see: everything for the owner; otherwise the granted
     * branches, plus the organization scope only with ORGANIZATION_DATA. Sums to zero per scope.
     */
    fun trialBalance(): List<Pair<Account, Long>> {
        val a = actor(Permission.LEDGER_VIEW)
        val scopes: List<Scope>? = if (a.isOwner) null else buildList {
            addAll(a.branchGrants.map { Scope.Branch(it) })
            if (a.canAccess(Scope.Organization)) add(Scope.Organization)
        }
        return core.accounts.all().map { acc ->
            acc to (scopes?.sumOf { core.journals.netDebit(acc.code, it, null) } ?: core.journals.netDebit(acc.code, null, null))
        }.filter { it.second != 0L }
    }
}
