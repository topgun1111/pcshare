function checkDock(errors) {
  const source = document.getElementById('dock-source').textContent;
  const make = (tag, cls, label) => Object.assign(document.createElement(tag), {
    className: cls || '', textContent: label || ''
  });
  const {plan, render} = new Function('E', 'ic', '$', source+
    ';return {plan:lmDockPlan,render:lmRenderDock};')(make, () => make('span'), q => document.querySelector(q));
  const fail = reason => errors.push('Selection dock: '+reason);
  let selected = '';
  const action = (k, c='') => ({l:{k,l:k,c},f:()=>{selected=k}});
  const copy=action('copy'), cut=action('cut'), print=action('print'), info=action('info');
  const share=action('share'), remove=action('del','dng'), transfer=action('send'), extract=action('extract','pri');
  const set=[remove, share, info, cut, print, copy];
  const keys = seq => seq.map(o=>o.l.k).join(',');
  const regular=plan(set,[transfer],360,false);
  if(keys(regular.visible)!=='copy,cut,print'||regular.last!==remove||
    keys(regular.overflow)!=='share,info,send') fail('standard order or overflow changed');
  const narrow=plan(set,[transfer],260,true);
  if(narrow.visible.length||narrow.last!==remove||narrow.overflow.length!==6) fail('large text overflow lost actions');
  const forced=plan([remove],[transfer],640,false);
  if(forced.visible.length||forced.last!==remove||keys(forced.overflow)!=='send') fail('forced overflow duplicated Delete');
  const special=plan([copy,extract,remove],[],360,false);
  if(keys(special.visible)!=='extract,copy,del') fail('Extract lost its priority');
  if(keys(plan([print,copy,info],[],360,false,true).visible)!=='print,copy,info') fail('Print space order changed');
  const empty=plan([],[],320,false);
  if(empty.visible.length||empty.overflow.length) fail('empty selection generated actions');

  const gallery=document.getElementById('gal');
  const bar=gallery.querySelector('.gb');
  gallery.classList.add('selm');
  gallery.style.display='flex';
  bar.style.width='168px';
  render(bar,set,[transfer]);
  const buttons=Array.from(bar.querySelectorAll('button'));
  const more=buttons.find(button=>button.title==='More');
  if(buttons.length!==3||buttons.at(-1)?.title!=='del'||!more) fail('rendered slots are out of order');
  if(buttons.some(button=>button.offsetHeight<72||button.offsetWidth<48)) fail('rendered touch target is too small');
  if(more){
    more.click();
    const popup=document.querySelector('.lm-dock-menu');
    if(!popup||document.activeElement!==popup.querySelector('button')) fail('opening More did not focus the menu');
    const transferButton=Array.from(popup?.querySelectorAll('button')||[]).find(button=>button.textContent==='send');
    transferButton?.click();
    if(selected!=='send'||popup?.isConnected||document.activeElement!==more) fail('overflow lost its callback or focus return');
  }
  gallery.classList.remove('selm');
  gallery.style.removeProperty('display');
  bar.style.removeProperty('width');
  bar.textContent='';
}
