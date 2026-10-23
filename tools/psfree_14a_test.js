// Part 14a: safe-margin clamp + four-corner resize + rotate-knob side, math extracted from ui.html (no DOM needed).
// Run: node tools/psfree_14a_test.js   (expects ALL OK)
const fs=require('fs'),path=require('path');
const h=fs.readFileSync(path.join(__dirname,'..','app/src/main/assets/ui.html'),'utf8');
const a=h.indexOf('const fBox='),a2=h.indexOf(',   // turned bounding box'),b=h.indexOf('const fSafePt='),c=h.indexOf('/* Part 12b: Fit width');
if(a<0||a2<0||b<0||c<0||c<b){console.log('FAIL: helpers not found');process.exit(1)}
const src=h.slice(a,a2)+';\n'+h.slice(b,c).replace(/;\s*$/,'')+';\nreturn{fBox,fSafePt,fSafeR,fClamp,fCornerPt,fCorner,fKnobSide}';
const LY={sw:595,sh:842},SP={safe:14};
const E=()=>({style:{},append(){},dataset:{}});
const M=new Function('LY','SP','E','FEL','FRL','pic',src)(LY,SP,E,[],[],()=>null);
const {fBox,fSafePt,fSafeR,fClamp,fCornerPt,fCorner,fKnobSide}=M;
let bad=0,n=0;const ok=(c,m)=>{n++;if(!c){bad++;if(bad<25)console.log('FAIL',m)}};const near=(x,y,t=1e-6)=>Math.abs(x-y)<t;
let seed=12345;const rnd=()=>{seed=(seed*1664525+1013904223)>>>0;return seed/4294967296};
const inside=(x,m,R,t=1e-7)=>{const b=fBox(x,m);return x.cx-b.bw/2>=R.l-t&&x.cx+b.bw/2<=R.r+t&&x.cy-b.bh/2>=R.t-t&&x.cy+b.bh/2<=R.b+t};

// --- safe rect
SP.safe=14;ok(near(fSafePt(),14),'safe default 14');SP.safe=undefined;ok(near(fSafePt(),14),'missing safe -> 14');SP.safe=0;ok(near(fSafePt(),0),'safe 0 stays 0');SP.safe=999;ok(near(fSafePt(),72),'safe capped 72');SP.safe=14;
let R=fSafeR(14);ok(near(R.l,14/595)&&near(R.r,1-14/595)&&near(R.t,14/842)&&near(R.b,1-14/842),'safe rect fractions');
R=fSafeR(0);ok(R.l===0&&R.r===1&&R.t===0&&R.b===1,'safe off = whole sheet');
R=fSafeR(9999);ok(R.l<=.45&&R.r>=.55,'absurd safe margin keeps a usable area');

// --- fClamp
{let x={cx:.99,cy:.01,w:.3,rot:0,a:1.4};R=fSafeR(14);fClamp(x,null,R);ok(inside(x,null,R),'clamp moves inside');ok(near(x.w,.3),'clamp does not resize when it fits');
 x={cx:.5,cy:.5,w:2,rot:0,a:1.4};fClamp(x,null,R);ok(inside(x,null,R),'clamp shrinks a huge picture');ok(near(x.a,1.4),'shape (a) kept');
 const b=fBox(x,null);ok(near(b.bw,R.r-R.l,1e-6)||near(b.bh,R.b-R.t,1e-6),'shrunk picture touches the frame on one axis');
 x={cx:.5,cy:.5,w:.5,rot:90,a:1.4};fClamp(x,null,R);ok(inside(x,null,R),'turned 90: box swaps width / height');
 x={cx:.3,cy:.3,w:.3,rot:0,a:1};const y=Object.assign({},x);fClamp(x,null,R);ok(near(x.cx,y.cx)&&near(x.cy,y.cy)&&near(x.w,y.w),'item already inside is untouched');
 x={cx:.5,cy:.5,w:.5,rot:0,a:1.4};fClamp(x,{rot:90},R);ok(inside(x,{rot:90},R),'picture own turn counts');}

// --- fuzz: fClamp always lands inside and is idempotent
for(let i=0;i<20000;i++){const sp=[0,9,14,18,28,72][i%6],Rr=fSafeR(sp),m=rnd()<.5?null:{rot:[0,90,180,270][(rnd()*4)|0]},
  x={cx:rnd()*1.6-.3,cy:rnd()*1.6-.3,w:.03+rnd()*1.5,rot:rnd()*360,a:.3+rnd()*2.7};
  fClamp(x,m,Rr);ok(inside(x,m,Rr,1e-6)||x.w<=.0300001,'fuzz clamp inside '+i);const y=Object.assign({},x);fClamp(y,m,Rr);ok(near(x.cx,y.cx,1e-9)&&near(x.cy,y.cy,1e-9)&&near(x.w,y.w,1e-9),'fuzz clamp idempotent '+i)}

// --- corner drag
const W=360,H=W*842/595,op={tl:'br',tr:'bl',br:'tl',bl:'tr'};
{R=fSafeR(14);const f0={cx:.5,cy:.5,w:.4,rot:0,a:1.4};
 // drag br outward along the diagonal, free space: exact scale
 const cp=fCornerPt(f0,null,'br',W,H),tl=fCornerPt(f0,null,'tl',W,H);
 const t=fCorner(f0,null,'br',(cp.x+20)/W,(cp.y+20*1.4)/H,R,W,H);const sc=t.w/f0.w;ok(sc>1,'drag out = bigger');
 const g=Object.assign({},f0,{w:t.w,cx:t.cx,cy:t.cy}),tl2=fCornerPt(g,null,'tl',W,H);ok(near(tl2.x,tl.x,1e-6)&&near(tl2.y,tl.y,1e-6),'opposite corner (tl) stays');
 // half way to the anchor on the diagonal -> half size
 const mid={x:(cp.x+tl.x)/2,y:(cp.y+tl.y)/2},h2=fCorner(f0,null,'br',mid.x/W,mid.y/H,R,W,H);ok(near(h2.w,f0.w/2,1e-9),'pointer on the diagonal half way = half width');
 // far outside the sheet -> stops at the frame
 const far=fCorner(f0,null,'br',3,3,R,W,H),gf=Object.assign({},f0,far);ok(inside(gf,null,R,1e-6),'drag far outside stays in the frame');
 // pointer beyond the anchor (crossed over) -> minimum size, still inside
 const cr=fCorner(f0,null,'br',-1,-1,R,W,H);ok(near(cr.w,.03,1e-9),'crossing the anchor gives the minimum size');}
for(let i=0;i<20000;i++){const sp=[0,9,14,18,28][i%5],Rr=fSafeR(sp),m=rnd()<.5?null:{rot:[0,90,180,270][(rnd()*4)|0]},
  f0={cx:0,cy:0,w:.05+rnd()*.5,rot:rnd()*360,a:.4+rnd()*2.2},k=['tl','tr','br','bl'][(rnd()*4)|0];
  f0.cx=.2+rnd()*.6;f0.cy=.2+rnd()*.6;fClamp(f0,m,Rr);
  const px=rnd()*1.6-.3,py=rnd()*1.6-.3,r=fCorner(f0,m,k,px,py,Rr,W,H),g=Object.assign({},f0,r);
  ok(inside(g,m,Rr,1e-6),'corner drag stays in the safe frame '+i);ok(r.w>=.03-1e-12&&r.w<=3+1e-12,'corner width range '+i);
  const A0=fCornerPt(f0,m,op[k],W,H),A1=fCornerPt(g,m,op[k],W,H);ok(near(A0.x,A1.x,1e-6)&&near(A0.y,A1.y,1e-6),'anchor fixed '+i+' '+k)}

// --- knob side: top when there is room, another side when the top is off the page
{const f={cx:.5,cy:.5,w:.3,rot:0,a:1};ok(fKnobSide(f,null,W,H)==='t','knob on top in the middle');
 const g={cx:.5,cy:.03,w:.2,rot:0,a:1};const s=fKnobSide(g,null,W,H);ok(s!=='t','knob leaves the top edge when it would be off the page: '+s);
 const q={cx:.5,cy:.5,w:.3,rot:180,a:1};ok(fKnobSide(q,null,W,H)==='t','turned 180: knob is on the picture top (visual bottom) while there is room')}
console.log(bad?'FAILED '+bad+' of '+n:'ALL OK ('+n+' checks)');process.exit(bad?1:0)
