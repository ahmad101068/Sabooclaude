# ADR-0044 — Inventory: documents, stock card, corrections, levels per location

**Status:** Accepted (2026-10-14)

## Context
A review of the inventory module found: waste, transfers, production and opening stock could not be taken back; the
documents could not be seen after they were recorded; there was no stock card (کاردکس); low-stock alerts compared every
location with one item-wide minimum (an item kept only in the storeroom was "low" in the kitchen and in other
branches); a count gain on empty stock came in at zero cost; an item created with the wrong unit could be neither fixed
nor deleted; locations could not be renamed or retired; waste took one item at a time and no dish; production assumed
the recipe's exact output.

## Decision
- **Reversal** — `ReverseInventoryDocument` (INVENTORY_ADJUST, with a reason) reverses every movement of a waste,
  transfer, production or opening document at its original value through the gateway's existing reversal (journals
  reversed, lots returned); numbered in the reversal series against the original. The goods must still be there.
- **Documents** — `inventory_documents` (schema 23) keeps what movements do not: reason, note, a dish waste, the planned
  batch of a production, the reason of a reversal. `StockRecords.documents` lists a branch's documents with their lines.
- **Stock card** — `StockRecords.card`: opening balance before the period, each movement with its document number and
  the running quantity and value; exportable.
- **Levels per location** — `Item.locationLevels` (minimum, par). With any set, only those locations are watched and
  ordered for; with none, the item-wide figures apply only to locations that hold the item.
- **Count gains** on empty stock are valued at the last purchase cost (as shortage write-offs already were).
- **Items** — category and code (unique); the unit may change while the item has no movement, recipe or pack;
  `DeleteItem` for an item nothing refers to (checked across every table). **Locations** — `UpdateLocation` renames or
  retires (empty, no pending count, no open shortage).
- **Waste** of several items in one document, or of dishes (portions × the recipe in force).
- **Production** takes `actualOutput`: ingredients for the planned batch, all their cost into what came out.
