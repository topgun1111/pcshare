# Part 6 browser check of the page script + CSS for floating objects. It uses a HAND-WRITTEN mirror of what DocxHtml.renderPrint emits (the Kotlin was not compiled that session): after a CI build, prefer `java -jar ost.jar --print sample_float.docx out.html` + print_layout_chromium.py. Needs pic.png / wm.png from make_sample_float.py. Run in tools/office_selftest.
import re,base64,json,sys
from playwright.sync_api import sync_playwright
K='../../app/src/main/java/com/lanshare/app/core/office/'
js=open(K+'DocxPrintJs.kt').read(); js=js.split('const val JS = """')[1].split('"""')[0].replace('\\"','"') if False else open(K+'DocxPrintJs.kt').read().split('const val JS = """')[1].split('"""')[0]
h=open(K+'DocxHtml.kt').read()
blk=h.split('private const val PCSS =')[1].split('\n\n')[0]
css=''.join(re.findall(r'"((?:[^"\\]|\\.)*)"',blk)); css=css.replace('\\"','"').replace('\\\\','\\')
b64=lambda f:'data:image/png;base64,'+base64.b64encode(open(f,'rb').read()).decode()
pic=b64('pic.png'); wm=b64('wm.png')
L='Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. '
P=lambda t:'<p style="margin-top:0pt;margin-bottom:8pt">'+t+'</p>'
body=P('Heading line for the floating object test')
body+='<div class=fo style="float:right;margin:0px 12px 0px 12px;width:200px"><img src="%s" style="display:block;width:200px;max-width:100%%;aspect-ratio:200/150"></div>'%pic+P(L*3)
body+='<div class=fo style="float:left;margin:0px 12px 0px 12px;width:240px"><div class=sh style="display:block;box-sizing:border-box;width:240px;max-width:100%;height:105px;overflow:hidden;background:#FFF2CC;border:2px solid #BF9000;padding:5px 10px 5px 10px"><p style="margin-top:0pt;margin-bottom:0pt"><b>TEXTBOX TITLE</b></p><p style="margin-top:0pt;margin-bottom:0pt">Text inside a floating text box with two paragraphs.</p></div></div>'+P(L*3)
body+='<div class=fo style="clear:both;width:336px;margin:0px auto 0px auto"><div class=sh style="display:block;box-sizing:border-box;width:336px;max-width:100%;height:52px;overflow:hidden;background:#C00000;border:1px solid #000000;border-radius:8px"></div></div>'+P('Paragraph with a top-and-bottom rectangle above. '+L)
body+='<p style="margin-top:0pt;margin-bottom:8pt"><span class=ao data-o=1></span>Paragraph with a behind-text watermark picture centred on the page. '+L*2+'</p>'+P(L*10)
objs='<div class=ob data-o=1 data-rh=page data-rv=page data-ha=center data-va=center data-ho=0 data-vo=0 data-w=384 data-h=192 data-b=1 data-z=0 style="width:384px"><img src="%s" style="display:block;width:384px;max-width:100%%"></div>'%wm
meta={"f":[{"pw":793.73,"ph":1122.52,"ml":94.47,"mt":94.47,"gut":0,"cw":604.8,"ch":933.58,"cols":1,"gap":48,"hd":47.2,"fd":47.2,"tp":False,"eo":False,"ns":-1,"nf":None}]}
fl='<div class=fl style="width:793.73px;max-width:100%;box-sizing:border-box;margin:8px auto;padding:94.47px 94.47px 94.47px 94.47px">'+body+'</div>'
html='<!doctype html><html class=pl><head><meta charset=utf-8><meta name=viewport content="width=810,initial-scale=1"><title>t</title><style>'+css+'body{font-family:sans-serif;font-size:11pt}</style></head><body><div id=pages></div><div id=stage>'+fl+'</div><div id=objs>'+objs+'</div><script id=meta type="application/json">'+json.dumps(meta)+'</script><script>'+js+'</script></body></html>'
open('float_test.html','w').write(html)
with sync_playwright() as p:
    b=p.chromium.launch(); pg=b.new_page(viewport={'width':1000,'height':1200})
    logs=[]; pg.on('console',lambda m:logs.append(m.text)); pg.on('pageerror',lambda e:logs.append('ERR '+str(e)))
    pg.set_content(html); pg.wait_for_function('window.__plReady===true',timeout=20000)
    print('err',pg.evaluate('window.__plError||null'),'objerr',pg.evaluate('window.__plObjError||null'),logs)
    print(pg.evaluate('JSON.stringify(window.__pl&&{pages:__pl.pages})'))
    pg.wait_for_timeout(400)
    print('ob2',pg.evaluate("document.querySelectorAll('.ob2').length"))
    for i,el in enumerate(pg.query_selector_all('.pg')): el.scroll_into_view_if_needed(); pg.wait_for_timeout(300); el.screenshot(path='shot_p%d.png'%(i+1))
    b.close()
