package ir.sabou.app.ui.screens

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
import ir.sabou.app.R
import ir.sabou.app.ui.LocalSession
import ir.sabou.app.ui.Nav
import ir.sabou.app.ui.Route
import ir.sabou.app.ui.components.Banner
import ir.sabou.app.ui.components.Chip
import ir.sabou.app.ui.components.ChipKind
import ir.sabou.app.ui.components.Choice
import ir.sabou.app.ui.components.DateInput
import ir.sabou.app.ui.components.Divider
import ir.sabou.app.ui.components.EmptyState
import ir.sabou.app.ui.components.FormCard
import ir.sabou.app.ui.components.Header
import ir.sabou.app.ui.components.KeyValue
import ir.sabou.app.ui.components.NavRow
import ir.sabou.app.ui.components.Page
import ir.sabou.app.ui.components.Picker
import ir.sabou.app.ui.components.QuantityInput
import ir.sabou.app.ui.components.SCard
import ir.sabou.app.ui.components.SecondaryButton
import ir.sabou.app.ui.load
import ir.sabou.app.ui.orNull
import ir.sabou.app.ui.rememberAction
import ir.sabou.app.ui.rememberValueMap
import ir.sabou.app.ui.theme.Sabou
import ir.sabou.app.ui.theme.SabouType
import ir.sabou.core.Fa
import ir.sabou.core.PlanLine
import ir.sabou.inventory.RecordProduction
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Quantity
import ir.sabou.platform.Permission

/** The kitchen's day (ADR-0033): expected dishes, what to prepare in which order, what is missing; allergens. */
object PrepScreens {
    @Composable
    fun PrepPlan(nav: Nav) {
        val session = LocalSession.current
        Column(Modifier.fillMaxSize()) {
            Header("برنامه‌ی آماده‌سازی", "پیش‌بینی فروش، اقلام آماده، کمبود مواد", onBack = nav.back) { BranchSwitcher() }
            WithBranch { branch ->
                var date by rememberSaveable { mutableStateOf(session.today) }
                var locationId by rememberSaveable(branch) { mutableStateOf<GlobalId?>(null) }
                var refresh by rememberSaveable { mutableStateOf(0) }
                val portions = rememberValueMap<GlobalId, Quantity?>()
                val added = rememberValueMap<GlobalId, Boolean>()
                val base by load(session, branch, date) {
                    Triple(prep.forecast(branch, date), overview.menu().map { it.item }.filter { it.isActive }, overview.locations(branch).filter { it.isActive })
                }
                val action = rememberAction()
                Page {
                    NavRow(R.drawable.ic_alert, "آلرژن‌های منو", "از مواد و اقلام آماده‌ی هر غذا", onClick = { nav.go(Route.Allergens) })
                    Loaded(base) { (forecast, menu, locations) ->
                        val loc = locationId ?: locations.firstOrNull()?.id
                        FormCard("فروش مورد انتظار") {
                            DateInput("روز", date, { date = it; portions.clear(); added.clear() }, session.today)
                            if (locations.size > 1) Picker("آشپزخانه", locations.map { Choice(it.id, it.name) }, loc, { locationId = it })
                            Text(if (forecast.isEmpty()) "برای این روز هفته فروشی در چهار هفته‌ی گذشته نیست؛ تعداد هر غذا را وارد کنید."
                                else "پیش‌فرض: میانگین همین روز هفته در ${Fa.number(forecast.maxOf { it.weeks }.toLong())} هفته‌ی گذشته. هر عدد را می‌توانید تغییر دهید.",
                                style = SabouType.caption, color = Sabou.colors.muted)
                            val rows = forecast.map { it.item } + menu.filter { m -> added[m.id] == true && forecast.none { it.item.id == m.id } }
                            rows.forEach { dish ->
                                key(dish.id, date) {
                                    QuantityInput(dish.name, "پرس", { portions[dish.id] = it }, blankAs = Quantity.ZERO,
                                        value = portions[dish.id] ?: forecast.firstOrNull { it.item.id == dish.id }?.portions)
                                }
                            }
                            val more = menu.filter { m -> rows.none { it.id == m.id } }
                            if (more.isNotEmpty()) Picker("افزودن غذا", more.map { Choice(it.id, it.name) }, null, { added[it] = true }, placeholder = "غذای دیگر")
                        }
                        val plan = forecast.associate { it.item.id to it.portions } + portions.entries.mapNotNull { (k, v) -> v?.let { k to it } }
                        val lines = plan.filter { !it.value.isZero }.map { PlanLine(it.key, it.value) }
                        if (loc != null) {
                            val result by load(session, loc, date, lines, refresh) { prep.plan(loc, date, lines) }
                            Loaded(result) { p ->
                                p.problems.forEach { Banner("${it.name}: ${it.detail}", ChipKind.ACCENT) }
                                SCard {
                                    Text("آماده‌سازی", style = SabouType.section, color = Sabou.colors.ink)
                                    if (p.tasks.isEmpty()) Text("چیزی برای آماده کردن نیست؛ موجودی اقلام آماده کافی است.", style = SabouType.caption, color = Sabou.colors.muted)
                                    else Text("به همین ترتیب (اول آنچه بقیه از آن ساخته می‌شوند).", style = SabouType.caption, color = Sabou.colors.muted)
                                    p.tasks.forEachIndexed { i, t ->
                                        val unit = unitName(t.item.unit)
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f)) {
                                                Text("${Fa.number(i + 1L)}. ${t.item.name}: ${Fa.quantity(t.toMake)} $unit", style = SabouType.bodyStrong, color = Sabou.colors.ink)
                                                Text("لازم ${Fa.quantity(t.required)}" + (if (!t.par.isZero) " · ذخیره ${Fa.quantity(t.par)}" else "") + " · موجود ${Fa.quantity(t.onHand)}",
                                                    style = SabouType.caption, color = Sabou.colors.muted)
                                            }
                                            if (session.can(Permission.INVENTORY_PRODUCE) && date == session.today) SecondaryButton("تولید شد", {
                                                action.run({ inventory.produce(RecordProduction(GlobalId.new(), branch, loc, t.item.id, t.toMake, date)) }) { refresh++ }
                                            }, enabled = !action.busy)
                                        }
                                        Divider()
                                    }
                                    action.error?.let { Banner(it) }
                                }
                                if (p.shortages.isNotEmpty()) SCard {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("کمبود مواد", style = SabouType.section, color = Sabou.colors.ink, modifier = Modifier.weight(1f))
                                        Chip(Fa.number(p.shortages.size.toLong()), ChipKind.DANGER)
                                    }
                                    p.shortages.forEach { s ->
                                        KeyValue(s.item.name, "لازم ${Fa.quantity(s.needed)} · موجود ${Fa.quantity(s.onHand)} ${unitName(s.item.unit)}", Sabou.colors.danger)
                                    }
                                    if (session.can(Permission.PURCHASE_ORDER)) SecondaryButton("پیشنهاد خرید", { nav.go(Route.Suggestions) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun Allergens(nav: Nav) {
        val session = LocalSession.current
        val data by load(session) { prep.allergens(session.today) }
        Column(Modifier.fillMaxSize()) {
            Header("آلرژن‌های منو", onBack = nav.back)
            Page {
                Text("از آلرژن‌های ثبت‌شده روی هر کالا (مشخصات کالا) و همه‌ی اقلام آماده‌ی داخل رسپی جمع می‌شود. اگر کالایی آلرژن ثبت‌نشده داشته باشد اینجا نمی‌آید.",
                    style = SabouType.caption, color = Sabou.colors.muted)
                Loaded(data) { list ->
                    if (list.isEmpty()) EmptyState("آیتم منویی تعریف نشده است.")
                    SCard {
                        list.forEach { d ->
                            KeyValue(d.item.name, if (d.allergens.isEmpty()) "—" else d.allergens.joinToString("، "),
                                if (d.allergens.isEmpty()) Sabou.colors.muted else Sabou.colors.ink)
                            Divider()
                        }
                    }
                }
            }
        }
    }
}
