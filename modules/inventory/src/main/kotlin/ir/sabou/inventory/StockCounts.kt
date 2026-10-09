package ir.sabou.inventory

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Scope
import ir.sabou.kernel.ensure
import ir.sabou.ledger.JournalDraft
import ir.sabou.ledger.LineDraft
import ir.sabou.ledger.SourceDocument
import ir.sabou.ledger.StandardAccounts
import ir.sabou.platform.AuditDraft
import ir.sabou.platform.Command
import ir.sabou.platform.CommandBus
import ir.sabou.platform.CommandOutcome
import ir.sabou.platform.ModuleId
import ir.sabou.platform.Permission
import ir.sabou.platform.Role

/*
 * Stock counts in two steps (separation of duties):
 * 1. whoever counts records what is on the shelf ("blind": the count sheet does not show the book
 *    quantity); the book quantity at that moment is kept with each line;
 * 2. someone else with INVENTORY_ADJUST reviews the differences, gives a reason for each one and
 *    approves (the stock and the books are corrected) or rejects (nothing changes, the reason is kept).
 * Every count, approved or rejected, stays in the history with who did what and why.
 */

enum class CountStatus { PENDING, POSTED, REJECTED }

/** Why a counted quantity differs from the book. */
enum class VarianceReason {
    /** Waste or spoilage that was never recorded. */
    UNRECORDED_WASTE,
    /** Used (staff meals, tests, recipes not followed) without a record. */
    UNRECORDED_USE,
    /** A purchase or transfer entered with the wrong quantity. */
    ENTRY_ERROR,
    /** The previous count was wrong. */
    PREVIOUS_COUNT_ERROR,
    /** Missing for no known reason (possible theft or loss). */
    MISSING,
    OTHER,
}

data class CountEntry(val itemId: GlobalId, val counted: Quantity, val note: String = "")

data class CountedLine(
    val itemId: GlobalId,
    val counted: Quantity,
    /** Book quantity when the count was recorded; the difference is measured against it. */
    val bookAtCount: Quantity,
    val note: String = "",
    val reason: VarianceReason? = null,
    val reasonNote: String = "",
    /** Set when approved: the value of the correction (+ gain, − loss). */
    val postedValue: Long? = null,
) {
    /** + more on the shelf than in the book, − missing. */
    val difference: Long get() = counted.micros - bookAtCount.micros
}

data class StockCount(
    val id: GlobalId,
    val scope: Scope.Branch,
    val locationId: GlobalId,
    val date: BusinessDate,
    val lines: List<CountedLine>,
    val note: String,
    val status: CountStatus,
    val countedBy: GlobalId,
    val countedByName: String,
    val countedAt: Long,
    val reviewedBy: GlobalId? = null,
    val reviewedByName: String? = null,
    val reviewedAt: Long? = null,
    val rejectReason: String? = null,
) {
    val differences: List<CountedLine> get() = lines.filter { it.difference != 0L }
}

data class LineReason(val reason: VarianceReason, val note: String = "")

interface StockCountStore {
    fun byId(id: GlobalId): StockCount?
    fun all(): List<StockCount>
    fun save(count: StockCount)
}

/** What was counted. Nothing in the stock or the books changes yet. */
data class SubmitStockCount(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val locationId: GlobalId,
    val date: BusinessDate,
    val lines: List<CountEntry>,
    val note: String = "",
) : Command {
    override val requiredPermission = Permission.INVENTORY_COUNT
    override fun fingerprint() = "$scope|$locationId|${date.epochDay}|$note|" + lines.joinToString(";") { "${it.itemId}:${it.counted.micros}:${it.note}" }
}

/** Accepts a count: every difference needs a reason ([reasons] by item); the stock and the books are corrected. */
data class ApproveStockCount(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val countId: GlobalId,
    val reasons: Map<GlobalId, LineReason>,
) : Command {
    override val requiredPermission = Permission.INVENTORY_ADJUST
    override fun fingerprint() = "$scope|$countId|" + reasons.entries.sortedBy { it.key.value.toString() }.joinToString(";") { "${it.key}:${it.value.reason}:${it.value.note}" }
}

/** Sends a count back (e.g. to be counted again); nothing changes and the reason is kept. */
data class RejectStockCount(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val countId: GlobalId,
    val reason: String,
) : Command {
    override val requiredPermission = Permission.INVENTORY_ADJUST
    override fun fingerprint() = "$scope|$countId|$reason"
}

class StockCountOperations(
    private val bus: CommandBus,
    private val gateway: InventoryGateway,
    private val counts: StockCountStore,
) {
    private val cap get() = gateway.ownCapability

    fun submit(c: SubmitStockCount): CommandOutcome = bus.execute(ModuleId.INVENTORY, c) { cmd, ctx ->
        val location = gateway.location(cmd.locationId)
        ensure(location.scope == cmd.scope) { DomainError.InvalidInput("scope", "انبار متعلق به این شعبه نیست.") }
        ensure(cmd.lines.isNotEmpty() && cmd.lines.map { it.itemId }.distinct().size == cmd.lines.size) {
            DomainError.InvalidInput("lines", "هر کالا فقط یک‌بار شمارش شود.")
        }
        ensure(cmd.note.length <= 500 && cmd.lines.all { it.note.length <= 200 }) { DomainError.InvalidInput("note", "توضیح طولانی است.") }
        // One open count per location: a second one would measure against a book the first may still change.
        ensure(counts.all().none { it.locationId == location.id && it.status == CountStatus.PENDING }) { DomainError.InvalidState("STOCK_COUNT", "PENDING_EXISTS") }
        val lines = cmd.lines.map { e ->
            gateway.item(e.itemId)
            CountedLine(e.itemId, e.counted, gateway.balance(e.itemId, location.id).quantity, e.note.trim())
        }
        val count = StockCount(GlobalId.new(), cmd.scope, location.id, cmd.date, lines, cmd.note.trim(), CountStatus.PENDING,
            ctx.actor.userId, ctx.actor.displayName, ctx.nowEpochMillis)
        counts.save(count)
        ctx.audit(AuditDraft("STOCK_COUNT_SUBMIT", "STOCK_COUNT", count.id.value, "location=${location.id};lines=${lines.size};differences=${count.differences.size}"))
        count.id
    }

    fun approve(c: ApproveStockCount): CommandOutcome = bus.execute(ModuleId.INVENTORY, c) { cmd, ctx ->
        val count = pending(cmd.countId, cmd.scope)
        requireOtherPerson(count, ctx.actor.userId, ctx.actor.role)
        val location = gateway.location(count.locationId)
        val lines = count.lines.map { line ->
            if (line.difference == 0L) return@map line
            val r = cmd.reasons[line.itemId] ?: throw DomainException(DomainError.InvalidInput("reason", "برای هر اختلاف دلیل لازم است."))
            ensure(r.reason != VarianceReason.OTHER || r.note.trim().length >= 3) { DomainError.InvalidInput("reasonNote", "برای «سایر» توضیح لازم است.") }
            ensure(r.note.length <= 200) { DomainError.InvalidInput("reasonNote", "توضیح طولانی است.") }
            line.copy(reason = r.reason, reasonNote = r.note.trim())
        }
        val source = SourceDocument(ModuleId.INVENTORY, InventoryOperations.COUNT, count.id)
        // The difference measured at count time is applied now, valued at today's average cost, so
        // sales and receipts recorded between counting and approval are not undone.
        val posted = lines.map { line ->
            if (line.difference == 0L) return@map line
            val book = gateway.balance(line.itemId, location.id)
            val memo = "${reasonName(line.reason!!)}${if (line.reasonNote.isBlank()) "" else " · ${line.reasonNote}"}"
            val value = if (line.difference < 0) {
                val loss = Quantity.of(-line.difference)
                val v = gateway.valueOf(book, loss)
                val journal = if (v.isZero) null else gateway.postOwn(ctx, JournalDraft(count.date, location.scope, InventoryOperations.COUNT, count.id, "کسری انبارگردانی", listOf(
                    LineDraft(StandardAccounts.INVENTORY_VARIANCE, debit = v, memo = memo, by = cap),
                    LineDraft(StandardAccounts.INVENTORY, credit = v, memo = location.name, by = cap),
                )))
                gateway.stockOut(ctx, line.itemId, location, loss, v, MovementKind.COUNT_LOSS, count.date, journal?.id, source, null)
                -v.rial
            } else {
                // A gain is valued at the current average cost; with no stock left there is no reliable cost.
                val gain = Quantity.of(line.difference)
                val v = if (book.quantity.isZero) Money.ZERO else Money.of(ir.sabou.kernel.Ratio.mulDiv(book.value.rial, gain.micros, book.quantity.micros))
                val journal = if (v.isZero) null else gateway.postOwn(ctx, JournalDraft(count.date, location.scope, InventoryOperations.COUNT, count.id, "اضافه انبارگردانی", listOf(
                    LineDraft(StandardAccounts.INVENTORY, debit = v, memo = location.name, by = cap),
                    LineDraft(StandardAccounts.INVENTORY_VARIANCE, credit = v, memo = memo, by = cap),
                )))
                gateway.stockIn(ctx, line.itemId, location, gain, v, MovementKind.COUNT_GAIN, count.date, journal?.id, source, null)
                v.rial
            }
            line.copy(postedValue = value)
        }
        counts.save(count.copy(lines = posted, status = CountStatus.POSTED, reviewedBy = ctx.actor.userId, reviewedByName = ctx.actor.displayName, reviewedAt = ctx.nowEpochMillis))
        ctx.audit(AuditDraft("STOCK_COUNT_APPROVE", "STOCK_COUNT", count.id.value,
            "differences=${count.differences.size};value=${posted.sumOf { it.postedValue ?: 0 }};" + posted.filter { it.reason != null }.joinToString(",") { "${it.itemId}:${it.reason}" }))
        count.id
    }

    fun reject(c: RejectStockCount): CommandOutcome = bus.execute(ModuleId.INVENTORY, c) { cmd, ctx ->
        val count = pending(cmd.countId, cmd.scope)
        ensure(cmd.reason.trim().length in 3..300) { DomainError.InvalidInput("reason", "دلیل رد الزامی است.") }
        counts.save(count.copy(status = CountStatus.REJECTED, reviewedBy = ctx.actor.userId, reviewedByName = ctx.actor.displayName,
            reviewedAt = ctx.nowEpochMillis, rejectReason = cmd.reason.trim()))
        ctx.audit(AuditDraft("STOCK_COUNT_REJECT", "STOCK_COUNT", count.id.value, cmd.reason.trim()))
        count.id
    }

    private fun pending(id: GlobalId, scope: Scope.Branch): StockCount {
        val count = counts.byId(id) ?: throw DomainException(DomainError.NotFound("STOCK_COUNT"))
        ensure(count.scope == scope) { DomainError.InvalidInput("scope", "این شمارش متعلق به این شعبه نیست.") }
        ensure(count.status == CountStatus.PENDING) { DomainError.InvalidState("STOCK_COUNT", count.status.name) }
        return count
    }

    /** Whoever counted does not approve their own differences (the owner of a one-person shop excepted). */
    private fun requireOtherPerson(count: StockCount, actor: GlobalId, role: Role) {
        ensure(count.countedBy != actor || role == Role.OWNER) { DomainError.InvalidState("STOCK_COUNT", "SAME_PERSON") }
    }

    companion object {
        fun reasonName(r: VarianceReason) = when (r) {
            VarianceReason.UNRECORDED_WASTE -> "ضایعات ثبت‌نشده"
            VarianceReason.UNRECORDED_USE -> "مصرف ثبت‌نشده"
            VarianceReason.ENTRY_ERROR -> "اشتباه در ثبت خرید یا انتقال"
            VarianceReason.PREVIOUS_COUNT_ERROR -> "اشتباه انبارگردانی قبلی"
            VarianceReason.MISSING -> "مفقودی بدون دلیل روشن"
            VarianceReason.OTHER -> "سایر"
        }
    }
}
