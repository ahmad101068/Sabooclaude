# ADR-0017 — Units of entry and purchase packs

**Status:** Accepted (2026-10-10).

## Context
Every quantity was typed in the item's stock unit. A kitchen buys rice by the sack, counts cola by the crate and writes recipes in grams while stock is kept in kilograms; people converted in their heads, which is where count and recipe errors start.

## Decision
1. **One unit of record.** Stock, recipes, documents and commands keep quantities in the item's stock unit. Nothing below the screen knows other units.
2. **Entry units.** A quantity may be typed in the stock unit, a metric sibling of the same dimension (گرم/کیلوگرم, میلی‌لیتر/لیتر) or one of the item's **packs** (`PackUnit(name, contains)`, at most 10, `contains` in the stock unit).
3. **One converter.** `ir.sabou.inventory.Units` is the only place a quantity changes unit: exact integer micro-units, half-up; a non-zero amount that would round to zero is refused rather than silently lost.
4. **Packs are item master data**, edited on the item screen (`UpdateItem.packs`, null keeps them) and persisted with the item.
5. The shared `ItemQuantityInput` shows the unit choices, converts while typing and shows the stock-unit equivalent.

## Consequences
- Every screen that takes the quantity of a known item (opening stock, waste, count, transfer, production, recipes, purchase invoice/order/return, review lines) accepts any of its units.
- Minimum/par levels stay in the stock unit (they are compared with stock).
- Supplier-specific packs and prices per pack come with purchasing rework (P1-1).
