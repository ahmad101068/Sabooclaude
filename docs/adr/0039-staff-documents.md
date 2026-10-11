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
- Expired or expiring within 30 days: listed per employee and as a home to-do; within 14 days or past: phone alert
  (`STAFF_DOCUMENT`) for those who manage personnel.

## Consequences

- ID photos are personal data: they never leave the phone except in the encrypted backup the owner controls.
