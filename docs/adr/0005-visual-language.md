# ADR-0005 — Visual language

**Status:** Approved by the product owner (2026-10-08)

## Decision
The app follows the approved design canvas. `docs/design/tokens.json` holds the exact values. The Compose theme in `app` is generated from those tokens; screens never hard-code colours.

- **Identity.** The deep green brand is kept. The gradient header is removed. Saffron is the accent and marks status and highlights only.
- **Type.** Vazirmatn. Digits are Persian with thousands separators.
- **Money direction.** Blue for money in, orange for money out, so it stays readable for colour-blind users.
- **Home.** "What needs action today" (close the day, low stock, invoices due) replaces a generic dashboard.
- **Operations.** Grouped by module; every card shows live status. The old "باز کردن" button list is gone.
- **Data entry.** Flows are stepped (items → settlement → confirm), with a persistent balance bar. Posting is impossible until the balance is zero, enforced by the domain and not only by the UI.
- **Ownership is visible.** Treasury rows carry their owning module, and reversal is offered only in that module (ADR-0002).
- **Accessibility.** Touch targets are at least 44 dp and text contrast is at least 4.5:1.
