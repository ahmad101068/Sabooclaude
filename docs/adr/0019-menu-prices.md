# ADR-0019 — Menu prices and computed sale totals

**Status:** Accepted (2026-10-10).

## Context
A day's sale was entered as quantity *and* total per menu item. Nothing tied the total to a price: a typo in a total went straight into revenue, food-cost percentages and the books, and nobody could tell a discount from a mistake.

## Decision
1. **Price list.** A menu item's price (Rial per portion) is a *version* effective from a date, for the whole organization or for one branch. Versions are append-only (the table refuses updates and deletes); the version in force on a day is the branch's own if it has one, else the organization's; of two versions on the same date the later-recorded wins. A branch version without a price ends the branch's own price.
2. **The total is never typed.** `SaveSaleDraft` takes `SaleLineInput(menuItem, quantity, unitPrice?, reason?)`. The sales domain prices each line — the menu price in force, or the typed price when the menu has none — and computes `gross = unitPrice × quantity` (half-up to the Rial). The screen shows the same computation; it cannot send a total.
3. **Overrides are explicit.** A typed price different from the menu's needs `SALES_PRICE_OVERRIDE` and a reason (3–200 characters). The line keeps its unit price, the list price and the reason; the command's audit record keeps who did it.
4. **Recorded lines are facts.** A saved line keeps the price it was recorded at; a price changed afterwards re-prices a draft only when it is saved again, and never a posted day.
5. Permissions: `MENU_PRICE_MANAGE` (owner, manager) and `SALES_PRICE_OVERRIDE` (owner, manager).

## Consequences
- Schema 8 adds `menu_prices`. Sale lines recorded before it keep their total; their unit price is shown as total ÷ quantity.
- The Sepand import (P1) prices imported lines through the same domain and flags rows whose price differs from the menu's.
- Sale types (dine-in, delivery) and time-of-day prices can be added as further price dimensions without changing recorded lines.
