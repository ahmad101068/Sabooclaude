# ADR-0040: Global search

Status: accepted · 2026-10

## Context

Finding a supplier invoice meant knowing which screen and month it was in. A paper in hand («فخ-1405-00012», a
supplier's invoice number, a cheque) had no way back to its record.

## Decision

- `Search.find(query)` searches what the signed-in person may see, with the same permission and branch rules as the
  screens: customers (name, phone, tax ID), suppliers (name, phone), items (name, shelf), dishes, staff (name,
  national ID), fixed assets, supplier invoices (their number, or the exact total), cheques (number, Sayad ID,
  counterparty, exact amount), and document numbers — the full «فخ-1405-00012» or only the sequence.
- Text is compared after the import normalization (Persian/Arabic letters and digits, no spaces); numbers on paper
  are compared by letters and digits only («A-778» = «a 778»); amounts may be typed with separators.
- Exact name matches first; at most 40 hits. No index or table: the stores are queried directly (sizes measured in
  ADR-0027 keep this well under a second for years of data).
- One search row on the home screen; each hit opens its record.

## Consequences

- Document kinds without their own screen are shown (to those who may read the ledger) but do not open.
