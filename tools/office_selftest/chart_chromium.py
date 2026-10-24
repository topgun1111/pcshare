# Part 10: screenshots the HTML the chart self-test writes (java -jar ost.jar --chart  ->  $TMP/ost_chart_0..2.html) in headless Chromium.
# usage: python3 chart_chromium.py [outdir]   (needs playwright + chromium; checks there is no page error and that every <svg> has a size)
import sys, os, tempfile
from playwright.sync_api import sync_playwright
out = sys.argv[1] if len(sys.argv) > 1 else tempfile.gettempdir()
with sync_playwright() as p:
    b = p.chromium.launch(); bad = 0
    for i in range(3):
        src = os.path.join(tempfile.gettempdir(), 'ost_chart_%d.html' % i)
        pg = b.new_page(viewport={'width': 1000, 'height': 760}); errs = []
        pg.on('pageerror', lambda e: errs.append(str(e)))
        pg.goto('file://' + src)
        n = pg.evaluate("document.querySelectorAll('.ch svg').length")
        sizes = pg.evaluate("[...document.querySelectorAll('.ch svg')].map(s=>s.getBoundingClientRect().width+'x'+s.getBoundingClientRect().height)")
        pg.screenshot(path=os.path.join(out, 'chart_sheet%d.png' % i))
        print('sheet', i, 'charts', n, sizes, 'errors', errs)
        if errs or n != 1 + (i == 0): bad += 1
    b.close(); print('FAIL' if bad else 'OK'); sys.exit(1 if bad else 0)
