package ir.sabou.ledger.memory

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Scope
import ir.sabou.ledger.Account
import ir.sabou.ledger.AccountCode
import ir.sabou.ledger.AccountStore
import ir.sabou.ledger.JournalEntry
import ir.sabou.ledger.JournalStore
import ir.sabou.ledger.PeriodLock
import ir.sabou.ledger.PeriodStore
import ir.sabou.platform.memory.Table
import ir.sabou.platform.memory.Transactional

class InMemoryAccountStore(seed: List<Account>) : Table<String, Account>(), AccountStore {
    init { seed.forEach { put(it.code.value, it) } }
    override fun byCode(code: AccountCode) = get(code.value)
    override fun all() = values()
    override fun upsert(account: Account) = put(account.code.value, account)
}

class InMemoryJournalStore : JournalStore, Transactional {
    private val entries = mutableListOf<JournalEntry>()
    override fun nextNumber(): Long = entries.size + 1L
    override fun insert(entry: JournalEntry) {
        check(entries.none { it.id == entry.id || it.number == entry.number }) { "journal_unique" }
        check(entry.reversalOf == null || entries.none { it.reversalOf == entry.reversalOf }) { "journal_reversal_unique" }
        entries += entry
    }
    override fun byId(id: GlobalId) = entries.firstOrNull { it.id == id }
    override fun reversalOf(id: GlobalId) = entries.firstOrNull { it.reversalOf == id }
    override fun bySource(type: String, id: GlobalId) = entries.filter { it.source.type == type && it.source.id == id }
    override fun netDebit(account: AccountCode, scope: Scope?, upTo: BusinessDate?): Long =
        entries.asSequence()
            .filter { (scope == null || it.scope == scope) && (upTo == null || it.date <= upTo) }
            .flatMap { it.lines.asSequence() }
            .filter { it.account == account }
            .fold(0L) { acc, l -> Math.addExact(acc, l.debit.rial - l.credit.rial) }
    override fun all() = entries.toList()
    override fun dailyTotals(from: BusinessDate, to: BusinessDate) =
        entries.filter { it.date >= from && it.date <= to }
            .flatMap { e -> e.lines.map { Triple(Triple(it.account, e.scope, e.date), it.debit.rial, it.credit.rial) } }
            .groupBy { it.first }
            .map { (k, v) -> ir.sabou.ledger.DailyAccountTotal(k.first, k.second, k.third, v.sumOf { it.second }, v.sumOf { it.third }) }
    override fun entriesTouching(account: AccountCode, scope: Scope?, from: BusinessDate, to: BusinessDate) =
        entries.filter { e -> e.date >= from && e.date <= to && (scope == null || e.scope == scope) && e.lines.any { it.account == account } }
            .sortedWith(compareBy({ it.date }, { it.number }))
    override fun snapshot(): Any = entries.toList()
    @Suppress("UNCHECKED_CAST")
    override fun restore(snapshot: Any) { entries.clear(); entries.addAll(snapshot as List<JournalEntry>) }
}

class InMemoryPeriodStore : Table<GlobalId, PeriodLock>(), PeriodStore {
    override fun closedLockCovering(date: BusinessDate) = values().firstOrNull { it.closed && date >= it.from && date <= it.to }
    override fun overlapsClosed(from: BusinessDate, to: BusinessDate) = values().any { it.closed && it.from <= to && it.to >= from }
    override fun byId(id: GlobalId) = get(id)
    override fun save(lock: PeriodLock) = put(lock.id, lock)
}

class InMemoryBudgetStore : ir.sabou.platform.memory.Table<ir.sabou.kernel.GlobalId, ir.sabou.ledger.BudgetEntry>(), ir.sabou.ledger.BudgetStore {
    override fun entries() = values()
    override fun save(entry: ir.sabou.ledger.BudgetEntry) = put(entry.id, entry)
}
