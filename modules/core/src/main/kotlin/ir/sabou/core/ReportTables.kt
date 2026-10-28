package ir.sabou.core

import ir.sabou.kernel.sumOfExact
import ir.sabou.inventory.StockUnit
import ir.sabou.kernel.BusinessDate

/** Turns reports into [ReportTable]s (Persian, ready for Excel and PDF). Pure formatting, no access to data. */
object ReportTables {
    private fun period(from: BusinessDate, to: BusinessDate) = if (from == to) Fa.date(from) else "از ${Fa.date(from)} تا ${Fa.date(to)}"
    private fun pct(bp: Long?): Cell = bp?.let { Cell.Percent(it) } ?: Cell.of("—")
    private fun amount(v: Long?): Cell = v?.let { Cell.Amount(it) } ?: Cell.of("—")
    private const val RIAL = "مبالغ به ریال"

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
            "سود و زیان", "$place · ${period(p.from, p.to)}", listOf("کد", "شرح", "مبلغ (ریال)"), rows,
            footer = listOf(Cell.EMPTY, Cell.of("سود (زیان) خالص"), Cell.Amount(p.totals.profit)),
            notes = listOf(
                RIAL,
                "درصد بهای غذا: ${pct(p.ratios.foodBp).text} · درصد نیروی کار: ${pct(p.ratios.laborBp).text} · بهای اصلی: ${pct(p.ratios.primeBp).text} (نسبت به فروش غذا)",
                "حقوق هر ماه در روز آخر همان ماه ثبت می‌شود؛ در بازه‌های کوتاه درصد نیروی کار را با احتیاط بخوانید.",
            ) + if (p.provisional) listOf(
                "سود موقت: ${Fa.number(p.openShortages.toLong())} کسری مصرف باز در این بازه است؛ بهای مواد آن فروش‌ها هنوز ثبت نشده و سود بیش از واقع است.",
            ) else emptyList(),
        )
        fun split(title: String, label: String, rows: List<Pair<String, PnlTotals>>) = ReportTable(
            title, "$place · ${period(p.from, p.to)}", listOf(label, "درآمد", "بهای تمام‌شده", "هزینه‌ها", "سود (زیان)"),
            rows.map { (k, t) -> listOf(Cell.of(k), Cell.Amount(t.revenue), Cell.Amount(t.cogs), Cell.Amount(t.expenses), Cell.Amount(t.profit)) },
            footer = listOf(Cell.of("جمع"), Cell.Amount(p.totals.revenue), Cell.Amount(p.totals.cogs), Cell.Amount(p.totals.expenses), Cell.Amount(p.totals.profit)),
            notes = listOf(RIAL),
        )
        return listOf(
            statement,
            split("سود و زیان شعب", "شعبه", p.byBranch),
            split("سود و زیان روزانه", "روز", p.byDay.map { (d, t) -> Fa.date(d) to t }),
        )
    }

    fun ledgerDetail(accountName: String, rows: List<LedgerDetail>, from: BusinessDate, to: BusinessDate) = ReportTable(
        "گردش $accountName", period(from, to), listOf("تاریخ", "شماره سند", "شرح", "شعبه", "بدهکار", "بستانکار"),
        rows.map { listOf(Cell.of(Fa.date(it.date)), Cell.of(Fa.digits(it.number)), Cell.of(it.description + if (it.memo.isBlank()) "" else " · ${it.memo}"), Cell.of(it.scope), Cell.Amount(it.debit), Cell.Amount(it.credit)) },
        footer = listOf(Cell.of("جمع"), Cell.EMPTY, Cell.EMPTY, Cell.EMPTY, Cell.Amount(rows.sumOfExact { it.debit }), Cell.Amount(rows.sumOfExact { it.credit })),
        notes = listOf(RIAL),
    )

    fun productMix(m: ProductMix) = ReportTable(
        "اقلام پرفروش و حاشیه‌ی سود", "${m.branch} · ${period(m.from, m.to)} · ${Fa.number(m.days.toLong())} روز فروش",
        listOf("آیتم منو", "تعداد", "فروش", "سهم از فروش", "میانگین قیمت", "بهای مواد هر پرس", "حاشیه‌ی هر پرس", "درصد بهای غذا",
            "بهای واقعی هر پرس", "حاشیه‌ی واقعی هر پرس", "درصد بهای واقعی", "پرس‌های با بهای ثبت‌شده"),
        m.rows.map {
            listOf(Cell.of(it.name), Cell.Qty(it.portions.micros), Cell.Amount(it.gross.rial), Cell.Percent(it.shareBp), amount(it.averagePrice?.rial),
                amount(it.unitCost?.rial), amount(it.unitMargin), pct(it.costBp),
                amount(it.actualUnitCost?.rial), amount(it.actualUnitMargin), pct(it.actualCostBp),
                if (it.actualCost == null) Cell.EMPTY else Cell.Qty(it.costedPortions.micros))
        },
        footer = listOf(Cell.of("جمع"), Cell.EMPTY, Cell.Amount(m.gross.rial), Cell.Percent(if (m.gross.isZero) 0 else 10_000), Cell.EMPTY, Cell.EMPTY, Cell.EMPTY, Cell.EMPTY,
            Cell.EMPTY, Cell.EMPTY, Cell.EMPTY, Cell.EMPTY),
        notes = listOfNotNull(RIAL, "بهای مواد با رسپی فعلی و میانگین بهای امروز انبارهای شعبه حساب شده است (اگر موجودی نباشد، آخرین قیمت خرید).",
            "این بها برای تصمیم قیمت و منو است؛ سود واقعی این بازه را گزارش «سود و زیان» از دفاتر نشان می‌دهد.",
            "«بهای واقعی» همان است که هنگام ثبت هر فروش از انبار برداشته شد (به بهای همان روز)، فقط برای پرس‌هایی که بهایشان ثبت شده است.",
            "در بعضی پرس‌ها کالا در موجودی دفتری کم بود؛ بهای واقعی آن‌ها تا تعیین تکلیف کسری کمتر از واقع است."
                .takeIf { m.rows.any { it.actualCostShort } }),
    )

    /** ADR-0053: from the base profit through each cause to the current profit. */
    fun profitBridge(b: ProfitBridge) = ReportTable(
        "چرا سود تغییر کرد", "${b.branchName} · ${period(b.current.from, b.current.to)} در برابر ${period(b.base.from, b.base.to)}",
        listOf("عامل", "اثر بر سود", "بیشترین سهم‌ها"),
        listOf(listOf(Cell.of("سود دوره‌ی مبنا"), Cell.Amount(b.base.profit), Cell.EMPTY)) +
            b.steps.map { s -> listOf(Cell.of(ProfitBridges.stepName(s.kind)), Cell.Amount(s.amount), Cell.of(s.details.joinToString("؛ ") { "${it.name} ${Fa.rial(it.amount)}" })) } +
            listOf(listOf(Cell.of("سود این دوره"), Cell.Amount(b.current.profit), Cell.EMPTY)),
        notes = listOf(RIAL) + b.summary + listOf(
            "عامل‌ها دقیقاً با تغییر سود در دفاتر برابرند. تعداد، ترکیب، قیمت و بهای مواد از ردیف‌های فروش و بهای واقعی‌شان (ADR-0050) است؛ بقیه از حساب‌های سود و زیان."),
    )

    /** ADR-0061: the scorecard, every indicator with its evidence, the findings and actions, and the data still missing. */
    fun assessment(a: Assessment): List<ReportTable> {
        val sub = "${a.branchName} · ${period(a.from, a.to)} در برابر ${period(a.baseFrom, a.baseTo)} · قواعد نسخه‌ی ${Fa.number(a.rulesVersion.toLong())}"
        fun score(s: Int?) = Cell.of(s?.let { Fa.number(it.toLong()) } ?: "داده‌ی کافی نیست")
        val card = ReportTable("کارنامه‌ی ۳۶۰ درجه", sub, listOf("حوزه یا بُعد", "وزن", "امتیاز از ۱۰۰"),
            listOf(listOf(Cell.of("امتیاز کل"), Cell.of("پوشش ${Fa.percent(a.coverageBp)}"), score(a.score))) +
                a.areas.flatMap { ar ->
                    listOf(listOf(Cell.of(ar.area.title), Cell.of(Fa.number(ar.area.weight)), score(ar.score))) +
                        ar.dimensions.map { d -> listOf(Cell.of("   ${d.dimension.code} ${d.dimension.title}"), Cell.EMPTY, score(d.score)) }
                },
            notes = listOfNotNull(
                a.critical.takeIf { it.isNotEmpty() }?.joinToString("؛ ", "بحرانی، جدا از امتیاز: ") { it.title },
                a.dataQualityBp?.let { "کیفیت داده ${Fa.percent(it)}: " + a.quality.joinToString("؛ ") { q -> "${q.title} ${Fa.percent(q.bp)}" } },
                "امتیاز هر شاخص ۱۰۰ در هدف و صفر در حد بد است؛ امتیاز کل میانگین وزنی ابعادی است که داده دارند. شاخص بی‌داده «نامعلوم» است، نه صفر.",
            ))
        val kpis = ReportTable("شاخص‌ها", sub, listOf("کد", "بُعد", "شاخص", "مقدار", "هدف", "امتیاز", "کیفیت داده", "منبع", "توضیح"),
            a.kpis.map { k -> listOf(Cell.of(k.id), Cell.of(k.dimension.title), Cell.of(k.title), Cell.of(Assessments.valueText(k)), Cell.of(Assessments.targetText(k)),
                Cell.of(k.score?.let { Fa.number(it.toLong()) } ?: "—"), Cell.of(Assessments.qualityName(k.quality)), Cell.of(k.source), Cell.of(k.note)) })
        val findings = ReportTable("عارضه‌ها و اقدام پیشنهادی", sub, listOf("شدت", "بُعد", "عارضه", "شواهد", "مبلغ در معرض (ریال)", "علت‌های محتمل", "اقدام پیشنهادی", "اطمینان"),
            a.findings.map { f -> listOf(Cell.of(f.severity.title), Cell.of(f.dimension.title), Cell.of(f.title), Cell.of(f.evidence.joinToString("؛ ")),
                Cell.of(exposure(f)), Cell.of(f.causes.joinToString("؛ ")), Cell.of(f.action), Cell.of(f.confidence.title)) },
            notes = listOf("اقدام‌ها پیشنهادند و تصمیم با شماست؛ گزارش هیچ سندی ثبت یا تغییر نمی‌دهد. علت‌ها از ارقام برمی‌آیند و حکم قطعی نیستند."))
        val requests = ReportTable("داده‌ی لازم برای سنجش کامل", sub, listOf("بُعد", "آنچه لازم است"),
            a.dataRequests.map { listOf(Cell.of(it.dimension.title), Cell.of(it.text)) })
        return listOf(card, kpis, findings, requests)
    }

    /** ADR-0064: the dashboard as tables — headline figures, the week, categories, payments, months, best sellers, latest sales. */
    fun managementDashboard(d: ManagementDashboard): List<ReportTable> {
        val sub = "${d.branchName} · ${period(d.from, d.to)} در برابر ${period(d.baseFrom, d.baseTo)}"
        fun change(k: DashKpi) = k.changeBp?.let { (if (it > 0) "+" else if (it < 0) "−" else "") + Fa.percent(kotlin.math.abs(it)) } ?: "—"
        fun value(k: DashKpi) = Assessments.valueText(Kpi("", Dimension.D02, k.title, k.value, k.unit, null, if (k.value == null) KpiQuality.UNKNOWN else KpiQuality.OK, ""))
        return listOf(
            ReportTable("داشبورد مدیریتی", sub, listOf("شاخص", "مقدار", "تغییر", "توضیح"), d.kpis.map { listOf(Cell.of(it.title), Cell.of(value(it)), Cell.of(change(it)), Cell.of(it.note)) }),
            ReportTable("فروش هفت روز اخیر", sub, listOf("روز", "فروش", "همان روز هفته‌ی قبل"),
                d.week.map { listOf(Cell.of(Fa.dayTitle(it.date)), Cell.Amount(it.sales), Cell.Amount(it.previous)) }, notes = listOf(RIAL)),
            ReportTable("فروش به تفکیک دسته", sub, listOf("دسته", "فروش", "سهم"), d.categories.map { listOf(Cell.of(it.name), Cell.Amount(it.amount), Cell.of(Fa.percent(it.shareBp))) }, notes = listOf(RIAL)),
            ReportTable("روش پرداخت", sub, listOf("روش", "مبلغ", "سهم"), d.payments.map { listOf(Cell.of(it.name), Cell.Amount(it.amount), Cell.of(Fa.percent(it.shareBp))) }, notes = listOf(RIAL)),
            ReportTable("هزینه و سود ماهانه", d.branchName, listOf("ماه", "هزینه‌ی پرسنل", "بهای مواد", "سود عملیاتی"),
                d.months.map { listOf(Cell.of("${Fa.monthNames[it.month - 1]} ${Fa.number(it.year.toLong())}"), Cell.Amount(it.labor), Cell.Amount(it.materials), Cell.Amount(it.profit)) }, notes = listOf(RIAL)),
            ReportTable("اقلام پرفروش", sub, listOf("غذا", "تعداد", "فروش", "حاشیه‌ی سود", "طبقه‌ی منو"),
                d.topItems.map { listOf(Cell.of(it.name), Cell.of(Fa.quantity(it.portions)), Cell.Amount(it.sales), Cell.of(it.marginBp?.let(Fa::percent) ?: "—"),
                    Cell.of(it.menuClass?.let(Assessments::menuClassName) ?: "—")) }, notes = listOf(RIAL)),
            ReportTable("آخرین فروش‌ها", d.branchName, listOf("روز", "اقلام", "مبلغ", "وضعیت"),
                d.latestSales.map { listOf(Cell.of(Fa.date(it.date)), Cell.of(it.dishes), Cell.Amount(it.payable), Cell.of(if (it.status == ir.sabou.sales.SaleStatus.REVERSED) "برگشت خورده" else "ثبت شده")) },
                notes = listOf(RIAL)),
        )
    }

    fun exposure(f: Finding): String = when {
        f.exposureHigh == null -> "—"
        f.exposureLow == null -> "تا ${Fa.rial(f.exposureHigh)}"
        f.exposureLow == f.exposureHigh -> Fa.rial(f.exposureHigh)
        else -> "${Fa.rial(f.exposureLow)} تا ${Fa.rial(f.exposureHigh)}"
    }

    /** ADR-0051: the weeks, then every item with its date and whether it is estimated. */
    fun cashForecast(f: CashForecast): List<ReportTable> {
        val kinds = CashItem.Kind.entries.filter { k -> f.weeks.any { (it.byKind[k] ?: 0L) != 0L } }
        val weeks = ReportTable(
            "پیش‌بینی نقدینگی", "${f.branchName} · از ${Fa.date(f.today)} · سناریوی ${f.scenario.name}",
            listOf("هفته", "از", "تا", "مانده‌ی اول") + kinds.map { cashKindName(it) } + listOf("ورودی", "خروجی", "مانده‌ی پایان"),
            f.weeks.map { w ->
                listOf(Cell.of(Fa.number(w.index.toLong())), Cell.of(Fa.date(w.from)), Cell.of(Fa.date(w.to)), Cell.Amount(w.opening)) +
                    kinds.map { Cell.Amount(w.byKind[it] ?: 0L) } + listOf(Cell.Amount(w.inflow), Cell.Amount(-w.outflow), Cell.Amount(w.closing))
            },
            notes = listOfNotNull(RIAL, CASH_BASIS,
                "فرض‌ها: ${cashAssumptions(f.assumptions)}",
                f.unscheduled.takeIf { it.isNotEmpty() }?.joinToString("؛ ", "بدون تاریخ (در جدول نیامده): ") { "${it.title} ${Fa.rial(it.amount)}" }),
        )
        val items = ReportTable(
            "اقلام پیش‌بینی نقدینگی", "${f.branchName} · سناریوی ${f.scenario.name}",
            listOf("تاریخ", "نوع", "شرح", "مبلغ", "برآوردی"),
            f.items.map { listOf(Cell.of(Fa.date(it.date)), Cell.of(cashKindName(it.kind)), Cell.of(it.text), Cell.Amount(it.amount), Cell.of(if (it.estimated) "برآورد" else "سند")) },
            notes = listOf(RIAL, "مبلغ مثبت ورودی و منفی خروجی است."),
        )
        return listOf(weeks, items)
    }

    fun customers(r: CustomerReport) = ReportTable(
        "مشتریان نسیه: سهم و وصول", "${r.branchName} · ${period(r.from, r.to)}",
        listOf("مشتری", "فروش نسیه", "سهم از فروش", "مانده‌ی طلب", "سررسیدگذشته", "تأخیر معمول (روز)", "وصول پس از فروش (روز)", "طلب وصول‌شده"),
        r.rows.map {
            listOf(Cell.of(it.customer.name), Cell.Amount(it.sales), pct(it.shareBp), Cell.Amount(it.outstanding), Cell.Amount(it.overdue),
                it.delayDays?.let { d -> Cell.of(Fa.number(d.toLong())) } ?: Cell.of("—"),
                it.collectionDays?.let { d -> Cell.of(Fa.number(d.toLong())) } ?: Cell.of("—"), Cell.of(Fa.number(it.collectedDebts.toLong())))
        },
        footer = listOf(Cell.of("جمع"), Cell.Amount(r.rows.sumOfExact { it.sales }), pct(r.creditShareBp), Cell.Amount(r.rows.sumOfExact { it.outstanding }),
            Cell.Amount(r.rows.sumOfExact { it.overdue }), Cell.EMPTY, Cell.EMPTY, Cell.EMPTY),
        notes = listOf(RIAL, "فروش کل شعبه در این بازه ${Fa.rial(r.totalSales)} ریال (قابل پرداخت).",
            "تأخیر و مدت وصول از طلب‌هایی است که کامل وصول شده‌اند، به نسبت مبلغ."),
    )

    fun cashKindName(k: CashItem.Kind) = when (k) {
        CashItem.Kind.CASH_SALES -> "فروش نقد و کارت"
        CashItem.Kind.CREDIT_SALES -> "وصول فروش نسیه‌ی آینده"
        CashItem.Kind.RECEIVABLE -> "وصول مطالبات"
        CashItem.Kind.CHEQUE_IN -> "چک‌های دریافتی"
        CashItem.Kind.SUPPLIER -> "فاکتورهای خرید"
        CashItem.Kind.CHEQUE_OUT -> "چک‌های پرداختی"
        CashItem.Kind.PURCHASES -> "خرید مواد آینده"
        CashItem.Kind.PAYROLL -> "حقوق"
        CashItem.Kind.PAYROLL_DUES -> "بیمه و مالیات حقوق"
        CashItem.Kind.OVERHEADS -> "هزینه‌های جاری (بودجه)"
    }

    fun cashAssumptions(a: CashAssumptions): String = listOfNotNull(
        if (a.liquidBp == null) "سابقه‌ی فروش نیست؛ فروش آینده در پیش‌بینی نیامده"
        else "${Fa.number(a.historyDays.toLong())} روز فروش اخیر: نقد و کارت ${Fa.percent(a.liquidBp)}، نسیه ${Fa.percent(a.creditBp ?: 0)}، خرید مواد ${Fa.percent(a.purchaseBp ?: 0)} از خالص فروش",
        "مهلت پرداخت به تأمین‌کننده ${Fa.number(a.supplierTermDays.toLong())} روز",
        "وصول نسیه ${Fa.number(a.collectionDays.toLong())} روز پس از فروش",
        if (a.payrollKnown) "حقوق ماهانه مثل آخرین حقوق تأییدشده" else "حقوقی تأیید نشده؛ حقوق آینده در پیش‌بینی نیامده",
        if (a.overheadsBudgeted) "اجاره و هزینه‌های جاری از بودجه" else "برای اجاره و هزینه‌های جاری بودجه ثبت نشده؛ در پیش‌بینی نیامده",
    ).joinToString("؛ ")

    const val CASH_BASIS = "مانده‌ی اول: صندوق، بانک، کارتخوان و تنخواه شعبه. اقلام سنددار (طلب، چک، فاکتور خرید، حقوق تأییدشده) در سررسیدشان؛ فروش، خرید آینده، حقوق ماه‌های بعد و هزینه‌های جاری برآوردی‌اند."

    /** ADR-0052: net food sales of the period by channel. */
    fun channels(m: ProductMix) = ReportTable(
        "فروش به تفکیک کانال", "${m.branch} · ${period(m.from, m.to)}",
        listOf("کانال", "خالص فروش", "سهم", "تعداد فاکتور", "میانگین هر فاکتور"),
        m.channels.map { listOf(Cell.of(channelName(it.channel)), Cell.Amount(it.net.rial), Cell.Percent(it.shareBp), Cell.of(Fa.number(it.transactions.toLong())), amount(it.perTransaction?.rial)) },
        footer = listOf(Cell.of("جمع"), Cell.Amount(m.channels.sumOfExact { it.net.rial }), Cell.Percent(if (m.channels.isEmpty()) 0 else 10_000),
            Cell.of(Fa.number(m.channels.sumOf { it.transactions }.toLong())), Cell.EMPTY),
        notes = listOf(RIAL, "خالص فروش: پس از تخفیف، پیش از سرویس و مالیات. کانال هر فروش هنگام ثبت انتخاب می‌شود یا از «نوع فروش» فاکتورهای صندوق می‌آید."),
    )

    fun channelName(c: ir.sabou.sales.SalesChannel?) = when (c) {
        ir.sabou.sales.SalesChannel.DINE_IN -> "حضوری (سالن)"
        ir.sabou.sales.SalesChannel.TAKEAWAY -> "بیرون‌بر"
        ir.sabou.sales.SalesChannel.DELIVERY -> "ارسال با پیک"
        ir.sabou.sales.SalesChannel.ONLINE -> "سفارش اینترنتی"
        ir.sabou.sales.SalesChannel.CORPORATE -> "شرکتی و قراردادی"
        ir.sabou.sales.SalesChannel.OTHER -> "سایر"
        null -> "نامشخص"
    }

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
        return ReportTable("گزارش پایان روز", "${f.branch} · ${Fa.dayTitle(f.date)} ${Fa.date(f.date)}", listOf("شرح", "مقدار"), rows, notes = listOf(RIAL))
    }

    private fun hours(minutes: Long): Cell = Cell.Qty(ir.sabou.kernel.Ratio.mulDiv(minutes, ir.sabou.kernel.Quantity.SCALE, 60))

    fun attendance(rows: List<AttendanceTotal>, branch: String, from: BusinessDate, to: BusinessDate) = ReportTable(
        "کارکرد و اضافه‌کار", "$branch · ${period(from, to)}", listOf("کارمند", "روزهای ثبت‌شده", "ساعت کار", "ساعت اضافه‌کار", "ساعت غیبت"),
        rows.map { listOf(Cell.of(it.name), Cell.Count(it.days.toLong()), hours(it.workedMinutes), hours(it.overtimeMinutes), hours(it.absentMinutes)) },
        footer = listOf(Cell.of("جمع"), Cell.Count(rows.sumOf { it.days }.toLong()), hours(rows.sumOf { it.workedMinutes }),
            hours(rows.sumOfExact { it.overtimeMinutes }), hours(rows.sumOfExact { it.absentMinutes })),
    )

    fun trialBalance(rows: List<Pair<ir.sabou.ledger.Account, Long>>, asOf: BusinessDate) = ReportTable(
        "تراز آزمایشی", "تا ${Fa.date(asOf)}", listOf("کد", "حساب", "بدهکار", "بستانکار"),
        rows.sortedBy { it.first.code.value }.map { (acc, net) ->
            listOf(Cell.of(acc.code.value), Cell.of(acc.name), Cell.Amount(maxOf(net, 0)), Cell.Amount(maxOf(-net, 0)))
        },
        footer = listOf(Cell.EMPTY, Cell.of("جمع"), Cell.Amount(rows.sumOfExact { maxOf(it.second, 0) }), Cell.Amount(rows.sumOfExact { maxOf(-it.second, 0) })),
        notes = listOf(RIAL),
    )

    /** Six-column trial balance of a period (ADR-0045). */
    fun trialBalance(rows: List<TrialRow>, from: BusinessDate, to: BusinessDate, place: String): ReportTable {
        fun d(v: Long) = Cell.Amount(maxOf(v, 0L))
        fun c(v: Long) = Cell.Amount(maxOf(-v, 0L))
        return ReportTable(
            "تراز آزمایشی", "$place · ${period(from, to)}",
            listOf("کد", "حساب", "مانده‌ی اول بدهکار", "مانده‌ی اول بستانکار", "گردش بدهکار", "گردش بستانکار", "مانده بدهکار", "مانده بستانکار"),
            rows.map { listOf(Cell.of(it.account.code.value), Cell.of(it.account.name), d(it.opening), c(it.opening), Cell.Amount(it.debit), Cell.Amount(it.credit), d(it.closing), c(it.closing)) },
            footer = listOf(Cell.EMPTY, Cell.of("جمع"), Cell.Amount(rows.sumOfExact { maxOf(it.opening, 0) }), Cell.Amount(rows.sumOfExact { maxOf(-it.opening, 0) }),
                Cell.Amount(rows.sumOfExact { it.debit }), Cell.Amount(rows.sumOfExact { it.credit }), Cell.Amount(rows.sumOfExact { maxOf(it.closing, 0) }), Cell.Amount(rows.sumOfExact { maxOf(-it.closing, 0) })),
            notes = listOf(RIAL),
        )
    }

    fun balanceSheet(b: BalanceSheet, place: String): ReportTable {
        val rows = buildList {
            add(listOf(Cell.of("دارایی‌ها"), Cell.EMPTY))
            b.assets.forEach { add(listOf(Cell.of(it.account.name), Cell.Amount(it.amount))) }
            add(listOf(Cell.of("جمع دارایی‌ها"), Cell.Amount(b.totalAssets)))
            add(listOf(Cell.of("بدهی‌ها"), Cell.EMPTY))
            b.liabilities.forEach { add(listOf(Cell.of(it.account.name), Cell.Amount(it.amount))) }
            add(listOf(Cell.of("جمع بدهی‌ها"), Cell.Amount(b.totalLiabilities)))
            add(listOf(Cell.of("حقوق مالکانه"), Cell.EMPTY))
            b.equity.forEach { add(listOf(Cell.of(it.account.name), Cell.Amount(it.amount))) }
            add(listOf(Cell.of("سود (زیان) سال‌های قبل"), Cell.Amount(b.earlierProfit)))
            add(listOf(Cell.of("سود (زیان) سال جاری"), Cell.Amount(b.yearProfit)))
            add(listOf(Cell.of("جمع حقوق مالکانه"), Cell.Amount(b.totalEquity)))
        }
        return ReportTable("ترازنامه", "$place · ${Fa.date(b.asOf)}", listOf("شرح", "مبلغ"), rows,
            footer = listOf(Cell.of("جمع بدهی‌ها و حقوق مالکانه"), Cell.Amount(b.totalLiabilities + b.totalEquity)), notes = listOf(RIAL))
    }

    fun journalBook(entries: List<JournalView>, accounts: Map<ir.sabou.ledger.AccountCode, String>, from: BusinessDate, to: BusinessDate, place: String) = ReportTable(
        "دفتر روزنامه", "$place · ${period(from, to)}", listOf("شماره", "تاریخ", "شرح سند", "حساب", "شرح ردیف", "بدهکار", "بستانکار"),
        entries.flatMap { v -> v.entry.lines.map { l ->
            listOf(Cell.of(v.number ?: v.entry.number.toString()), Cell.of(Fa.date(v.entry.date)), Cell.of(v.entry.description),
                Cell.of("${l.account.value} ${accounts[l.account].orEmpty()}"), Cell.of(l.memo), Cell.Amount(l.debit.rial), Cell.Amount(l.credit.rial))
        } },
        footer = listOf(Cell.EMPTY, Cell.EMPTY, Cell.EMPTY, Cell.EMPTY, Cell.of("جمع"),
            Cell.Amount(entries.sumOfExact { e -> e.entry.lines.sumOfExact { it.debit.rial } }), Cell.Amount(entries.sumOfExact { e -> e.entry.lines.sumOfExact { it.credit.rial } })),
        notes = listOf(RIAL),
    )

    fun statement(s: Statement, supplier: Boolean) = ReportTable(
        if (supplier) "صورت‌حساب تأمین‌کننده" else "صورت‌حساب مشتری", "${s.party} · ${period(s.from, s.to)}",
        listOf("تاریخ", "شرح", "شماره", "بدهکار", "بستانکار", "مانده"),
        listOf(listOf(Cell.of(Fa.date(s.from)), Cell.of("مانده‌ی ابتدای دوره"), Cell.EMPTY, Cell.EMPTY, Cell.EMPTY, Cell.Amount(s.opening))) +
            s.rows.map { listOf(Cell.of(Fa.date(it.date)), Cell.of(it.title), Cell.of(it.number?.let(Fa::digits) ?: ""), Cell.Amount(it.debit), Cell.Amount(it.credit), Cell.Amount(it.balance)) },
        footer = listOf(Cell.EMPTY, Cell.of("مانده‌ی پایان دوره"), Cell.EMPTY, Cell.Amount(s.rows.sumOfExact { it.debit }), Cell.Amount(s.rows.sumOfExact { it.credit }), Cell.Amount(s.closing)),
        notes = listOf(RIAL, if (supplier) "مانده = بدهی ما به تأمین‌کننده." else "مانده = بدهی مشتری به ما."),
    )

    fun aging(rows: List<AgingRow>, asOf: BusinessDate, receivables: Boolean) = ReportTable(
        if (receivables) "سنی مطالبات" else "سنی بدهی به تأمین‌کنندگان", Fa.date(asOf),
        listOf(if (receivables) "مشتری" else "تأمین‌کننده", "سررسید نشده", "۱ تا ۳۰ روز", "۳۱ تا ۶۰ روز", "۶۱ تا ۹۰ روز", "بیش از ۹۰ روز", "جمع"),
        rows.map { r -> listOf(Cell.of(r.party)) + r.buckets.map { Cell.Amount(it) } + Cell.Amount(r.total) },
        footer = listOf(Cell.of("جمع")) + (0 until 5).map { i -> Cell.Amount(rows.sumOfExact { it.buckets[i] }) } + Cell.Amount(rows.sumOfExact { it.total }),
        notes = listOf(RIAL, "روزهای گذشته از سررسید."),
    )

    fun stock(location: String, rows: List<Pair<ir.sabou.inventory.Item, ir.sabou.inventory.StockBalance>>, asOf: BusinessDate) = ReportTable(
        "موجودی انبار", "$location · ${Fa.date(asOf)}", listOf("کالا", "محل", "واحد", "مقدار", "ارزش", "میانگین بها"),
        rows.sortedWith(compareBy({ it.first.shelf }, { it.first.name })).map { (item, b) ->
            listOf(Cell.of(item.name), Cell.of(item.shelf), Cell.of(unitName(item.unit)), Cell.Qty(b.quantity.micros), Cell.Amount(b.value.rial),
                if (b.quantity.isZero) Cell.of("—") else Cell.Amount(ir.sabou.kernel.Ratio.mulDiv(b.value.rial, ir.sabou.kernel.Quantity.SCALE, b.quantity.micros)))
        },
        footer = listOf(Cell.of("جمع"), Cell.EMPTY, Cell.EMPTY, Cell.EMPTY, Cell.Amount(rows.sumOfExact { it.second.value.rial }), Cell.EMPTY),
        notes = listOf(RIAL, "میانگین بها برای یک واحد هر کالا است."),
    )

    fun invoices(rows: List<InvoiceRow>) = ReportTable(
        "فاکتورهای خرید", "", listOf("تاریخ", "تأمین‌کننده", "شماره", "سررسید", "مبلغ", "مانده", "وضعیت"),
        rows.map {
            listOf(Cell.of(Fa.date(it.invoice.date)), Cell.of(it.supplier), Cell.of(it.invoice.supplierInvoiceNo), Cell.of(Fa.date(it.invoice.dueDate)),
                Cell.Amount(it.invoice.total.rial), Cell.Amount(it.outstanding.rial),
                Cell.of(if (it.invoice.status == ir.sabou.purchasing.InvoiceStatus.REVERSED) "برگشت‌خورده" else "ثبت‌شده"))
        },
        footer = listOf(Cell.of("جمع"), Cell.EMPTY, Cell.EMPTY, Cell.EMPTY, Cell.Amount(rows.filter { it.invoice.status == ir.sabou.purchasing.InvoiceStatus.POSTED }.sumOfExact { it.invoice.total.rial }),
            Cell.Amount(rows.sumOfExact { it.outstanding.rial }), Cell.EMPTY),
        notes = listOf(RIAL),
    )

    fun suppliers(rows: List<SupplierBalance>) = ReportTable(
        "بدهی به تأمین‌کنندگان", "", listOf("تأمین‌کننده", "تلفن", "بدهی", "اعتبار مرجوعی"),
        rows.map { listOf(Cell.of(it.supplier.name), Cell.of(it.supplier.phone), Cell.Amount(it.owed.rial), Cell.Amount(it.credit.rial)) },
        footer = listOf(Cell.of("جمع"), Cell.EMPTY, Cell.Amount(rows.sumOfExact { it.owed.rial }), Cell.Amount(rows.sumOfExact { it.credit.rial })),
        notes = listOf(RIAL, "اعتبار مرجوعی: مبلغ کالای برگشتی که هنوز با فاکتوری تسویه نشده است."),
    )

    /** A purchase order as sent to the supplier. */
    fun order(o: OrderRow, items: Map<ir.sabou.kernel.GlobalId, ir.sabou.inventory.Item>, branch: String) = ReportTable(
        "سفارش خرید شماره ${Fa.number(o.order.number)}",
        "${o.supplier} · $branch · تاریخ سفارش ${Fa.date(o.order.date)} · تحویل ${Fa.date(o.order.expectedDate)}",
        listOf("ردیف", "کالا", "واحد", "مقدار", "قیمت واحد", "مبلغ"),
        o.order.lines.mapIndexed { i, l ->
            val item = items[l.itemId]
            listOf(Cell.Count(i + 1L), Cell.of(item?.name ?: ""), Cell.of(item?.let { unitName(it.unit) } ?: ""), Cell.Qty(l.quantity.micros), Cell.Amount(l.unitPrice.rial), Cell.Amount(l.value.rial))
        },
        footer = listOf(Cell.of("جمع"), Cell.EMPTY, Cell.EMPTY, Cell.EMPTY, Cell.EMPTY, Cell.Amount(o.order.total.rial)),
        notes = listOfNotNull(RIAL, "محل تحویل: ${o.location}", o.order.note.takeIf { it.isNotBlank() }?.let { "توضیح: $it" }),
    )

    fun priceChanges(rows: List<PriceChange>, from: BusinessDate, to: BusinessDate) = ReportTable(
        "تغییر قیمت تأمین‌کنندگان", period(from, to), listOf("تاریخ", "تأمین‌کننده", "کالا", "واحد", "قیمت قبلی", "تاریخ قبلی", "قیمت جدید", "تغییر"),
        rows.map {
            listOf(Cell.of(Fa.date(it.date)), Cell.of(it.supplier), Cell.of(it.item.name), Cell.of(unitName(it.item.unit)), Cell.Amount(it.previousPrice),
                Cell.of(Fa.date(it.previousDate)), Cell.Amount(it.price), it.changeBp?.let { bp -> Cell.Percent(bp) } ?: Cell.of("قیمت اول"))
        },
        notes = listOf(RIAL, "قیمت‌ها برای یک واحد هر کالاست و با فاکتور قبلی همان تأمین‌کننده مقایسه شده‌اند."),
    )

    fun suggestions(groups: List<SuggestionGroup>, place: String, date: BusinessDate) = ReportTable(
        "پیشنهاد خرید", "$place · ${Fa.date(date)}", listOf("تأمین‌کننده", "کالا", "واحد", "موجودی", "در راه", "سطح مطلوب", "برنامه", "پیشنهاد", "مبلغ تقریبی"),
        groups.flatMap { g ->
            g.lines.map {
                listOf(Cell.of(g.supplier?.name ?: "بدون تأمین‌کننده"), Cell.of(it.item.name), Cell.of(unitName(it.item.unit)), Cell.Qty(it.onHand.micros),
                    Cell.Qty(it.onOrder.micros), Cell.Qty(it.par.micros), Cell.Qty(it.planned.micros), Cell.Qty(it.suggested.micros), amount(it.value?.rial))
            }
        },
        footer = listOf(Cell.of("جمع"), Cell.EMPTY, Cell.EMPTY, Cell.EMPTY, Cell.EMPTY, Cell.EMPTY, Cell.EMPTY, Cell.EMPTY, Cell.Amount(groups.sumOfExact { it.total.rial })),
        notes = listOf(RIAL, "پیشنهاد = سطح مطلوب + مصرف برنامه‌ریزی‌شده − موجودی − سفارش‌های باز."),
    )

    fun receivables(rows: List<OpenReceivable>) = ReportTable(
        "مطالبات از مشتریان", "", listOf("مشتری", "سررسید", "مبلغ", "مانده"),
        rows.map { listOf(Cell.of(it.customer), Cell.of(Fa.date(it.receivable.dueDate)), Cell.Amount(it.receivable.amount.rial), Cell.Amount(it.outstanding.rial)) },
        footer = listOf(Cell.of("جمع"), Cell.EMPTY, Cell.EMPTY, Cell.Amount(rows.sumOfExact { it.outstanding.rial })), notes = listOf(RIAL),
    )

    fun accountHistory(account: AccountBalance, rows: List<ir.sabou.treasury.TreasuryMovement>) = ReportTable(
        "گردش ${account.account.name}", "مانده فعلی: ${Fa.rial(account.balance)} ریال", listOf("تاریخ", "شرح", "دریافت", "پرداخت"),
        rows.map {
            val inbound = it.direction == ir.sabou.treasury.Direction.RECEIPT
            listOf(Cell.of(Fa.date(it.date)), Cell.of(movementLabel(it.source.type) + if (it.reversalOf != null) " (برگشت)" else ""),
                Cell.Amount(if (inbound) it.amount.rial else 0), Cell.Amount(if (inbound) 0 else it.amount.rial))
        },
        footer = listOf(Cell.of("جمع"), Cell.EMPTY,
            Cell.Amount(rows.filter { it.direction == ir.sabou.treasury.Direction.RECEIPT }.sumOfExact { it.amount.rial }),
            Cell.Amount(rows.filter { it.direction == ir.sabou.treasury.Direction.PAYMENT }.sumOfExact { it.amount.rial })),
        notes = listOf(RIAL),
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
        listOf("کارمند", "روز کار", "حقوق پایه", "کسر غیبت", "اضافه‌کار", "مزایا", "ناخالص", "بیمه کارمند", "مالیات", "مساعده و کسور", "خالص", "بیمه کارفرما و بیکاری"),
        run.payslips.map { p ->
            listOf(Cell.of(names[p.employeeId].orEmpty()), p.payableDays?.let { Cell.Count(it.toLong()) } ?: Cell.of("کامل"), Cell.Amount(p.baseSalary.rial),
                Cell.Amount(p.absenceDeduction.rial), Cell.Amount(p.overtimePay.rial), Cell.Amount(p.total(ir.sabou.payroll.PayLine.Kind.EARNING).rial),
                Cell.Amount(p.gross.rial), Cell.Amount(p.employeeInsurance.rial), Cell.Amount(p.incomeTax.rial),
                Cell.Amount(p.total(ir.sabou.payroll.PayLine.Kind.ADVANCE).rial + p.total(ir.sabou.payroll.PayLine.Kind.DEDUCTION).rial),
                Cell.Amount(p.net.rial), Cell.Amount(p.employerInsurance.rial + p.unemploymentInsurance.rial))
        },
        footer = listOf(Cell.of("جمع"), Cell.EMPTY) + listOf<(ir.sabou.payroll.Payslip) -> Long>(
            { it.baseSalary.rial }, { it.absenceDeduction.rial }, { it.overtimePay.rial }, { it.total(ir.sabou.payroll.PayLine.Kind.EARNING).rial }, { it.gross.rial },
            { it.employeeInsurance.rial }, { it.incomeTax.rial },
            { it.total(ir.sabou.payroll.PayLine.Kind.ADVANCE).rial + it.total(ir.sabou.payroll.PayLine.Kind.DEDUCTION).rial },
            { it.net.rial }, { it.employerInsurance.rial + it.unemploymentInsurance.rial },
        ).map { f -> Cell.Amount(run.payslips.sumOf(f)) },
        notes = listOf(RIAL),
    )

    /**
     * Preparation sheet for the monthly insurance list (لیست بیمه): per person the days, wages and the insurable
     * amounts and contributions. It is not the portal's upload file: copy the figures, or check its columns against the
     * official format before uploading.
     */
    fun insuranceList(run: ir.sabou.payroll.PayrollRun, people: Map<ir.sabou.kernel.GlobalId, ir.sabou.payroll.Employee>, branch: String) = ReportTable(
        "پیش‌نویس لیست بیمه", "$branch · ${period(run.from, run.to)}",
        listOf("ردیف", "شماره بیمه", "کد ملی", "نام", "نام پدر", "روز کارکرد", "دستمزد روزانه", "دستمزد ماهانه", "مزایای مشمول", "جمع مشمول", "سهم کارمند", "سهم کارفرما", "بیکاری"),
        run.payslips.mapIndexed { i, p ->
            val e = people[p.employeeId]
            val days = p.payableDays ?: (run.to.epochDay - run.from.epochDay + 1).toInt()
            val insurableExtra = p.lines.filter { it.kind == ir.sabou.payroll.PayLine.Kind.EARNING && it.insurable }.sumOfExact { it.amount.rial } + p.overtimePay.rial
            val wage = p.baseSalary.rial - p.absenceDeduction.rial
            listOf(Cell.Count((i + 1).toLong()), Cell.of(e?.profile?.insuranceNumber.orEmpty()), Cell.of(e?.nationalId.orEmpty()), Cell.of(e?.name.orEmpty()),
                Cell.of(e?.profile?.fatherName.orEmpty()), Cell.Count(days.toLong()), Cell.Amount(if (days > 0) wage / days else 0), Cell.Amount(wage),
                Cell.Amount(insurableExtra), Cell.Amount(p.insurableBase.rial), Cell.Amount(p.employeeInsurance.rial), Cell.Amount(p.employerInsurance.rial),
                Cell.Amount(p.unemploymentInsurance.rial))
        },
        footer = listOf(Cell.of("جمع")) + List(8) { Cell.EMPTY } + listOf(
            Cell.Amount(run.payslips.sumOfExact { it.insurableBase.rial }), Cell.Amount(run.payslips.sumOfExact { it.employeeInsurance.rial }),
            Cell.Amount(run.payslips.sumOfExact { it.employerInsurance.rial }), Cell.Amount(run.payslips.sumOfExact { it.unemploymentInsurance.rial }),
        ),
        notes = listOf(RIAL, "پیش‌نویس برای تهیه‌ی لیست در سامانه‌ی تأمین اجتماعی؛ فایل بارگذاری سامانه نیست."),
    )

    /** Preparation sheet for the monthly salary tax list (لیست مالیات حقوق), likewise not the portal's file. */
    fun taxList(run: ir.sabou.payroll.PayrollRun, people: Map<ir.sabou.kernel.GlobalId, ir.sabou.payroll.Employee>, branch: String) = ReportTable(
        "پیش‌نویس لیست مالیات حقوق", "$branch · ${period(run.from, run.to)}",
        listOf("ردیف", "کد ملی", "نام", "ناخالص", "غیرمشمول و معافیت", "درآمد مشمول مالیات", "مالیات"),
        run.payslips.mapIndexed { i, p ->
            val e = people[p.employeeId]
            listOf(Cell.Count((i + 1).toLong()), Cell.of(e?.nationalId.orEmpty()), Cell.of(e?.name.orEmpty()), Cell.Amount(p.gross.rial),
                Cell.Amount(maxOf(0L, p.gross.rial - p.taxableIncome.rial)), Cell.Amount(p.taxableIncome.rial), Cell.Amount(p.incomeTax.rial))
        },
        footer = listOf(Cell.of("جمع"), Cell.EMPTY, Cell.EMPTY, Cell.Amount(run.payslips.sumOfExact { it.gross.rial }),
            Cell.Amount(run.payslips.sumOfExact { maxOf(0L, it.gross.rial - it.taxableIncome.rial) }), Cell.Amount(run.payslips.sumOfExact { it.taxableIncome.rial }),
            Cell.Amount(run.payslips.sumOfExact { it.incomeTax.rial })),
        notes = listOf(RIAL, "پیش‌نویس برای تهیه‌ی لیست در سامانه‌ی مالیات حقوق؛ فایل بارگذاری سامانه نیست."),
    )

    /** An item's stock card (کاردکس) for a period. */
    fun stockCard(card: StockCard, from: BusinessDate, to: BusinessDate) = ReportTable(
        "کاردکس ${card.item.name}", "${card.location} · ${period(from, to)}",
        listOf("تاریخ", "شرح", "شماره سند", "وارده", "صادره", "ارزش", "مانده", "ارزش مانده"),
        listOf(listOf(Cell.of(Fa.date(from)), Cell.of("مانده‌ی ابتدای دوره"), Cell.EMPTY, Cell.EMPTY, Cell.EMPTY, Cell.EMPTY,
            Cell.of(Fa.quantity(ir.sabou.kernel.Quantity.of(card.openingQuantity))), Cell.Amount(card.openingValue))) +
            card.rows.map { r ->
                listOf(Cell.of(Fa.date(r.date)), Cell.of(r.title), Cell.of(r.number.orEmpty()),
                    Cell.of(if (r.quantity > 0) Fa.quantity(ir.sabou.kernel.Quantity.of(r.quantity)) else ""),
                    Cell.of(if (r.quantity < 0) Fa.quantity(ir.sabou.kernel.Quantity.of(-r.quantity)) else ""),
                    Cell.Amount(r.value), Cell.of(Fa.quantity(ir.sabou.kernel.Quantity.of(r.balanceQuantity))), Cell.Amount(r.balanceValue))
            },
        notes = listOf(RIAL),
    )

    /** One employee's payslip as a two-column table (printed one per page). */
    fun payslip(run: ir.sabou.payroll.PayrollRun, slip: ir.sabou.payroll.Payslip, name: String, branch: String) = ReportTable(
        "فیش حقوقی", "$name · $branch · ${period(run.from, run.to)}", listOf("شرح", "مبلغ (ریال)"),
        buildList {
            fun kv(k: String, v: Long) = add(listOf(Cell.of(k), Cell.Amount(v)))
            slip.payableDays?.let { add(listOf(Cell.of("روزهای کار (ماه ناقص)"), Cell.Count(it.toLong()))) }
            kv("حقوق پایه", slip.baseSalary.rial); kv("اضافه‌کار", slip.overtimePay.rial); kv("کسر غیبت", -slip.absenceDeduction.rial)
            slip.lines.filter { it.kind == ir.sabou.payroll.PayLine.Kind.EARNING }.forEach { kv(it.title, it.amount.rial) }
            kv("حقوق و مزایای ناخالص", slip.gross.rial); kv("بیمه سهم کارمند", -slip.employeeInsurance.rial); kv("مالیات حقوق", -slip.incomeTax.rial)
            slip.lines.filter { it.kind != ir.sabou.payroll.PayLine.Kind.EARNING }.forEach { kv(it.title, -it.amount.rial) }
        },
        footer = listOf(Cell.of("خالص پرداختی"), Cell.Amount(slip.net.rial)),
        notes = listOf("نسخه‌ی پارامترهای قانونی: ${run.policyVersion}", "بیمه سهم کارفرما و بیکاری: ${Fa.rial(slip.employerInsurance.rial + slip.unemploymentInsurance.rial)} ریال (پرداخت کارفرما)"),
    )

    fun usage(u: UsageReport): ReportTable = ReportTable(
        "مصرف واقعی در برابر تئوریک", "${u.place} · ${period(u.from, u.to)}",
        listOf("کالا", "گروه", "واحد", "موجودی اول", "خرید", "انتقال", "تولید", "موجودی پایان", "مصرف واقعی", "مصرف تئوریک (فروش)", "ضایعات", "پذیرایی و اهدایی", "اختلاف توضیح‌داده‌نشده", "ارزش اختلاف", "کارایی"),
        u.rows.map {
            listOf(
                Cell.of(it.item.name), Cell.of(it.item.category), Cell.of(unitName(it.item.unit)), Cell.Qty(it.opening.quantity), Cell.Qty(it.purchases.quantity), Cell.Qty(it.transfers.quantity),
                Cell.Qty(it.production.quantity), Cell.Qty(it.closing.quantity), Cell.Qty(it.actual.quantity), Cell.Qty(it.theoretical.quantity),
                Cell.Qty(it.waste.quantity), Cell.Qty(it.comps.quantity), Cell.Qty(it.unexplained.quantity), Cell.Amount(it.unexplained.value), pct(it.efficiencyBp),
            )
        },
        footer = listOf(Cell.of("جمع ارزش"), Cell.EMPTY, Cell.EMPTY, Cell.EMPTY, Cell.EMPTY, Cell.EMPTY, Cell.EMPTY, Cell.EMPTY, Cell.Amount(u.actualValue),
            Cell.Amount(u.theoreticalValue), Cell.Amount(u.wasteValue), Cell.Amount(u.compsValue), Cell.EMPTY, Cell.Amount(u.unexplainedValue), Cell.EMPTY),
        notes = listOf(
            "مصرف واقعی = موجودی اول + خرید + انتقال + تولید − موجودی پایان. اختلاف توضیح‌داده‌نشده همان کسری (منهای اضافه‌ی) انبارگردانی است.",
            "مقادیر به واحد هر کالا؛ ارزش‌ها به ریال.",
            if (u.measuredByCount) "انبارگردانی تأییدشده در بازه: ${Fa.number(u.counts.toLong())}، آخرین ${u.lastCount?.let(Fa::date) ?: "—"}؛ کالاهای شمرده‌شده ${Fa.number(u.countedRows.toLong())} از ${Fa.number(u.rows.size.toLong())}. برای کالای شمرده‌نشده، «واقعی» عدد دفاتر است."
            else "در این بازه انبارگردانی تأییدشده‌ای نیست: «مصرف واقعی» عدد دفاتر است، نه اندازه‌گیری انبار؛ مصرف ثبت‌نشده در آن دیده نمی‌شود.",
        ),
    )

    fun chequeStatusName(s: ir.sabou.treasury.ChequeStatus) = when (s) {
        ir.sabou.treasury.ChequeStatus.IN_HAND -> "در صندوق"
        ir.sabou.treasury.ChequeStatus.DEPOSITED -> "واگذارشده به بانک"
        ir.sabou.treasury.ChequeStatus.COLLECTED -> "وصول‌شده"
        ir.sabou.treasury.ChequeStatus.ENDORSED -> "خرج‌شده"
        ir.sabou.treasury.ChequeStatus.BOUNCED -> "برگشتی"
        ir.sabou.treasury.ChequeStatus.SETTLED -> "برگشتی تسویه‌شده"
        ir.sabou.treasury.ChequeStatus.ISSUED -> "صادرشده"
        ir.sabou.treasury.ChequeStatus.CLEARED -> "پاس‌شده"
        ir.sabou.treasury.ChequeStatus.VOID -> "باطل"
    }

    fun cheques(rows: List<ChequeRow>, title: String) = ReportTable(
        title, "", listOf("سررسید", "نوع", "شماره", "بانک", "صیادی", "طرف حساب", "مبلغ", "وضعیت", "شعبه"),
        rows.map {
            val c = it.cheque
            listOf(Cell.of(Fa.date(c.dueDate)), Cell.of(if (c.direction == ir.sabou.treasury.ChequeDirection.RECEIVED) "دریافتی" else "پرداختی"),
                Cell.of(c.details.number), Cell.of(c.details.bank), Cell.of(c.details.sayadId), Cell.of(c.details.counterparty), Cell.Amount(c.amount.rial),
                Cell.of(chequeStatusName(c.status)), Cell.of(it.branch))
        },
        footer = listOf(Cell.of("جمع"), Cell.EMPTY, Cell.EMPTY, Cell.EMPTY, Cell.EMPTY, Cell.EMPTY, Cell.Amount(rows.sumOfExact { it.cheque.amount.rial }), Cell.EMPTY, Cell.EMPTY),
        notes = listOf(RIAL),
    )

    fun budget(r: BudgetReport) = ReportTable(
        "بودجه در برابر عملکرد", "${r.place} · ${period(r.from, r.to)}", listOf("کد", "حساب", "بودجه", "عملکرد", "اختلاف", "درصد مصرف"),
        r.lines.map { listOf(Cell.of(it.account.code.value), Cell.of(it.account.name), Cell.Amount(it.budget), Cell.Amount(it.actual), Cell.Amount(it.variance), pct(it.usedBp)) },
        notes = listOf(RIAL, "بودجه‌ی دوره‌ای که بخشی از آن در این بازه است به نسبت روزها حساب شده است. اختلاف مثبت در هزینه یعنی بیش از بودجه."),
    )

    fun assets(rows: List<AssetRow>, asOf: BusinessDate) = ReportTable(
        "دفتر دارایی‌های ثابت", Fa.date(asOf), listOf("دارایی", "گروه", "شعبه", "تاریخ خرید", "روش", "بها", "استهلاک انباشته", "ارزش دفتری", "وضعیت"),
        rows.map {
            val a = it.asset
            listOf(Cell.of(a.name), Cell.of(a.category), Cell.of(it.branch), Cell.of(Fa.date(a.acquiredOn)),
                Cell.of(if (a.method == ir.sabou.assets.DepreciationMethod.STRAIGHT_LINE) "خط مستقیم ${Fa.number(a.usefulLifeMonths?.toLong() ?: 0)} ماه" else "نزولی ${Fa.percent(a.rateBp ?: 0)}"),
                Cell.Amount(a.cost.rial), Cell.Amount(a.accumulated.rial), Cell.Amount(a.bookValue.rial),
                Cell.of(if (a.status == ir.sabou.assets.AssetStatus.ACTIVE) "فعال" else "واگذارشده"))
        },
        footer = listOf(Cell.of("جمع"), Cell.EMPTY, Cell.EMPTY, Cell.EMPTY, Cell.EMPTY, Cell.Amount(rows.filter { it.asset.status == ir.sabou.assets.AssetStatus.ACTIVE }.sumOfExact { it.asset.cost.rial }),
            Cell.Amount(rows.filter { it.asset.status == ir.sabou.assets.AssetStatus.ACTIVE }.sumOfExact { it.asset.accumulated.rial }),
            Cell.Amount(rows.filter { it.asset.status == ir.sabou.assets.AssetStatus.ACTIVE }.sumOfExact { it.asset.bookValue.rial }), Cell.EMPTY),
        notes = listOf(RIAL, "روش و نرخ استهلاک هر گروه باید با جدول استهلاکات مالیاتی و مشاور مالیاتی تطبیق داده شود."),
    )

    fun countStatusName(s: ir.sabou.inventory.CountStatus) = when (s) {
        ir.sabou.inventory.CountStatus.PENDING -> "در انتظار تأیید"
        ir.sabou.inventory.CountStatus.POSTED -> "تأیید و ثبت‌شده"
        ir.sabou.inventory.CountStatus.REJECTED -> "ردشده"
    }

    /** One count: counted against book, the difference, its value and reason. */
    fun stockCount(v: CountView, items: Map<ir.sabou.kernel.GlobalId, ir.sabou.inventory.Item>): ReportTable {
        val c = v.count
        return ReportTable(
            "انبارگردانی ${v.location}", "${Fa.date(c.date)} · ${countStatusName(c.status)} · شمارش: ${c.countedByName}" + (c.reviewedByName?.let { " · بررسی: $it" } ?: ""),
            listOf("کالا", "قفسه", "واحد", "دفتری", "شمارش‌شده", "اختلاف", "ارزش اختلاف", "دلیل", "توضیح"),
            c.lines.map { l ->
                val item = items[l.itemId]
                listOf(Cell.of(item?.name ?: ""), Cell.of(item?.shelf ?: ""), Cell.of(item?.let { unitName(it.unit) } ?: ""),
                    if (v.showsBook) Cell.Qty(l.bookAtCount.micros) else Cell.of("—"), Cell.Qty(l.counted.micros),
                    if (v.showsBook) Cell.Qty(l.difference) else Cell.of("—"), amount(l.postedValue),
                    Cell.of(l.reason?.let(ir.sabou.inventory.StockCountOperations::reasonName) ?: ""),
                    Cell.of(listOf(l.note, l.reasonNote).filter { it.isNotBlank() }.joinToString(" · ")))
            },
            footer = listOf(Cell.of("جمع ارزش اختلاف"), Cell.EMPTY, Cell.EMPTY, Cell.EMPTY, Cell.EMPTY, Cell.EMPTY, Cell.Amount(c.lines.sumOfExact { it.postedValue ?: 0 }), Cell.EMPTY, Cell.EMPTY),
            notes = listOfNotNull(RIAL, "اختلاف = شمارش‌شده − دفتری در زمان شمارش؛ منفی یعنی کسری.", c.note.takeIf { it.isNotBlank() }?.let { "توضیح: $it" },
                c.rejectReason?.let { "دلیل رد: $it" }),
        )
    }
}
