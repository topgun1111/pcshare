// Headless smoke test of the Print space layout editor (ui.html parts 5b + 6b): mocked /api/pslayout, /api/ps, /api/psrender, /api/job, jsdom.
// Run: cd tools && npm i jsdom && node pslayout_ui_smoke_test.js   (expects ALL OK; without jsdom: node pslayout_ui_smoke_chromium.js)
const {JSDOM}=require('jsdom');const fs=require('fs');
const html=fs.readFileSync(require('path').join(__dirname,'..','app/src/main/assets/ui.html'),'utf8');
const a=html.indexOf('/* ---- Print space part 5b'),b=html.indexOf('/* ---- Recycle bin: sticky top bar');
const code=html.slice(a,b);
const dom=new JSDOM('<body><div id=gal></div></body>',{runScripts:'outside-only',pretendToBeVisual:true});
const w=dom.window,d=w.document;
let fails=0;const ok=(c,m)=>{if(!c){fails++;console.log('FAIL',m)}else console.log('ok  ',m)};
process.on('unhandledRejection',e=>{fails++;console.log('UNHANDLED',e&&e.stack||e)});
w.IC={};w.$=s=>d.querySelector(s);
w.E=(t,c,x)=>{const e=d.createElement(t);if(c)e.className=c;if(x!=null)e.textContent=x;return e};
w.ic=k=>{const s=d.createElementNS('http://www.w3.org/2000/svg','svg');s.dataset.k=k;return s};
const LS={};w.LD=(k,df)=>LS[k]||df;w.SV=(k,v)=>{LS[k]=v};w.enc=encodeURIComponent;
let toasts=[],opened=0,calls=[],psCalls=[],dlgAns=true,failNext=null;
w.toast=m=>toasts.push(m);w.openPrintSpace=()=>{opened++};
const dlgLog=[];w.dlg=async o=>{dlgLog.push(o);return typeof dlgAns==='function'?dlgAns(o):dlgAns};
const psr=[],printed=[];let idn=0;w.track=async()=>true;w.watchJob=()=>{};w.doPrint=async(p,cx)=>{printed.push(Object.assign({},cx,{to:p}));return {id:'pc1',name:'PC'}};
let pics=[{id:'a',name:'a.jpg',path:'/p/a.jpg',copies:2,rot:0},{id:'b',name:'b.jpg',path:'/p/b.jpg',copies:1,rot:0},{id:'c',name:'c.jpg',path:'/p/c.jpg',copies:1,rot:0}];
function srv(b){
  if(failNext&&b.spec.margin>=failNext){throw new Error('Too many pictures for this margin / gap')}
  const spec=Object.assign({paper:'A4',orient:'auto',cols:2,rows:2,margin:14,gap:8,fit:'fit',flip:'long',autoRot:true,reverse:false},b.spec),per=spec.cols*spec.rows;
  const ex=()=>pics.flatMap(p=>Array(p.copies).fill(p.id));
  const fill=(mode)=>{const l=ex(),sh=[];for(let i=0;i<l.length;i+=per){const f=l.slice(i,i+per);while(f.length<per)f.push(null);sh.push({f,b:Array(per).fill(null)})}
    if(mode==='both'&&sh.length)sh[0].b[0]=l[0];return{portrait:true,sheets:sh}};
  let plan=b.plan?JSON.parse(JSON.stringify(b.plan)):fill('front');
  const side=s=>s==='back'?'b':'f';
  switch(b.op||'view'){case'fill':plan=fill(b.mode);break;
   case'assign':plan.sheets[b.sheet][side(b.side)][b.slot]=b.id;break;
   case'swap':{const A=plan.sheets[b.a.sheet][side(b.a.side)],B=plan.sheets[b.b.sheet][side(b.b.side)],t=A[b.a.slot];A[b.a.slot]=B[b.b.slot];B[b.b.slot]=t;break}
   case'addsheet':plan.sheets.push({f:Array(per).fill(null),b:Array(per).fill(null)});break;
   case'rmsheet':plan.sheets.splice(b.sheet,1);break;
   case'trim':while(plan.sheets.length&&![...plan.sheets.at(-1).f,...plan.sheets.at(-1).b].some(Boolean))plan.sheets.pop();break;
   case'reflow':plan=fill('front');break}
  const sw=595,sh=842,slots=[];for(let r=0;r<spec.rows;r++)for(let c=0;c<spec.cols;c++)slots.push({x:14+c*290,y:14+r*410,w:280,h:400});
  const behind=slots.map((_,i)=>{const r=Math.floor(i/spec.cols),c=i%spec.cols;return r*spec.cols+(spec.cols-1-c)});
  const pages=[],placed={},hasB=plan.sheets.some(s=>s.b.some(Boolean));
  plan.sheets.forEach((s,i)=>{['f','b'].forEach(k=>{if(k==='b'&&!hasB)return;const cells=[];s[k].forEach((id,slot)=>{if(id){cells.push({slot,id,rot:0,dw:200,dh:150});placed[id]=(placed[id]||0)+1}});pages.push({sheet:i,side:k==='f'?'front':'back',cells})})});
  return{ok:true,spec,plan,layout:{portrait:true,sw,sh,cols:spec.cols,rows:spec.rows,slots,behind,pages},placed,sheets:plan.sheets.length};
}
w.api=async(m,u,b)=>{if(u==='/api/pslayout'){calls.push(JSON.parse(JSON.stringify(b)));return srv(b)}
  if(u==='/api/psrender'){psr.push(JSON.parse(JSON.stringify(b)));if(b.op==='pdf')return{job:'j1'};
    const hb=b.plan.sheets.some(s=>s.b.some(Boolean)),list=[];b.plan.sheets.forEach((s,i)=>{list.push({sheet:i,side:'front'});if(hb)list.push({sheet:i,side:'back'})});
    return{ok:true,id:'id'+(++idn),pages:list.length,sheets:b.plan.sheets.length,sw:595,sh:842,duplex:hb,list}}
  if(u.startsWith('/api/job'))return{state:'done',result:{path:'/.LANShare Print/.render/Print space 1.pdf',name:'Print space 1.pdf',pages:2,bytes:1234,dpi:200,duplex:true}};
  if(u==='/api/ps'&&m==='GET')return{items:pics,count:pics.length,bytes:1,max:500};
  if(u==='/api/ps'){psCalls.push(b);const p=pics.find(x=>x.id===b.id);if(b.copies)p.copies=b.copies;if(b.rot!=null)p.rot=b.rot;return{items:pics}}};
w.HTMLCanvasElement.prototype.getContext=()=>new Proxy({},{get:()=>()=>{},set:()=>true});
const tick=(ms=15)=>new Promise(r=>setTimeout(r,ms));
const tap=el=>{el.onpointerdown({clientX:5,clientY:5,pointerType:'touch',pointerId:1});el.onpointerup({clientX:5,clientY:5})};
const $$=s=>[...d.querySelectorAll(s)];const cb=t=>$$('.lyctl button').find(x=>x.textContent===t);const btn=t=>$$('#gal button').find(x=>x.textContent===t);
(async()=>{
 w.eval(code);w.psLayout();await tick();
 ok(calls.length===1&&calls[0].op==='view'&&!calls[0].plan,'first call');
 ok($$('.lys').length===4,'4 slots drawn');ok($$('.lyt').length===3,'3 tray pictures');
 ok(d.querySelector('.gt small').textContent.includes('1 sheet')||d.querySelector('.gt small').textContent.includes('2 sheet'),'header '+d.querySelector('.gt small').textContent);
 ok($$('.lym').length===2,'strip: 1 sheet + add ('+$$('.lym').length+')');
 // tap pic b, then slot 3
 tap($$('.lyt')[1]);ok($$('.lyt')[1].classList.contains('sel'),'pic selected');
 const n0=calls.length;tap($$('.lys')[3]);await tick();
 let c=calls.at(-1);ok(calls.length===n0+1&&c.op==='assign'&&c.slot===3&&c.id==='b'&&c.side==='front'&&c.sheet===0,'tap pic then slot => assign '+JSON.stringify(c));
 ok(c.plan&&c.spec,'call carries spec + plan');
 // slot first then picture
 tap($$('.lys')[2]);ok($$('.lys')[2].classList.contains('sel'),'slot selected');
 tap($$('.lyt')[2]);await tick();c=calls.at(-1);ok(c.op==='assign'&&c.id==='c'&&c.slot===2,'slot first then pic => assign');
 // swap
 tap($$('.lys')[0]);tap($$('.lys')[1]);await tick();c=calls.at(-1);ok(c.op==='swap'&&c.a.slot===0&&c.b.slot===1,'two filled slots => swap');
 // drag pic -> slot
 const trayEl=$$('.lyt')[0],slotEl=$$('.lys')[3];let hitEl=slotEl;d.elementFromPoint=()=>hitEl;
 trayEl.onpointerdown({clientX:5,clientY:5,pointerType:'touch',pointerId:1});await tick(430);
 ok(!!d.querySelector('.lygh'),'drag ghost after long press');trayEl.onpointermove({clientX:50,clientY:60,preventDefault(){}});ok(slotEl.classList.contains('dt'),'drop target highlighted');
 trayEl.onpointerup({clientX:50,clientY:60});await tick();c=calls.at(-1);ok(c.op==='assign'&&c.id==='a'&&c.slot===3,'drag pic -> slot assign');ok(!d.querySelector('.lygh')&&!slotEl.classList.contains('dt'),'ghost removed');
 // drag slot -> tray = clear
 const s0=$$('.lys')[0];hitEl=$$('.lytr')[0];s0.onpointerdown({clientX:5,clientY:5,pointerType:'touch',pointerId:1});await tick(430);s0.onpointerup({clientX:9,clientY:9});await tick();
 c=calls.at(-1);ok(c.op==='assign'&&c.id===null&&c.slot===0,'drag slot -> tray clears');
 // scroll gesture cancels long press
 const t0=$$('.lyt')[0];const nn=calls.length;t0.onpointerdown({clientX:5,clientY:5,pointerType:'touch',pointerId:1});t0.onpointermove({clientX:60,clientY:5});await tick(430);t0.onpointercancel();ok(!d.querySelector('.lygh')&&calls.length===nn,'moving before 380ms cancels the drag');
 // settings
 ok(d.querySelector('.lyset').style.display==='','settings open on a fresh layout');
 btn('A5').onclick();await tick();c=calls.at(-1);ok(c.op==='reflow'&&c.spec.paper==='A5','paper chip => reflow');
 dlgAns='3x4';btn('Custom').onclick();await tick(30);c=calls.at(-1);ok(c.op==='reflow'&&c.spec.cols===3&&c.spec.rows===4,'custom 3x4 => reflow');
 dlgAns='9x9';const k=calls.length;btn('Custom').onclick();await tick(30);ok(calls.length===k&&toasts.at(-1).includes('3x4'),'custom 9x9 refused in the UI');
 dlgAns=true;
 btn('Fill (crop)').onclick();await tick();c=calls.at(-1);ok(c.op==='view'&&c.spec.fit==='fill','fit chip => view');
 btn('Short edge').onclick();await tick();ok(calls.at(-1).spec.flip==='short','flip chip');
 // margin stepper + rollback on refusal
 const mv=()=>$$('.lyst b')[0].textContent;const m0=mv();failNext=16;$$('.lyst button')[1].onclick();await tick(30);
 ok(toasts.at(-1).includes('Too many')&&mv()===m0,'refused margin change is rolled back ('+m0+' -> '+mv()+')');failNext=null;
 $$('.lyst button')[1].onclick();$$('.lyst button')[1].onclick();await tick(40);ok(mv()==='18 pt'&&calls.at(-1).spec.margin===18,'two quick + taps are serialized => 18 ('+mv()+')');
 // auto-fill both sides (asks first because pictures are placed)
 btn('Both sides').onclick();await tick(30);c=calls.at(-1);ok(c.op==='fill'&&c.mode==='both','fill both');
 // back tab
 btn('Back \u2022').onclick();await tick();ok($$('.lys').length===12,'back tab shows slots ('+$$('.lys').length+')');
 ok($$('.lys img.gh').length>=1,'ghost of the front picture on the empty back slots');
 // selected slot controls
 tap($$('.lys')[0]);ok(!!btn('Clear')||!!btn('Turn'),'slot controls: '+$$('.lyctl button').map(x=>x.textContent));
 // picture controls
 btn('Front').onclick();tap($$('.lyt')[0]);cb('+').onclick();await tick(30);
 ok(psCalls.at(-1).op==='set'&&psCalls.at(-1).copies===3&&calls.at(-1).op==='view','copies + => POST /api/ps set, then view');
 cb('Turn').onclick();await tick(30);ok(psCalls.at(-1).rot===90,'turn => rot 90');
 // rmsheet + addsheet
 d.querySelector('.lytab .ibtn').onclick();await tick(30);ok(calls.at(-1).op==='rmsheet','remove sheet');
 $$('.lym.lya')[0].onclick();await tick(30);ok(calls.at(-1).op==='addsheet','add sheet');
 // ---- part 6b: Preview + Print (fill both sides first: front + back pages)
 btn('Both sides').onclick();await tick(30);await tick(450);
 let pc=psr.filter(x=>x.op==='prepare');ok(pc.length>=1&&pc.at(-1).spec&&pc.at(-1).plan,'prepare sent with spec + plan ('+pc.length+' calls, debounced)');
 ok(!!btn('Preview')&&!!btn('Print'),'header has Preview and Print');
 btn('Preview').onclick();await tick(20);
 let im=d.querySelector('.lypg.lyp img');ok(!!im&&/\/api\/pspage\?id=id\d+&n=0&w=\d+/.test(im.getAttribute('src')),'preview shows pspage n=0: '+(im&&im.getAttribute('src')));
 ok(d.querySelectorAll('.lym img').length>=2&&d.querySelector('.lym img').getAttribute('src').endsWith('&w=120'),'page strip = renderer thumbnails ('+d.querySelectorAll('.lym img').length+')');
 ok(d.querySelector('.lypn').textContent.startsWith('Page 1 of '),'page label: '+d.querySelector('.lypn').textContent);
 ok(d.querySelector('#gal').classList.contains('lypv')&&btn('Edit'),'preview mode class + Edit button');
 d.querySelectorAll('.lytab .ibtn')[1].onclick();im=d.querySelector('.lypg.lyp img');ok(/&n=1&/.test(im.getAttribute('src')),'next page => n=1');
 d.querySelectorAll('.lym')[0].onclick();ok(/&n=0&/.test(d.querySelector('.lypg.lyp img').getAttribute('src')),'strip tap => page 0');
 // an expired id (img error) => prepare once more, then a message
 const np=psr.length,e1=d.querySelector('.lypg.lyp img'),pg1=e1.parentNode;e1.onerror();e1.onerror();await tick(30);
 ok(psr.length===np+1&&psr.at(-1).op==='prepare','img error => prepare once more (not twice)');ok(!!pg1.querySelector('.ge'),'second error => message, no loop');
 // Back leaves the preview first, system Back too
 d.querySelector('.gh .ibtn').onclick();ok(opened===0&&!d.querySelector('#gal').classList.contains('lypv')&&$$('.lys').length>0,'Back in preview => editor (not Print space)');
 btn('Preview').onclick();await tick(20);ok(w.$('#gal')._sel()===true&&opened===0&&$$('.lys').length>0,'system Back in preview => editor');
 // a layout change while in the editor re-prepares; Print: asks when pictures are not on a sheet, then pdf job -> doPrint with the PDF
 const nPrep=psr.filter(x=>x.op==='prepare').length;btn('Short edge').onclick();await tick(450);ok(psr.filter(x=>x.op==='prepare').length===nPrep+1&&psr.filter(x=>x.op==='prepare').at(-1).spec.flip==='short','layout change => one new prepare');
 tap($$('.lyt')[0]);cb('+').onclick();await tick(30);cb('+').onclick();await tick(30);   // more copies than slots filled => pictures not on a sheet
 dlgLog.length=0;dlgAns=false;btn('Print').onclick();await tick(30);
 ok(dlgLog.length===1&&/not on a sheet/.test(dlgLog[0].title)&&!psr.some(x=>x.op==='pdf'),'Print asks when pictures are unplaced; "no" starts nothing ('+(dlgLog[0]&&dlgLog[0].title)+')');
 dlgAns=true;btn('Print').onclick();await tick(40);
 const pj=psr.find(x=>x.op==='pdf');ok(!!pj&&pj.spec&&pj.plan,'Print => psrender pdf with spec + plan');
 ok(printed.length===1&&printed[0].dev==='local'&&printed[0].full===1&&printed[0].items[0].p==='/.LANShare Print/.render/Print space 1.pdf'&&printed[0].items[0].name==='Print space 1.pdf','doPrint gets the rendered PDF as a local path');
 {const asked=dlgLog.some(x=>x.title==='Two-sided sheets'),o=printed[0].opts;
  ok(o&&o.nup===1&&o.booklet===0&&o.duplex===(asked?'short':'off'),'sheets PDF: Sides preset to the flip edge when there are backs, off otherwise, no second layout ('+(o&&o.duplex)+')');
  ok(!psr.some(x=>x.op==='pdf'&&x.only),'normal print renders the whole PDF (no only)')}
 ok(!btn('Print').disabled,'Print button free again');
 // two-sided by hand: pictures on a back, "No, one side at a time" => fronts PDF (sides off), then the backs after the dialog (same printer, not the picker)
 const backTab=()=>$$('.lyc button').find(x=>x.textContent.startsWith('Back'));
 backTab().onclick();await tick(20);tap($$('.lyt')[0]);tap($$('.lys')[0]);await tick(30);
 ok(backTab().textContent==='Back \u2022','a picture is on the back now');
 psr.length=0;printed.length=0;dlgLog.length=0;
 dlgAns=o=>/Two-sided/.test(o.title)?'alt':/not on a sheet/.test(o.title)||/Now the backs/.test(o.title)?true:true;
 btn('Print').onclick();await tick(60);
 {const pd=psr.filter(x=>x.op==='pdf');
  ok(dlgLog.some(x=>x.title==='Two-sided sheets')&&dlgLog.some(x=>x.title==='Now the backs'),'by hand: asks about both sides, then \u201cNow the backs\u201d ('+dlgLog.map(x=>x.title).join(' | ')+')');
  ok(pd.length===2&&pd[0].only==='front'&&!pd[0].reverse&&pd[1].only==='back'&&!pd[1].reverse,'by hand: front PDF, then back PDF ('+pd.map(x=>x.only).join(',')+')');
  ok(printed.length===2&&printed[0].opts.duplex==='off'&&printed[1].opts.duplex==='off'&&!printed[0].to&&printed[1].to&&printed[1].to.id==='pc1','by hand: both one-sided, backs go to the printer used for the fronts')}
 // "Last sheet first" => reverse
 psr.length=0;printed.length=0;dlgLog.length=0;
 dlgAns=o=>/Two-sided/.test(o.title)?'alt':/Now the backs/.test(o.title)?'alt':true;
 btn('Print').onclick();await tick(60);
 {const pd=psr.filter(x=>x.op==='pdf');ok(pd.length===2&&pd[1].only==='back'&&pd[1].reverse===true,'by hand: \u201cLast sheet first\u201d => reverse back PDF')}
 // cancelling at the backs dialog stops after the fronts; both sides by the printer => ONE pdf, Sides preset to the flip edge
 psr.length=0;printed.length=0;dlgLog.length=0;
 dlgAns=o=>/Now the backs/.test(o.title)?null:/Two-sided/.test(o.title)?'alt':true;
 btn('Print').onclick();await tick(60);
 ok(psr.filter(x=>x.op==='pdf').length===1&&printed.length===1,'cancel at \u201cNow the backs\u201d: only the fronts were printed');
 psr.length=0;printed.length=0;dlgLog.length=0;dlgAns=true;
 btn('Print').onclick();await tick(60);
 ok(psr.filter(x=>x.op==='pdf').length===1&&!psr.find(x=>x.op==='pdf').only&&printed.length===1&&printed[0].opts.duplex==='short','printer does both sides: one PDF, Sides = Short edge (flip edge of the layout)');
 // saved state is reused on reopen
 const saved=LS['ls_ps_lay'];ok(!!saved&&JSON.parse(saved).plan.sheets.length>0,'layout saved in localStorage');
 calls.length=0;w.psLayout();await tick();ok(calls[0].plan&&calls[0].op==='view'&&d.querySelector('.lyset').style.display==='none','reopen: saved spec + plan sent, settings collapsed');
 // old editor must go quiet when openPrintSpace takes #gal back
 d.querySelector('#gal').style.display='none';
 // back button + system back
 w.psLayout();await tick();d.querySelector('.gh .ibtn').onclick();ok(opened===1,'Back button -> Print space');
 ok(w.$('#gal')._sel()===true&&opened===2,'system Back -> Print space');
 console.log(fails?('FAILED '+fails):'ALL OK');process.exit(fails?1:0);
})();
