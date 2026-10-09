package ir.sabou.persistence

import ir.sabou.kernel.BranchId
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Scope
import ir.sabou.ledger.SourceDocument
import ir.sabou.platform.ModuleId

/**
 * Shared encodings. Scope text matches CommandContext.scopeLabel ("ORG" / "BRANCH:<uuid>") so the
 * audit trail, event stream and tables use one vocabulary.
 */
internal object Codec {
    fun scope(s: Scope): String = when (s) {
        Scope.Organization -> "ORG"
        is Scope.Branch -> "BRANCH:" + s.branchId.value.value
    }

    fun scopeOf(text: String): Scope =
        if (text == "ORG") Scope.Organization
        else Scope.Branch(BranchId(GlobalId.parse(text.removePrefix("BRANCH:").also { require(text.startsWith("BRANCH:")) { "bad_scope:$text" } })))

    fun branchOf(text: String): Scope.Branch = scopeOf(text) as? Scope.Branch ?: error("expected_branch_scope:$text")

    fun id(text: String): GlobalId = GlobalId.parse(text)
    fun idOrNull(text: String?): GlobalId? = text?.let(GlobalId::parse)
    fun money(v: Long): Money = Money.of(v)
    fun qty(v: Long): Quantity = Quantity.of(v)
    fun date(v: Long): BusinessDate = BusinessDate(v)

    fun source(d: SourceDocument): Map<String, Any?> = mapOf("module" to d.module.name, "type" to d.type, "id" to d.id.value)
    fun cheque(c: ir.sabou.treasury.ChequeDetails): Map<String, Any?> = mapOf(
        "number" to c.number, "bank" to c.bank, "sayad" to c.sayadId, "due" to c.dueDate.epochDay, "counterparty" to c.counterparty,
        "note" to c.note, "bankAccount" to c.bankAccountId?.value,
    )
    fun chequeOf(d: Doc) = ir.sabou.treasury.ChequeDetails(
        d.str("number"), d.str("bank"), d.strOr("sayad", ""), date(d.long("due")), d.str("counterparty"), d.strOr("note", ""), idOrNull(d.strOrNull("bankAccount")),
    )
    fun sourceOf(d: Doc): SourceDocument = SourceDocument(ModuleId.valueOf(d.str("module")), d.str("type"), id(d.str("id")))
}

/** Base for document tables: one JSON document per row plus the columns queries need. */
abstract class SqlTable(protected val db: SqlDatabase) {
    protected fun docs(sql: String, vararg args: Any?): List<Doc> = db.query(sql, *args).map { Doc.parse(it.str("doc")) }
    protected fun doc(sql: String, vararg args: Any?): Doc? = docs(sql, *args).firstOrNull()

    /** Insert-or-update by primary key without deleting the row (keeps rowid order and FK children). */
    protected fun upsert(table: String, key: String, columns: Map<String, Any?>) = upsert(table, listOf(key), columns)

    protected fun upsert(table: String, keys: List<String>, columns: Map<String, Any?>) {
        val names = columns.keys.toList()
        val key = keys.joinToString(", ")
        val updates = names.filter { it !in keys }.joinToString(", ") { "$it = excluded.$it" }
        db.execute(
            "INSERT INTO $table (${names.joinToString(", ")}) VALUES (${names.joinToString(", ") { "?" }}) " +
                "ON CONFLICT($key) DO UPDATE SET $updates",
            *columns.values.toTypedArray(),
        )
    }
}
