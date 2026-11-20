"""metod.html -> QazAgroSat-metodika.pdf (A4) and page previews p1..pN.png. python topdf.py"""
import os
from pathlib import Path
from playwright.sync_api import sync_playwright

here = Path(__file__).resolve().parent
src = (here / 'metod.html').as_uri()
with sync_playwright() as p:
    b = p.chromium.launch(channel='msedge')
    pg = b.new_page()
    pg.goto(src)
    pg.wait_for_load_state('networkidle')
    pg.wait_for_timeout(1500)
    pg.pdf(path=str(here / 'QazAgroSat-metodika.pdf'), format='A4', print_background=True,
           margin={'top': '18mm', 'bottom': '18mm', 'left': '18mm', 'right': '18mm'})
    # previews: open the PDF's pages as images through the browser's print layout
    pg.emulate_media(media='print')
    pg.set_viewport_size({'width': 794, 'height': 1123})
    h = pg.evaluate('document.documentElement.scrollHeight')
    pg.screenshot(path=str(here / 'print-preview.png'), full_page=True)
    b.close()
print(os.path.getsize(here / 'QazAgroSat-metodika.pdf'), 'bytes; preview height', h)
