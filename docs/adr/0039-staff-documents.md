# ADR-0039: Staff documents and their expiry

Status: accepted · 2026-10

## Context

A food worker must hold a valid health card (کارت بهداشت), and an inspector asks for it; contracts and IDs are
kept in a folder. Nothing in the app held them, and an expired card was found at inspection.

## Decision

- `staff_documents` (schema 16, immutable): employee, kind (health card, contract, ID, insurance record, guarantee,
  other), title, expiry, note; files are ordinary attachments (photos/PDF, size and type limits, inside the
  encrypted database and its encrypted backups).
- A renewal is a new document; the latest of each kind is the one in force, the old ones stay as history.
- A health card needs an expiry date. Health card and contract are **required for every employee**: missing ones
  are listed.
- **Corrections and removals** (2026-10): a document entered wrongly is corrected (kind, title, expiry, note; files
  are added) by a new record that replaces it, keeping its place in the renewal order; one entered by mistake is
  removed by a void record with a reason. Rows stay immutable: every version remains, and the audit names what
  changed. Only the latest version can be changed; corrected and removed versions leave the list.
- What needs attention is named per document («کارت بهداشت: ثبت نشده», «قرارداد: ۵ روز مانده»), on the staff list and
  on the person's documents page with its fix, not counted.
- Expired or expiring within 30 days: listed per employee and as a home to-do; within 14 days or past: phone alert
  (`STAFF_DOCUMENT`) for those who manage personnel.

## Consequences

- ID photos are personal data: they never leave the phone except in the encrypted backup the owner controls.
