// Same checks as pslayout_ui_smoke_test.js, run in real Chromium (playwright) when jsdom cannot be installed.
// Run: npm i playwright && npx playwright install chromium && node tools/pslayout_ui_smoke_chromium.js   (expects ALL OK)
const {chromium}=require('playwright');
const fs=require('fs'),path=require('path');
const root=path.join(__dirname,'..');
const html=fs.readFileSync(path.join(root,'app/src/main/assets/ui.html'),'utf8');
const a=html.indexOf('/* ---- Print space part 5b'),b=html.indexOf('/* ---- Recycle bin: sticky top bar');
const code=html.slice(a,b);
const src=fs.readFileSync(path.join(__dirname,'pslayout_ui_smoke_test.js'),'utf8');
let body=src.slice(src.indexOf('const w=dom.window,d=w.document;'));
body=body.replace('const w=dom.window,d=w.document;','const w=window,d=document;')
 .replace(/process\.on\('unhandledRejection'[^\n]*\n/,"window.addEventListener('unhandledrejection',e=>{fails++;console.log('UNHANDLED',e.reason&&e.reason.stack||e.reason)});\n")
 .replace('w.eval(code)','(0,eval)(CODE)')
 .replace(/process\.exit\(fails\?1:0\);\n\}\)\(\);\s*$/,'return fails;\n})();')
 .replace(/\n\(async\(\)=>\{/,'\nreturn (async()=>{');
(async()=>{
  const br=await chromium.launch(),pg=await br.newPage();
  pg.on('console',m=>console.log(m.text()));pg.on('pageerror',e=>console.log('PAGEERROR',e.message));
  const png=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==','base64');
  await pg.route('http://localhost/**',r=>{const u=r.request().url();u.endsWith('/')?r.fulfill({contentType:'text/html',body:'<body><div id=gal></div></body>'}):r.fulfill({contentType:'image/png',body:png})});
  await pg.goto('http://localhost/');
  const fails=await pg.evaluate(new Function('CODE','return (async()=>{'+body+'})()'),code);
  await br.close();process.exit(fails?1:0);
})();
