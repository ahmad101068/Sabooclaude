# ADR-0028 — Menu engineering and food cost against a target

**Status:** Accepted (2026-10-11). Plan item P1-5.

## Decision
1. **Recipe cost is never stored, always derived:** one portion's cost is the recipe at today's average stock cost of each ingredient at the branch, else its last purchase price (prepared items through their prep recipes). A new purchase price therefore changes every dish that uses it, with nothing to recalculate.
2. **Menu engineering (Kasavana & Smith)** over a period: popular = share of portions ≥ 70 % of an equal share; profitable = margin per portion (average selling price − today's cost) ≥ the menu's portion-weighted average margin. Star, plowhorse, puzzle, dog — each with the usual advice; items without a known cost are listed apart.
3. **Food-cost target** (basis points of the menu price, 5–90 %, audited command `SetFoodCostTarget`). Dishes at or over it are marked on the menu and engineering pages, and announced by the FOOD_COST alert (once per dish and branch while over) to those who manage the menu.
