package ir.sabou.core

import ir.sabou.kernel.sumOfExact
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.JalaliCalendar
import ir.sabou.kernel.Scope
import ir.sabou.ledger.ClosePeriod
import ir.sabou.ledger.PeriodLock
import ir.sabou.ledger.ReopenPeriod
import ir.sabou.ledger.StandardAccounts
import ir.sabou.platform.Actor
import ir.sabou.platform.CommandOutcome
import ir.sabou.platform.Permission
import ir.sabou.purchasing.InvoiceStatus
import ir.sabou.sales.SaleStatus
import ir.sabou.treasury.ChequeDirection
import ir.sabou.treasury.ChequeStatus
import ir.sabou.treasury.TreasuryKind

/**
 * One item of the month-end checklist (ADR-0036). [BLOCK] items must be done before the month can be closed;
 * [WARN] items are shown so they are not forgotten, and the month may be closed with them.
 */
data class CloseCheck(val key: String, val severity: Severity, val title: String, val detail: String) {
    enum class Severity { OK, WARN, BLOCK }
}

data class MonthClose(val year: Int, val month: Int, val from: BusinessDate, val to: BusinessDate, val checks: List<CloseCheck>, val lock: PeriodLock?) {
    val closed: Boolean get() = lock?.closed == true
    val blocked: Boolean get() = checks.any { it.severity == CloseCheck.Severity.BLOCK }
}

/** The month-end close: a checklist across the books, then the period lock (ADR-0036). */
class Closing internal constructor(private val core: SabouCore) {
    private fun actor(vararg anyOf: Permission): Actor {
        val a = core.session.currentActor() ?: throw DomainException(DomainError.AuthenticationRequired)
        if (anyOf.none { a.role.allows(it) }) throw DomainException(DomainError.PermissionDenied(anyOf.first().name))
        return a
    }

    /**
     * The checklist covers every branch, because closing a month locks the whole organization's books. So only a
     * person who may close months AND sees every branch reads it (audit F02): a branch manager would otherwise learn
     * other branches' stores, banks and figures from it.
     */
    fun checklist(year: Int, month: Int): MonthClose {
        val a = actor(Permission.PERIOD_CLOSE)
        val unseen = core.branches.all().filter { !a.canAccess(Scope.Branch(it.id)) }
        if (unseen.isNotEmpty() || !a.canAccess(Scope.Organization)) {
            throw DomainException(DomainError.InvalidState("PERIOD", "NEEDS_ALL_BRANCHES"))
        }
        val from = JalaliCalendar.date(year, month, 1)
        val to = JalaliCalendar.date(year, month, JalaliCalendar.monthLength(year, month))
        val checks = ArrayList<CloseCheck>()
        fun add(key: String, problem: Boolean, severity: CloseCheck.Severity, title: String, detail: String, ok: String) {
            checks += if (problem) CloseCheck(key, severity, title, detail) else CloseCheck(key, CloseCheck.Severity.OK, title, ok)
        }
        val branches = core.branches.all().map { Scope.Branch(it.id) }
        val days = (from.epochDay..to.epochDay).map { BusinessDate(it) }

        // Sales: every day with sales closed, no drafts left.
        val openDays = ArrayList<String>(); var drafts = 0
        branches.forEach { b ->
            days.forEach { d ->
                val sales = core.sales.salesOn(b, d)
                drafts += sales.count { it.status == SaleStatus.DRAFT }
                if (sales.any { it.status == SaleStatus.POSTED } && core.sales.day(b, d)?.closed != true) openDays += Fa.date(d)
            }
        }
        add("SALES_DAYS", openDays.isNotEmpty(), CloseCheck.Severity.BLOCK, "بستن روزهای فروش",
            "${Fa.number(openDays.size.toLong())} روز با فروش بسته نشده: ${openDays.take(3).joinToString("، ")}", "همه‌ی روزهای فروش بسته شده‌اند.")
        add("SALE_DRAFTS", drafts > 0, CloseCheck.Severity.BLOCK, "پیش‌نویس‌های فروش", "${Fa.number(drafts.toLong())} پیش‌نویس ثبت نهایی یا حذف نشده است.", "پیش‌نویسی نمانده است.")

        // Purchasing: unknown lines still in 1302, invoices whose differences nobody reviewed.
        val invoices = core.purchases.invoices().filter { it.status == InvoiceStatus.POSTED && it.date <= to }
        val review = invoices.sumOf { inv -> inv.reviewLines.count { it.resolution == null } }
        add("REVIEW_QUEUE", review > 0, CloseCheck.Severity.BLOCK, "ردیف‌های فاکتور در انتظار بررسی",
            "${Fa.number(review.toLong())} ردیف هنوز به کالا یا هزینه وصل نشده (حساب ۱۳۰۲ صفر نیست).", "صف بررسی خالی است.")
        val matches = invoices.count { it.needsMatchReview }
        add("INVOICE_MATCH", matches > 0, CloseCheck.Severity.WARN, "مغایرت فاکتور با سفارش", "${Fa.number(matches.toLong())} فاکتور بررسی نشده است.", "مغایرتی در انتظار نیست.")

        // Inventory: pending counts, open shortages, at least one approved count per store.
        val pendingCounts = core.stockCounts.all().count { it.status == ir.sabou.inventory.CountStatus.PENDING && it.date <= to }
        add("PENDING_COUNTS", pendingCounts > 0, CloseCheck.Severity.WARN, "انبارگردانی در انتظار تأیید", "${Fa.number(pendingCounts.toLong())} شمارش تأیید یا رد نشده است.", "شمارشی در انتظار نیست.")
        val locations = core.locations.all().filter { it.isActive }
        val shortages = core.inventoryGateway.openShortages(locations.map { it.id }.toSet()).count { it.date <= to }
        // A blocker (audit SABOU-AUDIT-003): once the month is closed, a shortage settled later can no longer take its
        // cost back into it, so the month's food cost would stay short for good.
        add("SHORTAGES", shortages > 0, CloseCheck.Severity.BLOCK, "کسری مصرف باز",
            "${Fa.number(shortages.toLong())} کسری با خرید یا انبارگردانی بسته نشده است؛ فاکتورهای خرید ماه را ثبت کنید یا انبارگردانی کنید، وگرنه بهای مواد این ماه کمتر از واقع می‌ماند.",
            "کسری بازی نیست.")
        val stocked = locations.filter { loc -> core.stock.balances(loc.id).any { !it.quantity.isZero } }
        val counted = core.stockCounts.all().filter { it.status == ir.sabou.inventory.CountStatus.POSTED && it.date in from..to }.map { it.locationId }.toSet()
        val uncounted = stocked.filter { it.id !in counted }
        add("MONTH_COUNT", uncounted.isNotEmpty(), CloseCheck.Severity.WARN, "انبارگردانی ماه",
            "در این ماه شمارشی تأیید نشده: ${uncounted.take(3).joinToString("، ") { it.name }}", "هر انبار دست‌کم یک شمارش تأییدشده دارد.")

        // Payroll and depreciation for the month.
        val unpaidBranches = branches.filter { b ->
            core.personnel.employees(b).any { it.employedDays(from, to) > 0 } &&
                core.payrollStore.runs(b).none { it.status == ir.sabou.payroll.RunStatus.APPROVED && it.from <= to && it.to >= to }
        }
        add("PAYROLL", unpaidBranches.isNotEmpty(), CloseCheck.Severity.WARN, "حقوق ماه",
            "حقوق ${unpaidBranches.size} شعبه برای پایان این ماه تأیید نشده است.", "حقوق ماه تأیید شده است.")
        val assetBranches = core.assetStore.all().filter { it.status == ir.sabou.assets.AssetStatus.ACTIVE && it.acquiredOn <= to }.map { it.scope }.toSet()
        // A reversed run no longer counts (audit F08): its depreciation was taken back.
        val notDepreciated = assetBranches.filter { b -> core.assetStore.runs().none { it.scope == b && !it.reversed && it.through >= to } }
        add("DEPRECIATION", notDepreciated.isNotEmpty(), CloseCheck.Severity.WARN, "استهلاک دارایی‌ها",
            "استهلاک ${notDepreciated.size} شعبه تا پایان ماه ثبت نشده است.", "استهلاک تا پایان ماه ثبت شده است.")

        // Treasury: bank statements reconciled through month-end, cheques past due.
        val banks = core.treasuryAccounts.all().filter { it.kind == TreasuryKind.BANK && it.isActive }
        val unreconciled = banks.filter { bank ->
            val last = core.bankStatementStore.of(bank.id).maxByOrNull { it.to }
            // A role that may see the checklist but not reconcile banks counts it as not reconciled, rather than failing.
            last == null || last.to < to || runCatching { core.bankRec.reconciliation(last.id) }.getOrNull()
                ?.let { r -> r.statementOnly.isNotEmpty() || (r.unexplained ?: 0L) != 0L } ?: true
        }
        add("BANK_REC", unreconciled.isNotEmpty(), CloseCheck.Severity.WARN, "مغایرت بانکی",
            "صورت‌حساب ${unreconciled.joinToString("، ") { it.name }} تا پایان ماه وارد یا کامل تطبیق نشده است.", "همه‌ی حساب‌های بانکی تا پایان ماه تطبیق شده‌اند.")
        val overdue = core.cheques.all().count { c ->
            c.dueDate <= to && ((c.direction == ChequeDirection.RECEIVED && c.status == ChequeStatus.IN_HAND) || (c.direction == ChequeDirection.ISSUED && c.status == ChequeStatus.ISSUED))
        }
        add("CHEQUES", overdue > 0, CloseCheck.Severity.WARN, "چک‌های سررسید گذشته", "${Fa.number(overdue.toLong())} چک سررسید شده و هنوز وصول یا پاس نشده است.", "چک سررسیدگذشته‌ای نیست.")

        // Tax system readiness, once the seller's tax identity has been set up.
        if (core.taxStore.profile() != null) run {
            val readiness = runCatching { core.moadian.readiness(to) }.getOrNull() ?: return@run
            add("MOADIAN", !readiness.ready, CloseCheck.Severity.WARN, "آمادگی سامانه‌ی مودیان", readiness.problems.joinToString(" "), "اطلاعات مالیاتی کامل است.")
        }

        // Integrity: the inter-branch account nets to zero across the organization.
        val interBranch = core.journals.netDebit(StandardAccounts.INTER_BRANCH, null, to)
        add("INTER_BRANCH", interBranch != 0L, CloseCheck.Severity.BLOCK, "حساب جاری بین شعب",
            "جمع حساب بین شعب ${Fa.rial(interBranch)} ریال است و باید صفر باشد.", "حساب بین شعب صفر است.")

        val lock = core.periods.locks().filter { it.from == from && it.to == to }.maxByOrNull { if (it.closed) 1 else 0 }
        return MonthClose(year, month, from, to, checks, lock)
    }

    /** Closes the month when nothing blocks it: no document may then be dated inside it. */
    fun close(commandId: GlobalId, year: Int, month: Int): CommandOutcome =
        close(ClosePeriod(commandId, JalaliCalendar.date(year, month, 1), JalaliCalendar.date(year, month, JalaliCalendar.monthLength(year, month))))

    /**
     * Closes the month [command] spans, after its checklist. This, not the ledger's own entry, is where a closing
     * from the second phone runs on the main phone (ADR-0042), so the checklist is judged on the main phone's books.
     */
    fun close(command: ClosePeriod): CommandOutcome {
        val ymd = JalaliCalendar.of(command.from)
        val c = checklist(ymd.year, ymd.month)
        if (command.from != c.from || command.to != c.to) throw DomainException(DomainError.InvalidInput("period", "فقط یک ماه کامل بسته می‌شود."))
        if (c.closed) throw DomainException(DomainError.InvalidState("PERIOD", "ALREADY_CLOSED"))
        c.checks.firstOrNull { it.severity == CloseCheck.Severity.BLOCK }?.let { throw DomainException(DomainError.InvalidState("PERIOD", "CHECKLIST:${it.key}")) }
        return core.accounting.closePeriod(command)
    }

    fun reopen(commandId: GlobalId, year: Int, month: Int, reason: String): CommandOutcome {
        val c = checklist(year, month)
        val lock = c.lock?.takeIf { it.closed } ?: throw DomainException(DomainError.InvalidState("PERIOD", "NOT_CLOSED"))
        return core.accounting.reopenPeriod(ReopenPeriod(commandId, lock.id, reason))
    }
}
