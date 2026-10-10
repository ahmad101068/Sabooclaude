package ir.sabou.core

import ir.sabou.inventory.Item
import ir.sabou.inventory.InventoryOperations
import ir.sabou.inventory.MovementKind
import ir.sabou.inventory.RecipeBook
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Ratio
import ir.sabou.kernel.Scope
import ir.sabou.ledger.Account
import ir.sabou.ledger.AccountCode
import ir.sabou.ledger.AccountType
import ir.sabou.ledger.StandardAccounts
import ir.sabou.platform.Actor
import ir.sabou.platform.ModuleId
import ir.sabou.platform.Permission
import ir.sabou.purchasing.InvoiceStatus
import ir.sabou.sales.SaleStatus
import ir.sabou.sales.Settlement
import ir.sabou.treasury.TreasuryKind

// ---------------------------------------------------------------- Profit and loss

/** Revenue, cost of sales and expenses of a period. Amounts are positive in their natural direction. */
data class PnlTotals(val revenue: Long, val cogs: Long, val expenses: Long) {
    val grossProfit: Long get() = revenue - cogs
    val profit: Long get() = revenue - cogs - expenses

    operator fun plus(o: PnlTotals) = PnlTotals(revenue + o.revenue, cogs + o.cogs, expenses + o.expenses)

    companion object { val ZERO = PnlTotals(0, 0, 0) }
}

data class PnlLine(val account: Account, val amount: Long)

/**
 * Restaurant cost ratios on food and beverage sales (4101): food cost = cost of sales + waste + comps + stock
 * variance; labour = salaries + employer insurance; prime cost = both. Basis points (1/100 of a percent).
 * Labour is booked when a payroll month is approved, so short periods show it in the month's last days.
 */
data class CostRatios(val sales: Long, val foodCost: Long, val labor: Long) {
    val prime: Long get() = foodCost + labor
    val foodBp: Long? get() = bp(foodCost)
    val laborBp: Long? get() = bp(labor)
    val primeBp: Long? get() = bp(prime)
    private fun bp(part: Long): Long? = if (sales <= 0 || part < 0) null else Ratio.mulDiv(part, 10_000, sales)
}

data class ProfitAndLoss(
    val from: BusinessDate,
    val to: BusinessDate,
    val revenue: List<PnlLine>,
    val costOfSales: List<PnlLine>,
    val expenses: List<PnlLine>,
    val totals: PnlTotals,
    val byBranch: List<Pair<String, PnlTotals>>,
    val byDay: List<Pair<BusinessDate, PnlTotals>>,
    val ratios: CostRatios,
)

/** One journal line behind a P&L figure. */
data class LedgerDetail(
    val date: BusinessDate,
    /** The legal journal number («سح-1405-00042»). */
    val number: String,
    val description: String,
    val scope: String,
    val debit: Long,
    val credit: Long,
    val memo: String,
)

// ---------------------------------------------------------------- Sales mix and margins

data class MixRow(
    val menuItemId: GlobalId,
    val name: String,
    val portions: Quantity,
    val gross: Money,
    /** Share of gross sales, basis points. */
    val shareBp: Long,
    /** Ingredient cost of one portion at today's average costs (null: no recipe or no cost known). */
    val unitCost: Money?,
) {
    val averagePrice: Money? get() = if (portions.isZero) null else Money.of(Ratio.mulDiv(gross.rial, Quantity.SCALE, portions.micros))
    val unitMargin: Long? get() = unitCost?.let { c -> averagePrice?.let { it.rial - c.rial } }
    /** Food cost of the item as basis points of its price. */
    val costBp: Long? get() = unitCost?.let { c -> averagePrice?.takeIf { !it.isZero }?.let { Ratio.mulDiv(c.rial, 10_000, it.rial) } }
}

data class ProductMix(val from: BusinessDate, val to: BusinessDate, val branch: String, val rows: List<MixRow>, val gross: Money, val days: Int)

// ---------------------------------------------------------------- End of day

data class DayFlash(
    val branch: String,
    val date: BusinessDate,
    val posted: Boolean,
    val closed: Boolean,
    val gross: Money,
    val discount: Money,
    val serviceCharge: Money,
    val tax: Money,
    val payable: Money,
    val netFood: Money,
    val guests: Int,
    val transactions: Int,
    val settlements: List<Pair<String, Money>>,
    val credit: Money,
    val cost: Money,
    /** Null when the viewer may not see purchase values. */
    val purchases: Money?,
    /** Null when the viewer may not see stock values. */
    val waste: Money?,
    val cashSales: Money,
    val countedCash: Money?,
) {
    val perGuest: Money? get() = if (guests <= 0) null else Money.of(netFood.rial / guests)
    val perTransaction: Money? get() = if (transactions <= 0) null else Money.of(payable.rial / transactions)
    val foodCostBp: Long? get() = if (netFood.isZero) null else Ratio.mulDiv(cost.rial, 10_000, netFood.rial)
    /** Counted minus expected cash (positive = over). Expected = this day's cash settlements. */
    val cashDifference: Long? get() = countedCash?.let { it.rial - cashSales.rial }
}

// ---------------------------------------------------------------- Actual vs theoretical usage

/** Quantity (micro-units) and value (rial) together. */
data class QV(val quantity: Long, val value: Long) {
    operator fun plus(o: QV) = QV(quantity + o.quantity, value + o.value)
    operator fun minus(o: QV) = QV(quantity - o.quantity, value - o.value)
    operator fun unaryMinus() = QV(-quantity, -value)
    val isZero: Boolean get() = quantity == 0L && value == 0L

    companion object { val ZERO = QV(0, 0) }
}

/**
 * Usage of one item over a period. Actual usage = opening + purchases + transfers + production − closing.
 * Theoretical = what posted sales consumed through recipes. Unexplained = what stock counts found missing
 * (minus what they found extra). Efficiency = theoretical ÷ actual.
 */
data class UsageRow(
    val item: Item,
    val opening: QV,
    val purchases: QV,
    val transfers: QV,
    val production: QV,
    val closing: QV,
    val theoretical: QV,
    val waste: QV,
    val unexplained: QV,
    /** Given away: complimentary dishes, staff meals, donations (recorded with a reason). */
    val comps: QV = QV.ZERO,
) {
    val actual: QV get() = opening + purchases + transfers + production - closing
    /** Basis points; null when nothing was used. */
    val efficiencyBp: Long? get() = if (actual.quantity <= 0 || theoretical.quantity < 0) null else Ratio.mulDiv(theoretical.quantity, 10_000, actual.quantity)
}

data class UsageReport(val from: BusinessDate, val to: BusinessDate, val place: String, val rows: List<UsageRow>) {
    val theoreticalValue: Long get() = rows.sumOf { it.theoretical.value }
    val actualValue: Long get() = rows.sumOf { it.actual.value }
    val wasteValue: Long get() = rows.sumOf { it.waste.value }
    val compsValue: Long get() = rows.sumOf { it.comps.value }
    val unexplainedValue: Long get() = rows.sumOf { it.unexplained.value }
}

// ---------------------------------------------------------------- Attendance

data class AttendanceTotal(val employeeId: GlobalId, val name: String, val days: Int, val workedMinutes: Long, val overtimeMinutes: Long, val absentMinutes: Long)

/**
 * Period reports. Read-only, with the same access rules as [Overview]: financial statements need
 * LEDGER_VIEW and are limited to the scopes the actor may see; sales and stock reports need the
 * respective view permission and the branch.
 */
class Reports internal constructor(private val core: SabouCore) {
    private fun actor(vararg anyOf: Permission): Actor {
        val a = core.session.currentActor() ?: throw DomainException(DomainError.AuthenticationRequired)
        if (anyOf.none { a.role.allows(it) }) throw DomainException(DomainError.PermissionDenied(anyOf.first().name))
        return a
    }

    private fun Actor.require(scope: Scope) {
        if (!canAccess(scope)) throw DomainException(DomainError.ScopeDenied(ir.sabou.platform.CommandContext.scopeLabel(scope)))
    }

    /** The legal number of a journal entry; entries are always numbered (older ones by migration 7). */
    private fun journalNo(e: ir.sabou.ledger.JournalEntry): String =
        core.numbers.of(ir.sabou.platform.DocumentSeries.JOURNAL, e.id)?.text ?: e.number.toString()

    private fun scopeName(scope: Scope): String = when (scope) {
        Scope.Organization -> "دفتر مرکزی"
        is Scope.Branch -> core.branches.all().firstOrNull { it.id == scope.branchId }?.name ?: "شعبه"
    }

    private fun requirePeriod(from: BusinessDate, to: BusinessDate) {
        if (from > to || to.epochDay - from.epochDay > 3_660) throw DomainException(DomainError.InvalidInput("period", "بازه‌ی گزارش معتبر نیست."))
    }

    // ------------------------------------------------------------ Profit and loss

    /**
     * Profit and loss for [from]..[to]. [branch] null = every scope the actor may see (all of them for
     * the owner; the granted branches, plus head office with ORGANIZATION_DATA, otherwise).
     */
    fun profitAndLoss(from: BusinessDate, to: BusinessDate, branch: Scope.Branch? = null): ProfitAndLoss {
        val a = actor(Permission.LEDGER_VIEW)
        requirePeriod(from, to)
        branch?.let { a.require(it) }
        val accounts = core.accounts.all().associateBy { it.code }
        val totals = core.journals.dailyTotals(from, to).filter { t ->
            if (branch != null) t.scope == branch else a.canAccess(t.scope)
        }
        fun natural(code: AccountCode, debit: Long, credit: Long): Long? {
            val account = accounts[code] ?: return null
            return when (account.type) {
                AccountType.REVENUE -> credit - debit
                AccountType.EXPENSE -> debit - credit
                else -> null
            }
        }
        fun kind(code: AccountCode): Int? = when (accounts[code]?.type) {
            AccountType.REVENUE -> 0
            AccountType.EXPENSE -> if (code.value.startsWith("5")) 1 else 2
            else -> null
        }
        fun totalsOf(rows: List<ir.sabou.ledger.DailyAccountTotal>): PnlTotals = rows.fold(PnlTotals.ZERO) { acc, t ->
            val amount = natural(t.account, t.debit, t.credit) ?: return@fold acc
            when (kind(t.account)) {
                0 -> acc.copy(revenue = acc.revenue + amount)
                1 -> acc.copy(cogs = acc.cogs + amount)
                2 -> acc.copy(expenses = acc.expenses + amount)
                else -> acc
            }
        }
        val byAccount = totals.groupBy { it.account }.mapNotNull { (code, rows) ->
            val amount = rows.sumOf { natural(code, it.debit, it.credit) ?: 0 }
            if (kind(code) == null || amount == 0L) null else Triple(kind(code)!!, accounts.getValue(code), amount)
        }.sortedBy { it.second.code.value }
        fun sumOf(codes: List<AccountCode>) = codes.sumOf { c -> byAccount.firstOrNull { it.second.code == c }?.third ?: 0 }
        return ProfitAndLoss(
            from = from, to = to,
            revenue = byAccount.filter { it.first == 0 }.map { PnlLine(it.second, it.third) },
            costOfSales = byAccount.filter { it.first == 1 }.map { PnlLine(it.second, it.third) },
            expenses = byAccount.filter { it.first == 2 }.map { PnlLine(it.second, it.third) },
            totals = totalsOf(totals),
            byBranch = totals.groupBy { it.scope }.map { (s, rows) -> scopeName(s) to totalsOf(rows) }.sortedBy { it.first },
            byDay = totals.groupBy { it.date }.map { (d, rows) -> d to totalsOf(rows) }.sortedBy { it.first },
            ratios = CostRatios(
                sales = sumOf(listOf(StandardAccounts.FOOD_SALES)),
                foodCost = sumOf(listOf(StandardAccounts.COGS, StandardAccounts.WASTE, StandardAccounts.COMPS, StandardAccounts.INVENTORY_VARIANCE)),
                labor = sumOf(listOf(StandardAccounts.SALARIES, StandardAccounts.EMPLOYER_INSURANCE)),
            ),
        )
    }

    /** The journal lines behind one P&L figure: every posting to [account] in the period. */
    fun ledgerDetail(account: AccountCode, from: BusinessDate, to: BusinessDate, branch: Scope.Branch? = null): List<LedgerDetail> {
        val a = actor(Permission.LEDGER_VIEW)
        requirePeriod(from, to)
        branch?.let { a.require(it) }
        return core.journals.entriesTouching(account, branch, from, to).filter { a.canAccess(it.scope) }.flatMap { e ->
            e.lines.filter { it.account == account }.map { l ->
                LedgerDetail(e.date, journalNo(e), e.description, scopeName(e.scope), l.debit.rial, l.credit.rial, l.memo)
            }
        }
    }

    // ------------------------------------------------------------ Sales

    fun productMix(branch: Scope.Branch, from: BusinessDate, to: BusinessDate): ProductMix {
        val a = actor(Permission.SALES_VIEW)
        a.require(branch)
        requirePeriod(from, to)
        val posted = core.sales.sales(branch, from, to).filter { it.status == SaleStatus.POSTED }
        val names = core.recipes.menuItems().associate { it.id to it.name }
        val lines = posted.flatMap { it.lines }.groupBy { it.menuItemId }
        val gross = Money.sum(posted.flatMap { it.lines }.map { it.gross })
        // Ingredient costs are stock and purchase data: shown only to roles that may see those.
        val seesCosts = listOf(Permission.INVENTORY_VIEW, Permission.PURCHASE_VIEW, Permission.RECIPE_MANAGE).any { a.role.allows(it) }
        val costs = UnitCosts(branch, to)
        val rows = lines.map { (id, l) ->
            val g = Money.sum(l.map { it.gross })
            MixRow(
                menuItemId = id, name = names[id].orEmpty(),
                portions = l.fold(Quantity.ZERO) { acc, x -> acc + x.portions }, gross = g,
                shareBp = if (gross.isZero) 0 else Ratio.mulDiv(g.rial, 10_000, gross.rial),
                unitCost = if (seesCosts) costs.portion(id, to) else null,
            )
        }.sortedByDescending { it.gross }
        return ProductMix(from, to, scopeName(branch), rows, gross, posted.size)
    }

    fun dayFlash(branch: Scope.Branch, date: BusinessDate): DayFlash {
        val a = actor(Permission.SALES_VIEW)
        a.require(branch)
        val sale = core.sales.activeSale(branch, date)?.takeIf { it.status == SaleStatus.POSTED }
        val accounts = core.treasuryAccounts.all().associateBy { it.id }
        val liquid = sale?.settlements.orEmpty().filterIsInstance<Settlement.Liquid>()
        val locations = core.locations.all().filter { it.scope == branch }
        val waste = locations.sumOf { loc ->
            core.stock.movementsAt(loc.id, date, date).filter { it.kind == MovementKind.WASTE }.sumOf { -it.valueDelta }
        }
        val purchases = core.purchases.invoices().filter { it.scope == branch && it.date == date && it.status == InvoiceStatus.POSTED }
        val day = core.sales.day(branch, date)
        return DayFlash(
            branch = scopeName(branch), date = date, posted = sale != null, closed = day?.closed == true,
            gross = sale?.gross ?: Money.ZERO, discount = sale?.discount ?: Money.ZERO, serviceCharge = sale?.serviceCharge ?: Money.ZERO,
            tax = sale?.tax ?: Money.ZERO, payable = sale?.payable ?: Money.ZERO, netFood = sale?.netFood ?: Money.ZERO,
            guests = sale?.guests ?: 0, transactions = sale?.transactions ?: 0,
            settlements = liquid.groupBy { it.treasuryAccountId }.map { (id, l) -> (accounts[id]?.name ?: "حساب") to Money.sum(l.map { it.amount }) },
            credit = Money.sum(sale?.settlements.orEmpty().filterIsInstance<Settlement.Credit>().map { it.amount }),
            cost = sale?.cost ?: Money.ZERO,
            // Purchases and waste values only for roles that may see purchasing / stock values.
            purchases = if (a.role.allows(Permission.PURCHASE_VIEW)) Money.sum(purchases.map { it.total }) else null,
            waste = if (a.role.allows(Permission.INVENTORY_VIEW)) Money.of(maxOf(0, waste)) else null,
            cashSales = Money.sum(liquid.filter { accounts[it.treasuryAccountId]?.kind == TreasuryKind.CASH }.map { it.amount }),
            countedCash = day?.countedCash,
        )
    }

    // ------------------------------------------------------------ Inventory

    /** Actual vs theoretical usage at one location, or at all of a branch's locations when [locationId] is null. */
    fun actualVsTheoretical(branch: Scope.Branch, locationId: GlobalId?, from: BusinessDate, to: BusinessDate): UsageReport {
        val a = actor(Permission.INVENTORY_VIEW)
        a.require(branch)
        requirePeriod(from, to)
        val locations = core.locations.all().filter { it.scope == branch && (locationId == null || it.id == locationId) }
        if (locationId != null && locations.isEmpty()) throw DomainException(DomainError.NotFound("LOCATION"))
        val items = core.items.all().associateBy { it.id }
        class Acc { var opening = QV.ZERO; var purchases = QV.ZERO; var transfers = QV.ZERO; var production = QV.ZERO
            var theoretical = QV.ZERO; var waste = QV.ZERO; var comps = QV.ZERO; var unexplained = QV.ZERO; var movement = QV.ZERO }
        val acc = HashMap<GlobalId, Acc>()
        locations.forEach { loc ->
            core.stock.totalsBefore(loc.id, from).forEach { t -> acc.getOrPut(t.itemId) { Acc() }.opening += QV(t.quantity, t.value) }
            core.stock.movementsAt(loc.id, from, to).forEach { m ->
                val r = acc.getOrPut(m.itemId) { Acc() }
                val qv = QV(m.quantityDelta, m.valueDelta)
                r.movement += qv
                when (m.kind) {
                    MovementKind.RECEIPT, MovementKind.OPENING -> r.purchases += qv
                    MovementKind.TRANSFER_IN, MovementKind.TRANSFER_OUT -> r.transfers += qv
                    MovementKind.PRODUCTION_IN, MovementKind.PRODUCTION_OUT -> r.production += qv
                    MovementKind.WASTE -> if (m.source.type == InventoryOperations.COMP) r.comps -= qv else r.waste -= qv
                    MovementKind.COUNT_LOSS, MovementKind.COUNT_GAIN -> r.unexplained -= qv
                    // Issues: sales consumption is the theoretical usage; a return to the supplier undoes a purchase.
                    MovementKind.ISSUE -> if (m.source.module == ModuleId.SALES) r.theoretical -= qv else r.purchases += qv
                }
            }
        }
        val rows = acc.mapNotNull { (id, r) ->
            val item = items[id] ?: return@mapNotNull null
            val closing = r.opening + r.movement
            if (r.opening.isZero && r.movement.isZero) null
            else UsageRow(item, r.opening, r.purchases, r.transfers, r.production, closing, r.theoretical, r.waste, r.unexplained, r.comps)
        }.sortedBy { it.item.name }
        val place = if (locationId != null) locations.single().name else "همه‌ی انبارهای ${scopeName(branch)}"
        return UsageReport(from, to, place, rows)
    }

    // ------------------------------------------------------------ Attendance and overtime

    /** Worked, overtime and absent time per employee over a period (no salaries). */
    fun attendance(branch: Scope.Branch, from: BusinessDate, to: BusinessDate): List<AttendanceTotal> {
        val a = actor(Permission.PERSONNEL_VIEW, Permission.ATTENDANCE_RECORD, Permission.PAYROLL_CALCULATE)
        a.require(branch)
        requirePeriod(from, to)
        return core.personnel.employees(branch).filter { it.employedDays(from, to) > 0 }.map { e ->
            val records = core.personnel.attendance(e.id, from, to)
            AttendanceTotal(e.id, e.name, records.size, records.sumOf { it.workedMinutes.toLong() },
                records.sumOf { it.overtimeMinutes.toLong() }, records.sumOf { it.absentMinutes.toLong() })
        }
    }

    // ------------------------------------------------------------ Costs

    /** Average cost of ingredients in one branch: stock value ÷ quantity, else the last purchase price. */
    private inner class UnitCosts(private val branch: Scope.Branch, private val date: BusinessDate) {
        private val book = RecipeBook(core.recipes)
        private val cache = HashMap<GlobalId, Long?>()     // rial per unit (SCALE micro-units)

        fun perUnit(itemId: GlobalId): Long? = cache.getOrPut(itemId) {
            val balances = core.locations.all().filter { it.scope == branch }.map { core.stock.balance(itemId, it.id) }
            val qty = balances.sumOf { it.quantity.micros }
            val value = balances.sumOf { it.value.rial }
            if (qty > 0 && value > 0) Ratio.mulDiv(value, Quantity.SCALE, qty)
            else lastPurchase(itemId) ?: prepCost(itemId)
        }

        /** A prepared item with no stock: the cost of its prep recipe per unit (null if anything is unknown). */
        private val visiting = HashSet<GlobalId>()
        private fun prepCost(itemId: GlobalId): Long? {
            if (core.items.byId(itemId)?.prepared != true || !visiting.add(itemId)) return null
            try {
                val lines = runCatching { book.prepRequirements(itemId, date, Quantity.units(1)) }.getOrNull() ?: return null
                var total = 0L
                for (l in lines) total += Ratio.mulDiv(perUnit(l.itemId) ?: return null, l.quantity.micros, Quantity.SCALE)
                return total
            } finally {
                visiting.remove(itemId)
            }
        }

        private fun lastPurchase(itemId: GlobalId): Long? = core.purchases.invoices()
            .filter { it.status == InvoiceStatus.POSTED && it.scope == branch }     // never another branch's prices
            .sortedByDescending { it.date }
            .firstNotNullOfOrNull { inv -> inv.lines.firstOrNull { it.itemId == itemId && !it.quantity.isZero } }
            ?.let { Ratio.mulDiv(it.value.rial, Quantity.SCALE, it.quantity.micros) }

        fun portion(menuItemId: GlobalId, date: BusinessDate): Money? {
            val lines = runCatching { book.requirements(menuItemId, date, Quantity.units(1)) }.getOrNull() ?: return null
            var total = 0L
            for (l in lines) {
                val unit = perUnit(l.itemId) ?: return null
                total += Ratio.mulDiv(unit, l.quantity.micros, Quantity.SCALE)
            }
            return Money.of(total)
        }
    }
}
