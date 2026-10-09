# ADR-0013 — Cheques, invoice approval, budgets and fixed assets

**Status:** Accepted (2026-10-09)

## Cheques
- Two new kinds of treasury account: **cheque box** (received cheques, GL 1105) and **cheque book** (our cheques, GL 2107, a liability, so its balance is minus what is outstanding). Every existing money path works with them unchanged at GL level.
- Each movement into a box (receipt, customer collection) carries the cheque's details: number, bank, optional 16-digit Sayad id, due date, drawer. Each payment from a book (expense, supplier payment, asset purchase) creates our cheque, drawn on a bank account of the same branch. A payment *from* a box passes a held cheque on (endorsement), for exactly its amount.
- The cheque's status follows the money and nothing else:
  - received: in hand → (deposited) → collected / bounced / endorsed;
  - ours: issued → cleared / bounced;
  - a bounced cheque moves to 1107 / 2108 until it is **settled** in money.
- Collecting and clearing are one journal between the box/book and the bank.
- Reversing the document behind a step puts the cheque back one step. It is refused if the cheque has moved on since, and an untouched cheque whose receipt is reversed becomes VOID.
- The daily sale settlement does not offer cheque accounts. Transfers and cash counts refuse them.
- Due cheques (14 days, overdue first) are listed and shown on Home.
- Our cheques print on a leaf-sized PDF:
  - date in figures and words, amount in rial in figures and words, payee;
  - positions are a template to adjust after a test print, because banks differ.

## Invoice approval
- Rules (owner only) match by branch, supplier, invoice kind (goods / expenses / mixed) and minimum amount. The strictest matching rule sets how many approvals (1–3) an invoice needs.
- The number is fixed when the invoice is recorded, so changing a rule does not change existing invoices.
- An invoice is paid only when approved. Each approval is by a different person, never the one who recorded it (the owner excepted).
- Approving (PURCHASE_APPROVE: owner, manager) and taking approvals back (PURCHASE_UNAPPROVE: owner) are separate permissions. Taking back is refused once something has been paid.
- Return credit is not a payment and needs no approval.

## Budgets
- An amount per revenue/expense account, scope and period (the UI writes the 12 Jalali months).
- Budget vs actual counts a period partly inside the range by its share of days. Actual comes from the journal.

## Fixed assets (new module `assets`, ModuleId.ASSETS)
- Bought from a treasury account (also from a cheque book, or centrally for a branch), or registered as already owned against capital with its depreciation so far.
- Depreciation by days:
  - straight line over the useful life down to salvage;
  - or declining balance at a yearly rate.
- One journal per run (6110 / 1509). Only the latest run can be reversed.
- Disposal books depreciation to the day, clears cost and accumulated depreciation, and books the gain (4102) or loss (6105). Proceeds go to any treasury account through the inter-branch account.
- Presets follow common classes of the tax depreciation table and must be confirmed by a tax advisor.

## Consequences
Schema v4 adds the tables cheques, approval_rules, budgets, fixed_assets and depreciation_runs. Accounts 1105, 1107, 1501, 1509, 2107, 2108 and 6110 are added on open. Cash refunds of a supplier's unapplied credit, and cheques in the daily sale, are not modelled.
