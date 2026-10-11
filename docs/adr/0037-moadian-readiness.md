# ADR-0037: Tax system (Moadian) readiness

Status: accepted · 2026-10

## Context

Iran's tax system (سامانه مودیان) requires electronic invoices with a unique tax ID, the seller's identity, a product
or service ID per line, VAT, and — for invoices with buyer details — the buyer's national ID or economic code.
The restaurant's point of sale (Sepand) already issues the per-bill consumer invoices. Sabou records daily totals
(entered or imported), which cannot be turned into per-bill invoices. What Sabou will need to issue later are
invoices with buyer details (company customers buying on credit), and every report must be consistent with them.

## Decision

This package makes the **data** ready; nothing is sent.

- **Seller profile**: national ID (10 digits), legal-entity ID (11) or economic code (14), checked with the official
  check-digit rules; the tax memory ID (6 letters/digits); **VAT rates by effective date** (the rate is set by each
  year's budget law and changes), so any date's rate is known. Owner-only permission `TAX_SETTINGS`.
- **Product/service ID** (13 digits) and unit code per menu item (`tax_codes`, schema 14).
- **Customer tax ID and postal code**; a person needs a valid national ID (or a 14-digit economic code).
- **Readiness**: what is missing (profile, memory ID, today's VAT rate, dishes without ID, company customers without
  ID); shown on its own screen and as a month-close warning once a profile exists.
- **Tax ID generator** (`TaxIds.taxId`): memory ID + days since 1970 (5 hex) + serial (10 hex) + Verhoeff check
  digit over the decimal form, as published by client libraries. It is isolated and unit-tested but **must be
  verified against the official specification and a test submission before any invoice is sent**.

## Not done (needs a decision and credentials)

- Issuing and sending invoices: requires the taxpayer's private key / certificate and either direct API access
  or an intermediary («شرکت معتمد»). Credit sales would also need per-bill lines (which dishes for which customer).
- The current VAT rate is not hard-coded: the owner enters it from the law in force.
