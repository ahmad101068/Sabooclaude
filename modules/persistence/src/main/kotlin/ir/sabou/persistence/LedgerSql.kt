package ir.sabou.persistence

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Scope
import ir.sabou.ledger.Account
import ir.sabou.ledger.AccountCode
import ir.sabou.ledger.AccountStore
import ir.sabou.ledger.AccountType
import ir.sabou.ledger.JournalEntry
import ir.sabou.ledger.JournalLine
import ir.sabou.ledger.JournalStore
import ir.sabou.ledger.PeriodLock
import ir.sabou.ledger.PeriodStore
import ir.sabou.platform.ModuleId

class SqlAccountStore(db: SqlDatabase) : SqlTable(db), AccountStore {
    private fun read(d: Doc) = Account(
        AccountCode.of(d.str("code")), d.str("name"), AccountType.valueOf(d.str("type")),
        d.strs("postingModules").map(ModuleId::valueOf).toSet(), d.bool("active"), d.bool("system"),
    )

    override fun byCode(code: AccountCode) = doc("SELECT doc FROM accounts WHERE code = ?", code.value)?.let(::read)
    override fun all() = docs("SELECT doc FROM accounts ORDER BY code").map(::read)
    override fun upsert(account: Account) = upsert(
        "accounts", "code",
        mapOf(
            "code" to account.code.value,
            "doc" to Json.encode(
                mapOf(
                    "code" to account.code.value, "name" to account.name, "type" to account.type.name,
                    "postingModules" to account.postingModules.map { it.name }.sorted(), "active" to account.isActive, "system" to account.isSystem,
                ),
            ),
        ),
    )

    /** Adds missing chart accounts; never overwrites what is already stored. */
    fun seed(chart: List<Account>) = chart.forEach { if (byCode(it.code) == null) upsert(it) }
}

/**
 * Header in journal_entries, lines in journal_lines (the single source for balances). Both tables
 * are immutable; a correction is a new reversing entry.
 */
class SqlJournalStore(db: SqlDatabase) : SqlTable(db), JournalStore {
    override fun nextNumber(): Long = db.query("SELECT COALESCE(MAX(number), 0) + 1 AS n FROM journal_entries").single().long("n")

    override fun insert(entry: JournalEntry) {
        val scope = Codec.scope(entry.scope)
        db.execute(
            "INSERT INTO journal_entries (id, number, date, scope, source_module, source_type, source_id, reversal_of, doc) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            entry.id.value, entry.number, entry.date.epochDay, scope, entry.source.module.name, entry.source.type, entry.source.id.value,
            entry.reversalOf?.value,
            Json.encode(mapOf("description" to entry.description, "postedBy" to entry.postedBy.value, "postedAt" to entry.postedAtEpochMillis)),
        )
        entry.lines.forEachIndexed { i, l ->
            db.execute(
                "INSERT INTO journal_lines (entry_id, line_no, account, scope, date, debit, credit, contributor, memo) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                entry.id.value, i, l.account.value, scope, entry.date.epochDay, l.debit.rial, l.credit.rial, l.contributor.name, l.memo,
            )
        }
    }

    private fun entries(where: String, vararg args: Any?): List<JournalEntry> =
        db.query("SELECT * FROM journal_entries $where", *args).map { r ->
            val id = r.str("id")
            val d = Doc.parse(r.str("doc"))
            val lines = db.query("SELECT * FROM journal_lines WHERE entry_id = ? ORDER BY line_no", id).map { l ->
                JournalLine(AccountCode.of(l.str("account")), Codec.money(l.long("debit")), Codec.money(l.long("credit")), l.str("memo"), ModuleId.valueOf(l.str("contributor")))
            }
            JournalEntry(
                Codec.id(id), r.long("number"), Codec.date(r.long("date")), Codec.scopeOf(r.str("scope")),
                ir.sabou.ledger.SourceDocument(ModuleId.valueOf(r.str("source_module")), r.str("source_type"), Codec.id(r.str("source_id"))),
                d.str("description"), lines, Codec.idOrNull(r.strOrNull("reversal_of")), Codec.id(d.str("postedBy")), d.long("postedAt"),
            )
        }

    override fun byId(id: GlobalId) = entries("WHERE id = ?", id.value).firstOrNull()
    override fun reversalOf(id: GlobalId) = entries("WHERE reversal_of = ?", id.value).firstOrNull()
    override fun bySource(type: String, id: GlobalId) = entries("WHERE source_type = ? AND source_id = ? ORDER BY number", type, id.value)
    override fun all() = entries("ORDER BY number")

    /** Computed in SQL; SQLite raises on integer overflow instead of wrapping. */
    override fun netDebit(account: AccountCode, scope: Scope?, upTo: BusinessDate?): Long {
        val s = scope?.let(Codec::scope)
        val d = upTo?.epochDay
        return db.query(
            "SELECT COALESCE(SUM(debit - credit), 0) AS n FROM journal_lines WHERE account = ? AND (? IS NULL OR scope = ?) AND (? IS NULL OR date <= ?)",
            account.value, s, s, d, d,
        ).single().long("n")
    }
}

class SqlPeriodStore(private val db: SqlDatabase) : PeriodStore {
    private fun read(r: SqlRow) = PeriodLock(Codec.id(r.str("id")), Codec.date(r.long("from_day")), Codec.date(r.long("to_day")), r.long("closed") == 1L)
    override fun closedLockCovering(date: BusinessDate) =
        db.query("SELECT * FROM period_locks WHERE closed = 1 AND from_day <= ? AND to_day >= ? LIMIT 1", date.epochDay, date.epochDay).firstOrNull()?.let(::read)
    override fun overlapsClosed(from: BusinessDate, to: BusinessDate) =
        db.query("SELECT 1 AS x FROM period_locks WHERE closed = 1 AND from_day <= ? AND to_day >= ? LIMIT 1", to.epochDay, from.epochDay).isNotEmpty()
    override fun byId(id: GlobalId) = db.query("SELECT * FROM period_locks WHERE id = ?", id.value).firstOrNull()?.let(::read)
    override fun save(lock: PeriodLock) = db.execute(
        "INSERT INTO period_locks (id, from_day, to_day, closed) VALUES (?, ?, ?, ?) ON CONFLICT(id) DO UPDATE SET from_day = excluded.from_day, to_day = excluded.to_day, closed = excluded.closed",
        lock.id.value, lock.from.epochDay, lock.to.epochDay, if (lock.closed) 1L else 0L,
    )
}
