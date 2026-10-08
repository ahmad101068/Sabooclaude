package ir.sabou.persistence

/**
 * Forward-only, numbered schema migrations (ADR-0006). Every schema change is a new entry here
 * plus a test; the app refuses to open a database newer than it knows (AUD-014).
 */
object Schema {
    data class Migration(val version: Int, val statements: List<String>)

    private fun immutable(table: String) = listOf(
        "CREATE TRIGGER ${table}_no_update BEFORE UPDATE ON $table BEGIN SELECT RAISE(ABORT, 'IMMUTABLE:$table'); END",
        "CREATE TRIGGER ${table}_no_delete BEFORE DELETE ON $table BEGIN SELECT RAISE(ABORT, 'IMMUTABLE:$table'); END",
    )

    val migrations: List<Migration> = listOf(
        Migration(
            1,
            listOf(
                // Platform
                "CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
                "CREATE TABLE idempotency (command_id TEXT PRIMARY KEY, command_type TEXT NOT NULL, fingerprint TEXT NOT NULL, result_id TEXT NOT NULL, recorded_at INTEGER NOT NULL)",
                "CREATE TABLE audit_events (position INTEGER PRIMARY KEY AUTOINCREMENT, epoch TEXT NOT NULL, sequence INTEGER NOT NULL, previous_hash TEXT NOT NULL, hash TEXT NOT NULL, occurred_at INTEGER NOT NULL, actor_id TEXT NOT NULL, actor_name TEXT NOT NULL, module TEXT NOT NULL, action TEXT NOT NULL, entity_type TEXT NOT NULL, entity_id TEXT NOT NULL, scope TEXT NOT NULL, command_id TEXT NOT NULL, detail TEXT NOT NULL, UNIQUE(epoch, sequence))",
                "CREATE TABLE domain_events (event_id TEXT PRIMARY KEY, command_id TEXT NOT NULL, module TEXT NOT NULL, type TEXT NOT NULL, scope TEXT NOT NULL, occurred_at INTEGER NOT NULL, payload TEXT NOT NULL, synced INTEGER NOT NULL DEFAULT 0)",
                "CREATE TABLE users (id TEXT PRIMARY KEY, username TEXT NOT NULL UNIQUE, doc TEXT NOT NULL)",
                "CREATE TABLE branches (id TEXT PRIMARY KEY, doc TEXT NOT NULL)",
                // Ledger
                "CREATE TABLE accounts (code TEXT PRIMARY KEY, doc TEXT NOT NULL)",
                "CREATE TABLE journal_entries (id TEXT PRIMARY KEY, number INTEGER NOT NULL UNIQUE, date INTEGER NOT NULL, scope TEXT NOT NULL, source_module TEXT NOT NULL, source_type TEXT NOT NULL, source_id TEXT NOT NULL, reversal_of TEXT UNIQUE, doc TEXT NOT NULL)",
                "CREATE INDEX journal_entries_source ON journal_entries(source_type, source_id)",
                "CREATE TABLE journal_lines (entry_id TEXT NOT NULL REFERENCES journal_entries(id), line_no INTEGER NOT NULL, account TEXT NOT NULL REFERENCES accounts(code), scope TEXT NOT NULL, date INTEGER NOT NULL, debit INTEGER NOT NULL CHECK(debit >= 0), credit INTEGER NOT NULL CHECK(credit >= 0), contributor TEXT NOT NULL, memo TEXT NOT NULL, PRIMARY KEY(entry_id, line_no), CHECK((debit = 0) <> (credit = 0)))",
                "CREATE INDEX journal_lines_account ON journal_lines(account, scope, date)",
                "CREATE TABLE period_locks (id TEXT PRIMARY KEY, from_day INTEGER NOT NULL, to_day INTEGER NOT NULL, closed INTEGER NOT NULL)",
                // Treasury
                "CREATE TABLE treasury_accounts (id TEXT PRIMARY KEY, doc TEXT NOT NULL)",
                "CREATE TABLE treasury_movements (id TEXT PRIMARY KEY, account_id TEXT NOT NULL REFERENCES treasury_accounts(id), direction TEXT NOT NULL, amount INTEGER NOT NULL CHECK(amount > 0), source_type TEXT NOT NULL, source_id TEXT NOT NULL, reversal_of TEXT UNIQUE, doc TEXT NOT NULL)",
                "CREATE INDEX treasury_movements_account ON treasury_movements(account_id)",
                "CREATE INDEX treasury_movements_source ON treasury_movements(source_type, source_id)",
                // Inventory
                "CREATE TABLE items (id TEXT PRIMARY KEY, doc TEXT NOT NULL)",
                "CREATE TABLE locations (id TEXT PRIMARY KEY, doc TEXT NOT NULL)",
                "CREATE TABLE stock_balances (item_id TEXT NOT NULL REFERENCES items(id), location_id TEXT NOT NULL REFERENCES locations(id), quantity INTEGER NOT NULL CHECK(quantity >= 0), value INTEGER NOT NULL CHECK(value >= 0), PRIMARY KEY(item_id, location_id))",
                "CREATE TABLE stock_movements (id TEXT PRIMARY KEY, source_type TEXT NOT NULL, source_id TEXT NOT NULL, reversal_of TEXT UNIQUE, doc TEXT NOT NULL)",
                "CREATE INDEX stock_movements_source ON stock_movements(source_type, source_id)",
                "CREATE TABLE menu_items (id TEXT PRIMARY KEY, doc TEXT NOT NULL)",
                "CREATE TABLE recipe_versions (id TEXT PRIMARY KEY, menu_item_id TEXT NOT NULL REFERENCES menu_items(id), doc TEXT NOT NULL)",
                // Purchasing
                "CREATE TABLE suppliers (id TEXT PRIMARY KEY, doc TEXT NOT NULL)",
                "CREATE TABLE purchase_invoices (id TEXT PRIMARY KEY, supplier_id TEXT NOT NULL REFERENCES suppliers(id), number TEXT NOT NULL, doc TEXT NOT NULL, UNIQUE(supplier_id, number))",
                "CREATE TABLE supplier_payments (id TEXT PRIMARY KEY, invoice_id TEXT NOT NULL REFERENCES purchase_invoices(id), doc TEXT NOT NULL)",
                "CREATE TABLE purchase_returns (id TEXT PRIMARY KEY, invoice_id TEXT NOT NULL REFERENCES purchase_invoices(id), doc TEXT NOT NULL)",
                // Sales
                "CREATE TABLE customers (id TEXT PRIMARY KEY, doc TEXT NOT NULL)",
                "CREATE TABLE daily_sales (id TEXT PRIMARY KEY, scope TEXT NOT NULL, date INTEGER NOT NULL, status TEXT NOT NULL, doc TEXT NOT NULL)",
                "CREATE INDEX daily_sales_day ON daily_sales(scope, date)",
                "CREATE TABLE receivables (id TEXT PRIMARY KEY, sale_id TEXT NOT NULL, customer_id TEXT NOT NULL, doc TEXT NOT NULL)",
                "CREATE TABLE collections (id TEXT PRIMARY KEY, receivable_id TEXT NOT NULL REFERENCES receivables(id), doc TEXT NOT NULL)",
                "CREATE TABLE sales_days (scope TEXT NOT NULL, date INTEGER NOT NULL, doc TEXT NOT NULL, PRIMARY KEY(scope, date))",
                // Payroll
                "CREATE TABLE employees (id TEXT PRIMARY KEY, scope TEXT NOT NULL, doc TEXT NOT NULL)",
                "CREATE TABLE attendance (id TEXT PRIMARY KEY, employee_id TEXT NOT NULL REFERENCES employees(id), date INTEGER NOT NULL, doc TEXT NOT NULL, UNIQUE(employee_id, date))",
                "CREATE TABLE payroll_runs (id TEXT PRIMARY KEY, scope TEXT NOT NULL, doc TEXT NOT NULL)",
                "CREATE TABLE salary_payments (id TEXT PRIMARY KEY, run_id TEXT NOT NULL REFERENCES payroll_runs(id), doc TEXT NOT NULL)",
                "CREATE TABLE remittances (id TEXT PRIMARY KEY, scope TEXT NOT NULL, kind TEXT NOT NULL, doc TEXT NOT NULL)",
            ) + immutable("audit_events") + immutable("journal_entries") + immutable("journal_lines") +
                immutable("treasury_movements") + immutable("stock_movements"),
        ),
    )

    val latestVersion: Int = migrations.maxOf { it.version }

    /** Applies pending migrations inside one transaction. Refuses a newer, unknown database. */
    fun migrate(db: SqlDatabase) {
        db.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)")
        val current = db.query("SELECT COALESCE(MAX(version), 0) AS v FROM schema_version").single().long("v").toInt()
        check(current <= latestVersion) { "DATABASE_NEWER_THAN_APP:$current>$latestVersion" }
        val pending = migrations.filter { it.version > current }.sortedBy { it.version }
        if (pending.isEmpty()) return
        db.begin()
        try {
            pending.forEach { m ->
                m.statements.forEach { db.execute(it) }
                db.execute("INSERT INTO schema_version(version) VALUES (?)", m.version)
            }
            db.commit()
        } catch (e: Throwable) {
            db.rollback()
            throw e
        }
    }
}
