package ir.sabou.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import ir.sabou.app.R
import ir.sabou.app.ui.AppSession
import ir.sabou.app.ui.Load
import ir.sabou.app.ui.LocalSession
import ir.sabou.app.ui.components.Banner
import ir.sabou.app.ui.components.Choice
import ir.sabou.app.ui.components.ChipKind
import ir.sabou.app.ui.components.Loading
import ir.sabou.app.ui.components.Picker
import ir.sabou.app.ui.load
import ir.sabou.app.ui.orNull
import ir.sabou.app.ui.theme.Sabou
import ir.sabou.app.ui.theme.SabouShapes
import ir.sabou.app.ui.theme.SabouType
import ir.sabou.inventory.StockUnit
import ir.sabou.kernel.Scope
import ir.sabou.platform.Permission
import ir.sabou.treasury.TreasuryAccount
import ir.sabou.treasury.TreasuryKind

/** The branch the user is working in; tapping opens the list of branches they may access. */
@Composable
fun BranchSwitcher() {
    val session = LocalSession.current
    val branches by load(session) { overview.branches() }
    var open by remember { mutableStateOf(false) }
    val list = branches.orNull().orEmpty()
    val current = list.firstOrNull { it.id == session.branchId }
    Row(
        Modifier.heightIn(min = 40.dp).clip(SabouShapes.chip).background(Sabou.colors.surface)
            .border(1.dp, Sabou.colors.border, SabouShapes.chip).clickable(enabled = list.size > 1) { open = true }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(painterResource(R.drawable.ic_branch), contentDescription = null, tint = Sabou.colors.primary, modifier = Modifier.size(18.dp))
        Text(current?.name ?: "بدون شعبه", style = SabouType.bodyStrong, color = Sabou.colors.ink)
        if (list.size > 1) Icon(painterResource(R.drawable.ic_chevron_down), contentDescription = null, tint = Sabou.colors.muted, modifier = Modifier.size(16.dp))
    }
    if (open) {
        // Reuse the picker dialog without its field.
        BranchDialog(list.map { Choice(it.id, it.name) }, session) { open = false }
    }
}

@Composable
private fun BranchDialog(choices: List<Choice<ir.sabou.kernel.BranchId>>, session: AppSession, close: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = close,
        confirmButton = {},
        title = { Text("انتخاب شعبه", style = SabouType.section) },
        text = {
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                choices.forEach { c ->
                    Text(
                        c.label, style = SabouType.bodyStrong,
                        color = if (c.value == session.branchId) Sabou.colors.primary else Sabou.colors.ink,
                        modifier = Modifier.clip(SabouShapes.field).clickable { session.branchId = c.value; close() }.padding(12.dp),
                    )
                }
            }
        },
        containerColor = Sabou.colors.surface,
    )
}

/** Renders [content] only when a branch is selected; otherwise explains what to do. */
@Composable
fun WithBranch(content: @Composable (Scope.Branch) -> Unit) {
    val branch = LocalSession.current.branch
    if (branch == null) Banner("ابتدا یک شعبه بسازید یا از مالک بخواهید به شما دسترسی شعبه بدهد.", ChipKind.ACCENT)
    else content(branch)
}

@Composable
fun <T> Loaded(state: Load<T>, content: @Composable (T) -> Unit) {
    when (state) {
        Load.Loading -> Loading()
        is Load.Failed -> Banner(state.message)
        is Load.Done -> content(state.value)
    }
}

fun AppSession.can(permission: Permission) = actor.role.allows(permission)

fun unitName(u: StockUnit) = when (u) {
    StockUnit.GRAM -> "گرم"
    StockUnit.KILOGRAM -> "کیلوگرم"
    StockUnit.MILLILITER -> "میلی‌لیتر"
    StockUnit.LITER -> "لیتر"
    StockUnit.PIECE -> "عدد"
    StockUnit.PACK -> "بسته"
}

fun kindName(k: TreasuryKind) = when (k) {
    TreasuryKind.CASH -> "صندوق نقدی"
    TreasuryKind.BANK -> "حساب بانکی"
    TreasuryKind.CARD_TERMINAL -> "کارت‌خوان"
    TreasuryKind.PETTY_CASH -> "تنخواه"
}

fun kindIcon(k: TreasuryKind) = when (k) {
    TreasuryKind.CASH, TreasuryKind.PETTY_CASH -> R.drawable.ic_cash
    TreasuryKind.CARD_TERMINAL -> R.drawable.ic_card
    TreasuryKind.BANK -> R.drawable.ic_bank
}

@Composable
fun kindColors(k: TreasuryKind) = when (k) {
    TreasuryKind.CASH, TreasuryKind.PETTY_CASH -> Sabou.colors.primary to Sabou.colors.primarySoft
    TreasuryKind.CARD_TERMINAL -> Sabou.colors.moneyIn to Sabou.colors.moneyInSoft
    TreasuryKind.BANK -> Sabou.colors.bank to Sabou.colors.bankSoft
}

/** Accounts the user can move money with in [scope] (same scope only, as the domain requires). */
fun accountChoices(accounts: List<TreasuryAccount>, scope: Scope? = null) =
    accounts.filter { it.isActive && (scope == null || it.scope == scope) }.map { Choice(it.id, it.name, kindName(it.kind)) }

@Composable
fun <T> PickerOrHint(label: String, choices: List<Choice<T>>, selected: T?, onSelect: (T) -> Unit, emptyHint: String) {
    if (choices.isEmpty()) Banner(emptyHint, ChipKind.ACCENT) else Picker(label, choices, selected, onSelect)
}
