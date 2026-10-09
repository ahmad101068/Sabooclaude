package ir.sabou.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import ir.sabou.app.ui.LocalSession
import ir.sabou.app.ui.Nav
import ir.sabou.app.ui.components.Banner
import ir.sabou.app.ui.components.ChipKind
import ir.sabou.app.ui.components.Choice
import ir.sabou.app.ui.components.DateInput
import ir.sabou.app.ui.components.Divider
import ir.sabou.app.ui.components.EmptyState
import ir.sabou.app.ui.components.FormCard
import ir.sabou.app.ui.components.Header
import ir.sabou.app.ui.components.KeyValue
import ir.sabou.app.ui.components.Page
import ir.sabou.app.ui.components.Picker
import ir.sabou.app.ui.components.PrimaryButton
import ir.sabou.app.ui.components.QuantityInput
import ir.sabou.app.ui.components.SCard
import ir.sabou.app.ui.components.SecondaryButton
import ir.sabou.app.ui.components.TextInput
import ir.sabou.app.ui.load
import ir.sabou.app.ui.rememberAction
import ir.sabou.app.ui.theme.Sabou
import ir.sabou.app.ui.theme.SabouType
import ir.sabou.core.Fa
import ir.sabou.inventory.PublishPrepRecipe
import ir.sabou.inventory.RecipeLine
import ir.sabou.inventory.RecordProduction
import ir.sabou.inventory.UpdateItem
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Quantity
import ir.sabou.platform.Permission

/** Item details (par level, shelf, allergens), prep recipes and production of prepared items. */
object KitchenScreens {

    // ------------------------------------------------------------ Item details

    @Composable
    fun ItemEdit(nav: Nav, itemId: GlobalId) {
        val session = LocalSession.current
        val data by load(session, itemId) { overview.items().first { it.id == itemId } }
        Column(Modifier.fillMaxSize()) {
            Header("مشخصات کالا", onBack = nav.back)
            Page {
                Loaded(data) { item ->
                    var name by rememberSaveable { mutableStateOf(item.name) }
                    var minimum by rememberSaveable { mutableStateOf<Quantity?>(item.minimumStock) }
                    var par by rememberSaveable { mutableStateOf<Quantity?>(item.parLevel) }
                    var shelf by rememberSaveable { mutableStateOf(item.shelf) }
                    var allergens by rememberSaveable { mutableStateOf(item.allergens) }
                    var active by rememberSaveable { mutableStateOf(item.isActive) }
                    val id = ir.sabou.app.ui.rememberCommandId(itemId)
                    val action = rememberAction()
                    val unit = unitName(item.unit)
                    FormCard(item.name) {
                        Text("واحد: $unit" + if (item.prepared) " · تولید داخلی" else "", style = SabouType.caption, color = Sabou.colors.muted)
                        TextInput("نام کالا", name, { name = it })
                        QuantityInput("حداقل موجودی (نقطه‌ی سفارش)", unit, { minimum = it }, blankAs = Quantity.ZERO, value = item.minimumStock)
                        QuantityInput("سطح مطلوب (سفارش تا این مقدار)", unit, { par = it }, blankAs = Quantity.ZERO, value = item.parLevel)
                        TextInput("محل نگهداری (قفسه)", shelf, { shelf = it }, placeholder = "مثلاً یخچال ۱ · طبقه ۲")
                        TextInput("آلرژن‌ها", allergens, { allergens = it }, placeholder = "مثلاً گلوتن، لبنیات، بادام‌زمینی")
                        Row(Modifier.clickable { active = !active }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = active, onCheckedChange = { active = it })
                            Text("فعال (در فهرست‌ها نمایش داده شود)", style = SabouType.body, color = Sabou.colors.ink)
                        }
                        val parBad = par != null && minimum != null && !par!!.isZero && par!! < minimum!!
                        if (parBad) Banner("سطح مطلوب نباید کمتر از حداقل موجودی باشد.", ChipKind.ACCENT)
                        action.error?.let { Banner(it) }
                        PrimaryButton("ذخیره", {
                            action.run({
                                inventory.updateItem(UpdateItem(id.value, item.id, name, minimum!!, par!!, shelf, allergens, item.preferredSupplierId, item.approvedSupplierIds, active))
                            }) { nav.back() }
                        }, enabled = session.can(Permission.INVENTORY_ITEM_MANAGE) && name.trim().length >= 2 && minimum != null && par != null && !parBad, busy = action.busy)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ Prep recipes

    private class PrepRow(item: GlobalId?, qty: Quantity?, yieldText: String) {
        var item by mutableStateOf(item)
        var qty by mutableStateOf(qty)
        var yieldText by mutableStateOf(yieldText)
        val yieldPercent: Int? get() = if (yieldText.isBlank()) 100 else Fa.latinDigits(yieldText).trim().toIntOrNull()?.takeIf { it in 1..100 }
    }

    @Composable
    fun PrepRecipes(nav: Nav) {
        val session = LocalSession.current
        val data by load(session) { overview.prepRecipes() to overview.items().associateBy { it.id } }
        var target by rememberSaveable { mutableStateOf<GlobalId?>(null) }
        var from by rememberSaveable { mutableStateOf(session.today) }
        var output by rememberSaveable { mutableStateOf<Quantity?>(null) }
        val rows = ir.sabou.app.ui.rememberRows<PrepRow>({ listOf(it.item, it.qty, it.yieldText) },
            { PrepRow(it[0] as GlobalId?, it[1] as Quantity?, it[2] as String) }) { listOf(PrepRow(null, null, "")) }
        val id = ir.sabou.app.ui.rememberCommandId()
        val action = rememberAction()
        Column(Modifier.fillMaxSize()) {
            Header("رسپی اقلام آماده", onBack = nav.back)
            Page {
                Loaded(data) { (preps, items) ->
                    if (preps.isEmpty()) EmptyState("کالای «تولید داخلی» تعریف نشده است. در «کالاها» هنگام تعریف، گزینه‌ی تولید داخلی را بزنید.")
                    preps.forEach { (item, recipe) ->
                        SCard {
                            Text(item.name, style = SabouType.bodyStrong, color = Sabou.colors.ink)
                            if (recipe == null) Text("رسپی ندارد — تولید آن ثبت نمی‌شود.", style = SabouType.caption, color = Sabou.colors.danger)
                            else Text("نسخه ${Fa.number(recipe.version.toLong())} از ${Fa.date(recipe.effectiveFrom)} · خروجی ${Fa.quantity(recipe.outputQuantity)} ${unitName(item.unit)}: " +
                                recipe.lines.joinToString("، ") { l ->
                                    "${items[l.itemId]?.name ?: ""} ${Fa.quantity(l.quantityPerPortion)}" + if (l.yieldPercent < 100) " (بازده ${Fa.number(l.yieldPercent.toLong())}٪)" else ""
                                }, style = SabouType.caption, color = Sabou.colors.muted)
                            if (item.allergens.isNotBlank()) Text("آلرژن: ${item.allergens}", style = SabouType.caption, color = Sabou.colors.danger)
                        }
                    }
                    if (session.can(Permission.RECIPE_MANAGE) && preps.isNotEmpty()) {
                        FormCard("نسخه جدید رسپی") {
                            Text("مواد لازم برای یک بار تولید. بازده: درصدی از ماده که پس از پاک‌کردن و پخت می‌ماند (مثلاً پیاز ۹۰٪).",
                                style = SabouType.caption, color = Sabou.colors.muted)
                            Picker("قلم آماده", preps.map { Choice(it.first.id, it.first.name, unitName(it.first.unit)) }, target, { target = it })
                            DateInput("معتبر از", from, { from = it }, session.today)
                            QuantityInput("مقدار خروجی هر بار تولید", target?.let { items[it] }?.let { unitName(it.unit) } ?: "", { output = it })
                            Divider()
                            rows.forEach { r ->
                                key(r) {
                                    Picker("ماده اولیه", items.values.filter { it.isActive && it.id != target }.map { Choice(it.id, it.name, unitName(it.unit)) }, r.item, { r.item = it })
                                    QuantityInput("مقدار خالص", r.item?.let { items[it] }?.let { unitName(it.unit) } ?: "", { r.qty = it }, value = r.qty)
                                    TextInput("بازده ٪ (خالی = ۱۰۰)", r.yieldText, { r.yieldText = it }, keyboard = KeyboardType.Number,
                                        error = if (r.yieldPercent == null) "بین ۱ تا ۱۰۰" else null)
                                    Divider()
                                }
                            }
                            SecondaryButton("افزودن ماده", { rows.add(PrepRow(null, null, "")) })
                            action.error?.let { Banner(it) }
                            PrimaryButton("انتشار نسخه", {
                                val lines = rows.map { RecipeLine(it.item!!, it.qty!!, it.yieldPercent!!) }
                                action.run({ inventory.publishPrepRecipe(PublishPrepRecipe(id.value, target!!, from, output!!, lines)) }) {
                                    rows.clear(); rows.add(PrepRow(null, null, "")); id.value = GlobalId.new()
                                }
                            }, enabled = target != null && output != null && !output!!.isZero &&
                                rows.all { it.item != null && it.qty != null && !it.qty!!.isZero && it.yieldPercent != null }, busy = action.busy)
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ Production

    @Composable
    fun Production(nav: Nav) {
        val session = LocalSession.current
        Column(Modifier.fillMaxSize()) {
            Header("تولید اقلام آماده", onBack = nav.back) { BranchSwitcher() }
            WithBranch { branch ->
                val data by load(session, branch) {
                    overview.prepRecipes().filter { it.second != null && it.first.isActive }.map { it.first } to overview.locations(branch).filter { it.isActive }
                }
                var locationId by rememberSaveable(branch) { mutableStateOf<GlobalId?>(null) }
                var itemId by rememberSaveable { mutableStateOf<GlobalId?>(null) }
                var qty by rememberSaveable { mutableStateOf<Quantity?>(null) }
                var date by rememberSaveable { mutableStateOf(session.today) }
                val id = ir.sabou.app.ui.rememberCommandId()
                val action = rememberAction()
                Page {
                    Loaded(data) { (items, locations) ->
                        if (items.isEmpty()) EmptyState("هیچ قلم آماده‌ای رسپی ندارد. ابتدا در «رسپی اقلام آماده» نسخه‌ای منتشر کنید.")
                        else {
                            val loc = locationId ?: locations.firstOrNull()?.id
                            FormCard {
                                if (locations.size > 1) Picker("انبار یا آشپزخانه", locations.map { Choice(it.id, it.name) }, loc, { locationId = it })
                                Picker("قلم آماده", items.map { Choice(it.id, it.name, unitName(it.unit)) }, itemId, { itemId = it })
                                QuantityInput("مقدار تولیدشده", items.firstOrNull { it.id == itemId }?.let { unitName(it.unit) } ?: "", { qty = it })
                                DateInput("تاریخ", date, { date = it }, session.today)
                                val ready = loc != null && itemId != null && qty != null && !qty!!.isZero
                                if (ready) {
                                    val needs by load(session, loc, itemId, qty, date) { productionPreview(loc!!, itemId!!, qty!!, date) }
                                    Loaded(needs) { list ->
                                        Text("مواد لازم", style = SabouType.bodyStrong, color = Sabou.colors.ink)
                                        list.forEach { n ->
                                            KeyValue(n.item.name, "${Fa.quantity(n.needed)} از ${Fa.quantity(n.available)} ${unitName(n.item.unit)}",
                                                if (n.short) Sabou.colors.danger else Sabou.colors.ink)
                                        }
                                        if (list.any { it.short }) Banner("موجودی بعضی مواد کافی نیست؛ تولید ثبت نمی‌شود.", ChipKind.ACCENT)
                                    }
                                }
                                action.error?.let { Banner(it) }
                                PrimaryButton("ثبت تولید", {
                                    action.run({ inventory.produce(RecordProduction(id.value, branch, loc!!, itemId!!, qty!!, date)) }) { nav.back() }
                                }, enabled = ready && session.can(Permission.INVENTORY_PRODUCE), busy = action.busy)
                                Text("مواد به بهای میانگین از انبار کم و قلم آماده با همان ارزش به انبار اضافه می‌شود؛ سندی ثبت نمی‌شود چون ارزش موجودی تغییر نمی‌کند.",
                                    style = SabouType.caption, color = Sabou.colors.muted)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Preview extension on the core so screens can call it inside `load {}`. */
private fun ir.sabou.core.SabouCore.productionPreview(locationId: GlobalId, itemId: GlobalId, quantity: Quantity, date: ir.sabou.kernel.BusinessDate) =
    overview.productionPreview(locationId, itemId, quantity, date)
