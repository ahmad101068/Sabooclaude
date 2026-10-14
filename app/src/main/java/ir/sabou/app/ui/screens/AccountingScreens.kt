package ir.sabou.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import ir.sabou.app.ui.LocalSession
import ir.sabou.app.ui.Nav
import ir.sabou.app.ui.components.Banner
import ir.sabou.app.ui.components.DateInput
import ir.sabou.app.ui.components.FormCard
import ir.sabou.app.ui.components.Header
import ir.sabou.app.ui.components.KeyValue
import ir.sabou.app.ui.components.MoneyInput
import ir.sabou.app.ui.components.Page
import ir.sabou.app.ui.components.Picker
import ir.sabou.app.ui.components.PrimaryButton
import ir.sabou.app.ui.components.SCard
import ir.sabou.app.ui.components.TextInput
import ir.sabou.app.ui.load
import ir.sabou.app.ui.rememberAction
import ir.sabou.app.ui.theme.Sabou
import ir.sabou.app.ui.theme.SabouType
import ir.sabou.core.Fa
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.platform.Permission

/** Accounting screens of the review of the other modules (ADR-0045). */
object AccountingScreens {

    /** VAT and levies the branch's sales owe, paid to the tax office (net of VAT paid on purchases). */
    @Composable
    fun SalesTax(nav: Nav) {
        val session = LocalSession.current
        Column(Modifier.fillMaxSize()) {
            Header("مالیات و عوارض فروش", onBack = nav.back) { BranchSwitcher() }
            WithBranch { branch ->
                val data by load(session, branch) { ledgerReads.salesTax(branch) to overview.paymentAccounts(branch) }
                var accountId by rememberSaveable { mutableStateOf<GlobalId?>(null) }
                var amount by rememberSaveable { mutableStateOf<Money?>(null) }
                var credit by rememberSaveable { mutableStateOf<Money?>(null) }
                var note by rememberSaveable { mutableStateOf("") }
                var date by rememberSaveable { mutableStateOf(session.today) }
                val id = rememberSaveable { mutableStateOf(GlobalId.new()) }
                val action = rememberAction()
                Page {
                    Loaded(data) { (tax, accounts) ->
                        SCard {
                            KeyValue("بدهی مالیات و عوارض فروش", Fa.rial(tax.owed) + " ریال", strong = true)
                            KeyValue("اعتبار مالیاتی خرید", Fa.rial(tax.inputCredit) + " ریال")
                        }
                        if (session.can(Permission.TREASURY_PAYMENT)) FormCard("پرداخت به اداره‌ی مالیات") {
                            Text("مبلغ اظهارنامه را وارد کنید؛ اگر اعتبار مالیاتی خرید دارید، بخشی که با آن تهاتر می‌شود از حساب پرداخت نمی‌شود.",
                                style = SabouType.caption, color = Sabou.colors.muted)
                            MoneyInput("مالیات و عوارض این دوره", amount, { amount = it })
                            if (!tax.inputCredit.isZero) MoneyInput("تهاتر با اعتبار خرید (حداکثر ${Fa.rial(tax.inputCredit)})", credit, { credit = it })
                            val cash = (amount?.rial ?: 0L) - (credit?.rial ?: 0L)
                            if (cash > 0) Picker("پرداخت از", accountChoices(accounts), accountId, { accountId = it })
                            TextInput("توضیح", note, { note = it }, placeholder = "مثلاً اظهارنامه‌ی پاییز")
                            DateInput("تاریخ", date, { date = it }, session.today)
                            KeyValue("پرداخت از حساب", Fa.rial(maxOf(0L, cash)) + " ریال")
                            action.error?.let { Banner(it) }
                            PrimaryButton("ثبت پرداخت", {
                                action.run({
                                    salesOps.paySalesTax(ir.sabou.sales.PaySalesTax(id.value, branch, accountId ?: accounts.first().id, amount!!, date, note, credit ?: Money.ZERO))
                                }) { amount = null; credit = null; note = ""; id.value = GlobalId.new() }
                            }, enabled = amount != null && cash >= 0 && (cash == 0L || accountId != null) && accounts.isNotEmpty() && !action.busy, busy = action.busy)
                        }
                    }
                }
            }
        }
    }
}
