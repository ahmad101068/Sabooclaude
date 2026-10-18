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
import androidx.compose.ui.unit.dp
import ir.sabou.app.ui.ExportButtons
import ir.sabou.app.ui.LocalSession
import ir.sabou.app.ui.Nav
import ir.sabou.app.ui.components.Banner
import ir.sabou.app.ui.components.Chip
import ir.sabou.app.ui.components.ChipKind
import ir.sabou.app.ui.components.Divider
import ir.sabou.app.ui.components.Header
import ir.sabou.app.ui.components.KeyValue
import ir.sabou.app.ui.components.Page
import ir.sabou.app.ui.components.SCard
import ir.sabou.app.ui.components.SectionTitle
import ir.sabou.app.ui.load
import ir.sabou.app.ui.theme.Sabou
import ir.sabou.app.ui.theme.SabouShapes
import ir.sabou.app.ui.theme.SabouType
import ir.sabou.core.Assessments
import ir.sabou.core.Fa
import ir.sabou.core.Finding
import ir.sabou.core.KpiQuality
import ir.sabou.core.ReportTables

/**
 * The 360° scorecard and diagnosis (ADR-0061): critical findings first, then the score with its coverage and data
 * quality, the findings with evidence and a suggested action, every dimension's indicators, and the data still missing.
 */
object AssessmentScreen {

    @Composable
    fun Show(nav: Nav) {
        val session = LocalSession.current
        var from by rememberSaveable { mutableStateOf(Fa.jalali(session.today).let { Fa.fromJalali(it.year, it.month, 1) }) }
        var to by rememberSaveable { mutableStateOf(session.today) }
        var open by rememberSaveable { mutableStateOf<String?>(null) }
        Column(Modifier.fillMaxSize()) {
            Header("کارنامه و عارضه‌یابی", onBack = nav.back) { BranchSwitcher() }
            WithBranch { branch ->
                val data by load(session, branch, from, to) { assessments.of(branch, from, to) }
                Page {
                    ReportsScreens.PeriodPicker(from, to) { f, t -> from = f; to = t }
                    Loaded(data) { a ->
                        Text("در برابر ${Fa.date(a.baseFrom)} تا ${Fa.date(a.baseTo)} · قواعد نسخه‌ی ${Fa.number(a.rulesVersion.toLong())}",
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
                            a.areas.forEach { ar -> ScoreBar(ar.area.title, ar.score) }
                            if (a.quality.isNotEmpty()) {
                                Divider()
                                a.quality.forEach { q -> KeyValue(q.title, "${Fa.percent(q.bp)} · ${q.detail}", if (q.bp < 8_000) Sabou.colors.danger else Sabou.colors.ink) }
                            }
                        }

                        SectionTitle("عارضه‌ها و اقدام پیشنهادی", trailing = Fa.number(a.findings.size.toLong()))
                        if (a.findings.isEmpty()) Text("با داده‌ی این دوره عارضه‌ای پیدا نشد.", style = SabouType.body, color = Sabou.colors.muted)
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
                            }
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
                                            k.target?.let { "هدف ${Assessments.targetText(k)}" },
                                            k.score?.let { "امتیاز ${Fa.number(it.toLong())}" },
                                            Assessments.qualityName(k.quality),
                                            k.source.takeIf { it.isNotBlank() },
                                        ).joinToString(" · "), style = SabouType.caption, color = Sabou.colors.muted)
                                        if (k.note.isNotBlank()) Text(k.note, style = SabouType.caption, color = Sabou.colors.muted)
                                    }
                                }
                            }
                        }

                        if (a.dataRequests.isNotEmpty()) {
                            SectionTitle("داده‌ی لازم برای سنجش کامل")
                            SCard { a.dataRequests.forEach { Text("• ${it.dimension.title}: ${it.text}", style = SabouType.caption, color = Sabou.colors.ink) } }
                        }
                        Text("همه‌ی ارقام از دفاتر و ثبت‌های همین برنامه است و با قواعد ثابت محاسبه شده؛ اقدام‌ها پیشنهادند و گزارش چیزی را ثبت یا تغییر نمی‌دهد.",
                            style = SabouType.caption, color = Sabou.colors.muted)
                    }
                    ExportButtons("کارنامه-و-عارضه‌یابی") { ReportTables.assessment(assessments.of(branch, from, to)) }
                }
            }
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
