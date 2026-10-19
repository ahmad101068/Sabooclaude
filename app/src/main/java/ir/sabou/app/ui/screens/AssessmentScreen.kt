package ir.sabou.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ir.sabou.app.ui.ExportButtons
import ir.sabou.app.ui.LocalSession
import ir.sabou.app.ui.Nav
import ir.sabou.app.ui.components.Banner
import ir.sabou.app.ui.components.Chip
import ir.sabou.app.ui.components.ChipKind
import ir.sabou.app.ui.components.DateInput
import ir.sabou.app.ui.components.Divider
import ir.sabou.app.ui.components.Header
import ir.sabou.app.ui.components.KeyValue
import ir.sabou.app.ui.components.Page
import ir.sabou.app.ui.components.SCard
import ir.sabou.app.ui.components.SecondaryButton
import ir.sabou.app.ui.components.SectionTitle
import ir.sabou.app.ui.components.TextInput
import ir.sabou.app.ui.load
import ir.sabou.app.ui.rememberAction
import ir.sabou.app.ui.theme.Sabou
import ir.sabou.app.ui.theme.SabouShapes
import ir.sabou.app.ui.theme.SabouType
import ir.sabou.core.Assessment
import ir.sabou.core.AssessmentAction
import ir.sabou.core.AssessmentArea
import ir.sabou.core.Assessments
import ir.sabou.core.Fa
import ir.sabou.core.Finding
import ir.sabou.core.Kpi
import ir.sabou.core.KpiQuality
import ir.sabou.core.KpiUnit
import ir.sabou.core.ReportTables
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Scope
import ir.sabou.platform.Permission

/**
 * The 360° scorecard and diagnosis (ADR-0061, ADR-0062): critical findings first, then the score with its coverage
 * and data quality, the findings with evidence and a suggested action (each can become an action or be marked a
 * false alarm), the actions and their measured effect, every dimension's indicators (targets editable), the area
 * weights, and the data still missing.
 */
object AssessmentScreen {

    @Composable
    fun Show(nav: Nav) {
        val session = LocalSession.current
        var from by rememberSaveable { mutableStateOf(Fa.jalali(session.today).let { Fa.fromJalali(it.year, it.month, 1) }) }
        var to by rememberSaveable { mutableStateOf(session.today) }
        var open by rememberSaveable { mutableStateOf<String?>(null) }
        var acting by rememberSaveable { mutableStateOf<String?>(null) }
        var editing by rememberSaveable { mutableStateOf<String?>(null) }
        var weights by rememberSaveable { mutableStateOf(false) }
        val manage = session.can(Permission.BUDGET_MANAGE)
        Column(Modifier.fillMaxSize()) {
            Header("کارنامه و عارضه‌یابی", onBack = nav.back) { BranchSwitcher() }
            WithBranch { branch ->
                val data by load(session, branch, from, to) { assessments.of(branch, from, to) }
                Page {
                    ReportsScreens.PeriodPicker(from, to) { f, t -> from = f; to = t }
                    Loaded(data) { a ->
                        Text("در برابر ${Fa.date(a.baseFrom)} تا ${Fa.date(a.baseTo)} · قواعد نسخه‌ی ${Fa.number(a.rulesVersion.toLong())}" +
                            (if (a.settings.revision > 0) " · تنظیمات ${Fa.number(a.settings.revision.toLong())}" else ""),
                            style = SabouType.caption, color = Sabou.colors.muted)
                        a.critical.forEach { Banner("بحرانی: ${it.title}") }

                        SCard {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text("امتیاز کل", style = SabouType.label, color = Sabou.colors.muted)
                                    Text(a.score?.let { "${Fa.number(it.toLong())} از ۱۰۰" } ?: "داده‌ی کافی نیست", style = SabouType.title, color = scoreColor(a.score))
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("پوشش ${Fa.percent(a.coverageBp)}", style = SabouType.caption, color = Sabou.colors.muted)
                                    a.dataQualityBp?.let { Text("کیفیت داده ${Fa.percent(it)}", style = SabouType.caption, color = Sabou.colors.muted) }
                                }
                            }
                            Divider()
                            a.areas.forEach { ar -> ScoreBar("${ar.area.title} (وزن ${Fa.number(a.settings.weight(ar.area))})", ar.score) }
                            if (manage) Text(if (weights) "بستن وزن‌ها" else "تغییر وزن حوزه‌ها", style = SabouType.label, color = Sabou.colors.primary,
                                modifier = Modifier.padding(top = 4.dp).clickable { weights = !weights })
                            if (weights) WeightsForm(a) { weights = false }
                            if (a.quality.isNotEmpty()) {
                                Divider()
                                a.quality.forEach { q -> KeyValue(q.title, "${Fa.percent(q.bp)} · ${q.detail}", if (q.bp < 8_000) Sabou.colors.danger else Sabou.colors.ink) }
                            }
                        }

                        SectionTitle("عارضه‌ها و اقدام پیشنهادی", trailing = Fa.number(a.findings.size.toLong()))
                        if (a.findings.isEmpty()) Text("با داده‌ی این دوره عارضه‌ای پیدا نشد.", style = SabouType.body, color = Sabou.colors.muted)
                        if (a.silenced.isNotEmpty()) Text("${Fa.number(a.silenced.size.toLong())} عارضه به‌عنوان هشدار اشتباه کنار گذاشته شده: ${a.silenced.joinToString("، ")}",
                            style = SabouType.caption, color = Sabou.colors.muted)
                        a.findings.forEach { f ->
                            SCard {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Chip(f.severity.title, severityKind(f.severity))
                                    Text(f.dimension.title, style = SabouType.caption, color = Sabou.colors.muted)
                                }
                                Text(f.title, style = SabouType.bodyStrong, color = Sabou.colors.ink)
                                f.evidence.filter { it.isNotBlank() }.forEach { Text("• $it", style = SabouType.caption, color = Sabou.colors.ink) }
                                ReportTables.exposure(f).takeIf { it != "—" }?.let { KeyValue("مبلغ در معرض (ریال)", it, Sabou.colors.danger) }
                                if (f.causes.isNotEmpty()) {
                                    Text("علت‌های محتمل", style = SabouType.label, color = Sabou.colors.muted)
                                    f.causes.forEach { Text("• $it", style = SabouType.caption, color = Sabou.colors.ink) }
                                }
                                Text("اقدام پیشنهادی: ${f.action}", style = SabouType.body, color = Sabou.colors.primary)
                                Text("اطمینان: ${f.confidence.title}", style = SabouType.caption, color = Sabou.colors.muted)
                                val existing = a.actions.firstOrNull { it.findingKey == f.key && it.status == AssessmentAction.Status.OPEN }
                                if (existing != null) Text("اقدام باز: ${existing.owner} تا ${Fa.date(existing.due)}", style = SabouType.caption, color = Sabou.colors.primary)
                                else if (manage) {
                                    if (acting == f.key) ActionForm(branch, f) { acting = null }
                                    else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        SecondaryButton("ثبت اقدام", { acting = f.key }, Modifier.weight(1f))
                                        SecondaryButton("هشدار اشتباه", { acting = "false:" + f.key }, Modifier.weight(1f))
                                    }
                                    if (acting == "false:" + f.key) FalseAlarmForm(branch, f) { acting = null }
                                }
                            }
                        }

                        if (a.actions.isNotEmpty()) {
                            SectionTitle("اقدام‌ها", trailing = Fa.number(a.actions.count { it.status == AssessmentAction.Status.OPEN }.toLong()))
                            a.actions.forEach { act -> ActionCard(branch, act, manage, session.today) }
                        }

                        SectionTitle("بیست بُعد")
                        a.dimensions.forEach { d ->
                            SCard(onClick = { open = if (open == d.dimension.code) null else d.dimension.code }) {
                                KeyValue("${d.dimension.code} · ${d.dimension.title}",
                                    d.score?.let { Fa.number(it.toLong()) } ?: if (d.applicable) "نامعلوم" else "ندارد", scoreColor(d.score), strong = true)
                                if (open == d.dimension.code) {
                                    if (d.kpis.isEmpty()) Text("شاخصی از داده‌های برنامه برای این بُعد نیست.", style = SabouType.caption, color = Sabou.colors.muted)
                                    d.kpis.forEach { k ->
                                        Divider()
                                        KeyValue(k.title, Assessments.valueText(k), if (k.quality == KpiQuality.UNKNOWN) Sabou.colors.muted else scoreColor(k.score))
                                        Text(listOfNotNull(
                                            k.target?.let { "هدف ${Assessments.targetText(k)}" + (if (k.id in a.settings.targets) " (شما)" else "") },
                                            k.score?.let { "امتیاز ${Fa.number(it.toLong())}" },
                                            Assessments.qualityName(k.quality),
                                            k.source.takeIf { it.isNotBlank() },
                                        ).joinToString(" · "), style = SabouType.caption, color = Sabou.colors.muted)
                                        if (k.note.isNotBlank()) Text(k.note, style = SabouType.caption, color = Sabou.colors.muted)
                                        if (manage && k.target != null && k.id in Assessments.DIRECTIONS) {
                                            if (editing == k.id) TargetForm(a, k) { editing = null }
                                            else Text("تغییر هدف", style = SabouType.label, color = Sabou.colors.primary,
                                                modifier = Modifier.clickable { editing = k.id })
                                        }
                                    }
                                }
                            }
                        }

                        if (a.dataRequests.isNotEmpty()) {
                            SectionTitle("داده‌ی لازم برای سنجش کامل")
                            SCard { a.dataRequests.forEach { Text("• ${it.dimension.title}: ${it.text}", style = SabouType.caption, color = Sabou.colors.ink) } }
                        }
                        Text("همه‌ی ارقام از دفاتر و ثبت‌های همین برنامه است و با قواعد ثابت محاسبه شده؛ اقدام‌ها پیشنهادند و گزارش چیزی را در دفاتر ثبت یا تغییر نمی‌دهد.",
                            style = SabouType.caption, color = Sabou.colors.muted)
                    }
                    ExportButtons("کارنامه-و-عارضه‌یابی") { ReportTables.assessment(assessments.of(branch, from, to)) }
                }
            }
        }
    }

    @Composable
    private fun ActionForm(branch: Scope.Branch, f: Finding, onDone: () -> Unit) {
        val session = LocalSession.current
        val action = rememberAction()
        val id = ir.sabou.app.ui.rememberCommandId(f.key)
        var text by rememberSaveable { mutableStateOf(f.action) }
        var owner by rememberSaveable { mutableStateOf("") }
        var due by rememberSaveable { mutableStateOf(session.today.plusDays(7)) }
        var cost by rememberSaveable { mutableStateOf("") }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextInput("اقدام", text, { text = it }, singleLine = false)
            TextInput("مسئول", owner, { owner = it })
            DateInput("مهلت", due, { due = it }, session.today)
            TextInput("هزینه‌ی اقدام (ریال، اختیاری)", cost, { cost = it }, keyboard = KeyboardType.Number)
            action.error?.let { Banner(it) }
            val c = if (cost.isBlank()) 0L else Fa.latinDigits(cost.trim()).replace("٬", "").replace(",", "").toLongOrNull()
            SecondaryButton("ثبت اقدام", {
                action.run({ assessments.openAction(ir.sabou.core.OpenAssessmentAction(id.value, branch, f.key, f.kpiId, f.title, text, owner, due, c ?: 0, session.today)) }) { onDone() }
            }, Modifier.fillMaxWidth(), enabled = text.isNotBlank() && owner.isNotBlank() && c != null && !action.busy)
        }
    }

    @Composable
    private fun FalseAlarmForm(branch: Scope.Branch, f: Finding, onDone: () -> Unit) {
        val session = LocalSession.current
        val action = rememberAction()
        val id = ir.sabou.app.ui.rememberCommandId("false" + f.key)
        var note by rememberSaveable { mutableStateOf("") }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextInput("چرا اشتباه است؟", note, { note = it }, singleLine = false)
            Text("این عارضه ${Fa.number(Assessments.FALSE_ALARM_DAYS)} روز نشان داده و اعلام نمی‌شود.", style = SabouType.caption, color = Sabou.colors.muted)
            action.error?.let { Banner(it) }
            SecondaryButton("کنار گذاشتن", {
                action.run({ assessments.markFalseAlarm(ir.sabou.core.MarkFalseAlarm(id.value, branch, f.key, f.title, note, session.today)) }) { onDone() }
            }, Modifier.fillMaxWidth(), enabled = note.isNotBlank() && !action.busy, danger = true)
        }
    }

    @Composable
    private fun ActionCard(branch: Scope.Branch, act: AssessmentAction, manage: Boolean, today: ir.sabou.kernel.BusinessDate) {
        val action = rememberAction()
        var note by rememberSaveable(act.id.value) { mutableStateOf("") }
        var closing by rememberSaveable(act.id.value) { mutableStateOf(false) }
        SCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(act.status.title, if (act.late(today)) ChipKind.DANGER else if (act.status == AssessmentAction.Status.OPEN) ChipKind.ACCENT else ChipKind.NEUTRAL)
                Text(act.title, style = SabouType.caption, color = Sabou.colors.muted, modifier = Modifier.weight(1f))
            }
            Text(act.action, style = SabouType.body, color = Sabou.colors.ink)
            Text("${act.owner} · مهلت ${Fa.date(act.due)}" + (if (act.cost > 0) " · هزینه ${Fa.rial(act.cost)} ریال" else "") +
                (act.closed?.let { " · بسته شد ${Fa.date(it)}" } ?: ""), style = SabouType.caption, color = Sabou.colors.muted)
            if (act.before != null && act.after != null) {
                val k = Kpi(act.kpiId ?: "", ir.sabou.core.Dimension.D17, "", null, unitOf(act.kpiId), null, KpiQuality.OK, "")
                Text("اثر: ${Assessments.valueText(k.copy(value = act.before))} ← ${Assessments.valueText(k.copy(value = act.after))} " +
                    (if (act.improved == true) "(بهتر شد)" else "(بهتر نشد)"), style = SabouType.bodyStrong,
                    color = if (act.improved == true) Sabou.colors.primary else Sabou.colors.danger)
            }
            if (act.note.isNotBlank()) Text(act.note, style = SabouType.caption, color = Sabou.colors.muted)
            if (manage && act.status == AssessmentAction.Status.OPEN) {
                if (!closing) SecondaryButton("بستن اقدام", { closing = true }, Modifier.fillMaxWidth())
                else {
                    val done = ir.sabou.app.ui.rememberCommandId(act.id.value, "done")
                    val drop = ir.sabou.app.ui.rememberCommandId(act.id.value, "drop")
                    TextInput("نتیجه یا توضیح", note, { note = it })
                    action.error?.let { Banner(it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton("انجام شد", {
                            action.run({ assessments.closeAction(ir.sabou.core.CloseAssessmentAction(done.value, branch, act.id, AssessmentAction.Status.DONE, note, today)) })
                        }, Modifier.weight(1f), enabled = !action.busy)
                        SecondaryButton("کنار گذاشتن", {
                            action.run({ assessments.closeAction(ir.sabou.core.CloseAssessmentAction(drop.value, branch, act.id, AssessmentAction.Status.DROPPED, note, today)) })
                        }, Modifier.weight(1f), enabled = !action.busy, danger = true)
                    }
                }
            }
        }
    }

    /** The unit of an indicator by its id, for showing an action's before and after. */
    private fun unitOf(id: String?): KpiUnit = when (id) {
        "D04.CASH" -> KpiUnit.WEEKS
        "D04.DSO", "D18.BACKUP", "D19.RESTORE" -> KpiUnit.DAYS
        "D01.TRIAL" -> KpiUnit.RIAL
        "D01.OPEN", "D01.BANK", "D06.OVER", "D09.PRICES", "D09.MATCH", "D10.SHORT", "D10.COUNTS", "D10.EXPIRED", "D14.TEMP_BAD", "D15.BREAKDOWNS",
        "D16.TAX", "D16.CONTRACTS", "D16.PERMITS", "D17.BACKLOG", "D17.ACTIONS" -> KpiUnit.COUNT
        "D08.RATING" -> KpiUnit.RATING
        else -> KpiUnit.PERCENT
    }

    /** Typed in the indicator's own unit: percent for percentages (×100 into basis points), whole numbers otherwise. */
    private fun parse(text: String, unit: KpiUnit): Long? {
        val t = Fa.latinDigits(text.trim()).replace("٫", ".").replace("/", ".").replace("٬", "").replace(",", "").replace("−", "-")
        val d = t.toBigDecimalOrNull() ?: return null
        return when (unit) {
            KpiUnit.PERCENT, KpiUnit.RATING -> d.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).toLong()
            else -> d.setScale(0, java.math.RoundingMode.HALF_UP).toLong()
        }
    }

    private fun shown(v: Long, unit: KpiUnit): String = when (unit) {
        KpiUnit.PERCENT, KpiUnit.RATING -> java.math.BigDecimal.valueOf(v, 2).stripTrailingZeros().toPlainString()
        else -> v.toString()
    }

    private fun overrides(a: Assessment): Map<String, List<Long>> = a.settings.targets.mapValues { listOf(it.value.good, it.value.bad) }
    private fun weightMap(a: Assessment): Map<String, Long> = a.settings.weights.mapKeys { it.key.name }

    @Composable
    private fun TargetForm(a: Assessment, k: Kpi, onDone: () -> Unit) {
        val action = rememberAction()
        val target = k.target!!
        var good by rememberSaveable(k.id) { mutableStateOf(shown(target.good, k.unit)) }
        var bad by rememberSaveable(k.id) { mutableStateOf(shown(target.bad, k.unit)) }
        val unitWord = if (k.unit == KpiUnit.PERCENT) " (درصد)" else ""
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextInput("هدف — امتیاز ۱۰۰$unitWord", good, { good = it }, keyboard = KeyboardType.Decimal)
            TextInput("حد بد — امتیاز صفر$unitWord", bad, { bad = it }, keyboard = KeyboardType.Decimal)
            action.error?.let { Banner(it) }
            val g = parse(good, k.unit); val b = parse(bad, k.unit)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("ذخیره", {
                    action.run({ assessments.setSettings(ir.sabou.core.SetAssessmentSettings(GlobalId.new(), overrides(a) + (k.id to listOf(g!!, b!!)), weightMap(a))) }) { onDone() }
                }, Modifier.weight(1f), enabled = g != null && b != null && !action.busy)
                if (k.id in a.settings.targets) SecondaryButton("پیش‌فرض", {
                    action.run({ assessments.setSettings(ir.sabou.core.SetAssessmentSettings(GlobalId.new(), overrides(a) - k.id, weightMap(a))) }) { onDone() }
                }, Modifier.weight(1f), enabled = !action.busy)
            }
        }
    }

    @Composable
    private fun WeightsForm(a: Assessment, onDone: () -> Unit) {
        val action = rememberAction()
        val texts = AssessmentArea.entries.map { area -> rememberSaveable(area.name) { mutableStateOf(a.settings.weight(area).toString()) } }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AssessmentArea.entries.forEachIndexed { i, area ->
                TextInput("${area.title} (پیش‌فرض ${Fa.number(area.weight)})", texts[i].value, { texts[i].value = it }, keyboard = KeyboardType.Number)
            }
            action.error?.let { Banner(it) }
            val values = texts.map { Fa.latinDigits(it.value.trim()).toLongOrNull() }
            SecondaryButton("ذخیره‌ی وزن‌ها", {
                val map = AssessmentArea.entries.zip(values).filter { (area, v) -> v != area.weight }.associate { (area, v) -> area.name to v!! }
                action.run({ assessments.setSettings(ir.sabou.core.SetAssessmentSettings(GlobalId.new(), overrides(a), map)) }) { onDone() }
            }, Modifier.fillMaxWidth(), enabled = values.all { it != null } && !action.busy)
        }
    }

    @Composable
    private fun ScoreBar(title: String, score: Int?) {
        Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            KeyValue(title, score?.let { Fa.number(it.toLong()) } ?: "نامعلوم", scoreColor(score))
            Box(Modifier.fillMaxWidth().clip(SabouShapes.chip).background(Sabou.colors.track)) {
                if (score != null && score > 0) Box(Modifier.fillMaxWidth(score / 100f).padding(vertical = 3.dp).clip(SabouShapes.chip).background(scoreColor(score)))
            }
        }
    }

    @Composable
    private fun scoreColor(score: Int?) = when {
        score == null -> Sabou.colors.muted
        score < 50 -> Sabou.colors.danger
        else -> Sabou.colors.primary
    }

    private fun severityKind(s: Finding.Severity) = when (s) {
        Finding.Severity.CRITICAL, Finding.Severity.HIGH -> ChipKind.DANGER
        Finding.Severity.MEDIUM -> ChipKind.ACCENT
        Finding.Severity.LOW -> ChipKind.NEUTRAL
    }
}
