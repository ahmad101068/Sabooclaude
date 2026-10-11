# ADR-0024 — Alerts on the phone

**Status:** Accepted (2026-10-11). Field request: price differences and low stock as phone notifications; SMS and automatic purchase requests later.

## Decision
1. **One alert model in the core.** `core.alerts.forUser(user, today)` lists what that person should act on — low stock, open consumption shortages, purchase price moves of 10 % or more in the last two days (against the last price from any supplier), cheques due within two days or overdue, invoices waiting for their approval — through the ordinary read models, so the person's permissions and branches apply.
2. **No sign-in needed, nothing can be done.** The background check runs as a read-only stand-in for that user on its own thread (`Session.readAs`); the signed-in session is untouched and the command bus refuses any command while the stand-in is active.
3. **Announced once.** Each alert has a key naming the situation (item and location, invoice and item, cheque and due/overdue); the phone remembers keys already announced and forgets keys whose situation is resolved, so a recurrence is announced again.
4. **When.** A periodic system job (hourly, survives reboot) and a check a few seconds after anything is saved. The phone's user is whoever signed in last; restore and reset forget it.
5. **Privacy.** One channel per kind; on the lock screen only «سابو · هشدار تازه» shows. The person can switch alerts off (Me → امنیت و اعلان‌ها).

## Later
SMS through an Iranian SMS gateway (needs an account and a server or the gateway's API key on the phone) and automatic purchase requests to suppliers.
