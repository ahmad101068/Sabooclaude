# ADR-0066: Remediation of the independent audit (1405/08/06)

Status: accepted (2026-10)

## Context

An independent second audit of main @ 1abb2aa returned NO-GO with thirteen findings (SABOU-AUDIT-001 to 013), each
reproduced by a probe. The probes now live in the test suites as regressions (`CommandTrustTest`,
`IndependentAuditFixesTest`, `ClockPunchTimeTest`, `CountShortageOrderTest`, `TaxInvoiceAfterReversalTest`,
`InvoiceCorrectionTest`, `XlsxIndexTest`, `SyncHubResilienceTest`, `CommandIntegrityTest`).

## Decisions

- **001: commands from the second phone.** Commands travel as serialized objects, so stored fields are the sender's
  choice. `requiredPermission`, `additionalPermissions`, `sharedCatalog` and command scope (where derived) are now
  getters on the main phone's class; no command type stores them (checked over every routed type). An attachment's
  SHA-256 is computed from its bytes, not carried.
- **002: correcting a purchase invoice after its goods were used.** New `CorrectPurchaseInvoice` (document series «اف»,
  permission PURCHASE_REVERSE). It sets the true value per item and per expense line:
  - the change is split between stock still on hand (pro rata to what remains of that purchase) and cost of goods
    sold;
  - payables change by the total;
  - the invoice keeps its corrections, so `total` is the current amount and `originalTotal` is derived;
  - a correction cannot go below what was already paid;
  - returns are priced at the corrected value, reversing an invoice undoes its corrections first, and supplier
    statements show the corrections as their own rows.
  Stock value moves through a new zero-quantity `REVALUATION` movement posted by the inventory capability.
- **003: date rule coverage.** `Ledger.reverse` checks the date policy too, and stock-only documents call the new
  `Ledger.requireDate`.
- **004: closed cash days.** Treasury refuses any date on or before a closed sales day of the branch. Funds are
  checked as the lowest balance from the payment date onwards, so a back-dated payment can't push a past day negative.
- **005: tax invoice and reversal.** A sale with a live tax invoice (READY, SENT, ACCEPTED) can't be reversed. A
  never-sent invoice is withdrawn (`WithdrawTaxInvoice`, status WITHDRAWN); an accepted one is cancelled first. Sending
  skips invoices whose sale is no longer posted.
- **006: clock punches.** The time recorded is the main phone's own clock in Iran time. A device clock more than ten
  minutes away is refused.
- **007: sync hub resilience.** At most four connections per address and six in the handshake. Frames before
  authentication are capped at 64 KB, with a three-second timeout. Frames are read in 1 MB steps, so a declared size
  allocates nothing up front.
- **008: release signing.** A release key was generated outside the repository and given to the owner. CI signs the
  release APK once the four `SABOU_*` secrets are registered. The key and its password are never committed.
- **009: counts and later shortages.** A count writes off only the shortages open when it was submitted
  (`shortagesAtCount`).
- **010: xlsx indexes.** Row and column indexes outside the limits make the file invalid instead of allocating.
- **011: money screens.** Pay supplier, advances, salary, remittances and advance repayment keep one command id per
  attempt, so a double tap can't pay twice.
- **012: exact sums.** Money sums use `sumOfExact`, which fails on overflow instead of wrapping.
- **013: write-path speed.** The volume benchmark also times recording a purchase invoice, paying a supplier, and the
  management dashboard.

## A second, static review of the same source (1405/08/06)

A static review of the same archive (no build or device run) raised seven risks, mostly in two-phone sync. Each was
checked against the code. All seven held, and each now has a fix:

- **001: a copy whose pairing file is lost.** The database itself marks a second phone's copy (its applied position
  in the sync state, which a main phone never has). A copy without a matching pairing is locked: every command is
  refused with DEVICE:PAIRING_LOST, while its contents can still be read. The owner pairs it again or detaches it. A
  paired copy with no key fails the same way instead of silently not syncing.
- **002: success before the copy caught up.** The command stands on the main phone, which keeps the books. The second
  phone now says it is behind until the next applied batch, instead of looking up to date (or failing a recorded
  command).
- **003: position moved before a copy was sent.** Sending a full copy no longer moves the phone's recorded position;
  only its first pull after installing the copy records it.
- **004: unchecked position from a paired phone.** A pull from outside 0..head is refused and never recorded, so it
  cannot drop log entries.
- **005: deserialization limits.** Where the platform has the serialization filter (the JVM; newer Android), each
  message is limited in depth, references, array bytes and total bytes. It is applied by reflection, so older
  Android keeps the frame cap and the paired-key requirement only.
- **006: dashboard errors shown as "no sales".** Only a permission refusal leaves the dish-level sections out, and
  the screen says so. Any other failure fails the dashboard.
- **007: copy download size.** The announced size is checked against a 4 GB ceiling and the free space before writing.
  The stream may not run past the announced size, and a failed download leaves no partial file.

## Remaining

- On Android versions without the serialization filter, a paired phone holding the key could still send a message
  that asks for a very large array (finding 005).
- The device id in the first sync frame is still sent in clear. Exposure is limited by the pre-authentication caps.
- `customers.all()` and the cheque holder lookup still scan, on rare paths.
