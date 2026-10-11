# ADR-0026 — Importing point-of-sale exports (Sepand first)

**Status:** Accepted (2026-10-11). Parser: this change. Import into sales: next change.

## Context
Sales are rung up in Sepand (a Windows POS). Its reports go to Excel; the exact layout is not known yet (no real file has been seen). Typing a day of bills again is slow and error-prone.

## Decision
1. **A layout-free reader** in its own pure-JVM module (`modules/posimport`), with no spreadsheet library: `.xlsx` (zip + XML, first sheet, shared and inline strings; DTDs and external entities refused; size and zip-bomb limits) and `.csv` (UTF-8 or Windows-1256, `,` `;` or tab). The old binary `.xls` is refused with a clear «save as .xlsx» instruction.
2. **One normalization** (`Text`): Persian/Arabic digits, `٬` and `,` separators, `٫` decimals, `-` as zero, `(x)` and `x-` as negatives, Arabic ي/ك as Persian, zero-width marks.
3. **Header by vocabulary.** The header row is the one naming the most known columns (synonyms per report); titles, group headers, subtotals, repeated headers after page breaks and footers are recognised and skipped. Two report kinds: *sales by item* (name, quantity, amounts) and *invoices* (number, payable, cash/card/credit, status — void left out, returns negative).
4. **The file is checked against itself.** Every subtotal and total the file prints must equal the sum of its lines; each line must satisfy quantity × price = amount and net = amount − discount + tax; each invoice payable = total − discount + service + tax and cash + card + credit = payable. Any mismatch is a problem that stops the import.
5. **Nothing is guessed.** The money unit comes from the file («ریال»/«تومان» in a header or title) or the person chooses it; Toman is converted ×10 only here (the Rial-only rule exempts this module). The day comes from the file or the person. A report spanning several days is refused.
