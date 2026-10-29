"""build_guide.py [out.pdf]: the user guide (docs/guide) as one HTML page and an A4 PDF.

The guide is written as HTML fragments in docs/guide/parts, in reading order. This script joins them, builds the
table of contents from the part titles (h1.part) and chapter titles (h2), and prints the result with Chromium
(Playwright). It needs the Vazirmatn font installed on the machine (https://github.com/rastikerdar/vazirmatn).
"""
import html
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GUIDE = ROOT / "docs" / "guide"


def build_html() -> str:
    parts = sorted((GUIDE / "parts").glob("*.html"))
    body = "\n".join(p.read_text(encoding="utf-8") for p in parts)
    toc, n = [], 0

    def anchor(m):
        nonlocal n
        n += 1
        tag, attrs, text = m.group(1), m.group(2), m.group(3)
        # The «تازه» badge marks the chapter in the text, not in the table of contents.
        plain = re.sub(r"<[^>]+>", "", re.sub(r'<span class="new"[^>]*>.*?</span>', "", text)).strip()
        if tag == "h1":
            toc.append(("part", plain, n))
        else:
            toc.append(("chapter", plain, n))
        return f'<{tag}{attrs} id="s{n}">{text}</{tag}>'

    body = re.sub(r'<(h1)( class="part"[^>]*)>(.*?)</h1>|<(h2)([^>]*)>(.*?)</h2>',
                  lambda m: anchor(_norm(m)), body, flags=re.S)
    rows, open_cols = [], False
    for kind, text, i in toc:
        if kind == "part":
            if open_cols:
                rows.append("</div>")
            rows.append(f'<div class="tp">{html.escape(text)}</div><div class="cols">')
            open_cols = True
        else:
            rows.append(f'<div class="tc"><span class="t">{html.escape(text)}</span></div>')
    if open_cols:
        rows.append("</div>")
    toc_html = '<section class="toc"><h1>فهرست</h1>' + "".join(rows) + "</section>"
    body = body.replace("<!--TOC-->", toc_html, 1)
    css = (GUIDE / "guide.css").read_text(encoding="utf-8")
    return ('<!doctype html><html lang="fa" dir="rtl"><head><meta charset="utf-8">'
            '<meta name="viewport" content="width=device-width, initial-scale=1">'
            f"<title>راهنمای کامل سابو</title><style>{css}</style></head><body>{body}</body></html>")


class _M:
    def __init__(self, tag, attrs, text):
        self._g = (None, tag, attrs, text)

    def group(self, i):
        return self._g[i]


def _norm(m):
    if m.group(1):
        return _M(m.group(1), m.group(2), m.group(3))
    return _M(m.group(4), m.group(5), m.group(6))


def main():
    out_pdf = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "build" / "Sabou-Guide.pdf"
    out_pdf.parent.mkdir(parents=True, exist_ok=True)
    page = build_html()
    out_html = out_pdf.with_suffix(".html")
    out_html.write_text(page, encoding="utf-8")
    from playwright.sync_api import sync_playwright
    footer = ('<div style="width:100%;font-family:Vazirmatn;font-size:8pt;color:#5d6965;text-align:center;">'
              '<span class="pageNumber"></span> / <span class="totalPages"></span></div>')
    with sync_playwright() as p:
        browser = p.chromium.launch()
        tab = browser.new_page()
        tab.goto(out_html.as_uri())
        tab.wait_for_load_state("networkidle")
        tab.pdf(path=str(out_pdf), format="A4", print_background=True, display_header_footer=True,
                header_template="<div></div>", footer_template=footer,
                margin={"top": "18mm", "bottom": "20mm", "left": "16mm", "right": "16mm"})
        browser.close()
    print(out_pdf)


if __name__ == "__main__":
    main()
