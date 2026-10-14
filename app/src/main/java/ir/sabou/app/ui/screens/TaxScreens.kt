package ir.sabou.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ir.sabou.app.ui.LocalSession
import ir.sabou.app.ui.Nav
import ir.sabou.app.ui.components.Banner
import ir.sabou.app.ui.components.ChipKind
import ir.sabou.app.ui.components.DateInput
import ir.sabou.app.ui.components.FormCard
import ir.sabou.app.ui.components.Header
import ir.sabou.app.ui.components.KeyValue
import ir.sabou.app.ui.components.Page
import ir.sabou.app.ui.components.PrimaryButton
import ir.sabou.app.ui.components.SCard
import ir.sabou.app.ui.components.SecondaryButton
import ir.sabou.app.ui.components.TextInput
import ir.sabou.app.ui.load
import ir.sabou.app.ui.orNull
import ir.sabou.app.ui.rememberAction
import ir.sabou.app.ui.theme.Sabou
import ir.sabou.app.ui.theme.SabouType
import ir.sabou.core.Fa
import ir.sabou.kernel.GlobalId
import ir.sabou.platform.Permission
import ir.sabou.sales.SetCustomerTaxInfo
import ir.sabou.sales.SetItemTaxCode
import ir.sabou.sales.SetTaxProfile
import ir.sabou.sales.VatRate

/** The seller's tax identity, VAT rates and the data the tax system will need (ADR-0037). Nothing is sent from here. */
object TaxScreens {
    /** The service charge the sale form works out (after discount); none when blank. */
    @Composable
    private fun ServiceCharge() {
        val session = LocalSession.current
        val current by load(session) { salesOps.serviceCharge() }
        var percent by rememberSaveable { mutableStateOf("") }
        val action = rememberAction()
        FormCard("حق سرویس") {
            KeyValue("حق سرویس فعلی", current.orNull()?.let(Fa::percent) ?: "ندارد")
            TextInput("درصد (مثلاً ۱۰؛ خالی یعنی ندارد)", percent, { percent = it }, keyboard = KeyboardType.Decimal)
            Text("در فرم فروش، «محاسبه‌ی خودکار» حق سرویس را از فروش پس از تخفیف و مالیات را از فروش به‌اضافه‌ی حق سرویس حساب می‌کند؛ عدد را می‌شود دستی عوض کرد.",
                style = SabouType.caption, color = Sabou.colors.muted)
            action.error?.let { Banner(it) }
            val bp = Fa.latinDigits(percent.trim()).replace('٫', '.').toBigDecimalOrNull()?.movePointRight(2)?.toLong()
            PrimaryButton("ذخیره", { action.run({ salesOps.setServiceCharge(ir.sabou.sales.SetServiceCharge(GlobalId.new(), bp?.takeIf { it > 0 })) }) { percent = "" } },
                enabled = (percent.isBlank() || bp != null) && !action.busy, busy = action.busy)
        }
    }

    @Composable
    fun Settings(nav: Nav) {
        val session = LocalSession.current
        val data by load(session) { moadian.readiness(session.today) }
        val action = rememberAction()
        Column(Modifier.fillMaxSize()) {
            Header("اطلاعات مالیاتی", "آمادگی برای سامانه‌ی مودیان", onBack = nav.back)
            Page {
                Text("صورتحساب هر فیش را صندوق (سپند) برای سامانه‌ی مودیان صادر می‌کند. اینجا اطلاعاتی نگه داشته می‌شود که برای صورتحساب‌های با مشخصات خریدار (مشتریان حقوقی) و گزارش‌های مالیاتی لازم است. چیزی از اینجا ارسال نمی‌شود.",
                    style = SabouType.caption, color = Sabou.colors.muted)
                Loaded(data) { r ->
                    if (r.ready) Banner("اطلاعات مالیاتی کامل است.", ChipKind.PRIMARY, ir.sabou.app.R.drawable.ic_check)
                    else SCard { r.problems.forEach { Text("• $it", style = SabouType.body, color = Sabou.colors.ink) } }
                    action.error?.let { Banner(it) }
                    val p = r.profile
                    var seller by rememberSaveable { mutableStateOf(p?.sellerTaxId.orEmpty()) }
                    var memory by rememberSaveable { mutableStateOf(p?.memoryId.orEmpty()) }
                    var vatFrom by rememberSaveable { mutableStateOf(session.today) }
                    var vatPercent by rememberSaveable { mutableStateOf("") }
                    FormCard("فروشنده") {
                        TextInput("کد ملی / شناسه‌ی ملی / کد اقتصادی", seller, { seller = it }, keyboard = KeyboardType.Number)
                        TextInput("شناسه‌ی یکتای حافظه‌ی مالیاتی (از کارپوشه)", memory, { memory = it.uppercase() }, placeholder = "مثلاً A1B2C3")
                        Text("نرخ مالیات بر ارزش افزوده", style = SabouType.bodyStrong, color = Sabou.colors.ink)
                        p?.vatRates?.forEach { KeyValue("از ${Fa.date(it.from)}", Fa.percent(it.bp)) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { DateInput("از تاریخ", vatFrom, { vatFrom = it }, session.today) }
                            Column(Modifier.weight(1f)) { TextInput("درصد (مثلاً ۱۰)", vatPercent, { vatPercent = it }, keyboard = KeyboardType.Decimal) }
                        }
                        Text("نرخ را قانون بودجه‌ی هر سال تعیین می‌کند؛ نرخ تازه را با تاریخ شروعش اضافه کنید.", style = SabouType.caption, color = Sabou.colors.muted)
                        val newBp = Fa.latinDigits(vatPercent.trim()).replace('٫', '.').toBigDecimalOrNull()?.movePointRight(2)?.toLong()
                        PrimaryButton("ذخیره", {
                            val rates = p?.vatRates.orEmpty().filter { it.from != vatFrom } + listOfNotNull(newBp?.let { VatRate(vatFrom, it) })
                            action.run({ taxOps.setProfile(SetTaxProfile(GlobalId.new(), seller, memory, rates)) }) { vatPercent = "" }
                        }, enabled = session.can(Permission.TAX_SETTINGS) && seller.isNotBlank() && (newBp != null || !p?.vatRates.isNullOrEmpty()) && !action.busy, busy = action.busy)
                    }
                    if (session.can(Permission.MENU_PRICE_MANAGE)) ServiceCharge()
                    if (r.dishesWithoutId.isNotEmpty() || r.codes.isNotEmpty()) FormCard("شناسه‌ی کالا/خدمت آیتم‌های منو") {
                        Text("شناسه‌ی ۱۳ رقمی هر غذا را از سامانه‌ی شناسه‌ی کالا و خدمات (stuffid.tax.gov.ir) بگیرید؛ کد واحد برای «پرس» یا «عدد» را از جدول واحدهای سامانه.",
                            style = SabouType.caption, color = Sabou.colors.muted)
                        r.dishesWithoutId.forEach { dish ->
                            key(dish.id) {
                                var product by rememberSaveable { mutableStateOf("") }
                                var unit by rememberSaveable { mutableStateOf("") }
                                Text(dish.name, style = SabouType.bodyStrong, color = Sabou.colors.ink)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextInput("شناسه‌ی ۱۳ رقمی", product, { product = it }, Modifier.weight(2f), keyboard = KeyboardType.Number)
                                    TextInput("کد واحد", unit, { unit = it }, Modifier.weight(1f), keyboard = KeyboardType.Number)
                                }
                                SecondaryButton("ثبت", { action.run({ taxOps.setItemCode(SetItemTaxCode(GlobalId.new(), dish.id, product, unit)) }) },
                                    enabled = session.can(Permission.TAX_SETTINGS) && product.isNotBlank() && !action.busy)
                            }
                        }
                        if (r.dishesWithoutId.isEmpty()) Text("همه‌ی آیتم‌های فعال منو شناسه دارند.", style = SabouType.caption, color = Sabou.colors.primary)
                    }
                    if (r.companiesWithoutId.isNotEmpty()) FormCard("مشتریان حقوقی") {
                        r.companiesWithoutId.forEach { c ->
                            key(c.id) {
                                var taxId by rememberSaveable { mutableStateOf("") }
                                var postal by rememberSaveable { mutableStateOf("") }
                                Text(c.name, style = SabouType.bodyStrong, color = Sabou.colors.ink)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextInput("شناسه‌ی ملی یا کد اقتصادی", taxId, { taxId = it }, Modifier.weight(1f), keyboard = KeyboardType.Number)
                                    TextInput("کد پستی", postal, { postal = it }, Modifier.weight(1f), keyboard = KeyboardType.Number)
                                }
                                SecondaryButton("ثبت", { action.run({ taxOps.setCustomerTaxInfo(SetCustomerTaxInfo(GlobalId.new(), c.registeredIn, c.id, taxId, postal)) }) },
                                    enabled = session.can(Permission.CUSTOMER_MANAGE) && taxId.isNotBlank() && !action.busy)
                            }
                        }
                    }
                }
            }
        }
    }
}
