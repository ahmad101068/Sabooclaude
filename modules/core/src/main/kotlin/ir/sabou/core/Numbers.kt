package ir.sabou.core

import ir.sabou.kernel.GlobalId
import ir.sabou.platform.DocumentNumber
import ir.sabou.platform.DocumentSeries
import ir.sabou.platform.NumberedDocument

/** Document numbers for screens, prints and exports. Reads only; numbers are issued by the commands themselves. */
class Numbers internal constructor(private val core: SabouCore) {
    /** The number a document carries (its own series), or null for documents older than numbering. */
    fun of(documentId: GlobalId): DocumentNumber? =
        core.documentNumbers.ofDocument(documentId).firstOrNull { it.number.series != DocumentSeries.JOURNAL }?.number
            ?: core.documentNumbers.ofDocument(documentId).firstOrNull()?.number

    fun of(series: DocumentSeries, documentId: GlobalId): DocumentNumber? = core.documentNumbers.of(series, documentId)?.number

    /** Numbers of many documents of one series at once. */
    fun of(series: DocumentSeries, ids: Collection<GlobalId>): Map<GlobalId, DocumentNumber> = core.documentNumbers.ofDocuments(series, ids)

    /** The number of each document in [ids], whatever its series (e.g. the sources of treasury movements). */
    fun ofAny(ids: Collection<GlobalId>): Map<GlobalId, DocumentNumber> =
        ids.distinct().mapNotNull { id -> of(id)?.let { id to it } }.toMap()

    /** The full record (date, what it reverses) of a document's number. */
    fun record(series: DocumentSeries, documentId: GlobalId): NumberedDocument? = core.documentNumbers.of(series, documentId)
}
