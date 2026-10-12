# ADR-0041: Sales forecast and special days

Status: accepted · 2026-10

## Context

Prep (ADR-0033) used a 4-week same-weekday average of portions; the labour view (ADR-0038) priced that. Neither
knew that a day would be closed or a holiday, and nobody could see how good the guess was.

## Decision

- **Forecast per day** (net sales before service and tax): the weighted mean of the same weekday over the last 8
  weeks, last week weighing 8 and the eighth 1, over days that had posted sales (a closed day is not a zero). The
  weighted mean already follows a recent change of level, so the 4-week trend is **shown, not multiplied in**.
- **Range**: the weighted mean absolute deviation of those same weekdays.
- **Special days** (`special_days`, schema 17): closed (0), holiday or event with a percent of a normal day, and a note.
  They scale the sales forecast, the prep forecast per dish (closed → nothing to prep) and the labour view.
- **Accuracy**: the method is back-tested over the last 28 days (each day forecast from what came before) and its
  mean absolute percentage error is shown, so the owner knows how far to trust it.
- The labour view's expected sales now come from this forecast.

## Consequences

- With fewer than a few weeks of history the forecast says so (no samples) instead of inventing a number.
- Holidays are not built in (the calendar of official holidays changes); the owner marks them.
