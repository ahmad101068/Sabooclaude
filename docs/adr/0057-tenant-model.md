# ADR-0057: Tenant model — one core per tenant, schema per tenant

Status: accepted (2026-10) · gate G1/G2 of ADR-0056

## Context

A tenant is one customer of the service: a restaurant company with its own legal entity, branches, users and
books. Today one `SabouCore` serves one organization over one database; `Scope` is the organization or a branch
inside it. The handoff proposes a shared database with `tenant_id` on every row and PostgreSQL row-level security.
Applied here, that means a `tenant_id` in every domain model, command, store and query — the largest change the
code base could undergo, touching every rule that is already proven — and it still relies on every query being
written correctly, with RLS as the net. Its own draft also puts tenant memberships under the tenant policy, which
makes them unreadable at sign-in, before the tenant is known.

## Decision

- **One core per tenant.** A `SabouCore` is bound to exactly one tenant for its whole life; the domain stays
  tenant-agnostic, as it is now. There is no API in the core that names another tenant, so a domain bug cannot
  cross tenants: the boundary is below the domain, not in every query.
- **PostgreSQL: one schema per tenant** (`t_<id>`), the same tables and migrations as today. A tenant's core uses a
  connection whose role owns only its schema and whose `search_path` is that schema. A query of the core cannot
  name another tenant's tables; if it tried (`t_other.journal_entries`) the database refuses it for lack of
  privilege. RLS is not needed for isolation (it can still be added for platform tables).
- **Platform store, separate from tenants** (schema `platform`): tenants, users (OIDC subject), memberships (user ↔
  tenant ↔ role ↔ branches), devices, subscriptions. Only the server's identity layer reads it, with its own role;
  tenant cores have no privilege on it. Sign-in reads memberships here, then opens the tenant's core.
- **The server resolves the tenant from the authenticated membership**, never from a client-supplied id
  (handoff §4). The role and branch grants of the membership become the core's `Actor`, so the existing permission
  and scope checks apply unchanged.
- **Migrations run per tenant schema**, in the same order as on phones (`Schema.kt`); a tenant is created by creating
  its schema and role and running them. An enterprise tenant can get its own database with the same code.
- **The phone stays a single-tenant core** over SQLCipher, as now (standalone mode).

## Consequences

- Isolation is enforced by the database's privilege system and tested negatively (G2): tenant A's connection reads
  and writes nothing of tenant B, even with B's ids and fully qualified table names.
- Cross-tenant analytics (benchmarks between restaurants, ADR-0056 G6) cannot be written as one query; they run as
  a separate, consented pipeline over published aggregates, which is what the handoff requires anyway.
- Practical limit: thousands of schemas per database are fine for PostgreSQL; beyond that tenants are spread over
  several databases by the platform store. Connection pooling must set role and `search_path` per transaction.
