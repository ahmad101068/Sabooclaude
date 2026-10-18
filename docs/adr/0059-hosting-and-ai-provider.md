# ADR-0059: Hosting and AI provider — open decision

Status: proposed (2026-10) · needs the owner's decision before gate G4 (hosting) and G7 (AI)

## Context

Where the server runs decides which AI models can legally and reliably be used, and how restaurants reach the
service during internet disruptions. OpenAI's API does not list Iran among supported countries; using a service
outside its terms risks account closure. The handoff leaves both open ("provider abstraction").

## Options

| | Hosting in Iran (local cloud/datacenter) | Hosting abroad |
|---|---|---|
| Reach from restaurants during international disruptions | stays reachable inside the country | may be cut |
| Payment, contracts, data residency | local rules, rial billing | foreign payment and sanctions issues |
| AI models | self-hosted open-weight model on own GPU/CPU, or a local provider | commercial APIs where legally available for the operator |
| Cost | GPU capacity for the model | per-token fees |

## Proposed default (until the owner decides)

- Build every intelligence feature deterministic first (ADR-0053/0051/0055 already are); the AI layer only
  explains their results, so the product is complete without it.
- The AI gateway talks to models through one interface (`AdvisorModel`) with structured, schema-validated output
  and read-only tools; the provider is configuration.
- Prefer hosting in Iran for the transactional server; choose the model provider once hosting is decided.

## Decision needed from the owner

1. Hosting: Iran or abroad (or the pilot on a local server first).
2. AI: self-hosted open model, a local provider, or none until later.
