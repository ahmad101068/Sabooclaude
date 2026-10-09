package ir.sabou.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ir.sabou.app.ui.components.Banner
import ir.sabou.app.ui.components.ChipKind
import ir.sabou.app.ui.components.Choice
import ir.sabou.app.ui.components.DateInput
import ir.sabou.app.ui.components.Picker
import ir.sabou.app.ui.components.TextInput
import ir.sabou.app.ui.theme.Sabou
import ir.sabou.app.ui.theme.SabouType
import ir.sabou.core.ChequeRow
import ir.sabou.core.Fa
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Scope
import ir.sabou.treasury.ChequeDetails
import ir.sabou.treasury.TreasuryAccount
import ir.sabou.treasury.TreasuryKind

/** The fields of a cheque being received or written, kept with the form's draft. */
@Composable
fun rememberChequeFields(): SnapshotStateMap<String, Any?> = rememberValueMap<String, Any?>()

/** Number, bank, Sayad id, due date and the other party; for our own cheques also the bank account it is drawn on. */
@Composable
fun ChequeInputs(fields: SnapshotStateMap<String, Any?>, today: BusinessDate, defaultParty: String, issued: Boolean, banks: List<TreasuryAccount>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (issued) "مشخصات چک ما" else "مشخصات چک دریافتی", style = SabouType.bodyStrong, color = Sabou.colors.ink)
        if (issued) {
            val list = banks.filter { it.isActive && it.kind == TreasuryKind.BANK }
            if (list.isEmpty()) Banner("حساب بانکی برای این شعبه تعریف نشده است.", ChipKind.ACCENT)
            else Picker("از حساب بانکی", list.map { Choice(it.id, it.name) }, fields["bankAccount"] as GlobalId?, { fields["bankAccount"] = it })
        }
        TextInput("شماره چک", (fields["no"] as String?) ?: "", { fields["no"] = it }, keyboard = KeyboardType.Number)
        TextInput("بانک", (fields["bank"] as String?) ?: "", { fields["bank"] = it }, placeholder = "مثلاً ملت، شعبه ونک")
        val sayad = (fields["sayad"] as String?) ?: ""
        TextInput("شناسه صیادی (۱۶ رقم، اختیاری)", sayad, { fields["sayad"] = it }, keyboard = KeyboardType.Number,
            error = Fa.latinDigits(sayad).let { if (it.isNotBlank() && (it.length != 16 || !it.all(Char::isDigit))) "شناسه صیادی ۱۶ رقم است" else null })
        DateInput("سررسید", (fields["due"] as BusinessDate?) ?: today, { fields["due"] = it }, today)
        TextInput(if (issued) "در وجه" else "صاحب حساب", (fields["party"] as String?) ?: defaultParty, { fields["party"] = it })
    }
}

/** What was entered, or null while something required is missing. */
fun chequeDetails(fields: Map<String, Any?>, today: BusinessDate, defaultParty: String, issued: Boolean): ChequeDetails? {
    val no = Fa.latinDigits((fields["no"] as String?).orEmpty()).trim()
    val bank = (fields["bank"] as String?).orEmpty().trim()
    val party = ((fields["party"] as String?) ?: defaultParty).trim()
    val bankAccount = fields["bankAccount"] as GlobalId?
    if (no.isEmpty() || bank.isEmpty() || party.isEmpty() || (issued && bankAccount == null)) return null
    val sayad = Fa.latinDigits((fields["sayad"] as String?).orEmpty()).trim()
    return ChequeDetails(no, bank, sayad, (fields["due"] as BusinessDate?) ?: today, party, bankAccountId = bankAccount)
}

/** Customers' cheques held in [scope]'s cheque boxes, to pass on. */
@Composable
fun HeldChequePicker(scope: Scope, accountId: GlobalId, selected: GlobalId?, onPick: (ChequeRow) -> Unit) {
    val session = LocalSession.current
    val held by load(session, scope, accountId) { books.heldCheques(scope).filter { it.cheque.accountId == accountId } }
    val list = held.orNull().orEmpty()
    if (held is Load.Done && list.isEmpty()) Banner("چکی در این صندوق نیست.", ChipKind.ACCENT)
    if (list.isNotEmpty()) Picker(
        "چک",
        list.map { Choice(it.cheque.id, "${Fa.digits(it.cheque.details.number)} · ${it.cheque.details.counterparty}", "${Fa.toman(it.cheque.amount)} تومان · سررسید ${Fa.date(it.cheque.dueDate)}") },
        selected,
        { id -> list.firstOrNull { it.cheque.id == id }?.let(onPick) },
    )
}
