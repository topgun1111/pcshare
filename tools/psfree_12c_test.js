// Part 12c: group align + distribute helpers extracted from ui.html. Run: node tools/psfree_12c_test.js  (expects ALL OK)
const fs=require('fs'),path=require('path');
const h=fs.readFileSync(path.join(__dirname,'..','app/src/main/assets/ui.html'),'utf8');
const a=h.indexOf('const fBox='),b=h.indexOf('async function fNum');
if(a<0||b<0){console.log('FAIL: helpers not found');process.exit(1)}
const LY={sw:595,sh:842};
const {fBox,fAlignG,fDist}=new Function('LY','let fBox,fFitW,fAlign,fBoxes,fAlignG,fDist;'+h.slice(a,b).replace('const fBox=','fBox=').replace(/,\s*fFitW=/,';fFitW=').replace(/,\s*fAlign=/,';fAlign=').replace(/,\s*fBoxes=/,';fBoxes=').replace(/,\s*fAlignG=/,';fAlignG=').replace(/,\s*fDist=/,';fDist=')+';return{fBox,fAlignG,fDist}')(LY);
let bad=0;const ok=(c,m)=>{if(!c){bad++;console.log('FAIL',m)}};const near=(x,y)=>Math.abs(x-y)<1e-6;
const mk=(cx,cy,w,rot=0)=>({cx,cy,w,rot,a:1}),nm=n=>Array(n).fill(null);
let xs=[mk(.3,.3,.2),mk(.6,.5,.3)];fAlignG(xs,nm(2),'l');ok(near(xs[0].cx-.1,.2)&&near(xs[1].cx-.15,.2),'group left = leftmost edge');
xs=[mk(.3,.3,.2),mk(.6,.5,.3)];fAlignG(xs,nm(2),'r');ok(near(xs[0].cx+.1,.75)&&near(xs[1].cx+.15,.75),'group right = rightmost edge');
xs=[mk(.3,.3,.2),mk(.6,.5,.3)];fAlignG(xs,nm(2),'c');ok(near(xs[0].cx,xs[1].cx)&&near(xs[0].cx,(.2+.75)/2),'group centre');
xs=[mk(.3,.3,.2),mk(.6,.5,.3)];const T=Math.min(.3-fBox(xs[0],null).bh/2,.5-fBox(xs[1],null).bh/2);fAlignG(xs,nm(2),'t');ok(near(xs[0].cy-fBox(xs[0],null).bh/2,T)&&near(xs[1].cy-fBox(xs[1],null).bh/2,T),'group top');
xs=[mk(.3,.3,.2),mk(.6,.5,.3)];fAlignG(xs,nm(2),'m');ok(near(xs[0].cy,xs[1].cy),'group middle');
xs=[mk(.1,.5,.1),mk(.2,.5,.1),mk(.9,.5,.1)];ok(fDist(xs,nm(3),'h'),'dist ok');
ok(near(xs[0].cx,.1)&&near(xs[2].cx,.9)&&near(xs[1].cx,.5),'dist h: ends stay, middle centred, equal gaps');
xs=[mk(.5,.1,.1),mk(.5,.2,.1),mk(.5,.9,.1)];const g0=fBox(xs[0],null).bh;fDist(xs,nm(3),'v');const bh=g0,gap1=(xs[1].cy-bh/2)-(xs[0].cy+bh/2),gap2=(xs[2].cy-bh/2)-(xs[1].cy+bh/2);ok(near(gap1,gap2),'dist v equal gaps');
ok(!fDist([mk(.1,.5,.1),mk(.9,.5,.1)],nm(2),'h'),'dist needs 3');
xs=[mk(.5,.5,.2,45),mk(.2,.5,.2)];fAlignG(xs,[{rot:0},null],'l');ok(near(xs[0].cx-fBox(xs[0],{rot:0}).bw/2,xs[1].cx-fBox(xs[1],null).bw/2),'align uses the turned box');
console.log(bad?'FAILED':'ALL OK');process.exit(bad?1:0)
