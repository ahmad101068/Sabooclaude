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

## Decision — stage 2 (accounting)
- **Own accounts** — `DefineAccount` / `UpdateAccount` (new ACCOUNT_MANAGE: owner, manager, accountant): four-digit
  codes, the first digit giving the kind; never control accounts; retired only at zero balance. New standard accounts:
  prepaid expenses 1402, supplier advances 1403 (purchasing), deposits 1601, customer advances 2109 (sales), asset
  purchase payable 2110, loans 2201, and expenses 6113–6117 (repairs, packaging, marketing, delivery, finance costs).
- **Treasury** receipts and payments take any open account (`counterAccount`) besides the fixed purposes; purchase
  expense lines already took any open expense account, so the restaurant's own expenses work everywhere.
- **Opening balances** — `RecordSupplierOpeningBalance` (a payable against capital, paid like an invoice, approval
  rules apply) and `RecordCustomerOpeningBalance` / `ReverseCustomerOpeningBalance` (a receivable against capital).
- **Reports** (`ledgerReads`) — six-column trial balance of a period; balance sheet on a date; journal book; supplier and
  customer statements with running balance; receivables and payables aging (not due, 1–30, 31–60, 61–90, 90+); all
  exportable. Write-offs are kept with their dates for the statements.
- **Year end** — no closing entries: the balance sheet shows earlier years' and this year's result computed from the
  books (Farvardin 1 of the date's year), and the year is locked by closing its months. P&L of any period stays true.

## Decision — stage 3 (purchasing and sales)
- **Invoice** — `vat` (to 1108, offset against sales VAT), `discount` and `freight` spread over the goods lines by value
  (the stock carries them; the rounding rest goes to the largest line).
- **Paying a supplier** — `PaySupplier` pays approved open invoices oldest due first, one payment document each.
  `PaySupplierAdvance` / `ApplySupplierAdvance` / `ReverseSupplierAdvance`: an advance sits in 1403 and is moved to
  payables when set against an invoice (an allocation with its journal; releasing it reverses the journal).
- **Partial deliveries** — `orderPartial`: the invoice is compared with what is still to come; the order stays open
  until everything arrived (or it is cancelled). Suggestions count only what is still on order.
- **Suppliers** — tax ID, address and Sheba (validated).
- **Customer deposits** — `ReceiveCustomerDeposit` (to 2109), `ApplyCustomerDeposit` (2109 → receivable, a collection
  with its journal), `ReverseCustomerDeposit` (unused). Statements count the deposit when it came in.
- **Cash** — `CountCashShift` (SALES_RECORD): a blind count at the end of a shift, booked at once; the day's close
  continues from it. `CloseSalesDay.terminalTotals`: each card terminal's settlement report against the card sales
  recorded, kept with the day (not booked).
- **Alerts** — supplier invoices due within two days or late; customers' debts past due.
- Schema 25: `supplier_advances`, `customer_deposits`, `shift_counts`.
