package ir.sabou.core

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Scope
import ir.sabou.platform.Permission

/**
 * Something a person should act on, announced on their phone (ADR-0024). [key] names the situation, not the
 * moment: the same low stock keeps the same key until it is resolved, so it is announced once — and again
 * only if it comes back.
 */
data class Alert(val key: String, val kind: Kind, val title: String, val text: String) {
    enum class Kind { LOW_STOCK, PRICE_CHANGE, CHEQUE_DUE, SHORTAGE, APPROVAL, FOOD_COST, INVOICE_MATCH, EXPIRY, COUNT_DUE, STAFF_DOCUMENT, TRANSFER_IN, STOCK_REQUEST, BILL_DUE, RECEIVABLE_LATE, CASH_LOW, RUN_OUT,
        /** A critical or high finding of the scorecard (ADR-0062). */
        FINDING,
        /** Saturday: last week's scorecard in one line. */
        WEEKLY_REPORT }
}

/**
 * The alerts of one user, computed with that user's own permissions and branches through the ordinary read
 * models (as a read-only stand-in on the calling thread; commands are refused meanwhile). Used by the phone's
 * background check, so nobody needs to be signed in.
 */
class Alerts internal constructor(private val core: SabouCore) {
    fun forUser(userId: GlobalId, today: BusinessDate): List<Alert> {
        val user = core.users.byId(userId)?.takeIf { it.isActive } ?: return emptyList()
        val actor = user.toActor()
        return core.session.readAs(actor) {
            buildList {
                if (actor.role.allows(Permission.PERSONNEL_MANAGE)) {
                    core.branches.all().filter { actor.canAccess(Scope.Branch(it.id)) }.forEach { b ->
                        safe { core.labor.documentIssues(Scope.Branch(b.id), today) }.filter { it.issue.expired || (it.issue.daysLeft ?: 99) <= STAFF_DOC_DAYS }.forEach { v ->
                            add(Alert("STAFFDOC:${v.issue.document?.id?.value ?: v.employee.id.value}:${if (v.issue.expired) "expired" else "soon"}", Alert.Kind.STAFF_DOCUMENT,
                                "${staffDocName(v.issue.kind)} ${v.employee.name} " + if (v.issue.expired) "منقضی شده" else "رو به پایان است",
                                "اعتبار تا ${v.issue.document?.expiresOn?.let(Fa::date) ?: "—"}؛ مدرک تازه را در «کارکنان» ثبت کنید."))
                        }
                    }
                }
                if (actor.role.allows(Permission.INVENTORY_COUNT)) {
                    core.branches.all().filter { actor.canAccess(Scope.Branch(it.id)) }.forEach { b ->
                        safe { core.overview.countsDue(Scope.Branch(b.id), today) }.filter { it.due }.forEach { d ->
                            add(Alert("COUNT:${d.plan.id.value}:${d.periodStart.epochDay}", Alert.Kind.COUNT_DUE, "شمارش «${d.plan.name}» مانده است",
                                (d.lastCount?.let { "آخرین شمارش ${Fa.date(it)}" } ?: "هنوز شمرده نشده") + " · از ${Fa.date(d.periodStart)} موعد شمارش است."))
                        }
                    }
                }
                if (actor.role.allows(Permission.INVENTORY_VIEW)) {
                    safe { core.overview.lowStock() }.forEach { low ->
                        add(Alert("LOW:${low.item.id.value}:${low.location.id.value}", Alert.Kind.LOW_STOCK, "کمبود «${low.item.name}»",
                            "${low.location.name}: ${Fa.quantity(low.quantity)} مانده، کمتر از حداقل ${Fa.quantity(low.item.minimumAt(low.location.id))}"))
                    }
                    core.branches.all().filter { actor.canAccess(Scope.Branch(it.id)) }.forEach { b ->
                        safe { core.overview.openShortages(Scope.Branch(b.id)) }.forEach { v ->
                            add(Alert("SHORT:${v.shortage.id.value}", Alert.Kind.SHORTAGE, "کسری مصرف «${v.item.name}»",
                                "فروش ${Fa.quantity(v.shortage.open)} بیش از موجودی دفتری ${v.location.name} مصرف کرد؛ با ثبت خرید یا انبارگردانی بسته می‌شود."))
                        }
                    }
                }
                if (actor.role.allows(Permission.INVENTORY_VIEW)) {
                    core.branches.all().filter { actor.canAccess(Scope.Branch(it.id)) }.forEach { b ->
                        safe { core.overview.lots(Scope.Branch(b.id), today, EXPIRY_DAYS) }.forEach { v ->
                            val left = v.lot.daysLeft(today)
                            val state = when { left < 0 -> "expired"; left == 0L -> "today"; else -> "soon" }
                            add(Alert("EXPIRY:${v.lot.id.value}:$state", Alert.Kind.EXPIRY,
                                when { left < 0 -> "«${v.item.name}» تاریخ گذشته است"; left == 0L -> "«${v.item.name}» امروز منقضی می‌شود"; else -> "«${v.item.name}» فردا منقضی می‌شود" },
                                "${Fa.quantity(v.lot.remaining)} در ${v.location.name} · انقضا ${Fa.date(v.lot.expiresOn)}. اول همین را مصرف کنید یا ضایعات ثبت کنید."))
                        }
                    }
                }
                if (actor.role.allows(Permission.INVENTORY_TRANSFER)) {
                    core.branches.all().filter { actor.canAccess(Scope.Branch(it.id)) }.forEach { b ->
                        safe { core.stockRecords.transits(Scope.Branch(b.id)) }.filter { it.incoming && it.transit.status == ir.sabou.inventory.StockTransit.Status.IN_TRANSIT }.forEach { v ->
                            add(Alert("TRANSIT:${v.transit.id.value}", Alert.Kind.TRANSFER_IN, "کالا از ${v.from} در راه است",
                                "${v.lines.size} قلم، ارسال ${Fa.date(v.transit.date)}. با رسیدن کالا آن را در «انتقال بین شعب» تحویل بگیرید."))
                        }
                        safe { core.stockRecords.requests(Scope.Branch(b.id)) }.filter { it.toSend && it.request.status == ir.sabou.inventory.StockRequest.Status.OPEN }.forEach { v ->
                            add(Alert("REQUEST:${v.request.id.value}", Alert.Kind.STOCK_REQUEST, "درخواست کالا برای ${v.to}",
                                "${v.request.requestedBy}: ${v.lines.size} قلم از ${v.from}"))
                        }
                    }
                }
                if (actor.role.allows(Permission.PURCHASE_PAY)) {
                    // Supplier invoices due by the day after tomorrow (or past due), approved and still unpaid.
                    safe { core.overview.invoices() }.filter { it.invoice.status == ir.sabou.purchasing.InvoiceStatus.POSTED && !it.outstanding.isZero && it.invoice.dueDate <= today.plusDays(BILL_DAYS) }
                        .forEach { r ->
                            val late = r.invoice.dueDate < today
                            add(Alert("BILL:${r.invoice.id.value}:${if (late) "late" else "due"}", Alert.Kind.BILL_DUE,
                                "${if (late) "سررسید گذشته" else "سررسید نزدیک"}: فاکتور ${r.supplier}",
                                "${Fa.rial(r.outstanding)} ریال · سررسید ${Fa.date(r.invoice.dueDate)}" + if (!r.invoice.approved) " · هنوز تأیید نشده" else ""))
                        }
                }
                if (actor.role.allows(Permission.LEDGER_VIEW)) {
                    // Cash forecast (ADR-0051): the base case ends a week below zero within the next weeks. Only once
                    // there are sales to forecast from: without them the forecast sees the bills but none of the
                    // income, and the bills have their own alert (BILL_DUE).
                    core.branches.all().filter { actor.canAccess(Scope.Branch(it.id)) }.forEach { b ->
                        safe { listOf(core.cashFlows.forecast(Scope.Branch(b.id), today, weeks = CashFlows.ALERT_WEEKS)) }.firstOrNull()
                            ?.takeIf { it.assumptions.liquidBp != null }?.firstShortWeek?.let { w ->
                            add(Alert("CASH:${b.id.value}:${w.from.epochDay}", Alert.Kind.CASH_LOW, "کمبود نقدینگی در هفته‌ی ${Fa.number(w.index.toLong())}",
                                "${b.name}: مانده‌ی پیش‌بینی‌شده تا ${Fa.date(w.to)} به ${Fa.rial(w.closing)} ریال می‌رسد؛ «پیش‌بینی نقدینگی» را ببینید."))
                        }
                    }
                }
                if (actor.role.allows(Permission.LEDGER_VIEW)) {
                    // ADR-0062: the scorecard's critical and high findings over the last four weeks, those no other
                    // alert announces already; and on Saturdays, last week's card in one line.
                    core.branches.all().filter { actor.canAccess(Scope.Branch(it.id)) }.forEach { b ->
                        val branch = Scope.Branch(b.id)
                        findings(user.id, branch, today).forEach { add(it) }
                        if (ir.sabou.purchasing.Weekdays.index(today) == 0) {
                            safe { listOf(core.assessments.of(branch, today.plusDays(-7), today.plusDays(-1))) }.firstOrNull()
                                ?.takeIf { a -> a.kpis.any { it.id == "D18.DAYS" && it.value != null } }?.let { a ->   // a week with sales
                                add(Alert("WEEKLY:${b.id.value}:${today.epochDay}", Alert.Kind.WEEKLY_REPORT, "کارنامه‌ی هفته‌ی گذشته: ${b.name}",
                                    (a.score?.let { "امتیاز ${Fa.number(it.toLong())} از ۱۰۰" } ?: "داده‌ی کافی برای امتیاز نیست") +
                                        " · ${Fa.number(a.findings.size.toLong())} عارضه" + (a.findings.firstOrNull()?.let { "؛ مهم‌ترین: ${it.title}" } ?: "") +
                                        ". «کارنامه و عارضه‌یابی» را ببینید."))
                            }
                        }
                    }
                }
                if (actor.role.allows(Permission.PURCHASE_ORDER) || actor.role.allows(Permission.PURCHASE_RECORD)) {
                    // ADR-0054: what the expected dishes use up before the next delivery.
                    core.branches.all().filter { actor.canAccess(Scope.Branch(it.id)) }.forEach { b ->
                        safe { core.buying.runOuts(Scope.Branch(b.id), today, 0) }.forEach { r ->
                            add(Alert("RUNOUT:${r.item.id.value}:${b.id.value}:${r.runsOutOn.epochDay}", Alert.Kind.RUN_OUT, "«${r.item.name}» تا ${Fa.dayTitle(r.runsOutOn)} تمام می‌شود",
                                "نیاز پیش‌بینی‌شده ${Fa.quantity(r.need)} ${r.delivery?.let { "تا تحویل ${Fa.dayTitle(it)}" } ?: "در ۷ روز آینده"}؛ موجودی ${Fa.quantity(r.onHand)}" +
                                    (if (!r.onOrder.isZero) " و در راه ${Fa.quantity(r.onOrder)}" else "") + (r.supplier?.let { " · $it" } ?: "")))
                        }
                    }
                }
                if (actor.role.allows(Permission.RECEIVABLE_COLLECT)) {
                    safe { core.overview.openReceivables() }.filter { it.receivable.dueDate < today }.forEach { r ->
                        add(Alert("LATE:${r.receivable.id.value}", Alert.Kind.RECEIVABLE_LATE, "طلب سررسیدگذشته: ${r.customer}",
                            "${Fa.rial(r.outstanding)} ریال · سررسید ${Fa.date(r.receivable.dueDate)}"))
                    }
                }
                if (actor.role.allows(Permission.PURCHASE_VIEW)) {
                    safe { core.buying.priceChanges(today.plusDays(-PRICE_DAYS), today, PRICE_THRESHOLD_BP) }.forEach { c ->
                        val bp = c.changeBp
                        add(Alert("PRICE:${c.invoiceId.value}:${c.item.id.value}", Alert.Kind.PRICE_CHANGE,
                            "${if ((bp ?: 1) > 0) "گران‌تر" else "ارزان‌تر"}: «${c.item.name}» ${bp?.let { (if (it > 0) "+" else "−") + Fa.percent(kotlin.math.abs(it)) } ?: ""}".trim(),
                            "${c.supplier}: ${Fa.rial(c.price)} ریال؛ قبلی ${Fa.rial(c.previousPrice)} از ${c.previousSupplier}"))
                    }
                }
                if (actor.role.allows(Permission.TREASURY_VIEW) || actor.role.allows(Permission.CHEQUE_MANAGE)) {
                    safe { core.books.chequesDue(today, CHEQUE_DAYS) }.forEach { r ->
                        val c = r.cheque
                        val overdue = c.dueDate < today
                        // The key changes when a due cheque becomes overdue, so that step is announced too.
                        add(Alert("CHEQUE:${c.id.value}:${if (overdue) "late" else "due"}", Alert.Kind.CHEQUE_DUE,
                            "${if (c.direction == ir.sabou.treasury.ChequeDirection.RECEIVED) "چک دریافتی" else "چک پرداختی"} ${if (overdue) "سررسید گذشته" else "نزدیک سررسید"}",
                            "${Fa.rial(c.amount)} ریال · ${c.details.counterparty} · ${Fa.date(c.dueDate)}"))
                    }
                }
                // A dish whose recipe now costs more than the target share of its price (purchase prices moved).
                val target = core.salesOps.foodCostTarget()
                if (target != null && (actor.role.allows(Permission.MENU_PRICE_MANAGE) || actor.role.allows(Permission.RECIPE_MANAGE))) {
                    core.branches.all().filter { actor.canAccess(Scope.Branch(it.id)) }.forEach { b ->
                        safe { core.reports.foodCosts(Scope.Branch(b.id), today) }.filter { it.costBp >= target }.forEach { r ->
                            add(Alert("FOODCOST:${b.id.value.value}:${r.item.id.value}", Alert.Kind.FOOD_COST, "بهای مواد «${r.item.name}» ${Fa.percent(r.costBp)} قیمت",
                                "هدف ${Fa.percent(target)} است: بهای مواد ${Fa.rial(r.cost)} و قیمت منو ${Fa.rial(r.price)} ریال. قیمت یا رسپی را بازبینی کنید."))
                        }
                    }
                }
                if (actor.role.allows(Permission.PURCHASE_APPROVE)) {
                    safe { core.books.pendingApprovals() }.filter { it.approvedBy.none { n -> n == actor.displayName } }.forEach { a ->
                        add(Alert("APPROVE:${a.invoice.invoice.id.value}", Alert.Kind.APPROVAL, "فاکتور در انتظار تأیید",
                            "${a.invoice.supplier} · ${Fa.rial(a.invoice.invoice.total)} ریال"))
                    }
                    safe { core.books.matchReviews() }.filter { it.invoice.recordedBy != actor.userId || actor.role == ir.sabou.platform.Role.OWNER }.forEach { r ->
                        val duplicate = r.invoice.discrepancies.any { it.kind == ir.sabou.purchasing.Discrepancy.Kind.POSSIBLE_DUPLICATE }
                        add(Alert("MATCH:${r.invoice.id.value}", Alert.Kind.INVOICE_MATCH,
                            if (duplicate) "فاکتور شاید تکراری است" else "فاکتور با سفارش جور نیست",
                            "${r.supplier} · ${Fa.rial(r.invoice.total)} ریال · تا بررسی، پرداخت نمی‌شود"))
                    }
                }
            }
        }
    }

    /**
     * The scorecard's findings as alerts. The card reads every report, so it is worked out at most once per
     * [FINDINGS_EVERY_MS] for a user, branch and day; the alert check runs hourly and after each save.
     */
    private val findingCache = HashMap<Triple<GlobalId, ir.sabou.kernel.BranchId, Long>, Pair<Long, List<Alert>>>()

    private fun findings(userId: GlobalId, branch: Scope.Branch, today: BusinessDate): List<Alert> {
        val key = Triple(userId, branch.branchId, today.epochDay)
        val now = System.nanoTime() / 1_000_000
        synchronized(findingCache) { findingCache[key]?.takeIf { now - it.first < FINDINGS_EVERY_MS }?.let { return it.second } }
        val a = try { core.assessments.of(branch, today.plusDays(-27), today) } catch (e: DomainException) { return emptyList() }
        val alerts = a.findings.filter { (it.severity == Finding.Severity.CRITICAL || it.severity == Finding.Severity.HIGH) && it.key !in ANNOUNCED_ELSEWHERE }.map { f ->
            Alert("FINDING:${branch.branchId.value.value}:${f.key}", Alert.Kind.FINDING, "${f.severity.title}: ${f.title}",
                f.action + (f.exposureHigh?.let { " · مبلغ در معرض ${Fa.rial(it)} ریال" } ?: ""))
        }
        synchronized(findingCache) { findingCache.keys.removeAll { it.third != today.epochDay }; findingCache[key] = now to alerts }
        return alerts
    }

    /** A read this role may not do (or a branch it cannot see) simply yields nothing. */
    private fun <T> safe(block: () -> List<T>): List<T> = try { block() } catch (e: DomainException) { emptyList() }

    companion object {
        const val PRICE_DAYS = 2L
        const val PRICE_THRESHOLD_BP = 1_000L
        const val CHEQUE_DAYS = 2L
        const val BILL_DAYS = 2L
        /** Goods expiring by tomorrow (and those past their date) are alerted. */
        const val EXPIRY_DAYS = 1L
        /** Staff documents are alerted two weeks ahead (renewing a health card takes days). */
        const val STAFF_DOC_DAYS = 14L
        const val FINDINGS_EVERY_MS = 30 * 60_000L

        /** Findings that have an alert of their own (health cards, cash, receivables, expiry, shortages). */
        val ANNOUNCED_ELSEWHERE = setOf("HEALTH_CARDS", "CASH_SHORT", "RECEIVABLES_LATE", "EXPIRED_STOCK", "SHORTAGES")

        fun staffDocName(kind: ir.sabou.payroll.StaffDocument.Kind) = when (kind) {
            ir.sabou.payroll.StaffDocument.Kind.HEALTH_CARD -> "کارت بهداشت"
            ir.sabou.payroll.StaffDocument.Kind.CONTRACT -> "قرارداد"
            ir.sabou.payroll.StaffDocument.Kind.ID_CARD -> "کارت شناسایی"
            ir.sabou.payroll.StaffDocument.Kind.INSURANCE -> "سابقه‌ی بیمه"
            ir.sabou.payroll.StaffDocument.Kind.GUARANTEE -> "ضمانت"
            ir.sabou.payroll.StaffDocument.Kind.OTHER -> "مدرک"
        }
    }
}
