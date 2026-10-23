// Part 14c: runs the REAL ui.html helpers (fClamp, fHit, fSpot, fPack) on JSON cases; used by psclamp_14c_mirror_test.py
const fs=require('fs'),path=require('path');
const h=fs.readFileSync(path.join(__dirname,'..','app/src/main/assets/ui.html'),'utf8');
const a=h.indexOf('const fBox='),a2=h.indexOf(',   // turned bounding box'),b=h.indexOf('const fSafePt='),c=h.indexOf('/* Part 12b: Fit width'),k0=h.indexOf('const fKeepOn='),k1=h.indexOf('    fSides=');
if(a<0||a2<0||b<0||c<0||k0<0||k1<0){console.error('helpers not found');process.exit(1)}
const src=h.slice(a,a2)+';\n'+h.slice(b,c).replace(/;\s*$/,'')+';\n'+h.slice(k0,k1).replace(/,\s*$/,'')+';\nreturn{fBox,fSafeR,fClamp,fHit,fSpot,fPack}';
const inp=JSON.parse(fs.readFileSync(0,'utf8'));
const LY={sw:inp.sw,sh:inp.sh},SP={safe:inp.safe,free:true,keep:true,keepGap:inp.gap};
const ROT={};inp.pics.forEach((p,i)=>ROT['p'+i]={rot:p.rot});
const pic=id=>ROT[id]||null,E=()=>({style:{},append(){},dataset:{}});
const M=new Function('LY','SP','E','FEL','FRL','pic',src)(LY,SP,E,[],[],pic);
const W=inp.sw,H=inp.sh,R=M.fSafeR(inp.safe);
const mk=f=>({id:'p'+f.pic,cx:f.cx,cy:f.cy,w:f.w,rot:f.rot,a:inp.pics[f.pic].h/inp.pics[f.pic].w});
const out=inp.cases.map(cs=>{
  if(cs.t==='clamp'){const x=M.fClamp(mk(cs.f),pic('p'+cs.f.pic),R);return{cx:x.cx,cy:x.cy,w:x.w}}
  if(cs.t==='hit'){const f=mk(cs.f),g=mk(cs.g);return{hit:M.fHit(f,pic(f.id),g,pic(g.id),W,H,inp.gap)}}
  if(cs.t==='spot'){const L=cs.others.map(mk),f=mk(cs.f),r=M.fSpot(L,f,pic(f.id),R,W,H,inp.gap,cs.tx,cs.ty);return r?{cx:r.cx,cy:r.cy,w:r.w}:null}
  if(cs.t==='pack'){const L=cs.items.map(mk),r=M.fPack(L,L.map(f=>pic(f.id)),R,W,H,inp.gap);return r}
});
process.stdout.write(JSON.stringify(out));
