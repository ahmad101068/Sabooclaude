package ir.sabou.assets

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Ratio
import ir.sabou.kernel.Scope
import ir.sabou.kernel.ensure
import ir.sabou.ledger.JournalDraft
import ir.sabou.ledger.Ledger
import ir.sabou.ledger.LineDraft
import ir.sabou.ledger.PostingCapability
import ir.sabou.ledger.StandardAccounts
import ir.sabou.platform.AuditDraft
import ir.sabou.platform.Command
import ir.sabou.platform.CommandBus
import ir.sabou.platform.CommandOutcome
import ir.sabou.platform.ModuleId
import ir.sabou.platform.Permission
import ir.sabou.treasury.ChequeDetails
import ir.sabou.treasury.Direction
import ir.sabou.treasury.TreasuryGateway
import ir.sabou.treasury.paymentCheque

/**
 * Straight line: (cost − salvage) spread evenly over the useful life.
 * Declining balance: each year [rateBp] of the remaining book value (never below salvage).
 * Both are computed by days, so any period (a Jalali month, a year) gets its exact share.
 * The method and rate per asset class follow the tax depreciation table; an advisor confirms them.
 */
enum class DepreciationMethod { STRAIGHT_LINE, DECLINING_BALANCE }

enum class AssetStatus { ACTIVE, DISPOSED }

data class FixedAsset(
    val id: GlobalId,
    val name: String,
    val category: String,
    val scope: Scope.Branch,
    val cost: Money,
    val salvage: Money,
    val acquiredOn: BusinessDate,
    val method: DepreciationMethod,
    /** Straight line only. */
    val usefulLifeMonths: Int?,
    /** Declining balance only: yearly rate in basis points (e.g. 2500 = 25%). */
    val rateBp: Long?,
    val accumulated: Money,
    /** Depreciation has been booked up to and including this day. */
    val depreciatedThrough: BusinessDate?,
    val status: AssetStatus = AssetStatus.ACTIVE,
    val disposedOn: BusinessDate? = null,
    val acquisitionSource: String,
    val acquisitionId: GlobalId,
) {
    val bookValue: Money get() = cost - accumulated

    /** Depreciation from the day after [depreciatedThrough] (or the acquisition day) through [through]. */
    fun depreciationThrough(through: BusinessDate): Money {
        val start = depreciatedThrough?.plusDays(1) ?: acquiredOn
        if (through < start || status != AssetStatus.ACTIVE) return Money.ZERO
        val room = maxOf(0L, bookValue.rial - salvage.rial)
        if (room == 0L) return Money.ZERO
        return when (method) {
            DepreciationMethod.STRAIGHT_LINE -> {
                val lifeDays = Ratio.mulDiv(usefulLifeMonths!!.toLong(), 36_525, 1_200)
                val elapsed = through.epochDay - acquiredOn.epochDay + 1
                val target = if (elapsed >= lifeDays) cost.rial - salvage.rial else Ratio.mulDiv(cost.rial - salvage.rial, elapsed, lifeDays)
                Money.of(minOf(room, maxOf(0L, target - accumulated.rial)))
            }
            DepreciationMethod.DECLINING_BALANCE -> {
                val days = through.epochDay - start.epochDay + 1
                Money.of(minOf(room, Ratio.mulDiv(bookValue.rial, rateBp!! * days, 10_000L * 365)))
            }
        }
    }
}

/** One depreciation posting for a branch: which assets moved, by how much, and from where (for reversal). */
data class DepreciationRun(
    val id: GlobalId,
    val scope: Scope.Branch,
    val through: BusinessDate,
    val date: BusinessDate,
    val lines: List<DepreciationLine>,
    val journalId: GlobalId?,
    val reversed: Boolean = false,
) {
    val total: Money get() = Money.sum(lines.map { it.amount })
}

data class DepreciationLine(val assetId: GlobalId, val amount: Money, val previousThrough: BusinessDate?)

interface AssetStore {
    fun byId(id: GlobalId): FixedAsset?
    fun all(): List<FixedAsset>
    fun save(asset: FixedAsset)
    fun runs(): List<DepreciationRun>
    fun run(id: GlobalId): DepreciationRun?
    fun saveRun(run: DepreciationRun)
}

/** How an asset was paid for. */
sealed interface Funding {
    /** Bought now from a cash or bank account (or with a cheque from a cheque book / a held cheque). */
    data class Paid(val treasuryAccountId: GlobalId, val cheque: ChequeDetails? = null, val chequeId: GlobalId? = null) : Funding
    /** Already owned when the books started (or brought in by the owner): against capital, with depreciation so far. */
    data class Existing(val accumulatedSoFar: Money, val depreciatedThrough: BusinessDate?) : Funding
}

data class AcquireAsset(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val name: String,
    val category: String,
    val cost: Money,
    val salvage: Money,
    val date: BusinessDate,
    val method: DepreciationMethod,
    val usefulLifeMonths: Int?,
    val rateBp: Long?,
    val funding: Funding,
) : Command {
    override val requiredPermission = Permission.ASSET_MANAGE
    override val additionalPermissions = if (funding is Funding.Paid) setOf(Permission.TREASURY_PAYMENT) else emptySet()
    override fun fingerprint() = "$scope|$name|$category|${cost.rial}|${salvage.rial}|${date.epochDay}|$method|$usefulLifeMonths|$rateBp|" + when (funding) {
        is Funding.Paid -> "paid:${funding.treasuryAccountId}:${funding.cheque?.fingerprint()}:${funding.chequeId}"
        is Funding.Existing -> "existing:${funding.accumulatedSoFar.rial}:${funding.depreciatedThrough?.epochDay}"
    }
}

/** Books depreciation for every active asset of the branch up to [through] (one journal). */
data class RunDepreciation(override val commandId: GlobalId, override val scope: Scope.Branch, val through: BusinessDate) : Command {
    override val requiredPermission = Permission.ASSET_MANAGE
    override fun fingerprint() = "$scope|${through.epochDay}"
}

/** Takes back the latest depreciation run of a branch (e.g. it was run for the wrong month). */
data class ReverseDepreciationRun(override val commandId: GlobalId, override val scope: Scope.Branch, val runId: GlobalId, val date: BusinessDate, val reason: String) : Command {
    override val requiredPermission = Permission.ASSET_MANAGE
    override fun fingerprint() = "$scope|$runId|${date.epochDay}|$reason"
}

/** Sold or scrapped: depreciation up to [date], then the asset leaves the books; [proceeds] received into [treasuryAccountId]. */
data class DisposeAsset(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val assetId: GlobalId,
    val date: BusinessDate,
    val proceeds: Money,
    val treasuryAccountId: GlobalId?,
    val reason: String,
) : Command {
    override val requiredPermission = Permission.ASSET_MANAGE
    override val additionalPermissions = if (!proceeds.isZero) setOf(Permission.TREASURY_RECEIPT) else emptySet()
    override fun fingerprint() = "$scope|$assetId|${date.epochDay}|${proceeds.rial}|$treasuryAccountId|$reason"
}

class AssetOperations(
    private val bus: CommandBus,
    private val ledger: Ledger,
    private val capability: PostingCapability,
    private val treasury: TreasuryGateway,
    private val assets: AssetStore,
) {
    init {
        require(capability.module == ModuleId.ASSETS)
    }

    fun acquire(c: AcquireAsset): CommandOutcome = bus.execute(ModuleId.ASSETS, c) { cmd, ctx ->
        val name = cmd.name.trim()
        ensure(name.length in 2..120) { DomainError.InvalidInput("name", "نام دارایی الزامی است.") }
        ensure(!cmd.cost.isZero) { DomainError.InvalidInput("cost", "بهای دارایی باید بیشتر از صفر باشد.") }
        ensure(cmd.salvage < cmd.cost) { DomainError.InvalidInput("salvage", "ارزش اسقاط باید کمتر از بها باشد.") }
        when (cmd.method) {
            DepreciationMethod.STRAIGHT_LINE -> ensure(cmd.usefulLifeMonths != null && cmd.usefulLifeMonths in 1..1200) { DomainError.InvalidInput("life", "عمر مفید (ماه) را وارد کنید.") }
            DepreciationMethod.DECLINING_BALANCE -> ensure(cmd.rateBp != null && cmd.rateBp in 1..10_000) { DomainError.InvalidInput("rate", "نرخ استهلاک سالانه را وارد کنید.") }
        }
        val id = GlobalId.new()
        val title = "خرید دارایی: $name"
        val (accumulated, through) = when (val f = cmd.funding) {
            is Funding.Paid -> {
                val account = treasury.account(f.treasuryAccountId)
                val cheque = paymentCheque(f.cheque, f.chequeId)
                if (account.scope == cmd.scope) {
                    treasury.settle(ctx, capability, account.id, Direction.PAYMENT, cmd.cost, cmd.date, ACQUISITION, id, title,
                        listOf(LineDraft(StandardAccounts.FIXED_ASSETS, debit = cmd.cost, memo = name, by = capability)), cheque)
                } else {
                    // Paid centrally for a branch: each scope balances through the inter-branch account.
                    treasury.settle(ctx, capability, account.id, Direction.PAYMENT, cmd.cost, cmd.date, ACQUISITION, id, title,
                        listOf(LineDraft(StandardAccounts.INTER_BRANCH, debit = cmd.cost, memo = "دارایی شعبه", by = capability)), cheque)
                    ledger.post(ctx, capability, JournalDraft(cmd.date, cmd.scope, ACQUISITION, id, title, listOf(
                        LineDraft(StandardAccounts.FIXED_ASSETS, debit = cmd.cost, memo = name, by = capability),
                        LineDraft(StandardAccounts.INTER_BRANCH, credit = cmd.cost, memo = account.name, by = capability),
                    )))
                }
                Money.ZERO to null
            }
            is Funding.Existing -> {
                ensure(f.accumulatedSoFar <= cmd.cost - cmd.salvage) { DomainError.InvalidInput("accumulated", "استهلاک انباشته از بهای قابل استهلاک بیشتر است.") }
                ensure(f.accumulatedSoFar.isZero || f.depreciatedThrough != null) { DomainError.InvalidInput("through", "تاریخ آخرین استهلاک را وارد کنید.") }
                f.depreciatedThrough?.let { ensure(it >= cmd.date) { DomainError.InvalidInput("through", "تاریخ آخرین استهلاک قبل از تاریخ خرید است.") } }
                val net = cmd.cost - f.accumulatedSoFar
                ledger.post(ctx, capability, JournalDraft(f.depreciatedThrough ?: cmd.date, cmd.scope, ACQUISITION, id, "ثبت دارایی موجود: $name", listOfNotNull(
                    LineDraft(StandardAccounts.FIXED_ASSETS, debit = cmd.cost, memo = name, by = capability),
                    if (f.accumulatedSoFar.isZero) null else LineDraft(StandardAccounts.ACCUMULATED_DEPRECIATION, credit = f.accumulatedSoFar, memo = name, by = capability),
                    LineDraft(StandardAccounts.CAPITAL, credit = net, memo = "دارایی موجود", by = capability),
                )))
                f.accumulatedSoFar to f.depreciatedThrough
            }
        }
        val asset = FixedAsset(
            id, name, cmd.category.trim(), cmd.scope, cmd.cost, cmd.salvage, cmd.date, cmd.method,
            cmd.usefulLifeMonths.takeIf { cmd.method == DepreciationMethod.STRAIGHT_LINE }, cmd.rateBp.takeIf { cmd.method == DepreciationMethod.DECLINING_BALANCE },
            accumulated, through, acquisitionSource = ACQUISITION, acquisitionId = id,
        )
        assets.save(asset)
        ctx.audit(AuditDraft("ASSET_ACQUIRE", "FIXED_ASSET", id.value, "cost=${cmd.cost.rial};method=${cmd.method}"))
        id
    }

    fun depreciate(c: RunDepreciation): CommandOutcome = bus.execute(ModuleId.ASSETS, c) { cmd, ctx ->
        val runId = GlobalId.new()
        val lines = assets.all().filter { it.scope == cmd.scope && it.status == AssetStatus.ACTIVE }
            .map { it to it.depreciationThrough(cmd.through) }.filter { !it.second.isZero }
        ensure(lines.isNotEmpty()) { DomainError.InvalidState("DEPRECIATION", "NOTHING_TO_BOOK") }
        val total = Money.sum(lines.map { it.second })
        val journal = ledger.post(ctx, capability, JournalDraft(cmd.through, cmd.scope, DEPRECIATION, runId, "استهلاک دارایی‌ها تا ${cmd.through.epochDay}",
            lines.map { (a, amount) -> LineDraft(StandardAccounts.DEPRECIATION, debit = amount, memo = a.name, by = capability) } +
                LineDraft(StandardAccounts.ACCUMULATED_DEPRECIATION, credit = total, memo = "استهلاک انباشته", by = capability),
        ))
        lines.forEach { (a, amount) -> assets.save(a.copy(accumulated = a.accumulated + amount, depreciatedThrough = cmd.through)) }
        assets.saveRun(DepreciationRun(runId, cmd.scope, cmd.through, cmd.through, lines.map { (a, amount) -> DepreciationLine(a.id, amount, a.depreciatedThrough) }, journal.id))
        ctx.audit(AuditDraft("DEPRECIATION_RUN", "DEPRECIATION", runId.value, "through=${cmd.through.epochDay};total=${total.rial};assets=${lines.size}"))
        runId
    }

    fun reverseRun(c: ReverseDepreciationRun): CommandOutcome = bus.execute(ModuleId.ASSETS, c) { cmd, ctx ->
        val run = assets.run(cmd.runId) ?: throw DomainException(DomainError.NotFound("DEPRECIATION_RUN"))
        ensure(run.scope == cmd.scope) { DomainError.InvalidInput("scope", "متعلق به این شعبه نیست.") }
        ensure(!run.reversed) { DomainError.InvalidState("DEPRECIATION_RUN", "REVERSED") }
        // Only the latest step of each asset can be taken back, so balances never skip a period.
        run.lines.forEach { line ->
            val asset = assets.byId(line.assetId)!!
            ensure(asset.status == AssetStatus.ACTIVE && asset.depreciatedThrough == run.through) { DomainError.InvalidState("DEPRECIATION_RUN", "NOT_LATEST") }
        }
        run.journalId?.let { ledger.reverse(ctx, capability, emptySet(), it, cmd.date, cmd.reason) }
        run.lines.forEach { line ->
            val asset = assets.byId(line.assetId)!!
            assets.save(asset.copy(accumulated = asset.accumulated - line.amount, depreciatedThrough = line.previousThrough))
        }
        assets.saveRun(run.copy(reversed = true))
        ctx.audit(AuditDraft("DEPRECIATION_REVERSE", "DEPRECIATION", run.id.value, cmd.reason.trim()))
        run.id
    }

    fun dispose(c: DisposeAsset): CommandOutcome = bus.execute(ModuleId.ASSETS, c) { cmd, ctx ->
        val asset = assets.byId(cmd.assetId) ?: throw DomainException(DomainError.NotFound("FIXED_ASSET"))
        ensure(asset.scope == cmd.scope) { DomainError.InvalidInput("scope", "متعلق به این شعبه نیست.") }
        ensure(asset.status == AssetStatus.ACTIVE) { DomainError.InvalidState("FIXED_ASSET", asset.status.name) }
        ensure(cmd.date >= asset.acquiredOn && (asset.depreciatedThrough == null || cmd.date >= asset.depreciatedThrough)) { DomainError.InvalidInput("date", "تاریخ واگذاری قبل از آخرین استهلاک است.") }
        ensure(cmd.reason.trim().length in 3..300) { DomainError.InvalidInput("reason", "دلیل واگذاری الزامی است.") }
        ensure(cmd.proceeds.isZero == (cmd.treasuryAccountId == null)) { DomainError.InvalidInput("account", "حساب دریافت مبلغ فروش را انتخاب کنید.") }
        val docId = GlobalId.new()
        // Depreciation up to the disposal day first, so the gain or loss is measured on the true book value.
        val last = asset.depreciationThrough(cmd.date)
        val accumulated = asset.accumulated + last
        val book = asset.cost - accumulated
        val title = "واگذاری دارایی: ${asset.name}"
        // Clear the asset's cost and all its accumulated depreciation; the rest is gain or loss.
        val journalLines = mutableListOf<LineDraft>()
        if (!last.isZero) journalLines += LineDraft(StandardAccounts.DEPRECIATION, debit = last, memo = asset.name, by = capability)
        if (!accumulated.isZero) journalLines += LineDraft(StandardAccounts.ACCUMULATED_DEPRECIATION, debit = accumulated, memo = asset.name, by = capability)
        if (!last.isZero) journalLines += LineDraft(StandardAccounts.ACCUMULATED_DEPRECIATION, credit = last, memo = "استهلاک تا روز واگذاری", by = capability)
        if (!cmd.proceeds.isZero) journalLines += LineDraft(StandardAccounts.INTER_BRANCH, debit = cmd.proceeds, memo = "مبلغ فروش", by = capability)
        val gain = cmd.proceeds.rial - book.rial
        if (gain > 0) journalLines += LineDraft(StandardAccounts.OTHER_INCOME, credit = Money.of(gain), memo = "سود فروش دارایی", by = capability)
        if (gain < 0) journalLines += LineDraft(StandardAccounts.OTHER_EXPENSE, debit = Money.of(-gain), memo = "زیان واگذاری دارایی", by = capability)
        journalLines += LineDraft(StandardAccounts.FIXED_ASSETS, credit = asset.cost, memo = asset.name, by = capability)
        ledger.post(ctx, capability, JournalDraft(cmd.date, cmd.scope, DISPOSAL, docId, "$title · ${cmd.reason.trim()}", journalLines))
        if (!cmd.proceeds.isZero) {
            // The sale money arrives in a treasury account (any scope the user may use) via the inter-branch account.
            treasury.settle(ctx, capability, cmd.treasuryAccountId!!, Direction.RECEIPT, cmd.proceeds, cmd.date, DISPOSAL, docId, title,
                listOf(LineDraft(StandardAccounts.INTER_BRANCH, credit = cmd.proceeds, memo = asset.name, by = capability)))
        }
        assets.save(asset.copy(accumulated = accumulated, depreciatedThrough = cmd.date, status = AssetStatus.DISPOSED, disposedOn = cmd.date))
        ctx.audit(AuditDraft("ASSET_DISPOSE", "FIXED_ASSET", asset.id.value, "proceeds=${cmd.proceeds.rial};book=${book.rial}"))
        docId
    }

    companion object {
        const val ACQUISITION = "ASSET_ACQUISITION"
        const val DEPRECIATION = "ASSET_DEPRECIATION"
        const val DISPOSAL = "ASSET_DISPOSAL"
    }
}
