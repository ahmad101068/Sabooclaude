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
import androidx.compose.ui.text.input.KeyboardType
import ir.sabou.app.ui.LocalSession
import ir.sabou.app.ui.Nav
import ir.sabou.app.ui.components.Choice
import ir.sabou.app.ui.components.Divider
import ir.sabou.app.ui.components.Header
import ir.sabou.app.ui.components.KeyValue
import ir.sabou.app.ui.components.Page
import ir.sabou.app.ui.components.Picker
import ir.sabou.app.ui.components.SCard
import ir.sabou.app.ui.components.SectionTitle
import ir.sabou.app.ui.components.TextInput
import ir.sabou.app.ui.load
import ir.sabou.app.ui.theme.Sabou
import ir.sabou.app.ui.theme.SabouType
import ir.sabou.core.Fa
import ir.sabou.kernel.GlobalId

/**
 * ADR-0062: «what if» on prices — an ingredient dearer or cheaper, and the menu up or down — replayed on the last
 * four weeks' sales, with the price each dish would need to keep today's food-cost share. Nothing is recorded.
 */
object WhatIfScreen {

    private fun bp(text: String): Long? = Fa.latinDigits(text.trim()).replace("٫", ".").replace("/", ".").replace("−", "-")
        .toBigDecimalOrNull()?.movePointRight(2)?.setScale(0, java.math.RoundingMode.HALF_UP)?.toLong()

    @Composable
    fun Show(nav: Nav) {
        val session = LocalSession.current
        var item by rememberSaveable { mutableStateOf<String?>(null) }
        var itemPercent by rememberSaveable { mutableStateOf("15") }
        var menuPercent by rememberSaveable { mutableStateOf("0") }
        Column(Modifier.fillMaxSize()) {
            Header("اگر قیمت‌ها تغییر کند", "روی فروش ۴ هفته‌ی گذشته", onBack = nav.back) { BranchSwitcher() }
            WithBranch { branch ->
                val items by load(session) { overview.items().filter { it.isActive }.sortedBy { it.name } }
                Page {
                    Loaded(items) { list ->
                        Picker("کدام کالا گران یا ارزان می‌شود؟", list.map { Choice(it.id.value, it.name) }, item, { item = it })
                    }
                    TextInput("تغییر قیمت کالا (درصد، مثلاً ۱۵ یا ۱۰-)", itemPercent, { itemPercent = it }, keyboard = KeyboardType.Text)
                    TextInput("تغییر قیمت همه‌ی غذاهای منو (درصد)", menuPercent, { menuPercent = it }, keyboard = KeyboardType.Text)
                    val itemBp = bp(itemPercent); val menuBp = bp(menuPercent)
                    if (itemBp != null && menuBp != null && (item != null || menuBp != 0L)) {
                        val result by load(session, branch, item, itemBp, menuBp) {
                            val to = session.today; val from = to.plusDays(-27)
                            val changes = item?.let { mapOf(GlobalId.parse(it) to itemBp) }.orEmpty()
                            val dishes = reports.productMix(branch, from, to).rows.map { it.menuItemId }
                            whatIfs.prices(branch, from, to, changes, if (menuBp == 0L) emptyMap() else dishes.associateWith { menuBp })
                        }
                        Loaded(result) { w ->
                            SCard {
                                KeyValue("اثر بر سود این ۴ هفته (ریال)", (if (w.profitChange > 0) "+" else "") + Fa.rial(w.profitChange),
                                    if (w.profitChange < 0) Sabou.colors.danger else Sabou.colors.primary, strong = true)
                                KeyValue("درصد بهای غذا", "${w.foodBpNow?.let(Fa::percent) ?: "—"} ← ${w.foodBpNew?.let(Fa::percent) ?: "—"}")
                                KeyValue("فروش غذاهای حساب‌شده (ریال)", "${Fa.rial(w.salesNow)} ← ${Fa.rial(w.salesNew)}")
                                KeyValue("بهای مواد (ریال)", "${Fa.rial(w.costNow)} ← ${Fa.rial(w.costNew)}")
                            }
                            SectionTitle("غذاها", trailing = Fa.number(w.dishes.size.toLong()))
                            w.dishes.filter { it.costNew != it.costNow || it.priceNew != it.priceNow }.forEach { d ->
                                SCard {
                                    KeyValue(d.name, (if (d.profitChange > 0) "+" else "") + Fa.rial(d.profitChange), if (d.profitChange < 0) Sabou.colors.danger else Sabou.colors.primary, strong = true)
                                    Text("${Fa.quantity(d.portions)} پرس · بهای مواد هر پرس ${Fa.rial(d.costNow)} ← ${Fa.rial(d.costNew)} · قیمت ${Fa.rial(d.priceNow)} ← ${Fa.rial(d.priceNew)}",
                                        style = SabouType.caption, color = Sabou.colors.muted)
                                    d.suggestedPrice?.takeIf { it != d.priceNow }?.let {
                                        Divider()
                                        Text("قیمت لازم برای حفظ درصد بهای غذای امروز: ${Fa.rial(it)} ریال", style = SabouType.caption, color = Sabou.colors.primary)
                                    }
                                }
                            }
                            if (w.leftOut.isNotEmpty()) Text("بی رسپی یا بهای نامعلوم، حساب نشد: ${w.leftOut.joinToString("، ")}", style = SabouType.caption, color = Sabou.colors.muted)
                            Text("فرض: همان تعداد پرس فروش می‌رود. چیزی ثبت نمی‌شود؛ برای تغییر قیمت منو از «قیمت‌های منو» استفاده کنید.",
                                style = SabouType.caption, color = Sabou.colors.muted)
                        }
                    } else Text("کالا و درصد تغییر را بنویسید.", style = SabouType.body, color = Sabou.colors.muted)
                }
            }
        }
    }
}
