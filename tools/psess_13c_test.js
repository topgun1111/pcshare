// Part 13c logic test (no DOM): node tools/psess_13c_test.js  - extracts pssEdit / pssAgain / phSheets head from ui.html
const fs=require('fs'),vm=require('vm');
const src=fs.readFileSync(__dirname+'/../app/src/main/assets/ui.html','utf8');
const grab=n=>{const i=src.indexOf('async function '+n+'(');if(i<0)throw Error(n);let d=0,j=src.indexOf('{',i);for(let k=j;;k++){if(src[k]==='{')d++;else if(src[k]==='}'&&--d===0)return src.slice(i,k+1)}};
let ok=0,bad=0;const t=(c,m)=>{c?ok++:(bad++,console.log('FAIL',m))};
const calls=[],ls={},toasts=[],ev=[];let pdfFail=false,dlgAns=false;
const ctx={api:async(m,u,b)=>{calls.push([m,u,b]);if(b&&b.op==='get')return{session:{spec:{a:1},plan:{sheets:[]},mode:'both',to:'pc1',toName:'PC',opts:{duplex:'long',printer:'HP'}}};
 if(b&&b.op==='restore')return{restored:3,failed:1,session:{spec:{a:1},plan:{p:1},mode:'both',to:'pc1',opts:{duplex:'long',printer:'HP'}}};
 if(b&&b.op==='pdf'){if(pdfFail)throw Error('no pdf');return{path:'/x.pdf',name:'x.pdf'}} if(u==='/api/print')return{job:'j1'};return{}},
 SV:(k,v)=>{ls[k]=v},lsGet:(k,d)=>ls[k]?JSON.parse(ls[k]):d,$:()=>({style:{},onclick:null}),toast:m=>toasts.push(m),
 dlg:async()=>dlgAns,openPrintSpace:()=>ev.push('ops'),psLayout:()=>ev.push('lay'),doPrint:()=>ev.push('doPrint'),
 phAdd:h=>{ev.push('phAdd:'+h.sid);return 'id1'},watchJob:()=>{},track:async()=>true,phMark:()=>{},JSON,String};
vm.createContext(ctx);vm.runInContext(grab('pssEdit')+grab('pssAgain'),ctx);
(async()=>{
 await ctx.pssEdit('s1');
 t(JSON.parse(ls.ls_ps_lay).plan.p===1,'ls_ps_lay written');t(ls.printTo==='pc1','printTo');t(JSON.parse(ls.printOptsBy)['pc1|HP'].duplex==='long','printOptsBy');
 t(ev.join()==='ops,lay','editor opened');t(toasts[0].includes('1 picture could not'),'failed toast');
 ev.length=0;await ctx.pssAgain('s1');
 t(calls.some(c=>c[1]==='/api/print'&&c[2].paths[0]==='/x.pdf'&&c[2].to==='pc1'),'print posted with stored path');t(ev.join()==='phAdd:s1','history keeps sid, no new save');
 ev.length=0;calls.length=0;pdfFail=true;dlgAns=true;await ctx.pssAgain('s1');
 t(!calls.some(c=>c[1]==='/api/print'),'no print when PDF missing');t(calls.some(c=>c[2]&&c[2].op==='restore'),'missing PDF falls back to Edit');
 console.log(bad?'FAILED '+bad:'ALL OK ('+ok+' checks)');process.exit(bad?1:0)})();
