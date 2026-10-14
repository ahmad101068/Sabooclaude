# ADR-0049: Cheque print calibration and published payroll values

Status: accepted (2026-10)

## Context

Two items before production need people, not code: the cheque print positions on the bank's real leaves, and the
yearly payroll values (and depreciation classes) confirmed by an advisor. The app can make both quicker and safer.

## Decision

- **Cheque print calibration** («تنظیم محل چاپ چک», from a cheque): positions per bank name, kept on the phone;
  a test print on plain paper draws the leaf's outline, a millimetre ruler and each field's name and position;
  a whole-print shift (printer margin) in 1 mm steps; leaf size, line width and font size. Printing a cheque uses
  its bank's positions, otherwise the default.
- **Published payroll values** (`PayrollPresets`): for 1405 a button fills the legal-parameters form — minimum daily
  wage 5,541,850 rial, insurable ceiling 7 × the minimum wage, insurance 7 / 20 / 3 %, 2/7 of the employee's
  insurance exempt, salary tax exempt to 400,000,000 rial a month then 10 / 15 / 20 / 25 / 30 % at 800 m, 1,000 m,
  1,200 m, 1,400 m. The sources are shown under the button. Nothing is saved until the owner presses «ثبت».
- **Depreciation classes**: unchanged. The official table (ضوابط استهلاک، ماده ۱۴۹، ۱۳۹۵) could not be read from a
  reliable copy here; the existing presets stay labelled «پیشنهاد اولیه … با مشاور تطبیق دهید».

## Consequences

- The values were read from secondary sources (official pages were not reachable from here); the advisor's
  confirmation remains a step before production, now a check rather than data entry.
