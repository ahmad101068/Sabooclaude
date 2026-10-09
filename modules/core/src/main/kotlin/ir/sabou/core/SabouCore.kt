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
import ir.sabou.persistence.SqlAttachmentStore
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
import ir.sabou.purchasing.OrderOperations
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
    internal val db: SqlDatabase,
    private val anchors: AnchorStore,
    val clock: Clock,
    policies: List<StatutoryPolicy>,
    newDatabaseEpoch: String?,
) {
    private val meta = DatabaseMeta(db)
    internal val unitOfWork = SqlUnitOfWork(db)

    // Stores
    private val auditStore = SqlAuditStore(db)
    internal val events = SqlEventLog(db)
    private val users = SqlUserStore(db)
    internal val branches = SqlBranchStore(db)
    internal val accounts = SqlAccountStore(db)
    internal val journals = SqlJournalStore(db)
    internal val periods = SqlPeriodStore(db)
    internal val treasuryAccounts = SqlTreasuryAccountStore(db)
    internal val treasuryMovements = SqlMovementStore(db)
    internal val cheques = ir.sabou.persistence.SqlChequeStore(db)
    internal val stockCounts = ir.sabou.persistence.SqlStockCountStore(db)
    internal val approvalRules = ir.sabou.persistence.SqlApprovalRuleStore(db)
    internal val budgetStore = ir.sabou.persistence.SqlBudgetStore(db)
    internal val assetStore = ir.sabou.persistence.SqlAssetStore(db)
    internal val items = SqlItemStore(db)
    internal val locations = SqlLocationStore(db)
    internal val stock = SqlStockStore(db)
    internal val recipes = SqlRecipeStore(db)
    internal val suppliers = SqlSupplierStore(db)
    internal val purchases = SqlPurchaseStore(db)
    internal val attachments = SqlAttachmentStore(db)
    internal val customers = SqlCustomerStore(db)
    internal val sales = SqlSalesStore(db)
    internal val personnel = SqlPersonnelStore(db)
    internal val payrollStore = SqlPayrollStore(db)

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
    internal val ledger = Ledger(registry, accounts, journals, periods)
    private val treasuryCapability = registry.issue(ModuleId.TREASURY)

    val accounting = ManualAccounting(bus, ledger, registry.issue(ModuleId.LEDGER_MANUAL), periods)
    internal val treasuryGateway = TreasuryGateway(ledger, treasuryCapability, treasuryAccounts, treasuryMovements, cheques)
    val treasury = TreasuryOperations(bus, treasuryGateway, treasuryCapability, treasuryAccounts)
    val chequeOps = ir.sabou.treasury.ChequeOperations(bus, treasuryGateway, treasuryCapability)
    val budgets = ir.sabou.ledger.BudgetOperations(bus, accounts, budgetStore)
    internal val inventoryGateway = InventoryGateway(ledger, registry.issue(ModuleId.INVENTORY), items, locations, stock)
    val inventory = InventoryOperations(bus, inventoryGateway, items, locations, recipes)
    val counts = ir.sabou.inventory.StockCountOperations(bus, inventoryGateway, stockCounts)
    internal val recipeBook = RecipeBook(recipes)
    val purchasing = PurchasingOperations(bus, ledger, registry.issue(ModuleId.PURCHASING), inventoryGateway, treasuryGateway, suppliers, purchases, attachments, approvalRules)
    val approvals = ir.sabou.purchasing.ApprovalOperations(bus, approvalRules, purchases)
    val orders = OrderOperations(bus, inventoryGateway, suppliers, purchases)
    val salesOps = SalesOperations(bus, ledger, registry.issue(ModuleId.SALES), inventoryGateway, recipeBook, treasuryGateway, customers, sales)
    /** Stored policies (entered by the owner) plus any supplied by the caller (tests). */
    val payrollPolicies = PolicyAdministration(bus, SqlPolicyStore(db))
    private val policyRegistry = StatutoryPolicyRegistry { payrollPolicies.policies() + policies }
    val fixedAssets = ir.sabou.assets.AssetOperations(bus, ledger, registry.issue(ModuleId.ASSETS), treasuryGateway, assetStore)
    val payroll = PayrollOperations(bus, ledger, registry.issue(ModuleId.PAYROLL), treasuryGateway, personnel, payrollStore, policyRegistry)

    val overview = Overview(this)
    val reports = Reports(this)
    val buying = Buying(this)
    val books = Books(this)

    /**
     * Startup check: the database must continue the anchored history, and the audit chain must verify
     * from the last anchored checkpoint onward (incremental, so startup time does not grow with history,
     * AUD-007). On success a new checkpoint anchor is written. A failure is returned, never thrown, so
     * the app can show a recovery screen instead of crashing. [verifyAuditFull] re-checks everything.
     */
    fun verifyStartup(): StartupVerdict {
        val verdict = integrity.verify(epoch)
        if (verdict != StartupVerdict.Healthy) return verdict
        return try {
            val anchor = anchors.latest()
            // Incremental from the signed checkpoint, but the whole chain is re-verified at least every
            // FULL_VERIFY_DAYS, so editing old events behind the app's back cannot stay hidden.
            val lastFull = meta.get(LAST_FULL_VERIFY)?.toLongOrNull() ?: 0L
            val fullDue = clock.nowEpochMillis() - lastFull >= FULL_VERIFY_DAYS * 86_400_000L
            val from = anchor?.takeIf { !fullDue && it.kind == ir.sabou.platform.AnchorKind.CHECKPOINT && it.epoch == epoch && it.position > 0 }
                ?.let { ir.sabou.platform.AuditCheckpoint(it.epoch, it.sequence, it.hash, it.position) }
            AuditTrail(auditStore).verify(from)
            if (from == null) unitOfWork.transaction { meta.put(LAST_FULL_VERIFY, clock.nowEpochMillis().toString()) }
            integrity.recordCheckpoint(epoch, clock.nowEpochMillis())
            applyEventRetention()
            StartupVerdict.Healthy
        } catch (e: ir.sabou.kernel.DomainException) {
            StartupVerdict.RollbackDetected(e.error.code)
        }
    }

    /**
     * Domain events are the future sync stream (ADR-0001). Until sync is switched on they are kept
     * for [LOCAL_EVENT_DAYS] days; once it is on, only events already delivered are pruned (AUD-022).
     */
    private fun applyEventRetention() {
        val day = 86_400_000L
        val syncOn = meta.get(SYNC_ENABLED) == "true"
        events.prune(clock.nowEpochMillis() - (if (syncOn) SYNCED_EVENT_DAYS else LOCAL_EVENT_DAYS) * day, onlySynced = syncOn)
    }

    /** True when the whole chain has not been verified for a day: run [verifyAuditInBackground]. */
    fun backgroundVerificationDue(): Boolean =
        clock.nowEpochMillis() - (meta.get(LAST_FULL_VERIFY)?.toLongOrNull() ?: 0L) >= BACKGROUND_VERIFY_HOURS * 3_600_000L

    /**
     * The daily full check, run off the startup path so opening the app stays fast. Startup still forces a
     * full check when this has not succeeded for [FULL_VERIFY_DAYS] (e.g. the app is always closed quickly).
     */
    fun verifyAuditInBackground(): StartupVerdict {
        val verdict = verifyAuditFull()
        if (verdict == StartupVerdict.Healthy) unitOfWork.transaction { meta.put(LAST_FULL_VERIFY, clock.nowEpochMillis().toString()) }
        return verdict
    }

    /** Verifies the whole audit chain from its first event (before a backup, or on demand). */
    fun verifyAuditFull(): StartupVerdict = try {
        AuditTrail(auditStore).verify()
        StartupVerdict.Healthy
    } catch (e: ir.sabou.kernel.DomainException) {
        StartupVerdict.RollbackDetected(e.error.code)
    }

    /**
     * Records the rebase anchor for a database that is about to replace this one. Only a signed-in user
     * holding the matching permission may announce a restore or reset; the decision is audited first.
     */
    fun acceptReplacement(newEpoch: String, reason: String) {
        val permission = if (reason == "FACTORY_RESET") ir.sabou.platform.Permission.FACTORY_RESET else ir.sabou.platform.Permission.BACKUP_RESTORE
        administrative(permission, "DATABASE_REPLACE", "$reason:$newEpoch")
        integrity.recordRebase(newEpoch, reason, clock.nowEpochMillis())
    }

    /** Checks and audits taking a full backup. Call before exporting the database. */
    fun authorizeBackup() {
        administrative(ir.sabou.platform.Permission.BACKUP_CREATE, "BACKUP_CREATE", "")
        val full = verifyAuditFull()
        if (full is StartupVerdict.RollbackDetected) throw ir.sabou.kernel.DomainException(ir.sabou.kernel.DomainError.IntegrityViolation(full.detail))
    }

    private fun administrative(permission: ir.sabou.platform.Permission, action: String, detail: String) = unitOfWork.transaction {
        val actor = session.currentActor() ?: throw ir.sabou.kernel.DomainException(ir.sabou.kernel.DomainError.AuthenticationRequired)
        if (!actor.role.allows(permission)) throw ir.sabou.kernel.DomainException(ir.sabou.kernel.DomainError.PermissionDenied(permission.name))
        AuditTrail(auditStore).append(
            ir.sabou.platform.AuditDraft(action, "DATABASE", epoch, detail), actor, ModuleId.PLATFORM, "ORG",
            ir.sabou.kernel.GlobalId.new().value, clock.nowEpochMillis(), epoch,
        )
    }

    /**
     * First run in one transaction: the owner, the first branch, a cash box and a kitchen. Either all
     * of it exists afterwards or none of it (a failed attempt can simply be retried).
     */
    fun bootstrap(branchName: String, ownerName: String, username: String, pin: CharArray) {
        val name = branchName.trim()
        if (name.length !in 2..80) throw ir.sabou.kernel.DomainException(ir.sabou.kernel.DomainError.InvalidInput("name", "نام شعبه باید ۲ تا ۸۰ حرف باشد."))
        try {
            unitOfWork.transaction {
                identity.bootstrapOwner(username, ownerName, pin)
                val branch = ir.sabou.kernel.Scope.Branch(identity.createBranch(name))
                treasury.openAccount(ir.sabou.treasury.OpenTreasuryAccount(ir.sabou.kernel.GlobalId.new(), branch, "صندوق $name", ir.sabou.treasury.TreasuryKind.CASH))
                inventory.createLocation(ir.sabou.inventory.CreateLocation(ir.sabou.kernel.GlobalId.new(), branch, "آشپزخانه"))
            }
        } catch (e: Throwable) {
            session.end()
            throw e
        }
    }

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

        const val SYNC_ENABLED = "sync_enabled"
        const val LAST_FULL_VERIFY = "audit_full_verified_at"
        const val FULL_VERIFY_DAYS = 7L
        const val BACKGROUND_VERIFY_HOURS = 24L
        const val LOCAL_EVENT_DAYS = 90L
        const val SYNCED_EVENT_DAYS = 30L
    }
}
