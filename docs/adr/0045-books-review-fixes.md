# ADR-0045 — Review of sales, purchasing, treasury, ledger, assets and access

**Status:** Accepted (2026-10-14)

## Context
After personnel (ADR-0043) and inventory (ADR-0044), the remaining modules were reviewed. Found: VAT owed by sales
(2103, a control account of sales) had no way to be paid; the day-close cash count compared with the box's balance
*now*, so closing a past day after later sales booked a false over/short; a counted (closed) day's cash box could still
change; a discount needed no right and no reason while a price change needed both; a dish without a recipe stopped the
whole day's posting; customers, cash boxes and branches could not be edited; an invoice with an assigned review line
could never be reversed. (A reversed invoice already freed its number: the unique column holds only posted numbers.)

## Decision — stage 1 (defects)
- **Sales tax** — `PaySalesTax` (TREASURY_PAYMENT) pays 2103 from a treasury account; `inputCredit` sets VAT paid on
  purchases (new 1108, owned by purchasing and sales) against it first. Never more than is owed.
- **Dated cash count** — `MovementStore.balanceAsOf`: the count is against the box at the end of the counted day.
- **Closed cash day** — the treasury gateway refuses any movement, reversal or transfer of a cash/petty-cash box dated
  on a closed sales day of its branch (`SALES_DAY:CLOSED`); sale reversals also check the reversal date.
- **Discount** — `SaveSaleDraft.discountReason` (required with a discount); posting a sale with a discount needs the
  new `SALES_DISCOUNT` (owner, manager): a cashier records it, the manager who posts approves it. POS imports carry
  their own reason.
- **Service charge and VAT** — `SetServiceCharge` (bp); the sale form works both out (service on food after discount,
  VAT on food + service at the rate in force), still editable.
- **No recipe** — a dish without a recipe on the day is sold without taking stock, flagged in the form.
- **Customers** — `UpdateCustomer`; a customer of any branch may buy on credit in another. `WriteOffReceivable`
  (CREDIT_OVERRIDE) books bad debt (new 6112) against the receivable; a sale with a write-off is not reversed.
- **Edits** — `UpdateTreasuryAccount` (rename, close when empty and no open cheque, reopen); `renameBranch`.
- **Purchase reversal** reverses assigned review lines too (goods back out, or the expense journal reversed).
