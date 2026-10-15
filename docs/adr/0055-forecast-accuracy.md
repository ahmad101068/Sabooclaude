# ADR-0055: How good the sales forecast is

Status: accepted (2026-10) · builds on ADR-0041

## Context

The forecast showed one accuracy figure, the mean absolute percentage error of a 28-day back-test. It weighs a
quiet day's miss like a busy day's, and it cannot say whether the method tends to expect too much or too little —
which is what decides whether to trust it for buying and staffing. Any future method (a model with more inputs)
must beat the current one on the same test before it replaces it.

## Decision

- The back-test also gives **WAPE** (total absolute error over total actual sales) and **bias**
  (Σ(forecast − actual) over Σ actual, signed). `SalesForecast.wapeBp`, `SalesForecast.biasBp`; null without
  7 scored days, like the existing error.
- The forecast screen shows both («خطا به نسبت کل فروش», «سوگیری: بیشتر/کمتر از واقع»).
- The range stays what it is, the weighted spread of the same weekdays, and is not called a confidence interval.

## Consequences

- A replacement method is judged on the same three figures over the same days.
