// Part 14b: keep-apart maths (turned-picture overlap test, gesture stop, free spot, shelf packing), extracted from ui.html (no DOM needed).
// Run: node tools/psfree_14b_test.js   (expects ALL OK)
const fs=require('fs'),path=require('path');
const h=fs.readFileSync(path.join(__dirname,'..','app/src/main/assets/ui.html'),'utf8');
const a=h.indexOf('const fBox='),a2=h.indexOf(',   // turned bounding box'),b=h.indexOf('const fSafePt='),c=h.indexOf('/* Part 12b: Fit width'),k0=h.indexOf('const fKeepOn='),k1=h.indexOf('    fSides=()=>');
if(a<0||a2<0||b<0||c<0||k0<0||k1<0||c<b||k1<k0){console.log('FAIL: helpers not found');process.exit(1)}
const src=h.slice(a,a2)+';\n'+h.slice(b,c).replace(/;\s*$/,'')+';\n'+h.slice(k0,k1).replace(/,\s*$/,'')+';\nreturn{fBox,fSafeR,fCornerPt,fKeepOn,fGapPt,fSep0,fPolyD,fHit,fHitAny,fHitSet,fPairs,fViol,fLerp,fStop,fMoveTo,fSpot,fPack}';
const LY={sw:595,sh:842},SP={safe:14,free:true,keep:true,keepGap:6};
const ROT={};const pic=id=>ROT[id]||null;
const E=()=>({style:{},append(){},dataset:{}});
const M=new Function('LY','SP','E','FEL','FRL','pic',src)(LY,SP,E,[],[],pic);
const {fBox,fSafeR,fKeepOn,fGapPt,fHit,fHitAny,fHitSet,fPairs,fViol,fLerp,fStop,fMoveTo,fSpot,fPack,fPolyD,fCornerPt,fSep0}=M;
let bad=0,n=0;const ok=(c,m)=>{n++;if(!c){bad++;if(bad<25)console.log('FAIL',m)}};
let seed=777;const rnd=()=>{seed=(seed*1664525+1013904223)>>>0;return seed/4294967296};
const W=LY.sw,H=LY.sh;
const it=(id,cx,cy,w,rot,a)=>({id,cx,cy,w,rot,a:a==null?1:a});

// --- switches
ok(fKeepOn()===true,'keep on by default');SP.keep=false;ok(fKeepOn()===false,'keep off');SP.keep=undefined;ok(fKeepOn()===true,'missing keep = on');SP.keep=true;
SP.free=false;ok(fKeepOn()===false,'grid mode: no keep');SP.free=true;
ok(fGapPt()===6,'gap default 6');SP.keepGap=99;ok(fGapPt()===40,'gap capped');SP.keepGap=-3;ok(fGapPt()===0,'gap floor');SP.keepGap=6;

// --- axis-aligned pairs (square pictures, a = 1, w = .2 of 595 = 119 pt)
{const A=it('a',.3,.2,.2,0),B=(dx)=>it('b',.3+dx,.2,.2,0),sq=.2*W/W;   // width as fraction of sheet width = .2
 ok(fHit(A,null,B(0),null,W,H,0),'same place = hit');
 ok(fHit(A,null,B(.15),null,W,H,0),'overlapping = hit');
 ok(!fHit(A,null,B(.2001),null,W,H,0),'just apart, gap 0 = free');
 ok(fHit(A,null,B(.2001),null,W,H,6),'just apart but under the gap = hit');
 ok(!fHit(A,null,B(.2+6/W+1e-4),null,W,H,6),'apart by more than the gap = free');
 ok(!fHit(A,null,B(.2),null,W,H,0),'touching with gap 0 = allowed');}
// --- turned neighbours: bounding boxes overlap, shapes do not
{const A=it('a',.30,.30,.30,45,1),B=it('b',.30+.215,.30+.215*W/H,.30,45,1);   // two 45 deg diamonds touching only near a corner pair
 const bA=fBox(A,null),bB=fBox(B,null);
 ok(Math.abs(A.cx-B.cx)<bA.bw/2+bB.bw/2&&Math.abs(A.cy-B.cy)<bA.bh/2+bB.bh/2,'test setup: bounding boxes overlap');
 ok(!fHit(A,null,B,null,W,H,0),'diamonds on a diagonal: shapes do not touch');
 const C=it('c',.30+.12,.30,.30,45,1);ok(fHit(A,null,C,null,W,H,0),'diamonds side by side too close = hit');}
// --- gap is a real clearance (Euclidean), not an axis projection
{const A=it('a',.3,.3,.2,45,1),P=fCornerPt(A,null,'tr',W,H);const dx=A.cx*W;
 const tip=A.cx*W+Math.hypot(.2*W,.2*W)/2;           // right tip of the diamond
 const B=it('b',(tip+10+Math.hypot(.2*W,.2*W)/2)/W,.3,.2,45,1);   // its left tip 10 pt right of A's tip
 ok(!fHit(A,null,B,null,W,H,9),'tips 10 pt apart, gap 9 = free');ok(fHit(A,null,B,null,W,H,11),'tips 10 pt apart, gap 11 = hit');}
// --- the picture's own turn counts
{ROT.p={rot:90};const A=it('p',.3,.3,.2,0,2),B=it('q',.3,.3+.26,.2,0,1);   // A is 119 x 238 pt, turned by 90 = 238 x 119
 ok(!fHit(A,ROT.p,B,null,W,H,0)===!fHit(A,ROT.p,B,null,W,H,0),'sanity');
 const B2=it('q',.3+.27,.3,.2,0,1);ok(fHit(A,ROT.p,B2,null,W,H,0),'turned A is wider: hits a neighbour to the right');ok(!fHit(A,null,B2,null,W,H,0),'unturned A is narrower: no hit');delete ROT.p}

// --- fStop / fMoveTo never end in an overlap and cannot jump over a neighbour
{const L=[it('1',.3,.3,.2,0,1),it('2',.7,.3,.2,0,1)],last=Object.assign({},L[0]),want=Object.assign({},L[0],{cx:.95});   // 1 is dragged right through 2
 const r=fStop(L,0,last,want,W,H,6,null);ok(r.t<1,'blocked');const q=Object.assign({},L[0],r.s);ok(fHitAny(L,q,null,W,H,6,0,null)<0,'stopped state is legal');ok(r.s.cx<.7-.2,'did not jump over the neighbour');
 const m=fMoveTo(L,0,last,Object.assign({},L[0],{cx:.62,cy:.38}),W,H,6,null);ok(fHitAny(L,Object.assign({},L[0],m),null,W,H,6,0,null)<0,'slide result is legal');ok(m.cy>.3,'slides down along the neighbour');
 const ign=new Set([1]),ov=[it('1',.5,.3,.2,0,1),it('2',.55,.3,.2,0,1)];   // already overlapping: allowed to leave, not to cross
 const r2=fStop(ov,0,ov[0],Object.assign({},ov[0],{cx:.2}),W,H,6,ign);ok(Math.abs(r2.s.cx-.2)<1e-9,'a pre-existing overlap does not block the move away');}
// --- packing
const R=fSafeR(14),gapPt=6;
for(let t=0;t<300;t++){const cnt=2+((rnd()*10)|0),L=[],ms=[];for(let i=0;i<cnt;i++){const id='p'+i;ROT[id]=rnd()<.3?{rot:90}:null;ms.push(ROT[id]);L.push(it(id,rnd(),rnd(),.1+rnd()*.4,rnd()*360,.4+rnd()*1.6))}
  const pk=fPack(L,ms,R,W,H,gapPt);if(!pk)continue;
  const P=L.map((f,i)=>Object.assign({},f,pk[i]));
  ok(fPairs(P,W,H,gapPt).length===0,'pack: nobody touches '+t);
  ok(P.every(f=>{const b=fBox(f,pic(f.id));return f.cx-b.bw/2>=R.l-1e-6&&f.cx+b.bw/2<=R.r+1e-6&&f.cy-b.bh/2>=R.t-1e-6&&f.cy+b.bh/2<=R.b+1e-6}),'pack: inside the safe frame '+t);
  ok(P.every((f,i)=>f.w<=L[i].w+1e-9&&f.w>=.03-1e-9),'pack: only shrinks '+t);
  ok(P.every((f,i)=>Math.abs(f.w/L[i].w-P[0].w/L[0].w)<1e-6||f.w<=.0300001),'pack: one common factor '+t)}
ok(fPack([it('x',.5,.5,3,0,1)],[null],R,W,H,6)!==null,'one huge picture gets shrunk to fit');
{const many=[];for(let i=0;i<60;i++){ROT['m'+i]=null;many.push(it('m'+i,.5,.5,.9,0,20))}ok(fPack(many,many.map(()=>null),R,W,H,40)===null,'impossible pack = null')}
// --- fSpot
{const L=[it('1',.3,.3,.4,0,1)],f=it('n',.3,.3,.4,0,1);ROT.n=null;const s=fSpot(L,f,null,R,W,H,6,.3,.3);ok(!!s,'spot found');
 const g=Object.assign({},f,s);ok(fHitAny(L,g,null,W,H,6,-1,null)<0,'spot is legal');
 const b=fBox(g,null);ok(g.cx-b.bw/2>=R.l-1e-6&&g.cx+b.bw/2<=R.r+1e-6&&g.cy-b.bh/2>=R.t-1e-6&&g.cy+b.bh/2<=R.b+1e-6,'spot inside the frame');
 const full=[it('F',.5,.5,2,0,1.414)];ROT.F=null;const none=fSpot(full,it('n',.5,.5,.5,0,1),null,R,W,H,6,.5,.5);ok(none===null||fHitAny(full,Object.assign({},f,none),null,W,H,6,-1,null)<0,'full sheet: no spot or a legal one')}
// --- gesture fuzz: start legal (packed), random wanted states, every result legal and never tunnelled
for(let t=0;t<120;t++){const cnt=3+((rnd()*5)|0),L=[],ms=[];for(let i=0;i<cnt;i++){const id='g'+i;ROT[id]=null;ms.push(null);L.push(it(id,.5,.5,.12+rnd()*.2,rnd()<.5?0:rnd()*360,.5+rnd()))}
  const pk=fPack(L,ms,R,W,H,gapPt);if(!pk)continue;const P=L.map((f,i)=>Object.assign({},f,pk[i]));
  for(let step=0;step<25;step++){const i=(rnd()*cnt)|0,last={cx:P[i].cx,cy:P[i].cy,w:P[i].w,rot:P[i].rot,a:P[i].a},want=Object.assign({},last,{cx:last.cx+(rnd()-.5)*.6,cy:last.cy+(rnd()-.5)*.6});
    if(rnd()<.3)want.w=Math.max(.03,last.w*(.5+rnd()*1.5));if(rnd()<.3)want.rot=rnd()*360;
    const s=(step%2?fMoveTo(P,i,last,want,W,H,gapPt,null):fStop(P,i,last,want,W,H,gapPt,null).s);
    ok(fHitAny(P,Object.assign({},P[i],s),null,W,H,gapPt,i,null)<0,'fuzz: result legal '+t+':'+step);
    Object.assign(P[i],{cx:s.cx,cy:s.cy,w:s.w,rot:s.rot})}
  ok(fPairs(P,W,H,gapPt).length===0,'fuzz: no overlap after the whole sequence '+t)}
// --- violation check used by the buttons
{const A=[it('1',.3,.3,.2,0,1),it('2',.7,.3,.2,0,1)],B=A.map(x=>Object.assign({},x));B[1].cx=.35;
 ok(fViol(A,B,W,H,6),'new overlap = violation');ok(!fViol(B,B,W,H,6),'old overlap is not a new violation');ok(!fViol(A,A,W,H,6),'no change = fine')}
console.log(bad?('FAILED '+bad+' of '+n):'ALL OK ('+n+' checks)');process.exit(bad?1:0);
