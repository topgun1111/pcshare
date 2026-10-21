# Headless smoke test of the Print space SCREEN (ui.html openPrintSpace, parts 2 + 9a): mocked /api/ps (with the undo buffer), real Chromium.
# Run: pip install playwright && playwright install chromium && python3 psscreen_ui_smoke_chromium.py   (expects ALL OK)
import os, re, sys, json
from playwright.sync_api import sync_playwright
here = os.path.dirname(os.path.abspath(__file__))
html = open(os.path.join(here, '..', 'app/src/main/assets/ui.html'), encoding='utf-8').read()
a = html.index('/* ---- Print space (HANDOVER 9.7, part 2)')
b = html.index('/* ---- Print space part 5b')
code = html[a:b]
page_html = """<!doctype html><meta charset=utf-8><style>#gal{display:none;flex-direction:column;position:fixed;inset:0}.gpst{display:none;align-items:center;gap:8px}.gc.sel{outline:2px solid red}
.gh{display:flex;flex:none}.gh.sl{display:none}#gal.selm .gh{display:none}#gal.selm .gh.sl{display:flex}.gs{flex:1;min-height:0;overflow:auto}
.gg{display:grid;grid-template-columns:repeat(3,1fr);gap:4px}.gc{position:relative;height:90px}.gc img{width:100%;height:100%;object-fit:cover}
.gb{display:none;flex:none}#gal.selm .gb{display:grid;grid-template-columns:repeat(var(--cols,5),minmax(0,1fr))}.ib{height:40px}</style>
<body><div id=gal></div><div id=sheet></div><div id=toast><span id=tx></span></div></body>"""
mock = r"""
window.$=s=>document.querySelector(s);
window.E=(t,c,x)=>{const e=document.createElement(t);if(c)e.className=c;if(x!=null)e.textContent=x;return e};
window.ic=k=>{const s=document.createElementNS('http://www.w3.org/2000/svg','svg');s.dataset.k=k;return s};
const LS={};window.LD=(k,df)=>k in LS?LS[k]:df;window.SV=(k,v)=>{LS[k]=v};window.__LS=LS;
window.enc=encodeURIComponent;window.PV_IMG=['jpg','jpeg','png','webp'];window.fmt=n=>n+' B';
window.toasts=[];window.toast=(m)=>{toasts.push(m)};
window.galClose=()=>{};window.psLayout=()=>{};window.printRecent=()=>{};window.lsGet=(k,d)=>d;window.doPrint=(p,cx)=>{window.__printed=cx};
window.dlgAns=true;window.dlgLog=[];window.dlg=async o=>{dlgLog.push(o);return typeof dlgAns==='function'?dlgAns(o):dlgAns};
// fake phone
let seq=0,items=[],undo=[],calls=[];window.__calls=calls;
for(let i=0;i<4;i++)items.push({id:'i'+(seq++),name:'p'+i+'.jpg',size:100,from:'/x',at:Date.now(),copies:1,rot:0,path:'/.LANShare Print/i'+i+'/p'+i+'.jpg'});
const answer=(extra)=>Object.assign({items:items.map(x=>Object.assign({},x)),count:items.length,bytes:items.length*100,max:500,cfg:{days:0,mb:0}},undo.length?{undo:undo.length}:{},extra||{});
window.api=async(m,u,b)=>{calls.push([m,u,b]);
  if(m==='GET')return answer();
  const op=b.op;
  if(op==='rm'||op==='clear'){undo=[];const ids=op==='clear'?items.map(x=>x.id):b.ids;let n=0;items=items.filter((x,i)=>{if(ids.includes(x.id)){undo.push([i,x]);n++;return false}return true});return answer({removed:n})}
  if(op==='undo'){let n=0;undo.sort((p,q)=>p[0]-q[0]).forEach(([i,x])=>{items.splice(Math.min(i,items.length),0,x);n++});undo=[];return answer({restored:n})}
  if(op==='set'){let n=0;items.forEach(x=>{if(b.ids.includes(x.id)){if(b.copies!=null)x.copies=b.copies;if(b.rotBy!=null)x.rot=(x.rot+b.rotBy)%360;n++}});return answer({changed:n})}
  throw new Error('op '+op)};
"""
fails = 0
def ok(c, m):
    global fails
    if not c: fails += 1
    print('ok  ' if c else 'FAIL', m)
with sync_playwright() as p:
    br = p.chromium.launch(); pg = br.new_page(viewport={'width': 400, 'height': 800})
    errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    pg.set_content(page_html)
    pg.add_script_tag(content=mock)
    pg.add_script_tag(content=code + "\nwindow.__open=openPrintSpace;window.__psAdd=psAdd;window.__on=PS_ON;")
    pg.evaluate("__open()"); pg.wait_for_selector('#gal .gc'); pg.wait_for_timeout(100)
    cnt = lambda: pg.evaluate("document.querySelectorAll('#gal .gc').length")
    ok(cnt() == 4, 'four pictures drawn')
    sel = lambda *ix: [pg.click(f'#gal .gc:nth-child({i+1})') for i in ix]
    btns = lambda: pg.evaluate("[...document.querySelectorAll('#gal .gb .ib small')].map(e=>e.textContent)")
    sel(0, 1)
    ok(btns() == ['Print', 'Copies', 'Rotate', 'Remove'], 'selection bar: Print, Copies, Rotate, Remove -> ' + str(btns()))
    # Rotate
    pg.click('#gal .gb .ib:nth-child(3)'); pg.wait_for_timeout(100)
    last = pg.evaluate("__calls[__calls.length-1]")
    ok(last[2].get('rotBy') == 90 and len(last[2]['ids']) == 2, 'Rotate sends rotBy 90 for both ids')
    ok(pg.evaluate("document.querySelector('#gal .gc img').style.transform") == 'rotate(90deg)', 'thumbnail drawn rotated')
    # Copies: bad then good
    pg.evaluate("dlgAns=o=>'0'"); pg.click('#gal .gb .ib:nth-child(2)'); pg.wait_for_timeout(100)
    ok(pg.evaluate("toasts[toasts.length-1]").startswith('Enter a number'), 'copies 0 refused with a toast')
    pg.evaluate("dlgAns=o=>'3'"); pg.click('#gal .gb .ib:nth-child(2)'); pg.wait_for_timeout(100)
    ok(pg.evaluate("[...document.querySelectorAll('#gal .gd')].map(e=>e.textContent)") == ['×3', '×3'], 'copies badge x3 on both')
    pg.evaluate("dlgAns=true")
    # Remove 2 -> undo bar
    pg.click('#gal .gb .ib:nth-child(4)'); pg.wait_for_timeout(150)
    ok(cnt() == 2, 'two removed')
    vis = lambda: pg.evaluate("getComputedStyle(document.querySelector('#gal .gpst')).display")
    ok(vis() == 'flex' and '2 pictures removed' in pg.inner_text('#gal .gpst'), 'undo bar shown: ' + pg.inner_text('#gal .gpst').replace('\n', ' '))
    pg.click('#gal .gpst button'); pg.wait_for_timeout(150)
    ok(cnt() == 4 and vis() == 'none', 'Undo brings the pictures back and hides the bar')
    ok(pg.evaluate("[...document.querySelectorAll('#gal .gc')].map(e=>(e.querySelector('img').src.match(/%2Fp(\\d)\\.jpg/)||[])[1])") == ['0', '1', '2', '3'], 'old order kept')
    # Remove all -> undo bar -> second remove replaces
    pg.evaluate("[...document.querySelectorAll('#gal .gh:not(.sl) .ibtn')].pop().click()"); pg.wait_for_timeout(150)
    ok(cnt() == 0 and '4 pictures removed' in pg.inner_text('#gal .gpst'), 'Remove all shows undo bar for 4')
    pg.click('#gal .gpst button'); pg.wait_for_timeout(150)
    ok(cnt() == 4, 'Undo after Remove all restores all 4')
    ok(pg.evaluate("__LS.ls_ps===undefined"), 'kill switch off by default')
    # kill switch
    pg.evaluate("SV('ls_ps','0');window.__calls.length=0;document.querySelector('#gal').style.display='none'")
    pg.evaluate("__open()"); pg.wait_for_timeout(100)
    ok(pg.evaluate("getComputedStyle(document.querySelector('#gal')).display") == 'none', "ls_ps='0': Print space does not open")
    pg.evaluate("__psAdd('local',['/a.jpg'])"); pg.wait_for_timeout(100)
    ok(pg.evaluate("__calls.length") == 0, "ls_ps='0': psAdd sends nothing")
    ok(not errs, 'no page errors ' + str(errs))
    br.close()
print('ALL OK' if not fails else f'{fails} FAILED'); sys.exit(1 if fails else 0)
