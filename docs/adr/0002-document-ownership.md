# ADR-0002 — Document ownership and control accounts

**Status:** Accepted (2026-10-08)

## Context
In the previous code base, the generic treasury screen could reverse or create purchase, sales, receivable and payroll settlements without touching their documents (AUD-002, AUD-003). Manual journals could post to cash, AP, AR and inventory (AUD-010). The general ledger and the sub-ledgers drifted apart.

## Decision
- Each `ModuleId` owns its documents. Only the owner posts or reverses them.
- Control accounts (cash, bank, AR, AP, inventory, payroll payable…) list the modules allowed to post to them. Manual journals can only use open accounts.
- A module proves its identity with an unforgeable `PostingCapability`, issued once per module by `LedgerAccessRegistry` at the composition root.
- Money moves only through `TreasuryGateway`. The caller (for example purchasing) supplies its own journal lines; treasury adds the cash line, checks funds, and records the movement under the caller's document.
- Reversing a journal requires the capabilities of every module that contributed lines.

## Consequences
"Fix it with a manual journal" is impossible for control accounts by design. Corrections go through the owning module.
