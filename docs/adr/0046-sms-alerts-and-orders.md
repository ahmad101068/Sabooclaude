# ADR-0046: SMS alerts and orders to suppliers

Status: accepted (2026-10)

## Context

ADR-0024 put alerts on the phone and left for later "SMS through an Iranian SMS gateway … and automatic purchase
requests to suppliers". The owner asked for both. Many restaurant suppliers take orders by SMS or phone, and the
owner is not always holding the restaurant's phone.

## Decision

- **Two ways to send**, chosen by the owner in «من › امنیت و داده‌ها › پیامک»:
  - the phone's own **SIM card** (`SmsManager`, `SEND_SMS` permission asked only when this is chosen) — no internet
    and no account, charged to that line;
  - an **SMS service**: Kavenegar (`POST https://api.kavenegar.com/v1/{key}/sms/send.json`, form `receptor`,
    `message`, `sender`; accepted when `return.status` is 200) or SMS.ir (`POST https://api.sms.ir/v1/send/bulk`,
    header `X-API-KEY`, JSON `lineNumber`, `messageText`, `mobiles`; accepted when `status` is 1). The API key is
    sealed with the phone's Keystore and never enters a backup.
- **Alerts by SMS**: the owner adds recipients — a user, a mobile number and which kinds of alert. Each recipient's
  alerts are computed with *that user's* permissions (the same `core.alerts.forUser` as the phone notifications), so
  SMS shows nobody more than the app would. Same once-per-situation rule (ADR-0024), kept per recipient; several new
  alerts go in one message, cut to five parts. Optional quiet night (23:00–07:00): nothing is sent and nothing is
  marked as sent, so the morning's check sends what is still true. A message that fails stays unsent and is retried
  at the next check.
- **Orders by SMS**: a supplier can be marked «سفارش‌ها خودکار پیامک شود» (needs a mobile number, checked by the
  command). When an order is placed, the order's text — branch, number, delivery day, items and quantities, note —
  is sent in the background. The order page shows whether it went and can send it (again) by hand. Prices are not in
  the SMS; the PDF stays the way to send the full order.
- **Only the main phone sends** (ADR-0042): the second phone's data is a copy, and two senders would duplicate.
- A send log (last 200) on the settings page; texts and the HTTP requests/replies are in `core` (`Sms.kt`) and
  tested; the Android part only moves bytes.
- Settings are forgotten when the database is replaced (restore, factory reset): the recipients are that database's
  users.

## Consequences

- SMS costs money per part; Persian text is 70 characters per single message, 67 per part of a long one.
- The app now uses the internet when the owner chooses an SMS service; the books themselves never leave the phone.
- Delivery reports are not tracked: "sent" means the SIM or the service accepted the message.
