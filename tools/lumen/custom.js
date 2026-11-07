/* Lumen Part 17: Custom theme (spec 14.2). Source: tools/lumen/custom.js (gen.py copies it into ui.html; never edit it there).
   lmH2r..lmFromInputs are a line-by-line port of tools/lumen/derive.py (tools/lumen/parity.py checks both give the same hex).
   Only function declarations, so the call right after the S.theme line works before this block is reached. */
function lmH2r(h){h=h.replace('#','');if(h.length===3||h.length===4)h=h.replace(/./g,'$&$&');return[0,2,4].map(function(i){return parseInt(h.substr(i,2),16)})}
function lmR2h(r){return'#'+r.map(function(v){return('0'+Math.max(0,Math.min(255,Math.floor(v+0.5))).toString(16)).slice(-2)}).join('')}
function lmMod(a,b){var r=a%b;if(r!==0&&(r<0)!==(b<0))r+=b;return r}   /* same as Python's float % (keeps the last bit equal to derive.py) */
function lmV(m1,m2,h){h=lmMod(h,1);if(h<1/6)return m1+(m2-m1)*h*6;if(h<0.5)return m2;if(h<2/3)return m1+(m2-m1)*(2/3-h)*6;return m1}
function lmHsl(h,s,l){l=Math.max(0,Math.min(1,l));s=Math.max(0,Math.min(1,s));h=lmMod(h,360)/360;var r,g,b;
  if(s===0){r=g=b=l}else{var m2=l<=0.5?l*(1+s):l+s-l*s,m1=2*l-m2;r=lmV(m1,m2,h+1/3);g=lmV(m1,m2,h);b=lmV(m1,m2,h-1/3)}
  return lmR2h([r*255,g*255,b*255])}
function lmToHsl(x){var c=lmH2r(x).map(function(v){return v/255}),r=c[0],g=c[1],b=c[2],mx=Math.max(r,g,b),mn=Math.min(r,g,b),sm=mx+mn,rg=mx-mn,l=sm/2,h,s;
  if(mn===mx)return[0,0,l];s=l<=0.5?rg/sm:rg/(2-mx-mn);var rc=(mx-r)/rg,gc=(mx-g)/rg,bc=(mx-b)/rg;
  if(r===mx)h=bc-gc;else if(g===mx)h=2+rc-bc;else h=4+gc-rc;return[lmMod(h/6,1)*360,s,l]}
function lmMix(a,b,t){var x=lmH2r(a),y=lmH2r(b);return lmR2h([0,1,2].map(function(i){return x[i]+(y[i]-x[i])*t}))}
function lmLin(c){c/=255;return c<=0.03928?c/12.92:Math.pow((c+0.055)/1.055,2.4)}
function lmLum(x){var c=lmH2r(x);return 0.2126*lmLin(c[0])+0.7152*lmLin(c[1])+0.0722*lmLin(c[2])}
function lmCr(a,b){var x=lmLum(a),y=lmLum(b);if(x<y){var t=x;x=y;y=t}return(x+0.05)/(y+0.05)}
function lmDarkCanvas(x){var c=lmH2r(x);return 0.2126*(c[0]/255)+0.7152*(c[1]/255)+0.0722*(c[2]/255)<0.4}
function lmSnap(fg,bgs,min,darker){var ok=function(c){return bgs.every(function(b){return lmCr(c,b)>=min})};if(ok(fg))return[fg,false];
  var p=lmToHsl(fg),h=p[0],s=p[1],l=p[2];for(var i=0;i<100;i++){l+=darker?-0.01:0.01;var c=lmHsl(h,s,l);if(ok(c))return[c,true];if(l<=0||l>=1)break}
  return[darker?'#000000':'#ffffff',true]}
function lmGradOk(p,lab){return[lmMix(p,'#ffffff',0.06),lmMix(p,'#000000',0.12)].every(function(e){return lmCr(lab,e)>=4.5})}
function lmSnapGrad(fg,lab,darker){if(lmGradOk(fg,lab))return[fg,false];var q=lmToHsl(fg),h=q[0],s=q[1],l=q[2];
  for(var i=0;i<100;i++){l+=darker?-0.01:0.01;var c=lmHsl(h,s,l);if(lmGradOk(c,lab))return[c,true];if(l<=0||l>=1)break}
  return[fg,false]}
function lmGradcOk(pc,p,lab){return[pc,lmMix(pc,p,0.10)].every(function(e){return lmCr(lab,e)>=4.5})}
function lmSnapGradc(pc,p,lab,darker){if(lmGradcOk(pc,p,lab))return[pc,false];var q=lmToHsl(pc),h=q[0],s=q[1],l=q[2];
  for(var i=0;i<100;i++){l+=darker?-0.01:0.01;var c=lmHsl(h,s,l);if(lmGradcOk(c,p,lab))return[c,true];if(l<=0||l>=1)break}
  return[pc,false]}
function lmNeutral(hue,tint,l){var k=1-Math.abs(2*l-1),s=k<1e-6?0:Math.min(1,(tint/100)/k);return lmHsl(hue,s,l)}
function lmHexa(h,a){return h+('0'+Math.floor(a*255+0.5).toString(16)).slice(-2)}
function lmFresh(d){var dark=lmDarkCanvas(d.canvas),p=lmToHsl(d.primary),sat=p[1]<0.1?0.08:0.28,L=dark?0.78:0.40,o={};
  o.surfaceLo=dark?lmMix(d.canvas,'#000000',0.35):lmMix(d.canvas,d.ink,0.06);
  o.edgeHi=dark?'#ffffff29':'#ffffffb3';
  o.inkDim=lmSnap(lmMix(d.inkMut,d.canvas,0.45),[d.canvas],3.0,!dark)[0];
  o.secondary=lmSnap(lmHsl(p[0]+40,sat,L),[d.canvas,d.surface],3.0,!dark)[0];
  o.tertiary=lmSnap(lmHsl(p[0]+150,sat,L),[d.canvas,d.surface],3.0,!dark)[0];
  o.scrim=dark?'#0000008f':lmHexa(d.ink,0.32);
  o.glassTint=dark?lmMix(d.canvas,d.surface,0.5):d.canvas;
  var f=dark?'#8ab4ff':'#0b5fff',cs=[f,'#0b5fff','#8ab4ff','#ffb000','#ffffff','#000000'];
  for(var i=0;i<cs.length;i++){if(lmCr(cs[i],d.canvas)>=3&&lmCr(cs[i],d.surface)>=3){f=cs[i];break}}
  var w=d.warning||'#f9ab00';o.onWarning=lmCr('#1f1400',w)>=lmCr('#ffffff',w)?'#1f1400':'#ffffff';
  o.shadowTint=lmMix(d.primary,'#000000',0.65);
  o.focus=f;return o}
/* function, not var: the boot call runs before a later `var` line would have been assigned */
function lmSem(dark){return dark?{errorCont:'#8c1d18',onErrorCont:'#f9dedc',error:'#f2b8b5',success:'#7fd99a',warning:'#ffc15e'}:{errorCont:'#f9dedc',onErrorCont:'#410e0b',error:'#b3261e',success:'#1e8e3e',warning:'#f9ab00'}}
function lmFromInputs(hue,tint,mode){hue=lmMod(+hue,360);tint=Math.max(0,Math.min(8,+tint));var dark=mode==='dark'||mode==='amoled',
  N=function(l){return lmNeutral(hue,tint,l)},A=function(l){return lmHsl(hue,0.9,l)},sn=false,d={},t;
  if(!dark){d.canvas=N(0.975);d.surface='#ffffff';d.surfaceHi=N(0.94);d.surfaceLo=N(0.91);d.edge=N(0.88);d.ink=N(0.10)}
  else{d.canvas=mode==='amoled'?'#000000':N(0.075);d.surface=mode==='amoled'?N(0.04):N(0.11);d.surfaceHi=mode==='amoled'?N(0.10):N(0.15);
    d.surfaceLo=mode==='amoled'?'#000000':N(0.045);d.edge=N(0.21);d.ink=N(0.92)}
  var bgs=[d.canvas,d.surface,d.surfaceHi];
  t=lmSnap(!dark?N(0.33):N(0.78),bgs,4.5,!dark);d.inkMut=t[0];sn=sn||t[1];
  t=lmSnap(!dark?N(0.52):N(0.55),[d.canvas],3.0,!dark);d.inkDim=t[0];sn=sn||t[1];
  if(!dark){d.onPrimary='#ffffff';t=lmSnap(A(0.38),[d.onPrimary],4.5,true);d.primary=t[0];sn=sn||t[1];d.primaryCont=A(0.88);
    t=lmSnap(A(0.10),[d.primaryCont],4.5,true);d.onPrimaryCont=t[0];sn=sn||t[1]}
  else{d.onPrimary=A(0.12);t=lmSnap(A(0.77),[d.onPrimary],4.5,false);d.primary=t[0];sn=sn||t[1];d.onPrimaryCont=A(0.90);
    t=lmSnap(A(0.26),[d.onPrimaryCont],4.5,true);d.primaryCont=t[0];sn=sn||t[1]}
  t=lmSnap(d.primary,[d.canvas],3.0,!dark);d.primary=t[0];sn=sn||t[1];
  t=lmSnapGrad(d.primary,d.onPrimary,!dark);d.primary=t[0];
  t=lmSnapGradc(d.primaryCont,d.primary,d.onPrimaryCont,dark);d.primaryCont=t[0];
  if(lmCr(d.onPrimary,d.primary)<4.5)d.onPrimary=lmLum(d.primary)<0.18?'#ffffff':'#000000';
  var sem=lmSem(dark);for(var k in sem)d[k]=sem[k];
  var fr=lmFresh(d);for(var k2 in fr)d[k2]=fr[k2];
  d.scrim=dark?'#0000008f':lmHexa(N(0.06),0.32);
  return{d:d,sn:sn}}
/* vars the 42 presets declare by hand and the slots do not cover: FAB, bars, file-type colours, home tiles (Custom derives them) */
function lmCustExtras(d,hue,dark){var o={},K={img:340,vid:265,aud:205,pdf:5,zip:45,apk:95,doc:30};
  o.fab=d.primaryCont;o.onfab=d.onPrimaryCont;o.hov=dark?'#ffffff14':'#0000000f';
  o.sh=dark?'0 1px 3px #000a,0 4px 8px 3px #0006':'0 1px 3px #0000004d,0 4px 8px 3px #00000026';
  if(dark){o.bar=d.canvas;o.onbar=d.ink;o.barmut=d.inkMut}else{o.bar=lmMix(d.primary,'#000000',0.8);o.onbar='#ffffff';o.barmut=lmMix(o.bar,'#ffffff',0.78)}
  o['k-folder-c']=d.primary;o['k-folder-b']=d.primaryCont;o['k-file-c']=d.inkMut;o['k-file-b']=d.surfaceHi;
  Object.keys(K).forEach(function(n){var b=dark?lmHsl(K[n],0.40,0.22):lmHsl(K[n],0.70,0.90);
    o['k-'+n+'-b']=b;o['k-'+n+'-c']=lmSnap(dark?lmHsl(K[n],0.70,0.80):lmHsl(K[n],0.60,0.30),[b],4.5,!dark)[0]});
  o.tl=d.primary;
  [0,60,120,180,240,300].forEach(function(a,i){o['chc'+(i+1)]=lmSnap(lmHsl(hue+a,0.65,dark?0.72:0.40),[d.canvas],3.0,!dark)[0]});
  return o}
function lmCustGet(){var o={};try{o=JSON.parse(LD('ls_cust','{}'))||{}}catch(e){}
  return{h:isFinite(o.h)?+o.h:24,t:isFinite(o.t)?+o.t:4,m:(o.m==='dark'||o.m==='amoled'||o.m==='auto')?o.m:'light',w:o.w===0?0:1}}
function lmCustClear(){var el=document.documentElement,s=el.style;(window.__lmCV||[]).forEach(function(k){s.removeProperty(k)});window.__lmCV=[];
  s.removeProperty('color-scheme');delete el.dataset.cdark;delete el.dataset.cwall}
/* paints the Custom theme as inline vars on <html> (inline beats every stylesheet rule); returns true when some tone had to move to pass contrast */
function lmCustApply(){var c=lmCustGet(),cm=lmCustMode(c),r=lmFromInputs(c.h,c.t,cm),dark=cm!=='light',el=document.documentElement,s=el.style,all=Object.assign({},r.d,lmCustExtras(r.d,c.h,dark));
  lmCustClear();window.__lmCV=[];Object.keys(all).forEach(function(k){s.setProperty('--'+k,all[k]);window.__lmCV.push('--'+k)});
  s.setProperty('color-scheme',dark?'dark':'light');if(dark)el.dataset.cdark='1';if(!c.w||cm==='amoled')el.dataset.cwall='0';
  return r.sn}
function lmThemeSet(id){lmThemeFx();S.theme=id;SV('ls_theme',id);lmThemeApply(1)}
/* ---- Part 19 T2: Light / Dark pairs with a separate Auto / Light / Dark choice (ls_tmode: theme | auto | light | dark; theme = the chosen preset as it is) ---- */
function lmPairs(){return /*PAIRS*/[]}
function lmPair(id){if(id==='auto')id='light';var p=lmPairs();for(var i=0;i<p.length;i++)if(p[i][0]===id||p[i][1]===id)return p[i];return null}
function lmSysDark(){try{return matchMedia('(prefers-color-scheme: dark)').matches}catch(e){return false}}
function lmMode(){var m=LD('ls_tmode','theme');return(m==='auto'||m==='light'||m==='dark')?m:'theme'}
/* the data-theme to paint: the chosen preset, or its Light / Dark partner when a mode is set; presets without a partner and Custom stay as they are */
function lmResolve(id){var m=lmMode();if(m==='theme'||id==='custom')return id;var p=lmPair(id);if(!p)return id;
  return m==='light'?p[0]:m==='dark'?p[1]:(lmSysDark()?p[1]:p[0])}
/* Custom: its own Light / Dark / AMOLED / Auto chip, overridden by the global mode when that is set */
function lmCustMode(c){var tm=lmMode(),m=c.m;if(tm==='light')return'light';if(tm==='dark')return m==='amoled'?'amoled':'dark';
  if(tm==='auto'||m==='auto')return lmSysDark()?(m==='amoled'?'amoled':'dark'):'light';return m}
function lmOsFont(){var el=document.documentElement;if(LD('ls_osfont','1')==='0')el.dataset.osfont='0';else delete el.dataset.osfont}
function lmThemeApply(nat){var el=document.documentElement;lmCustClear();el.dataset.theme=lmResolve(S.theme);if(S.theme==='custom')lmCustApply();lmOsFont();if(nat){try{glassApply()}catch(e){}nlTheme()}}
function lmModeSet(m){lmThemeFx();SV('ls_tmode',m);lmThemeApply(1)}
try{matchMedia('(prefers-color-scheme: dark)').addEventListener('change',function(){if(typeof S!=='undefined'&&S.theme&&(lmMode()==='auto'||(S.theme==='custom'&&lmCustGet().m==='auto')))lmThemeApply(1)})}catch(e){}
