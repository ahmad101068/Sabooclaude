package ir.sabou.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ir.sabou.app.ui.LocalSession
import ir.sabou.app.ui.Nav
import ir.sabou.app.ui.Route
import ir.sabou.app.ui.components.Banner
import ir.sabou.app.ui.components.Chip
import ir.sabou.app.ui.components.ChipKind
import ir.sabou.app.ui.components.Confirm
import ir.sabou.app.ui.components.Divider
import ir.sabou.app.ui.components.FormCard
import ir.sabou.app.ui.components.Header
import ir.sabou.app.ui.components.Page
import ir.sabou.app.ui.components.PrimaryButton
import ir.sabou.app.ui.components.SCard
import ir.sabou.app.ui.components.SecondaryButton
import ir.sabou.app.ui.components.TextInput
import ir.sabou.app.ui.load
import ir.sabou.app.ui.rememberAction
import ir.sabou.app.ui.theme.Sabou
import ir.sabou.app.ui.theme.SabouType
import ir.sabou.core.CloseCheck
import ir.sabou.core.Fa
import ir.sabou.kernel.GlobalId
import ir.sabou.platform.Permission

/** Month-end close: the checklist, then the period lock (ADR-0036). */
object ClosingScreens {
    private val monthNames = listOf("فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور", "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند")

    @Composable
    fun MonthClose(nav: Nav) {
        val session = LocalSession.current
        val today = Fa.jalali(session.today)
        // Default: last month (this month is still running).
        var index by rememberSaveable { mutableStateOf(today.year * 12 + today.month - 2) }
        val year = Math.floorDiv(index, 12); val month = Math.floorMod(index, 12) + 1
        var confirm by rememberSaveable { mutableStateOf(false) }
        var reason by rememberSaveable { mutableStateOf("") }
        val action = rememberAction()
        val data by load(session, year, month) { closing.checklist(year, month) }
        Column(Modifier.fillMaxSize()) {
            Header("بستن ماه", "چک‌لیست پایان دوره", onBack = nav.back)
            Page {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("‹ ماه قبل", style = SabouType.label, color = Sabou.colors.primary, modifier = Modifier.clickable { index-- }.padding(8.dp))
                    Text("${monthNames[month - 1]} ${Fa.digits(year.toString())}", style = SabouType.section, color = Sabou.colors.ink, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                    Text("ماه بعد ›", style = SabouType.label, color = Sabou.colors.primary, modifier = Modifier.clickable { index++ }.padding(8.dp))
                }
                Loaded(data) { c ->
                    if (c.closed) Banner("این ماه بسته است؛ سندی با تاریخ این ماه ثبت نمی‌شود.", ChipKind.PRIMARY, ir.sabou.app.R.drawable.ic_check)
                    SCard {
                        c.checks.sortedBy { it.severity != CloseCheck.Severity.BLOCK }.sortedBy { it.severity == CloseCheck.Severity.OK }.forEach { check ->
                            val route = routeOf(check.key)
                            Row(Modifier.clickable(enabled = route != null && check.severity != CloseCheck.Severity.OK) { route?.let { nav.go(it) } }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(check.title, style = SabouType.bodyStrong, color = Sabou.colors.ink)
                                    Text(check.detail, style = SabouType.caption, color = Sabou.colors.muted)
                                }
                                when (check.severity) {
                                    CloseCheck.Severity.OK -> Chip("انجام شد", ChipKind.PRIMARY)
                                    CloseCheck.Severity.WARN -> Chip("بررسی", ChipKind.ACCENT)
                                    CloseCheck.Severity.BLOCK -> Chip("لازم", ChipKind.DANGER)
                                }
                            }
                            Divider()
                        }
                    }
                    action.error?.let { Banner(it) }
                    if (!c.closed && session.can(Permission.PERIOD_CLOSE)) {
                        if (c.blocked) Text("موارد «لازم» باید پیش از بستن انجام شوند.", style = SabouType.caption, color = Sabou.colors.danger)
                        PrimaryButton("بستن ${monthNames[month - 1]}", { confirm = true }, enabled = !c.blocked && c.to < session.today, busy = action.busy)
                        if (c.to >= session.today) Text("ماه هنوز تمام نشده است.", style = SabouType.caption, color = Sabou.colors.muted)
                    }
                    if (c.closed && session.can(Permission.PERIOD_REOPEN)) FormCard("باز کردن دوباره") {
                        TextInput("دلیل", reason, { reason = it })
                        SecondaryButton("باز کردن ماه", { action.run({ closing.reopen(GlobalId.new(), year, month, reason) }) { reason = "" } },
                            enabled = reason.trim().length >= 3 && !action.busy, danger = true)
                    }
                }
            }
        }
        if (confirm) Confirm("بستن ماه؟", "پس از بستن، هیچ سندی با تاریخ این ماه ثبت یا برگشت نمی‌شود مگر ماه دوباره باز شود.", "بستن",
            onConfirm = { confirm = false; action.run({ closing.close(GlobalId.new(), year, month) }) },
            onDismiss = { confirm = false })
    }

    private fun routeOf(key: String): Route? = when (key) {
        "SALES_DAYS", "SALE_DRAFTS" -> Route.Sales
        "REVIEW_QUEUE" -> Route.ReviewQueue
        "INVOICE_MATCH" -> Route.PendingApprovals
        "PENDING_COUNTS" -> Route.CountHistory
        "SHORTAGES" -> Route.Stock
        "MONTH_COUNT" -> Route.Count
        "PAYROLL" -> Route.Payroll
        "DEPRECIATION" -> Route.Assets
        "BANK_REC" -> Route.BankStatements
        "CHEQUES" -> Route.Cheques
        "INTER_BRANCH" -> Route.TrialBalance
        else -> null
    }
}
