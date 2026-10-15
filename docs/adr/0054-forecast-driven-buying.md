# ADR-0054: Buying from the sales forecast

Status: accepted (2026-10) · builds on ADR-0012, ADR-0032, ADR-0033, ADR-0041

## Context

Suggested purchases (ADR-0012) were par level + dishes the owner typed in − stock − open orders. The kitchen
already forecasts portions per dish (ADR-0033) and suppliers have delivery days (ADR-0032), but nothing joined
them: the owner had to guess the dishes, and the minimum-stock alert fires only once stock is already low, not
when what is expected to sell will finish it before the next delivery.

## Decision

- **`Buying.forecastSuggestions(location, date, minutes)`**: per item, its supplier's next delivery D1 (the one the
  order can still make) and the delivery after it D2 (without delivery days: tomorrow and a week later). From the
  dish forecast of each day, broken down through recipes and prepared items:
  suggested = use before D1 + max(par at D1, use from D1 to D2) − on hand − on order.
  The stock left when the goods arrive is then at least the par, and covers the days until the next delivery.
  The manual plan (`suggestions`) is unchanged; the screen offers both, forecast first.
- **`Buying.runOuts(branch, date, minutes)`**: day by day, the first day the cumulative expected use passes what the
  branch has and has on order, for items short before their next delivery (7 days without one).
- **Alert `RUN_OUT`** for purchasing roles: «… تا … تمام می‌شود» with the need, stock, what is on order and the
  supplier. Shown on the suggestions screen too.
- `Prep.forecastOf`: the dish forecast without its permission check, for callers that check their own.

## Consequences

- Without sales history the forecast is empty and suggestions fall back to par levels, as before.
- The forecast is the same weekday average as the prep plan, with special days; it is as good as that, and no more.
