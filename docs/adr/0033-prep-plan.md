# ADR-0033: Daily prep plan and allergens

Status: accepted · 2026-10

## Context

Prepared items (dough, sauces) could be produced and costed, but the kitchen had no answer to «what do we make
this morning, how much, in what order?». Allergens were a free-text note on each item with no view per dish.

## Decision

- **Forecast** per dish: the average of posted sales on the same weekday over the last 4 weeks, counting only weeks
  that had sales (a closed day does not pull it down), rounded up to whole portions. It is a default the person edits.
- **Prep plan** for a kitchen and a day: dishes → ingredients (current recipes), plus each prepared item's par
  (per weekday, ADR-0032), broken down through prepared items at any depth with the existing `PrepGraph` (on-hand
  stock used first). Each prepared item to make shows required, par, on hand and **to make**, ordered so that
  what others are made from comes first. Raw ingredients needed beyond stock are listed as shortages, linked to the
  purchase suggestions. «Made» records the production (`RecordProduction`) for today.
- **Allergens per dish**: the union of the allergens noted on every item in the current recipe, through prepared
  items at any depth (separators «،», «,», «؛»).
- Read-only (`Prep`); no new tables. Persian alphabetical order is explicit (`Fa.compareAlphabetical`), since the
  JVM has no Persian collation rules.

## Consequences

- An item with no allergen note contributes nothing; the screen says so.
- A real forecast (trend, holidays, events) is stage 5; this one is deliberately simple and explainable.
