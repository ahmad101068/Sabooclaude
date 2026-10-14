# ADR-0053: Why profit changed

Status: accepted (2026-10) · builds on ADR-0050

## Context

The P&L says profit fell; it does not say why. The owner's question is "sales are up, so why is profit down?",
and the answer is spread over the books (each account), the sale lines (which dishes, at what price) and their
actual costs (ADR-0050). An answer that does not add up to the change in the books cannot be trusted or acted on.

## Decision

- **`ProfitBridges.of(branch, from, to, baseFrom, baseTo)`** (LEDGER_VIEW, through the P&L): the change in profit
  between the base and the current period as steps that add up to it **exactly**:
  - from the dish lines (posted sales, per-portion price and actual cost per dish in each period; a dish missing
    from one period takes the other's figures): **volume** (portions at base mix and margins), **mix**, **prices**
    and **ingredient costs** — the standard price-volume-mix split, in exact Rial (`Ratio.mulDivSigned`);
  - **discounts** (from the sales);
  - **untraced cost of sales**: cost of sales in the books not explained by the dish lines' costs (sales before
    line costs, shortages settled later);
  - from the books, account by account: **food losses** (waste, comps, count differences), **labour**, **other
    costs**, **other income**;
  - **rounding**: what per-portion rounding leaves (a few Rial), shown, never spread.
- Each step lists the dishes or accounts behind it (top five).
- A **summary in plain Persian** is built from these figures only: the change, the three biggest causes with the
  dish or account that weighs most, how much of the period carries actual costs, and whether open shortages make it
  provisional. No language model writes or estimates anything.
- `previous(from, to)`: the base defaults to the period of the same length just before.
- Screen «چرا سود تغییر کرد» under reports (summary, waterfall with drill-down); Excel/PDF export.

## Consequences

- Before line costs existed (ADR-0050) a period's dish costs are estimated from the dish's costed portions, or
  zero; the difference shows as untraced cost, and the summary says how much is costed.
