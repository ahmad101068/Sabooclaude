# ADR-0047: Electronic invoices and sending them to the tax system

Status: accepted (2026-10) · builds on ADR-0037

## Context

ADR-0037 prepared the data (seller, VAT by date, product IDs, customer tax IDs) and left sending for later. The
owner asked to finish it. The point of sale (Sepand) issues the consumer bills; what Sabou must issue is the
invoice **with the buyer's details** (type 1) for sales on account to companies and people with a tax ID.

The official specification PDFs could not be fetched from here. The protocol below is taken from two independent,
maintained client libraries (Jooyeshgar's PHP package for API v2, updated 2026-06, and Arjavand's Python package),
which agree on it, and the tax ID algorithm matches both (unit-tested against values computed with them).

## Decision

- **Issuing** (`IssueTaxInvoice`, owner permission `TAX_SETTINGS`): from a *posted* sale whose credit part belongs
  to one customer with a tax ID. The invoice is fixed at issue: serial (sequential), tax ID (memory ID + days since
  1970 in hex + serial in hex + Verhoeff), lines with the 13-digit product ID and unit code, the sale's discount
  spread over the lines by value (exact, largest remainder), VAT at the legal rate of the sale's date. The VAT on the
  sale must equal the lines' VAT (a rial per line of rounding), otherwise it is refused — the books and the tax
  system must agree. Sales with a separate service charge are refused (the service should be a menu item with its
  own service ID). Settlement method, cash and credit amounts come from the sale's settlements.
- **Cancelling** (`CancelTaxInvoice`): of an accepted invoice, as a new invoice (`ins` 3, `irtaxid` = original) with
  a new serial; once accepted the original is `CANCELLED` and the sale can be issued again. A refused invoice can be
  issued again with a new serial.
- **Sending** (`core.moadian.send/inquire` with `MoadianClient`), API `https://tp.tax.gov.ir/requestsmanager/api/v2/`:
  `GET nonce` → token = JWS RS256 over `{nonce, clientId}` with header `x5c` (certificate), `sigT` (UTC),
  `crit: [sigT]`, `cty: text/plain`; `GET server-information` → the server's public key and key id; `POST invoice`
  with `[{payload: JWE(JWS(invoice)), header: {requestTraceId, fiscalId}}]`, JWE = RSA-OAEP-256 + A256GCM; then
  `GET inquiry-by-reference-id` → `SUCCESS` / `FAILED` (+ errors) / pending. Each answer is recorded by a command
  (`RecordTaxInvoiceResult`); no network call happens inside a transaction. A test drives the whole exchange against
  a fake server that verifies the token and signature and decrypts the invoice.
- **Credentials**: the taxpayer's private key (PKCS#8 or PKCS#1 PEM) and certificate, picked as files, sealed with
  the phone's Keystore, never in a backup, forgotten when the database is replaced. Off by default; only the main
  phone sends. A connection test calls `fiscal-information`.

## Not verified / open

- Not yet run against the real tax system: the first invoice must be checked in the taxpayer's کارپوشه. Fields the
  libraries leave open and we chose: `inno` = serial as 10 hex digits; `bid` = the buyer's national ID (left empty
  for a 14-digit economic code); `tob` 1 person (10 digits) / 2 legal entity (11 digits); issue time = when issued.
- Consumer (type 2) invoices are not issued here (the point of sale does that).
- Payments (`payments`) are not itemised; `setm`, `cap` and `insp` carry the split.
