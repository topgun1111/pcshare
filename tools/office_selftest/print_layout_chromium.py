# Part 5 browser check: java -jar ost.jar --print X.docx out.html ; python3 print_layout_chromium.py out.html /tmp/shot  (needs playwright + chromium; maps the font URL to assets/fonts/office)
import sys, json, os
from playwright.sync_api import sync_playwright
html=sys.argv[1]; out=sys.argv[2]
FONTS=os.path.join(os.path.dirname(os.path.abspath(__file__)),"..","..","app/src/main/assets/fonts/office")+"/"
with sync_playwright() as p:
    b=p.chromium.launch(); print('chromium',b.version)
    pg=b.new_page(viewport={'width':412,'height':900}, device_scale_factor=2, is_mobile=True, has_touch=True)
    reqs=[]
    def route(r):
        u=r.request.url
        if u.startswith('https://appassets.androidplatform.net/office-fonts/'):
            reqs.append(u.rsplit('/',1)[1]); r.fulfill(path=FONTS+u.rsplit('/',1)[1],content_type='font/ttf',headers={'access-control-allow-origin':'*'})
        elif u=='https://appassets.androidplatform.net/':
            r.fulfill(body=open(html,encoding='utf-8').read(),content_type='text/html')
        else: r.abort()
    pg.route('**/*',route)
    logs=[]; pg.on('console',lambda m:logs.append(m.text)); pg.on('pageerror',lambda e:logs.append('ERR '+str(e)))
    pg.goto('https://appassets.androidplatform.net/')
    pg.wait_for_function('window.__plReady===true',timeout=30000)
    print('error',pg.evaluate('window.__plError||null'))
    info=pg.evaluate('window.__pl && {pages:__pl.pages,flows:__pl.flows,numbers:__pl.numbers}')
    print(json.dumps(info), 'fonts', sorted(set(reqs)), logs[:5])
    print('live clones', pg.evaluate("document.querySelectorAll('.vp > .fl').length"), 'of', pg.evaluate("document.querySelectorAll('.pg').length"))
    pg.screenshot(path=out+'_top.png')
    # per-page desktop shots: render at 1x wide viewport
    pg2=b.new_page(viewport={'width':1100,'height':1200})
    pg2.route('**/*',route); pg2.goto('https://appassets.androidplatform.net/'); pg2.wait_for_function('window.__plReady===true')
    n=pg2.evaluate('__pl.pages')
    for i in range(n):
        el=pg2.query_selector_all('.pg')[i]; el.scroll_into_view_if_needed(); pg2.wait_for_timeout(250)
        el.screenshot(path='%s_p%02d.png'%(out,i+1))
    b.close()
