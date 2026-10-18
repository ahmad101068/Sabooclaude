# ADR-0061: The 360° scorecard and diagnosis, as a report from the books

Status: accepted
Date: 2026-10-18

## Context

The intelligence architecture (handoff, chapters 3, 8, 9 and 11) asks for a 20-dimension assessment with versioned
indicators, data quality shown apart from the score, «Unknown» instead of zero, critical findings before the score,
and a diagnosis that gives evidence, the money at stake, likely causes and a suggested action, never a verdict. The
owner wants this as a report, not a conversation, and on one restaurant, with no server and no paid AI service.

## Decision

`core.assessments.of(branch, from, to)` builds an `Assessment` from the existing read models only (profit and loss,
profit bridge, product mix and menu engineering, actual vs theoretical usage, cash forecast, credit customers, price
changes, stock, staff documents, tax readiness, the forecast back-test). Everything is exact, deterministic Kotlin;
nothing is written. Rules carry a version (`RULES_VERSION`), printed on every report.

- **Twenty dimensions in five areas.** Area weights 30/25/20/15/10 (finance; cost, menu and buying; operations;
  people, safety and customers; governance), shared equally by each area's dimensions — a stated suggestion, not a
  standard.
- **Indicators** (`Kpi`) carry their numerator, denominator, source, target and a quality: `OK`, `PARTIAL` (scored,
  with what is missing), `UNKNOWN` (no value, no score, a data request instead) or `NOT_APPLICABLE` (no credit
  customers, no employees: left out of the weight). A score is 100 at the target, 0 at the «bad» bound, straight in
  between; a dimension is the mean of its scored indicators.
- **The overall score** is the weighted mean over the dimensions that could be scored, with the coverage of the
  applicable weight; below 50 % coverage there is no score («داده‌ی کافی نیست»). Data quality (complete sales days,
  recipes, actual costs, counts, open shortages) is shown beside it, not mixed into it.
- **Findings** (`Finding`): severity (critical / high / medium / low), evidence, exposure in Rial (exact when the
  books give it, a bound otherwise, none when it cannot be priced), likely causes from the figures, a suggested
  action and a confidence. Critical findings (books out of balance, health cards missing or expired) come first,
  whatever the score. Diagnoses: profit drop (causes from the profit bridge, to the Rial), food cost over target,
  unexplained usage found by counts (causes listed as possibilities — never theft), cash shortfall, late receivables,
  customer concentration, menu decisions per menu-engineering class (keep, reprice/reformulate, promote, retire),
  supplier price rises, expired stock, late work, figures not yet final, open shortages.
- **Data requests** name what the app does not record and would measure a dimension: marketing sources (D07),
  customer feedback (D08), service times (D12), temperature logs (D14), equipment repairs (D15), and every Unknown
  indicator's missing data.
- One screen (Reports → «کارنامه و عارضه‌یابی») and the usual Excel / PDF export: card, indicators, findings and
  actions, data requests.

## Consequences

- The reasoning engines of the architecture (E2 KPIs, E3 rules, E4 diagnosis) exist without a server or a model; a
  later conversational layer, if ever wanted, would only phrase this report.
- Targets are constants of rules version 1 (food cost uses the owner's target when set); making them editable is a
  later change that bumps the rules version.
- Dimensions without data in the app stay Unknown until the app records that data; they lower coverage, never the
  score.
