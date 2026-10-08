package ir.sabou.core

import ir.sabou.inventory.InventoryGateway
import ir.sabou.inventory.InventoryOperations
import ir.sabou.inventory.RecipeBook
import ir.sabou.kernel.Clock
import ir.sabou.ledger.Ledger
import ir.sabou.ledger.LedgerAccessRegistry
import ir.sabou.ledger.ManualAccounting
import ir.sabou.ledger.StandardAccounts
import ir.sabou.payroll.PayrollOperations
import ir.sabou.payroll.PolicyAdministration
import ir.sabou.payroll.StatutoryPolicy
import ir.sabou.payroll.StatutoryPolicyRegistry
import ir.sabou.persistence.DatabaseMeta
import ir.sabou.persistence.Schema
import ir.sabou.persistence.SqlAccountStore
import ir.sabou.persistence.SqlAuditStore
import ir.sabou.persistence.SqlBranchStore
import ir.sabou.persistence.SqlCustomerStore
import ir.sabou.persistence.SqlDatabase
import ir.sabou.persistence.SqlEventLog
import ir.sabou.persistence.SqlIdempotencyStore
import ir.sabou.persistence.SqlItemStore
import ir.sabou.persistence.SqlJournalStore
import ir.sabou.persistence.SqlLocationStore
import ir.sabou.persistence.SqlMovementStore
import ir.sabou.persistence.SqlPayrollStore
import ir.sabou.persistence.SqlPeriodStore
import ir.sabou.persistence.SqlPersonnelStore
import ir.sabou.persistence.SqlPolicyStore
import ir.sabou.persistence.SqlPurchaseStore
import ir.sabou.persistence.SqlRecipeStore
import ir.sabou.persistence.SqlSalesStore
import ir.sabou.persistence.SqlStockStore
import ir.sabou.persistence.SqlSupplierStore
import ir.sabou.persistence.SqlTreasuryAccountStore
import ir.sabou.persistence.SqlUnitOfWork
import ir.sabou.persistence.SqlUserStore
import ir.sabou.platform.AnchorStore
import ir.sabou.platform.AuditTrail
import ir.sabou.platform.CommandBus
import ir.sabou.platform.IdentityService
import ir.sabou.platform.IntegrityGuard
import ir.sabou.platform.ModuleId
import ir.sabou.platform.Session
import ir.sabou.platform.StartupVerdict
import ir.sabou.purchasing.PurchasingOperations
import ir.sabou.sales.SalesOperations
import ir.sabou.treasury.TreasuryGateway
import ir.sabou.treasury.TreasuryOperations

/**
 * The single composition root. Every platform (Android today, a server later) builds the system
 * through here, so there is exactly one place where posting capabilities are issued — once per
 * module — and one wiring of stores, pipeline and modules (ADR-0002, ADR-0003).
 *
 * The anchor store lives OUTSIDE the database (Android: an HMAC-protected file), so replacing the
 * database with an older copy is detected at startup (ADR-0004).
 */
class SabouCore private constructor(
    val db: SqlDatabase,
    private val anchors: AnchorStore,
    val clock: Clock,
    policies: List<StatutoryPolicy>,
    newDatabaseEpoch: String?,
) {
    private val meta = DatabaseMeta(db)
    val unitOfWork = SqlUnitOfWork(db)

    // Stores
    private val auditStore = SqlAuditStore(db)
    val events = SqlEventLog(db)
    private val users = SqlUserStore(db)
    val branches = SqlBranchStore(db)
    val accounts = SqlAccountStore(db)
    val journals = SqlJournalStore(db)
    val periods = SqlPeriodStore(db)
    val treasuryAccounts = SqlTreasuryAccountStore(db)
    val treasuryMovements = SqlMovementStore(db)
    val items = SqlItemStore(db)
    val locations = SqlLocationStore(db)
    val stock = SqlStockStore(db)
    val recipes = SqlRecipeStore(db)
    val suppliers = SqlSupplierStore(db)
    val purchases = SqlPurchaseStore(db)
    val customers = SqlCustomerStore(db)
    val sales = SqlSalesStore(db)
    val personnel = SqlPersonnelStore(db)
    val payrollStore = SqlPayrollStore(db)

    val epoch: String = unitOfWork.transaction {
        accounts.seed(StandardAccounts.chart())
        meta.epoch(newDatabaseEpoch)
    }

    // Pipeline and identity
    val session = Session(users, clock)
    val identity = IdentityService(users, branches, session, unitOfWork, auditStore, clock) { epoch }
    private val bus = CommandBus(session, unitOfWork, SqlIdempotencyStore(db), auditStore, events, clock) { epoch }
    private val integrity = IntegrityGuard(anchors, auditStore)

    // Capabilities: issued here and nowhere else.
    private val registry = LedgerAccessRegistry()
    val ledger = Ledger(registry, accounts, journals, periods)
    private val treasuryCapability = registry.issue(ModuleId.TREASURY)

    val accounting = ManualAccounting(bus, ledger, registry.issue(ModuleId.LEDGER_MANUAL), periods)
    val treasuryGateway = TreasuryGateway(ledger, treasuryCapability, treasuryAccounts, treasuryMovements)
    val treasury = TreasuryOperations(bus, treasuryGateway, treasuryCapability, treasuryAccounts)
    val inventoryGateway = InventoryGateway(ledger, registry.issue(ModuleId.INVENTORY), items, locations, stock)
    val inventory = InventoryOperations(bus, inventoryGateway, items, locations, recipes)
    val recipeBook = RecipeBook(recipes)
    val purchasing = PurchasingOperations(bus, ledger, registry.issue(ModuleId.PURCHASING), inventoryGateway, treasuryGateway, suppliers, purchases)
    val salesOps = SalesOperations(bus, ledger, registry.issue(ModuleId.SALES), inventoryGateway, recipeBook, treasuryGateway, customers, sales)
    /** Stored policies (entered by the owner) plus any supplied by the caller (tests). */
    val payrollPolicies = PolicyAdministration(bus, SqlPolicyStore(db))
    private val policyRegistry = StatutoryPolicyRegistry { payrollPolicies.policies() + policies }
    val payroll = PayrollOperations(bus, ledger, registry.issue(ModuleId.PAYROLL), treasuryGateway, personnel, payrollStore, policyRegistry)

    val overview = Overview(this)

    /**
     * Startup check: the database must continue the anchored history and the whole audit chain
     * must verify. On success a new checkpoint anchor is written. A failure is returned, never
     * thrown, so the app can show a recovery screen instead of crashing.
     */
    fun verifyStartup(): StartupVerdict {
        val verdict = integrity.verify(epoch)
        if (verdict != StartupVerdict.Healthy) return verdict
        return try {
            AuditTrail(auditStore).verify()
            integrity.recordCheckpoint(epoch, clock.nowEpochMillis())
            StartupVerdict.Healthy
        } catch (e: ir.sabou.kernel.DomainException) {
            StartupVerdict.RollbackDetected(e.error.code)
        }
    }

    /** Records the rebase anchor for a database that is about to replace this one (restore / reset). */
    fun acceptReplacement(newEpoch: String, reason: String) = integrity.recordRebase(newEpoch, reason, clock.nowEpochMillis())

    companion object {
        /**
         * Migrates [db] to the latest schema and builds the system. Foreign keys must already be enabled by
         * the adapter. [newDatabaseEpoch] is used only when [db] is brand new: after a factory reset it must
         * be the epoch announced by [acceptReplacement] beforehand.
         */
        fun open(
            db: SqlDatabase,
            anchors: AnchorStore,
            clock: Clock = Clock.SYSTEM,
            policies: List<StatutoryPolicy> = emptyList(),
            newDatabaseEpoch: String? = null,
        ): SabouCore {
            check(db.query("PRAGMA foreign_keys").single().long("foreign_keys") == 1L) { "FOREIGN_KEYS_DISABLED" }
            Schema.migrate(db)
            return SabouCore(db, anchors, clock, policies, newDatabaseEpoch)
        }

        fun newEpoch(): String = java.util.UUID.randomUUID().toString()
    }
}
