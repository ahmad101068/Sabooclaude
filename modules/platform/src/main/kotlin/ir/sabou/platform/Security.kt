package ir.sabou.platform

import ir.sabou.kernel.BranchId
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Scope

/** Business modules. A module owns its documents; only the owner may post or reverse them (ADR-0002). */
enum class ModuleId { PLATFORM, LEDGER_MANUAL, TREASURY, SALES, PURCHASING, INVENTORY, PAYROLL, ASSETS }

enum class Permission {
    // Platform
    USER_MANAGE, BACKUP_CREATE, BACKUP_RESTORE, FACTORY_RESET, AUDIT_VIEW,
    // Ledger
    LEDGER_VIEW, JOURNAL_MANUAL_POST, JOURNAL_MANUAL_REVERSE, PERIOD_CLOSE, PERIOD_REOPEN, BUDGET_MANAGE,
    // Fixed assets
    ASSET_VIEW, ASSET_MANAGE,
    // Treasury
    TREASURY_VIEW, TREASURY_ACCOUNT_MANAGE, TREASURY_RECEIPT, TREASURY_PAYMENT, TREASURY_TRANSFER, TREASURY_RECONCILE, TREASURY_REVERSE,
    /** Deposit, collect, bounce and settle cheques. */
    CHEQUE_MANAGE,
    // Sales
    SALES_VIEW, SALES_RECORD, SALES_POST, SALES_REVERSE, SALES_DAY_CLOSE, SALES_DAY_REOPEN, RECEIVABLE_COLLECT, RECEIVABLE_REVERSE,
    CUSTOMER_MANAGE, CREDIT_OVERRIDE,
    /** Set menu prices (for every branch or one); sell at a price other than the menu's, with a reason. */
    MENU_PRICE_MANAGE, SALES_PRICE_OVERRIDE,
    // Purchasing & inventory
    PURCHASE_VIEW, PURCHASE_RECORD, PURCHASE_PAY, PURCHASE_REVERSE, SUPPLIER_MANAGE, PURCHASE_ORDER,
    /** Approve an invoice for payment; take approvals back; define the approval rules. */
    PURCHASE_APPROVE, PURCHASE_UNAPPROVE, APPROVAL_RULES,
    INVENTORY_LOCATION_MANAGE, INVENTORY_WASTE, INVENTORY_COUNT, INVENTORY_OPENING,
    INVENTORY_VIEW, INVENTORY_ITEM_MANAGE, INVENTORY_ADJUST, INVENTORY_TRANSFER, RECIPE_MANAGE, INVENTORY_PRODUCE,
    // Payroll
    PERSONNEL_VIEW, PERSONNEL_MANAGE, ATTENDANCE_RECORD, PAYROLL_CALCULATE, PAYROLL_APPROVE, PAYROLL_PAY,
    // Organization-wide data (non-branch documents)
    ORGANIZATION_DATA,
}

enum class Role(val permissions: Set<Permission>) {
    OWNER(Permission.entries.toSet()),
    MANAGER(
        Permission.entries.toSet() - setOf(
            Permission.USER_MANAGE, Permission.BACKUP_RESTORE, Permission.FACTORY_RESET,
            Permission.PERIOD_REOPEN, Permission.PAYROLL_APPROVE, Permission.ORGANIZATION_DATA,
            Permission.SALES_DAY_REOPEN, Permission.CREDIT_OVERRIDE,
            // Approval controls stay with the owner: taking approvals back and changing the rules.
            Permission.PURCHASE_UNAPPROVE, Permission.APPROVAL_RULES,
            // Organization-wide: closing the books and full-data backups stay with the owner/accountant.
            Permission.PERIOD_CLOSE, Permission.BACKUP_CREATE,
        ),
    ),
    ACCOUNTANT(
        setOf(
            Permission.LEDGER_VIEW, Permission.JOURNAL_MANUAL_POST, Permission.JOURNAL_MANUAL_REVERSE, Permission.PERIOD_CLOSE,
            Permission.BUDGET_MANAGE, Permission.ASSET_VIEW, Permission.ASSET_MANAGE,
            Permission.TREASURY_VIEW, Permission.TREASURY_RECEIPT, Permission.TREASURY_PAYMENT, Permission.TREASURY_TRANSFER,
            Permission.TREASURY_RECONCILE, Permission.TREASURY_REVERSE, Permission.CHEQUE_MANAGE, Permission.SALES_VIEW, Permission.RECEIVABLE_COLLECT,
            Permission.PURCHASE_VIEW, Permission.PURCHASE_PAY, Permission.PERSONNEL_VIEW, Permission.PAYROLL_CALCULATE,
            Permission.PAYROLL_PAY, Permission.AUDIT_VIEW, Permission.ORGANIZATION_DATA,
        ),
    ),
    CASHIER(setOf(Permission.SALES_VIEW, Permission.SALES_RECORD, Permission.TREASURY_VIEW)),
    STOREKEEPER(
        setOf(
            Permission.INVENTORY_VIEW, Permission.INVENTORY_ITEM_MANAGE, Permission.INVENTORY_TRANSFER,
            Permission.INVENTORY_WASTE, Permission.INVENTORY_COUNT, Permission.INVENTORY_PRODUCE,
            // Records invoices but does not create suppliers: creating a payee and billing it are kept apart.
            Permission.PURCHASE_VIEW, Permission.PURCHASE_RECORD, Permission.PURCHASE_ORDER,
        ),
    ),
    RESTRICTED(emptySet());

    fun allows(permission: Permission): Boolean = permission in permissions
}

/**
 * The authenticated user for one command. Branch grants are part of the actor so that scope is
 * checked by the pipeline, never by individual handlers (ADR-0003, AUD-004).
 */
data class Actor(
    val userId: GlobalId,
    val displayName: String,
    val role: Role,
    val branchGrants: Set<BranchId>,
) {
    val isOwner: Boolean get() = role == Role.OWNER

    fun canAccess(scope: Scope): Boolean = when (scope) {
        Scope.Organization -> isOwner || role.allows(Permission.ORGANIZATION_DATA)
        is Scope.Branch -> isOwner || scope.branchId in branchGrants
    }
}

/** Supplies the currently authenticated actor, or null. Implemented by the session layer. */
fun interface SessionPort {
    fun currentActor(): Actor?
}
