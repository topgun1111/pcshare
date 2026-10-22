// Part 12b: checks of the Free-mode helpers (Fit width, Align, ghost mirror rule) extracted from ui.html. Run: node tools/psfree_12b_test.js  (expects ALL OK)
const fs=require('fs'),path=require('path');
const h=fs.readFileSync(path.join(__dirname,'..','app/src/main/assets/ui.html'),'utf8');
const a=h.indexOf('const fBox='),b=h.indexOf('async function fNum');
if(a<0||b<0){console.log('FAIL: helpers not found');process.exit(1)}
const LY={sw:595,sh:842};
const {fBox,fFitW,fAlign}=new Function('LY','let fBox,fFitW,fAlign;'+h.slice(a,b).replace('const fBox=','fBox=').replace(/,\s*fFitW=/,';fFitW=').replace(/,\s*fAlign=/,';fAlign=')+';return{fBox,fFitW,fAlign}')(LY);
let bad=0;const ok=(c,m)=>{if(!c){bad++;console.log('FAIL',m)}};const near=(x,y)=>Math.abs(x-y)<1e-6;
let x={cx:.3,cy:.4,w:.5,rot:0,a:1.5};fFitW(x,null);ok(near(x.w,1)&&near(x.cx,.5),'fit width unturned');
x={cx:.3,cy:.4,w:.5,rot:90,a:1.5};fFitW(x,null);ok(near(fBox(x,null).bw,1),'fit width turned 90 -> box width 1');
x={cx:.5,cy:.5,w:.4,rot:0,a:1};fAlign(x,null,'l');ok(near(x.cx,.2),'align left');fAlign(x,null,'r');ok(near(x.cx,.8),'align right');
fAlign(x,null,'t');ok(near(x.cy,.4*LY.sw/LY.sh/2),'align top');fAlign(x,null,'b');ok(near(x.cy,1-.4*LY.sw/LY.sh/2),'align bottom');
fAlign(x,null,'c');fAlign(x,null,'m');ok(x.cx===.5&&x.cy===.5,'align centre / middle');
x={cx:.5,cy:.5,w:.4,rot:45,a:1};const bx=fBox(x,{rot:0});fAlign(x,{rot:0},'l');ok(near(x.cx,bx.bw/2),'align left uses the turned box');
// ghost rule: long edge + portrait -> mirror x; short edge + portrait -> mirror y (SheetLayout.backSlot)
const mx=(flip,p)=>(flip!=='short')===p;ok(mx('long',true)&&!mx('short',true)&&!mx('long',false)&&mx('short',false),'mirror rule');
console.log(bad?'FAILED':'ALL OK');process.exit(bad?1:0)
