# ADR-0018 — Rial is the one money unit

**Status:** Accepted (2026-10-10). Supersedes the "shown in Toman" rule of ADR-0007 and ADR-0011.

## Context
Amounts were stored in Rial but typed and shown in Toman (÷10), and exported to Excel ÷10. Cheques were printed in Rial, bank statements, POS exports (Sepand) and tax rules are in Rial. Two units in one product meant every comparison with an outside document needed a mental ×10, and an odd Rial could not be typed at all.

## Decision
1. Rial everywhere: storage (unchanged), entry (`MoneyInput`, `Fa.parseRial`), display (`Fa.rial`, `Fa.rialShort`), reports, messages, prints and Excel export (plain integer Rial).
2. No conversion code exists: `Fa.toman*`, `parseToman` and the ÷10 in export are removed.
3. An architecture test (`MoneyUnitRuleTest`) scans production sources for a Toman label or identifier, `rial / 10`, `rial * 10` or a decimal shift of an amount, so it cannot return unnoticed.

## Consequences
- No data migration: stored values were already Rial; only presentation and entry change.
- Users type ten times larger numbers; amounts group with «٬» while typing, and cards use the compact form (م / ب).
- Excel files exported before this change are in Toman; files exported after it are in Rial (the column titles say «ریال»).
