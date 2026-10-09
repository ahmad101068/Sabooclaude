package ir.sabou.treasury.memory

import ir.sabou.kernel.GlobalId
import ir.sabou.platform.memory.Table
import ir.sabou.platform.memory.Transactional
import ir.sabou.treasury.Direction
import ir.sabou.treasury.MovementStore
import ir.sabou.treasury.TreasuryAccount
import ir.sabou.treasury.TreasuryAccountStore
import ir.sabou.treasury.TreasuryMovement

class InMemoryTreasuryAccountStore : Table<GlobalId, TreasuryAccount>(), TreasuryAccountStore {
    override fun byId(id: GlobalId) = get(id)
    override fun all() = values()
    override fun save(account: TreasuryAccount) = put(account.id, account)
}

class InMemoryMovementStore : MovementStore, Transactional {
    private val rows = mutableListOf<TreasuryMovement>()
    override fun insert(movement: TreasuryMovement) {
        check(movement.reversalOf == null || rows.none { it.reversalOf == movement.reversalOf }) { "movement_reversal_unique" }
        rows += movement
    }
    override fun byId(id: GlobalId) = rows.firstOrNull { it.id == id }
    override fun reversalOf(id: GlobalId) = rows.firstOrNull { it.reversalOf == id }
    override fun bySource(type: String, id: GlobalId) = rows.filter { it.source.type == type && it.source.id == id }
    override fun balance(accountId: GlobalId): Long = rows.filter { it.accountId == accountId }
        .fold(0L) { acc, m -> if (m.direction == Direction.RECEIPT) Math.addExact(acc, m.amount.rial) else Math.subtractExact(acc, m.amount.rial) }
    override fun snapshot(): Any = rows.toList()
    @Suppress("UNCHECKED_CAST")
    override fun restore(snapshot: Any) { rows.clear(); rows.addAll(snapshot as List<TreasuryMovement>) }
}
class InMemoryChequeStore : Table<GlobalId, ir.sabou.treasury.Cheque>(), ir.sabou.treasury.ChequeStore {
    override fun byId(id: GlobalId) = get(id)
    override fun all() = values()
    override fun save(cheque: ir.sabou.treasury.Cheque) = put(cheque.id, cheque)
}
