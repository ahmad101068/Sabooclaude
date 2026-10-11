package ir.sabou.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
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
import ir.sabou.app.R
import ir.sabou.app.ui.LocalSession
import ir.sabou.app.ui.Nav
import ir.sabou.app.ui.components.Banner
import ir.sabou.app.ui.components.Chip
import ir.sabou.app.ui.components.ChipKind
import ir.sabou.app.ui.components.Choice
import ir.sabou.app.ui.components.Divider
import ir.sabou.app.ui.components.EmptyState
import ir.sabou.app.ui.components.FormCard
import ir.sabou.app.ui.components.Header
import ir.sabou.app.ui.components.NavRow
import ir.sabou.app.ui.components.Page
import ir.sabou.app.ui.components.Picker
import ir.sabou.app.ui.components.PrimaryButton
import ir.sabou.app.ui.components.SecondaryButton
import ir.sabou.app.ui.components.Segmented
import ir.sabou.app.ui.components.TextInput
import ir.sabou.app.ui.load
import ir.sabou.app.ui.orNull
import ir.sabou.app.ui.rememberAction
import ir.sabou.app.ui.rememberCommandId
import ir.sabou.app.ui.rememberValueList
import ir.sabou.app.ui.theme.Sabou
import ir.sabou.app.ui.theme.SabouType
import ir.sabou.core.Fa
import ir.sabou.inventory.CountFrequency
import ir.sabou.inventory.CountPlan
import ir.sabou.inventory.SaveCountPlan
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Scope
import ir.sabou.platform.Permission

/** Repeating counts (ADR-0031): which items of which store, how often. */
object CountPlanScreens {
    /** Share of the stock value that marks the «A» items (the few that hold most of the money). */
    private const val A_SHARE_BP = 7_000L

    @Composable
    fun Plans(nav: Nav) {
        val session = LocalSession.current
        Column(Modifier.fillMaxSize()) {
            Header("برنامه‌های شمارش", onBack = nav.back) { BranchSwitcher() }
            WithBranch { branch ->
                val data by load(session, branch) { overview.countPlans(branch) to overview.locations(branch).filter { it.isActive } }
                var editing by rememberSaveable(branch) { mutableStateOf<GlobalId?>(null) }
                var creating by rememberSaveable(branch) { mutableStateOf(false) }
                Page {
                    Text("کالاهای گران یا پرمصرف (گوشت، پنیر، زعفران) را هر روز بشمارید و بقیه را هفتگی یا ماهانه. موعد هر شمارش روی صفحه‌ی اصلی و با اعلان گوشی یادآوری می‌شود.",
                        style = SabouType.caption, color = Sabou.colors.muted)
                    Loaded(data) { (plans, locations) ->
                        if (plans.isEmpty() && !creating) EmptyState("هنوز برنامه‌ای نیست.")
                        plans.forEach { p ->
                            if (editing == p.id) key(p.id) { PlanForm(branch, p, locations) { editing = null } }
                            else NavRow(R.drawable.ic_count, p.name, countFrequencyText(p.frequency) + " · " +
                                (locations.firstOrNull { it.id == p.locationId }?.name ?: "") + " · " +
                                (if (p.itemIds.isEmpty()) "همه‌ی کالاها" else "${Fa.number(p.itemIds.size.toLong())} کالا") + if (p.isActive) "" else " · غیرفعال",
                                onClick = { editing = p.id; creating = false })
                        }
                        if (creating) PlanForm(branch, null, locations) { creating = false }
                        else if (session.can(Permission.INVENTORY_ADJUST)) SecondaryButton("برنامه‌ی تازه", { creating = true; editing = null })
                    }
                }
            }
        }
    }

    @Composable
    private fun PlanForm(branch: Scope.Branch, plan: CountPlan?, locations: List<ir.sabou.inventory.Location>, onDone: () -> Unit) {
        val session = LocalSession.current
        var name by rememberSaveable { mutableStateOf(plan?.name.orEmpty()) }
        var locationId by rememberSaveable { mutableStateOf(plan?.locationId ?: locations.firstOrNull()?.id) }
        var kind by rememberSaveable { mutableStateOf((plan?.frequency?.kind ?: CountFrequency.Kind.DAILY).ordinal) }
        var weekday by rememberSaveable { mutableStateOf(if (plan?.frequency?.kind == CountFrequency.Kind.WEEKLY) plan.frequency.day else 0) }
        var monthDay by rememberSaveable { mutableStateOf(if (plan?.frequency?.kind == CountFrequency.Kind.MONTHLY) Fa.number(plan.frequency.day.toLong()) else Fa.number(1L)) }
        var all by rememberSaveable { mutableStateOf(plan != null && plan.itemIds.isEmpty()) }
        var active by rememberSaveable { mutableStateOf(plan?.isActive ?: true) }
        val chosen = rememberValueList { plan?.itemIds.orEmpty() }
        val id = rememberCommandId(plan?.id)
        val action = rememberAction()
        val items by load(session, locationId) {
            val values = locationId?.let { loc -> overview.stock(loc).associate { it.itemId to it.value.rial } }.orEmpty()
            overview.items().filter { it.isActive }.sortedWith(compareBy({ it.shelf.ifBlank { "￿" } }, { it.name })) to values
        }
        FormCard(plan?.name ?: "برنامه‌ی تازه") {
            TextInput("نام", name, { name = it }, placeholder = "مثلاً «گران‌ها، روزانه»")
            if (locations.size > 1) Picker("انبار", locations.map { Choice(it.id, it.name) }, locationId, { locationId = it })
            Segmented(listOf("روزانه", "هفتگی", "ماهانه"), kind, { kind = it })
            val frequencyKind = CountFrequency.Kind.entries[kind]
            if (frequencyKind == CountFrequency.Kind.WEEKLY) Picker("روز هفته", WEEKDAYS.mapIndexed { i, d -> Choice(i, d) }, weekday, { weekday = it })
            val dayNumber = Fa.latinDigits(monthDay.trim()).toIntOrNull()
            val dayBad = frequencyKind == CountFrequency.Kind.MONTHLY && (dayNumber == null || dayNumber !in 1..31)
            if (frequencyKind == CountFrequency.Kind.MONTHLY) TextInput("روز ماه (۱ تا ۳۱؛ ماه کوتاه‌تر: روز آخر)", monthDay, { monthDay = it },
                keyboard = KeyboardType.Number, error = if (dayBad) "عددی بین ۱ و ۳۱" else null)
            Divider()
            Row(Modifier.clickable { all = !all }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = all, onCheckedChange = { all = it })
                Text("همه‌ی کالاها (شمارش کامل)", style = SabouType.body, color = Sabou.colors.ink)
            }
            val loaded = items.orNull()
            if (!all && loaded != null) {
                val (list, values) = loaded
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${Fa.number(chosen.size.toLong())} کالا انتخاب شده", style = SabouType.bodyStrong, color = Sabou.colors.ink, modifier = Modifier.weight(1f))
                    Text("پیشنهاد: پرارزش‌ها", style = SabouType.label, color = Sabou.colors.primary, modifier = Modifier.clickable {
                        chosen.clear(); chosen.addAll(highValue(values))
                    }.padding(6.dp))
                }
                Text("«پرارزش‌ها» کالاهایی هستند که روی هم ۷۰٪ ارزش موجودی این انبار را دارند.", style = SabouType.caption, color = Sabou.colors.muted)
                list.forEach { item ->
                    Row(Modifier.clickable { if (item.id in chosen) chosen.remove(item.id) else chosen.add(item.id) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = item.id in chosen, onCheckedChange = { if (it) chosen.add(item.id) else chosen.remove(item.id) })
                        Text(item.name, style = SabouType.body, color = Sabou.colors.ink, modifier = Modifier.weight(1f))
                        values[item.id]?.takeIf { it > 0 }?.let { Text(Fa.rialShort(it), style = SabouType.caption, color = Sabou.colors.muted) }
                    }
                }
            }
            if (plan != null) Row(Modifier.clickable { active = !active }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = active, onCheckedChange = { active = it })
                Text("فعال", style = SabouType.body, color = Sabou.colors.ink)
            }
            if (!active) Chip("غیرفعال: دیگر یادآوری نمی‌شود", ChipKind.NEUTRAL)
            action.error?.let { Banner(it) }
            PrimaryButton("ذخیره", {
                val frequency = when (frequencyKind) {
                    CountFrequency.Kind.DAILY -> CountFrequency(frequencyKind)
                    CountFrequency.Kind.WEEKLY -> CountFrequency(frequencyKind, weekday)
                    CountFrequency.Kind.MONTHLY -> CountFrequency(frequencyKind, dayNumber!!)
                }
                action.run({
                    countPlanOps.save(SaveCountPlan(id.value, branch, plan?.id, name, locationId!!, if (all) emptyList() else chosen.toList(), frequency, active))
                }) { onDone() }
            }, enabled = session.can(Permission.INVENTORY_ADJUST) && name.trim().length >= 2 && locationId != null && !dayBad && (all || chosen.isNotEmpty()),
                busy = action.busy)
            SecondaryButton("انصراف", onDone)
        }
    }

    /** The items that together hold [A_SHARE_BP] of the stock value, most valuable first. */
    private fun highValue(values: Map<GlobalId, Long>): List<GlobalId> {
        val total = values.values.filter { it > 0 }.sum()
        if (total <= 0) return emptyList()
        var running = 0L
        val out = ArrayList<GlobalId>()
        for ((item, v) in values.entries.filter { it.value > 0 }.sortedByDescending { it.value }) {
            if (running * 10_000 >= total * A_SHARE_BP) break
            out += item
            running += v
        }
        return out
    }
}
