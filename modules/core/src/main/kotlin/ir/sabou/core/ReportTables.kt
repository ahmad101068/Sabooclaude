package ir.sabou.core

import ir.sabou.inventory.StockUnit
import ir.sabou.kernel.BusinessDate

/** Turns reports into [ReportTable]s (Persian, ready for Excel and PDF). Pure formatting, no access to data. */
object ReportTables {
    private fun period(from: BusinessDate, to: BusinessDate) = if (from == to) Fa.date(from) else "از ${Fa.date(from)} تا ${Fa.date(to)}"
    private fun pct(bp: Long?): Cell = bp?.let { Cell.Percent(it) } ?: Cell.of("—")
    private fun amount(v: Long?): Cell = v?.let { Cell.Amount(it) } ?: Cell.of("—")
    private const val TOMAN = "مبالغ به تومان"

    fun unitName(unit: StockUnit) = when (unit) {
        StockUnit.GRAM -> "گرم"; StockUnit.KILOGRAM -> "کیلوگرم"; StockUnit.MILLILITER -> "میلی‌لیتر"
        StockUnit.LITER -> "لیتر"; StockUnit.PIECE -> "عدد"; StockUnit.PACK -> "بسته"
    }

    fun profitAndLoss(p: ProfitAndLoss, place: String): List<ReportTable> {
        val rows = buildList {
            add(listOf(Cell.of("درآمدها"), Cell.EMPTY, Cell.EMPTY))
            p.revenue.forEach { add(listOf(Cell.of(it.account.code.value), Cell.of(it.account.name), Cell.Amount(it.amount))) }
            add(listOf(Cell.EMPTY, Cell.of("جمع درآمد"), Cell.Amount(p.totals.revenue)))
            add(listOf(Cell.of("بهای تمام‌شده"), Cell.EMPTY, Cell.EMPTY))
            p.costOfSales.forEach { add(listOf(Cell.of(it.account.code.value), Cell.of(it.account.name), Cell.Amount(it.amount))) }
            add(listOf(Cell.EMPTY, Cell.of("سود ناخالص"), Cell.Amount(p.totals.grossProfit)))
            add(listOf(Cell.of("هزینه‌ها"), Cell.EMPTY, Cell.EMPTY))
            p.expenses.forEach { add(listOf(Cell.of(it.account.code.value), Cell.of(it.account.name), Cell.Amount(it.amount))) }
            add(listOf(Cell.EMPTY, Cell.of("جمع هزینه‌ها"), Cell.Amount(p.totals.expenses)))
        }
        val statement = ReportTable(
            "سود و زیان", "$place · ${period(p.from, p.to)}", listOf("کد", "شرح", "مبلغ (تومان)"), rows,
            footer = listOf(Cell.EMPTY, Cell.of("سود (زیان) خالص"), Cell.Amount(p.totals.profit)),
            notes = listOf(
                TOMAN,
                "درصد بهای غذا: ${pct(p.ratios.foodBp).text} · درصد نیروی کار: ${pct(p.ratios.laborBp).text} · بهای اصلی: ${pct(p.ratios.primeBp).text} (نسبت به فروش غذا)",
                "حقوق هر ماه در روز آخر همان ماه ثبت می‌شود؛ در بازه‌های کوتاه درصد نیروی کار را با احتیاط بخوانید.",
            ),
        )
        fun split(title: String, label: String, rows: List<Pair<String, PnlTotals>>) = ReportTable(
            title, "$place · ${period(p.from, p.to)}", listOf(label, "درآمد", "بهای تمام‌شده", "هزینه‌ها", "سود (زیان)"),
            rows.map { (k, t) -> listOf(Cell.of(k), Cell.Amount(t.revenue), Cell.Amount(t.cogs), Cell.Amount(t.expenses), Cell.Amount(t.profit)) },
            footer = listOf(Cell.of("جمع"), Cell.Amount(p.totals.revenue), Cell.Amount(p.totals.cogs), Cell.Amount(p.totals.expenses), Cell.Amount(p.totals.profit)),
            notes = listOf(TOMAN),
        )
        return listOf(
            statement,
            split("سود و زیان شعب", "شعبه", p.byBranch),
            split("سود و زیان روزانه", "روز", p.byDay.map { (d, t) -> Fa.date(d) to t }),
        )
    }

    fun ledgerDetail(accountName: String, rows: List<LedgerDetail>, from: BusinessDate, to: BusinessDate) = ReportTable(
        "گردش $accountName", period(from, to), listOf("تاریخ", "شماره سند", "شرح", "شعبه", "بدهکار", "بستانکار"),
        rows.map { listOf(Cell.of(Fa.date(it.date)), Cell.Count(it.number), Cell.of(it.description + if (it.memo.isBlank()) "" else " · ${it.memo}"), Cell.of(it.scope), Cell.Amount(it.debit), Cell.Amount(it.credit)) },
        footer = listOf(Cell.of("جمع"), Cell.EMPTY, Cell.EMPTY, Cell.EMPTY, Cell.Amount(rows.sumOf { it.debit }), Cell.Amount(rows.sumOf { it.credit })),
        notes = listOf(TOMAN),
    )

    fun productMix(m: ProductMix) = ReportTable(
        "اقلام پرفروش و حاشیه‌ی سود", "${m.branch} · ${period(m.from, m.to)} · ${Fa.number(m.days.toLong())} روز فروش",
        listOf("آیتم منو", "تعداد", "فروش", "سهم از فروش", "میانگین قیمت", "بهای مواد هر پرس", "حاشیه‌ی هر پرس", "درصد بهای غذا"),
        m.rows.map {
            listOf(Cell.of(it.name), Cell.Qty(it.portions.micros), Cell.Amount(it.gross.rial), Cell.Percent(it.shareBp), amount(it.averagePrice?.rial),
                amount(it.unitCost?.rial), amount(it.unitMargin), pct(it.costBp))
        },
        footer = listOf(Cell.of("جمع"), Cell.EMPTY, Cell.Amount(m.gross.rial), Cell.Percent(if (m.gross.isZero) 0 else 10_000), Cell.EMPTY, Cell.EMPTY, Cell.EMPTY, Cell.EMPTY),
        notes = listOf(TOMAN, "بهای مواد با رسپی فعلی و میانگین بهای امروز انبارهای شعبه حساب شده است (اگر موجودی نباشد، آخرین قیمت خرید)."),
    )

    fun dayFlash(f: DayFlash): ReportTable {
        val rows = buildList {
            fun kv(k: String, v: Cell) = add(listOf(Cell.of(k), v))
            kv("وضعیت", Cell.of(if (!f.posted) "فروش این روز ثبت نهایی نشده" else if (f.closed) "روز بسته شده" else "ثبت نهایی شده، روز باز"))
            kv("فروش ناخالص", Cell.Amount(f.gross.rial)); kv("تخفیف", Cell.Amount(f.discount.rial)); kv("حق سرویس", Cell.Amount(f.serviceCharge.rial))
            kv("مالیات و عوارض", Cell.Amount(f.tax.rial)); kv("قابل تسویه", Cell.Amount(f.payable.rial))
            kv("تعداد مهمان", Cell.Count(f.guests.toLong())); kv("تعداد تراکنش", Cell.Count(f.transactions.toLong()))
            kv("میانگین هر مهمان", amount(f.perGuest?.rial)); kv("میانگین هر تراکنش", amount(f.perTransaction?.rial))
            f.settlements.forEach { (name, m) -> kv("دریافتی · $name", Cell.Amount(m.rial)) }
            kv("نسیه", Cell.Amount(f.credit.rial))
            kv("بهای مواد مصرفی", Cell.Amount(f.cost.rial)); kv("درصد بهای غذا", pct(f.foodCostBp))
            f.purchases?.let { kv("خرید امروز", Cell.Amount(it.rial)) }; f.waste?.let { kv("ضایعات امروز", Cell.Amount(it.rial)) }
            kv("نقد فروش (مورد انتظار)", Cell.Amount(f.cashSales.rial))
            kv("نقد شمارش‌شده", amount(f.countedCash?.rial))
            kv("کسر / اضافه صندوق", amount(f.cashDifference))
        }
        return ReportTable("گزارش پایان روز", "${f.branch} · ${Fa.dayTitle(f.date)} ${Fa.date(f.date)}", listOf("شرح", "مقدار"), rows, notes = listOf(TOMAN))
    }

    private fun hours(minutes: Long): Cell = Cell.Qty(ir.sabou.kernel.Ratio.mulDiv(minutes, ir.sabou.kernel.Quantity.SCALE, 60))

    fun attendance(rows: List<AttendanceTotal>, branch: String, from: BusinessDate, to: BusinessDate) = ReportTable(
        "کارکرد و اضافه‌کار", "$branch · ${period(from, to)}", listOf("کارمند", "روزهای ثبت‌شده", "ساعت کار", "ساعت اضافه‌کار", "ساعت غیبت"),
        rows.map { listOf(Cell.of(it.name), Cell.Count(it.days.toLong()), hours(it.workedMinutes), hours(it.overtimeMinutes), hours(it.absentMinutes)) },
        footer = listOf(Cell.of("جمع"), Cell.Count(rows.sumOf { it.days }.toLong()), hours(rows.sumOf { it.workedMinutes }),
            hours(rows.sumOf { it.overtimeMinutes }), hours(rows.sumOf { it.absentMinutes })),
    )

    fun trialBalance(rows: List<Pair<ir.sabou.ledger.Account, Long>>, asOf: BusinessDate) = ReportTable(
        "تراز آزمایشی", "تا ${Fa.date(asOf)}", listOf("کد", "حساب", "بدهکار", "بستانکار"),
        rows.sortedBy { it.first.code.value }.map { (acc, net) ->
            listOf(Cell.of(acc.code.value), Cell.of(acc.name), Cell.Amount(maxOf(net, 0)), Cell.Amount(maxOf(-net, 0)))
        },
        footer = listOf(Cell.EMPTY, Cell.of("جمع"), Cell.Amount(rows.sumOf { maxOf(it.second, 0) }), Cell.Amount(rows.sumOf { maxOf(-it.second, 0) })),
        notes = listOf(TOMAN),
    )

    fun stock(location: String, rows: List<Pair<ir.sabou.inventory.Item, ir.sabou.inventory.StockBalance>>, asOf: BusinessDate) = ReportTable(
        "موجودی انبار", "$location · ${Fa.date(asOf)}", listOf("کالا", "محل", "واحد", "مقدار", "ارزش", "میانگین بها"),
        rows.sortedWith(compareBy({ it.first.shelf }, { it.first.name })).map { (item, b) ->
            listOf(Cell.of(item.name), Cell.of(item.shelf), Cell.of(unitName(item.unit)), Cell.Qty(b.quantity.micros), Cell.Amount(b.value.rial),
                if (b.quantity.isZero) Cell.of("—") else Cell.Amount(ir.sabou.kernel.Ratio.mulDiv(b.value.rial, ir.sabou.kernel.Quantity.SCALE, b.quantity.micros)))
        },
        footer = listOf(Cell.of("جمع"), Cell.EMPTY, Cell.EMPTY, Cell.EMPTY, Cell.Amount(rows.sumOf { it.second.value.rial }), Cell.EMPTY),
        notes = listOf(TOMAN, "میانگین بها برای یک واحد هر کالا است."),
    )

    fun invoices(rows: List<InvoiceRow>) = ReportTable(
        "فاکتورهای خرید", "", listOf("تاریخ", "تأمین‌کننده", "شماره", "سررسید", "مبلغ", "مانده", "وضعیت"),
        rows.map {
            listOf(Cell.of(Fa.date(it.invoice.date)), Cell.of(it.supplier), Cell.of(it.invoice.supplierInvoiceNo), Cell.of(Fa.date(it.invoice.dueDate)),
                Cell.Amount(it.invoice.total.rial), Cell.Amount(it.outstanding.rial),
                Cell.of(if (it.invoice.status == ir.sabou.purchasing.InvoiceStatus.REVERSED) "برگشت‌خورده" else "ثبت‌شده"))
        },
        footer = listOf(Cell.of("جمع"), Cell.EMPTY, Cell.EMPTY, Cell.EMPTY, Cell.Amount(rows.filter { it.invoice.status == ir.sabou.purchasing.InvoiceStatus.POSTED }.sumOf { it.invoice.total.rial }),
            Cell.Amount(rows.sumOf { it.outstanding.rial }), Cell.EMPTY),
        notes = listOf(TOMAN),
    )

    fun suppliers(rows: List<SupplierBalance>) = ReportTable(
        "بدهی به تأمین‌کنندگان", "", listOf("تأمین‌کننده", "تلفن", "بدهی"),
        rows.map { listOf(Cell.of(it.supplier.name), Cell.of(it.supplier.phone), Cell.Amount(it.owed.rial)) },
        footer = listOf(Cell.of("جمع"), Cell.EMPTY, Cell.Amount(rows.sumOf { it.owed.rial })), notes = listOf(TOMAN),
    )

    fun receivables(rows: List<OpenReceivable>) = ReportTable(
        "مطالبات از مشتریان", "", listOf("مشتری", "سررسید", "مبلغ", "مانده"),
        rows.map { listOf(Cell.of(it.customer), Cell.of(Fa.date(it.receivable.dueDate)), Cell.Amount(it.receivable.amount.rial), Cell.Amount(it.outstanding.rial)) },
        footer = listOf(Cell.of("جمع"), Cell.EMPTY, Cell.EMPTY, Cell.Amount(rows.sumOf { it.outstanding.rial })), notes = listOf(TOMAN),
    )

    fun accountHistory(account: AccountBalance, rows: List<ir.sabou.treasury.TreasuryMovement>) = ReportTable(
        "گردش ${account.account.name}", "مانده فعلی: ${Fa.toman(account.balance)} تومان", listOf("تاریخ", "شرح", "دریافت", "پرداخت"),
        rows.map {
            val inbound = it.direction == ir.sabou.treasury.Direction.RECEIPT
            listOf(Cell.of(Fa.date(it.date)), Cell.of(movementLabel(it.source.type) + if (it.reversalOf != null) " (برگشت)" else ""),
                Cell.Amount(if (inbound) it.amount.rial else 0), Cell.Amount(if (inbound) 0 else it.amount.rial))
        },
        footer = listOf(Cell.of("جمع"), Cell.EMPTY,
            Cell.Amount(rows.filter { it.direction == ir.sabou.treasury.Direction.RECEIPT }.sumOf { it.amount.rial }),
            Cell.Amount(rows.filter { it.direction == ir.sabou.treasury.Direction.PAYMENT }.sumOf { it.amount.rial })),
        notes = listOf(TOMAN),
    )

    fun movementLabel(sourceType: String): String = when {
        sourceType.startsWith("DAILY_SALE") -> "فروش روز"
        sourceType == "RECEIVABLE_COLLECTION" -> "وصول مطالبات"
        sourceType == "SUPPLIER_PAYMENT" -> "پرداخت به تأمین‌کننده"
        sourceType == "SALARY_PAYMENT" -> "پرداخت حقوق"
        sourceType == "PAYROLL_REMITTANCE" -> "پرداخت بیمه / مالیات"
        sourceType.contains("TRANSFER") -> "انتقال وجه"
        sourceType.contains("RECEIPT") -> "دریافت"
        sourceType.contains("PAYMENT") -> "پرداخت"
        sourceType.contains("COUNT") || sourceType.contains("RECONCIL") -> "شمارش و تطبیق"
        else -> "سند"
    }

    fun payrollRun(run: ir.sabou.payroll.PayrollRun, names: Map<ir.sabou.kernel.GlobalId, String>, branch: String) = ReportTable(
        "لیست حقوق", "$branch · ${period(run.from, run.to)} · نسخه‌ی پارامترها ${run.policyVersion}",
        listOf("کارمند", "روز کار", "حقوق پایه", "کسر غیبت", "اضافه‌کار", "ناخالص", "بیمه کارمند", "مالیات", "خالص", "بیمه کارفرما و بیکاری"),
        run.payslips.map { p ->
            listOf(Cell.of(names[p.employeeId].orEmpty()), p.payableDays?.let { Cell.Count(it.toLong()) } ?: Cell.of("کامل"), Cell.Amount(p.baseSalary.rial),
                Cell.Amount(p.absenceDeduction.rial), Cell.Amount(p.overtimePay.rial), Cell.Amount(p.gross.rial), Cell.Amount(p.employeeInsurance.rial),
                Cell.Amount(p.incomeTax.rial), Cell.Amount(p.net.rial), Cell.Amount(p.employerInsurance.rial + p.unemploymentInsurance.rial))
        },
        footer = listOf(Cell.of("جمع"), Cell.EMPTY) + listOf<(ir.sabou.payroll.Payslip) -> Long>(
            { it.baseSalary.rial }, { it.absenceDeduction.rial }, { it.overtimePay.rial }, { it.gross.rial }, { it.employeeInsurance.rial },
            { it.incomeTax.rial }, { it.net.rial }, { it.employerInsurance.rial + it.unemploymentInsurance.rial },
        ).map { f -> Cell.Amount(run.payslips.sumOf(f)) },
        notes = listOf(TOMAN),
    )

    /** One employee's payslip as a two-column table (printed one per page). */
    fun payslip(run: ir.sabou.payroll.PayrollRun, slip: ir.sabou.payroll.Payslip, name: String, branch: String) = ReportTable(
        "فیش حقوقی", "$name · $branch · ${period(run.from, run.to)}", listOf("شرح", "مبلغ (تومان)"),
        buildList {
            fun kv(k: String, v: Long) = add(listOf(Cell.of(k), Cell.Amount(v)))
            slip.payableDays?.let { add(listOf(Cell.of("روزهای کار (ماه ناقص)"), Cell.Count(it.toLong()))) }
            kv("حقوق پایه", slip.baseSalary.rial); kv("اضافه‌کار", slip.overtimePay.rial); kv("کسر غیبت", -slip.absenceDeduction.rial)
            kv("حقوق و مزایای ناخالص", slip.gross.rial); kv("بیمه سهم کارمند", -slip.employeeInsurance.rial); kv("مالیات حقوق", -slip.incomeTax.rial)
        },
        footer = listOf(Cell.of("خالص پرداختی"), Cell.Amount(slip.net.rial)),
        notes = listOf("نسخه‌ی پارامترهای قانونی: ${run.policyVersion}", "بیمه سهم کارفرما و بیکاری: ${Fa.toman(slip.employerInsurance.rial + slip.unemploymentInsurance.rial)} تومان (پرداخت کارفرما)"),
    )

    fun usage(u: UsageReport): ReportTable = ReportTable(
        "مصرف واقعی در برابر تئوریک", "${u.place} · ${period(u.from, u.to)}",
        listOf("کالا", "واحد", "موجودی اول", "خرید", "انتقال", "تولید", "موجودی پایان", "مصرف واقعی", "مصرف تئوریک (فروش)", "ضایعات", "اختلاف توضیح‌داده‌نشده", "ارزش اختلاف", "کارایی"),
        u.rows.map {
            listOf(
                Cell.of(it.item.name), Cell.of(unitName(it.item.unit)), Cell.Qty(it.opening.quantity), Cell.Qty(it.purchases.quantity), Cell.Qty(it.transfers.quantity),
                Cell.Qty(it.production.quantity), Cell.Qty(it.closing.quantity), Cell.Qty(it.actual.quantity), Cell.Qty(it.theoretical.quantity),
                Cell.Qty(it.waste.quantity), Cell.Qty(it.unexplained.quantity), Cell.Amount(it.unexplained.value), pct(it.efficiencyBp),
            )
        },
        footer = listOf(Cell.of("جمع ارزش"), Cell.EMPTY, Cell.EMPTY, Cell.EMPTY, Cell.EMPTY, Cell.EMPTY, Cell.EMPTY, Cell.Amount(u.actualValue),
            Cell.Amount(u.theoreticalValue), Cell.Amount(u.wasteValue), Cell.EMPTY, Cell.Amount(u.unexplainedValue), Cell.EMPTY),
        notes = listOf(
            "مصرف واقعی = موجودی اول + خرید + انتقال + تولید − موجودی پایان. اختلاف توضیح‌داده‌نشده همان کسری (منهای اضافه‌ی) انبارگردانی است.",
            "مقادیر به واحد هر کالا؛ ارزش‌ها به تومان.",
        ),
    )
}
