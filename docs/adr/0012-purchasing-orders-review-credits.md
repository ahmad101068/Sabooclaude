# ADR-0012 — Purchase orders, richer invoices, supplier credit and suggested purchases

**Status:** Accepted (2026-10-09)

## Context
The owner's wishlist asked for invoices that match paper reality (photos, notes, lines that are not stock, items the storekeeper does not recognise, supplier names for our items), orders before delivery, par-based and recipe-based purchase suggestions, supplier delivery days and cut-off times, approved suppliers, price change alerts, and purchase returns that settle other open invoices.

## Decision
- **Invoice lines of three kinds**, posted in one command:
  - *goods* into a location (as before; the stock receipt journal);
  - *account lines* straight to an open expense account, optionally for another granted branch (an inter-branch journal pair, like central payments);
  - *held lines* whose item is unknown: debited to **1302 «خرید در انتظار بررسی»** (purchasing-owned) and listed in a review queue until assigned to an item (stock receipt against 1302) or an expense account. An invoice with assigned held lines can no longer be reversed as a whole.
- **Supplier item names** typed on goods lines (or on assigned held lines) are remembered per supplier and pre-select the item next time.
- **Attachments** (photo/PDF, ≤1.5 MB, photos shrunk on the device) are stored immutably in the encrypted database so backups carry them. Reading one requires access to the document it belongs to.
- **Return credit allocation:** a return's credit settles its own invoice first, then the same supplier's other open invoices in the branch (oldest due first); the rest is *unapplied supplier credit*, applied later by `ApplySupplierCredit` (PURCHASE_PAY) or released again. Sub-ledger: outstanding = total − payments − allocations; supplier balance = outstanding − unapplied credit = GL 2101. Schema v3 records every earlier return as an allocation to its own invoice.
- **Purchase orders** (new permission PURCHASE_ORDER: owner, manager, storekeeper) move no stock or money. The invoice that delivers an order closes it; reversing that invoice reopens it. The **approved-supplier list** of an item is enforced on orders (invoices only warn, deliveries happen).
- **Suppliers** have delivery week days, a lead time and an order cut-off; the next delivery an order can still make is shown on orders, invoices and suggestions.
- **Suggested purchases** (read model `Buying.suggestions`): par + planned dishes (recipes, with prepared items broken down into ingredients for their shortfall) − on hand − open orders, grouped by preferred supplier with its next delivery; one tap turns a group into an order.
- **Price changes** compare each invoice's unit price with the same supplier's previous invoice for the item (threshold 5/10/20%); the invoice form shows the difference while typing.
- **Comps:** waste reasons «پذیرایی مهمان»، «غذای پرسنل»، «اهدایی» are booked to **6109** and shown as their own column in actual-vs-theoretical usage; food cost includes them.

## Consequences
New tables (schema v3): attachments, supplier_item_aliases, credit_allocations, purchase_orders. New accounts 1302 and 6109 are added to existing databases on open. A supplier's cash refund of unapplied credit is not modelled yet (credit stays until used).
