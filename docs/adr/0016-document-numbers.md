# ADR-0016 — Document numbers

**Status:** Accepted (2026-10-10)

## Context
Only journal entries and purchase orders had numbers, both a global `MAX+1` across all years and branches. Sales, receipts, payments, transfers, cheques, counts, waste, payroll, assets and every reversal had none; a purchase invoice carried only the supplier's own number. The cause was structural: "document number" was not a concept of the domain, so each module decided for itself and most decided nothing.

## Decision
- **One service, one rule.** `CommandContext.number(series, date, documentId)` issues the next number of a series (`DocumentSeries`) for the fiscal (Jalali) year of the document's date and its branch (the journal: whole organization). The counter row moves inside the command's transaction: no gaps (a failed command rolls back), no duplicates (primary keys), never reused or changed (immutable table).
- **Declared, then enforced.** Every command class carries `@IssuesDocument(series)` or `@NoDocument`. The command bus rolls back a command that declares a document and did not number it; an architecture test fails if any command carries neither. Numbering cannot be forgotten by a new module.
- **Reversals are documents.** They get a number of the `REVERSAL` series, issued under the reversing command's id, and record the number of what they reverse.
- **The journal's legal number** (`سح-1405-00042`) is issued by the ledger for every entry, whatever module posts it; reversal descriptions name the original by it. The internal global `number` stays for ordering only.
- **Migration 7** creates the tables and gives existing journals their legal numbers in posting order per fiscal year.
- **Audit envelope.** The bus also writes a `COMMAND` audit record for a successful command whose handler wrote none (ChatGPT B-AUD-003).

## Consequences
- Screens, the ledger report and its export show `فخ-۱۴۰۵-۰۰۰۱۲`-style numbers; the supplier's own invoice number is kept and shown separately.
- The fiscal year is the Jalali calendar year until fiscal periods with a configurable start arrive (they reuse `FiscalYear`).
- The same series and numbers are the basis of the future tax-system (Moadian) invoice serials.
