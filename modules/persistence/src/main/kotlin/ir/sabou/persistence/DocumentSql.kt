package ir.sabou.persistence

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.GlobalId
import ir.sabou.platform.DocumentNumber
import ir.sabou.platform.DocumentNumberStore
import ir.sabou.platform.DocumentSeries
import ir.sabou.platform.NumberedDocument

/**
 * Number series in SQL. The counter row is incremented by one statement inside the command's transaction, so
 * numbers are gap-free (a failed command rolls the increment back) and unique (primary keys on both tables).
 * Issued numbers are immutable (trigger).
 */
class SqlDocumentNumberStore(private val db: SqlDatabase) : DocumentNumberStore {
    override fun next(series: DocumentSeries, fiscalYear: Int, scope: String): Long {
        db.execute(
            "INSERT INTO document_sequences (series, fiscal_year, scope, last) VALUES (?, ?, ?, 0) ON CONFLICT(series, fiscal_year, scope) DO NOTHING",
            series.name, fiscalYear.toLong(), scope,
        )
        db.execute("UPDATE document_sequences SET last = last + 1 WHERE series = ? AND fiscal_year = ? AND scope = ?", series.name, fiscalYear.toLong(), scope)
        return db.query("SELECT last FROM document_sequences WHERE series = ? AND fiscal_year = ? AND scope = ?", series.name, fiscalYear.toLong(), scope)
            .single().long("last")
    }

    override fun record(document: NumberedDocument) {
        val n = document.number
        db.execute(
            "INSERT INTO document_numbers (series, fiscal_year, scope, sequence, document_id, date, command_id, reverses_series, reverses_year, reverses_scope, reverses_sequence) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            n.series.name, n.fiscalYear.toLong(), n.scope, n.sequence, document.documentId.value, document.date.epochDay, document.commandId,
            document.reverses?.series?.name, document.reverses?.fiscalYear?.toLong(), document.reverses?.scope, document.reverses?.sequence,
        )
    }

    override fun of(series: DocumentSeries, documentId: GlobalId): NumberedDocument? =
        db.query("SELECT * FROM document_numbers WHERE series = ? AND document_id = ?", series.name, documentId.value).firstOrNull()?.let(::read)

    override fun ofDocument(documentId: GlobalId): List<NumberedDocument> =
        db.query("SELECT * FROM document_numbers WHERE document_id = ? ORDER BY rowid", documentId.value).map(::read)

    /** Numbers of many documents of one series in one query (lists and reports). */
    fun ofDocuments(series: DocumentSeries, ids: Collection<GlobalId>): Map<GlobalId, DocumentNumber> {
        if (ids.isEmpty()) return emptyMap()
        val result = HashMap<GlobalId, DocumentNumber>()
        ids.map { it.value }.distinct().chunked(500).forEach { chunk ->
            db.query(
                "SELECT * FROM document_numbers WHERE series = ? AND document_id IN (${chunk.joinToString(",") { "?" }})",
                series.name, *chunk.toTypedArray(),
            ).forEach { r -> read(r).let { result[it.documentId] = it.number } }
        }
        return result
    }

    private fun read(r: SqlRow) = NumberedDocument(
        DocumentNumber(DocumentSeries.valueOf(r.str("series")), r.long("fiscal_year").toInt(), r.str("scope"), r.long("sequence")),
        GlobalId.parse(r.str("document_id")), BusinessDate(r.long("date")), r.str("command_id"),
        r.strOrNull("reverses_series")?.let {
            DocumentNumber(DocumentSeries.valueOf(it), r.long("reverses_year").toInt(), r.str("reverses_scope"), r.long("reverses_sequence"))
        },
    )
}
