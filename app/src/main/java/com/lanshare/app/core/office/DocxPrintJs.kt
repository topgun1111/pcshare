package com.lanshare.app.core.office

/**
 * Part 5: the pagination script of the print-layout page ([DocxHtml.renderPrint]). Plain JS, no libraries, no network.
 * It is the ONLY script the viewer ever runs and only in print layout (OfficeViewerActivity turns JS on for that page alone).
 * No dollar signs in here (Kotlin raw string): no template literals.
 *
 * How it paginates: every flow (a run of sections with the same page geometry) is laid out once by the browser as a CSS multi-column
 * container with fixed height = text height; each column is one page, so the browser breaks lines, paragraphs and table rows itself.
 * The page count is read from the scroll width. Pages are then white sheets stacked vertically; a sheet shows its own column of a CLONE
 * of the flow (translated by -page * stride inside an overflow:hidden box). Clones exist only for sheets near the screen (IntersectionObserver).
 */
internal object DocxPrintJs {
    const val JS = """
(function(){
var root=document.documentElement,M,pagesEl,stage,pages=[],flows=[],obs=null,total=0;
function qa(s,r){return Array.prototype.slice.call((r||document).querySelectorAll(s));}
function roman(n,up){var v=[1000,900,500,400,100,90,50,40,10,9,5,4,1],s=['m','cm','d','cd','c','xc','l','xl','x','ix','v','iv','i'],o='',i;for(i=0;i<v.length;i++){while(n>=v[i]){o+=s[i];n-=v[i];}}return up?o.toUpperCase():o;}
function letters(n,up){var o='';while(n>0){n--;o=String.fromCharCode(97+n%26)+o;n=Math.floor(n/26);}return up?o.toUpperCase():o;}
function fmt(n,f){if(f==='upperRoman')return roman(n,1);if(f==='lowerRoman')return roman(n,0);if(f==='upperLetter')return letters(n,1);if(f==='lowerLetter')return letters(n,0);return String(n);}
function div(c,st){var d=document.createElement('div');if(c)d.className=c;if(st)d.style.cssText=st;return d;}
function tpl(fi,k){return stage.querySelector('.hft[data-f="'+fi+'"][data-k="'+k+'"]');}
function hf(fi,k,box,pn,fmtName){
  var t=tpl(fi,k);if(!t)return;
  var c=t.cloneNode(true);c.className='';c.removeAttribute('data-f');c.removeAttribute('data-k');
  qa('[id]',c).forEach(function(e){e.removeAttribute('id');});
  qa('.fld',c).forEach(function(e){e.textContent=e.getAttribute('data-f')==='PAGE'?fmt(pn,fmtName):String(total);});
  box.appendChild(c);
}
function layout(){
  flows=qa('.fl',stage);var n=0;
  flows.forEach(function(fl,fi){
    var g=M.f[fi];
    fl.style.cssText+=';padding:0;margin:0;max-width:none;box-sizing:content-box;background:none;width:'+g.cw+'px;height:'+g.ch+'px;column-count:'+g.cols+';column-gap:'+g.gap+'px;column-fill:auto;--ch:'+g.ch+'px';
    var stride=g.cw+g.gap;
    fl._n=Math.max(1,Math.ceil((fl.scrollWidth-1)/stride));fl._stride=stride;fl._first=n;n+=fl._n;
  });
  total=n;
}
function fill(p){
  if(p.vp.firstChild)return;
  var fl=flows[p.fi],c=fl.cloneNode(true);
  c.removeAttribute('id');qa('[id]',c).forEach(function(e){e.removeAttribute('id');});
  c.style.cssText+=';position:absolute;left:0;top:0;transform:translateX('+(-p.k*fl._stride)+'px)';
  p.vp.appendChild(c);
  try{objs(p);}catch(e){window.__plObjError=String(e&&e.stack||e);}
}
function empty(p){
  while(p.vp.firstChild)p.vp.removeChild(p.vp.firstChild);
  qa('.ob2',p.sh).forEach(function(e){e.parentNode.removeChild(e);});
}
/* Part 6: floating objects in front of / behind the text. The text carries an empty marker (.ao); the object itself sits in #objs.
   The marker's place on the sheet (measured in the live clone) gives the page and the reference point for the object's offsets. */
function objs(p){
  var sh=p.sh,g=M.f[p.fi],sr=sh.getBoundingClientRect(),vl=g.ml+g.gut,cw=(g.cw-(g.cols-1)*g.gap)/g.cols,out=[];
  qa('.ao',sh).forEach(function(m){
    var o=document.querySelector('#objs .ob[data-o="'+m.getAttribute('data-o')+'"]');if(!o)return;
    var r=m.getBoundingClientRect(),x=r.left-sr.left,y=r.top-sr.top,inVp=p.vp.contains(m);
    if(inVp&&(x<vl-1||x>vl+g.cw+1))return;
    var d=o.dataset,w=+d.w,h=+d.h,ho=+d.ho,vo=+d.vo,ci=inVp?Math.max(0,Math.min(g.cols-1,Math.floor((x-vl)/(cw+g.gap)))):0;
    var cl=vl+ci*(cw+g.gap),L,T;
    var rl=d.rh==='page'?0:(d.rh==='margin'||!inVp?vl:cl),rw=d.rh==='page'?g.pw:(d.rh==='margin'||!inVp?g.cw:cw);
    if(d.rh==='character')L=x+ho;
    else if(d.ha==='center')L=rl+(rw-w)/2;else if(d.ha==='right'||d.ha==='outside')L=rl+rw-w;else if(d.ha==='left'||d.ha==='inside')L=rl;else L=rl+ho;
    var rt=d.rv==='page'?0:(d.rv==='margin'?g.mt:y),rh=d.rv==='page'?g.ph:(d.rv==='margin'?g.ch:h);
    if(d.va==='center')T=rt+(rh-h)/2;else if(d.va==='bottom')T=rt+rh-h;else if(d.va==='top'||d.va==='inside')T=rt;else T=rt+vo;
    var c=o.cloneNode(true);c.className='ob2';c.removeAttribute('data-o');
    c.style.cssText+=';left:'+L+'px;top:'+T+'px;z-index:'+(d.b==='1'?0:1);
    out.push({c:c,b:d.b==='1',z:+d.z||0});
  });
  out.sort(function(a,b){return a.z-b.z;});
  out.forEach(function(e){if(e.b)sh.insertBefore(e.c,sh.firstChild);else sh.appendChild(e.c);});
}
function build(){
  var no=0;
  flows.forEach(function(fl,fi){
    var g=M.f[fi];
    for(var k=0;k<fl._n;k++){
      var first=(k===0);
      no=(first&&g.ns>=0)?g.ns:no+1;
      var sh=div('pg','width:'+g.pw+'px;height:'+g.ph+'px');
      var vp=div('vp','left:'+(g.ml+g.gut)+'px;top:'+g.mt+'px;width:'+g.cw+'px;height:'+g.ch+'px');
      sh.appendChild(vp);
      var useFirst=g.tp&&first,useEven=g.eo&&(no%2===0);
      var hb=div('hf','left:'+(g.ml+g.gut)+'px;top:'+g.hd+'px;width:'+g.cw+'px');
      var fb=div('hf','left:'+(g.ml+g.gut)+'px;bottom:'+g.fd+'px;width:'+g.cw+'px');
      hf(fi,useFirst?'hf':(useEven?'he':'hd'),hb,no,g.nf);
      hf(fi,useFirst?'ff':(useEven?'fe':'fd'),fb,no,g.nf);
      sh.appendChild(hb);sh.appendChild(fb);
      pagesEl.appendChild(sh);
      var p={sh:sh,vp:vp,fi:fi,k:k,no:no,idx:pages.length};sh._p=p;pages.push(p);
    }
  });
}
function watch(){
  if(!('IntersectionObserver' in window)){pages.forEach(fill);return;}
  obs=new IntersectionObserver(function(es){es.forEach(function(e){var p=e.target._p;if(e.isIntersecting)fill(p);else empty(p);});},{rootMargin:'60% 0px 60% 0px'});
  pages.forEach(function(p){obs.observe(p.sh);});
}
function pageOf(el){
  for(var fi=0;fi<flows.length;fi++){
    var fl=flows[fi];if(!fl.contains(el))continue;
    var a=el.getBoundingClientRect(),b=fl.getBoundingClientRect();
    var k=Math.max(0,Math.min(fl._n-1,Math.floor((a.left-b.left+1)/fl._stride)));
    return fl._first+k;
  }
  return -1;
}
function links(){
  pagesEl.addEventListener('click',function(ev){
    var a=ev.target.closest?ev.target.closest('a'):null;if(!a)return;
    var h=a.getAttribute('href');if(!h||h.charAt(0)!=='#')return;
    ev.preventDefault();
    var t=stage.querySelector('[id="'+h.slice(1).replace(/"/g,'')+'"]');if(!t)return;
    var i=pageOf(t);if(i>=0)pages[i].sh.scrollIntoView();
  });
}
function go(){
  try{
    M=JSON.parse(document.getElementById('meta').textContent);
    pagesEl=document.getElementById('pages');stage=document.getElementById('stage');
    root.className+=' js';
    layout();build();watch();links();
    window.__pl={pages:total,flows:flows.length,numbers:pages.map(function(p){return p.no;}),pageOf:pageOf};
    window.__plReady=true;
  }catch(e){
    root.className=root.className.replace(/ ?js/g,'');window.__plError=String(e&&e.stack||e);window.__plReady=true;
  }
}
function start(){if(document.fonts&&document.fonts.ready)document.fonts.ready.then(function(){setTimeout(go,0);});else go();}
if(document.readyState==='complete')start();else window.addEventListener('load',start);
})();
"""
}
