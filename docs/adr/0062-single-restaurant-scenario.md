# ADR-0062: The single-restaurant scenario, phases 1–7

Status: accepted
Date: 2026-10-19

## Context

The owner chose one restaurant, no server, no paid AI service, and reports rather than conversation. The scenario
written for that ("سناریوی هوشمندسازی سبو — نسخه‌ی تک‌رستورانی") has seven phases, each with an acceptance test.
This ADR records what each phase built; `ScenarioPhasesTest` holds the acceptance criteria.

## Decision

1. **Targets and weights are the owner's.** `SetAssessmentSettings` (BUDGET_MANAGE) stores a target per indicator and
   a weight per area in `meta`; whatever is not set keeps the rule's default. Each change raises a settings revision,
   printed on the report beside the rules version (now 2). A target must keep its indicator's direction
   (`Assessments.DIRECTIONS`).
2. **Alerts.** The scorecard's critical and high findings over the last four weeks become phone alerts
   (`Alert.Kind.FINDING`, key `FINDING:<branch>:<finding>`), except those already announced by their own alert
   (health cards, cash, receivables, expiry, shortages). The card is worked out at most every 30 minutes per user,
   branch and day. On Saturdays, last week's card in one line (`WEEKLY_REPORT`), only for a week with sales.
3. **Actions.** A finding can become an action (owner, due date, cost) or be marked a false alarm (reason required;
   the finding is quiet for 30 days). Closing an action measures it: the finding's indicator from the day it was
   opened to the day it was closed against as many days (at least seven) before — stored, never re-estimated.
   Open actions past their due date are indicator D17.ACTIONS and a finding.
4. **Field records** for what the books do not hold, each with its own permission: fridge and freezer
   temperatures (INVENTORY_COUNT), customer feedback 1–5 (SALES_RECORD), campaigns (MENU_PRICE_MANAGE), breakdowns
   and portion weights (INVENTORY_COUNT), permits (BUDGET_MANAGE). A wrong record is voided with its reason, never
   deleted. They feed D07, D08, D11, D14, D15 and D16; an unsafe latest temperature and an expired permit are
   critical.
5. **What if on prices.** `WhatIfs.prices`: ingredients and menu prices changed by basis points, replayed on the
   period's portions (demand assumed unchanged), dish costs broken down to raw ingredients through prep recipes.
   Exact Rial per dish, the price that keeps today's food-cost share, nothing recorded.
6. **Backups.** The nightly backup (ADR-0025) now opens the file it wrote once a week as a restore would and verifies
   its audit chain (`AppContainer.testRestore`), recording the result (`BACKUP_VERIFIED`). Backup age and the last
   successful test are indicators (D18.BACKUP, D19.RESTORE); no backup for a week with sales is high, a failed test
   critical.
7. **Derived indicators.** Orders received on time and complete (D09), sales against the same weeks a year earlier
   (D20.YOY, 364 days so weekdays match).

Records and actions live in two new tables (migration 27, replicated to the second phone like every table).

## Consequences

- Five dimensions that were Unknown can now be measured, by data entered in under a minute.
- Every write here is a command through the bus: permissions, idempotency and the audit chain apply; none touches
  the books.

## Hardening (after the first release)

- **Speed.** On 90 days of a busy restaurant (`VolumeBenchmarkTest`, run in CI) the card took 16 s: the late-bills
  count read the whole invoice list once per invoice. Now one read; actual-vs-theoretical usage is read only when the
  period has an approved count, the profit bridge only when the books show a drop, and the audit trail has an index
  on its action for the backup history (migration 28). The card takes about 0.45 s; its budget in CI is 1.5 s.
- **Actions** are measured only on days that happened: from opening to closing, against as many days before.
- **Settings** of an indicator or area a later rules version drops are ignored, never a broken report; targets and
  weights are shown for editing only to who may change organization data.
- **Channel costs (D05).** The owner sets what each sales channel costs (commission, courier) as a share of its
  sales; the card estimates the total and flags a channel costing a fifth or more of its sales.
- **Saturday PDF.** The nightly backup writes last week's card as a PDF beside its files, as the person who switched
  the backup on would see it, when the week had sales.
