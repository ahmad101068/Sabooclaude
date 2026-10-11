# ADR-0035: Bank statement reconciliation

Status: accepted · 2026-10

## Context

«Reconcile» meant a cash count. For bank accounts nothing compared the books with what the bank says: fees, card
terminal settlements and cleared cheques were found by eye, if at all.

## Decision

- **Import**: the statement exported from internet banking (Excel or CSV) is read by `BankStatementFile` with the
  same readers as POS files: header with «تاریخ», description, money out («برداشت»/«بدهکار») and in
  («واریز»/«بستانکار») or one signed amount, optional balance and reference; Toman headers are converted to Rial.
  Newest-first files are put oldest-first. When balances are present, every row's balance must follow from the
  previous one; a break is reported (the columns were misread or rows are missing) and blocks the import.
- **Statement** (`bank_statements`, schema 13) keeps its lines and the book movement each one matched. The same
  lines cannot be imported twice.
- **Matching**: same account, same direction, same amount, date within 4 days; exact-date matches first, then
  1 day, … so a close match is never taken by a far one; one movement matches one line ever (across statements).
  Reversed movements and reversals are not matchable. Matches can be undone and made by hand.
- **Statement-only lines** are recorded from the line itself: bank fee (new account 6111 «کارمزد بانکی»), other
  expense, owner withdrawal, other income, owner capital, or a transfer from/to another treasury account (a card
  terminal's settlement). The statement is matched again right after, so the new movement matches its line.
- **Reconciliation**: book balance on the statement's last day − book-only items + statement-only items = what the
  bank should show; the difference from the statement's closing balance is shown as unexplained.

## Consequences

- No journal is posted by importing or matching; only the explicit recording posts, through the ordinary
  treasury commands.
- Book-only items before the statement's first day (minus the matching window) are not listed; an old uncleared
  cheque shows up in the unexplained difference until a statement clears it.
