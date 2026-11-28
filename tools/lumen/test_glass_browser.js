/* Check the runtime glass policy and the computed effects of stacked surfaces. */
function checkGlass(errors) {
  const root = document.documentElement;
  const source = document.getElementById('glass-source').textContent;
  const fail = reason => errors.push('Glass ladder: '+reason);
  const makePick = new Function('lmHint','LD','matchMedia','CSS','document',source+';return glassPick;');
  const cases = [
    ['default', {}, {}, true, true, false, 'ember', 'f0'],
    ['light quality', {}, {ls_glassq:'light'}, true, true, false, 'ember', 'f1'],
    ['low RAM', {low:1}, {}, true, true, false, 'ember', 'f1'],
    ['saver overrides full', {saver:1}, {ls_glassq:'full'}, true, true, false, 'ember', 'f2'],
    ['old blur support', {}, {}, false, true, false, 'ember', 'f2'],
    ['old color support', {}, {}, true, false, false, 'ember', 'f3'],
    ['secure overrides full', {secure:1}, {ls_glassq:'full'}, true, true, false, 'ember', 'f3'],
    ['reduce transparency', {}, {ls_reduceT:'1'}, true, true, false, 'ember', 'f3'],
    ['high contrast preset', {}, {}, true, true, false, 'hcd', 'f3'],
    ['system high contrast', {}, {}, true, true, true, 'ember', 'f3']
  ];
  for (const [name,hint,prefs,blur,mix,hc,theme,expected] of cases) {
    const pick=makePick(()=>hint,(k,d)=>prefs[k]??d,()=>({matches:hc}),
      {supports:property=>property==='background'?mix:blur},{documentElement:{dataset:{theme}}});
    if (pick().step !== expected) fail(name+' chose '+pick().step);
  }

  // Remove Custom inline values from the palette loop before measuring the cascade.
  root.removeAttribute('style');
  root.removeAttribute('data-cdark');
  root.dataset.theme = 'vivaldi';
  root.dataset.glass = 'f0';
  const effect = selector => {
    const css = getComputedStyle(document.querySelector(selector));
    return css.backdropFilter || css.webkitBackdropFilter;
  };
  for (const selector of ['#dock','.g1','#sheet .card','#dlg .dcard']) {
    if (effect(selector) === 'none') fail(selector+' has no F0 blur');
  }
  root.classList.add('lm-sheet-open');
  if (effect('#dock') !== 'none' || effect('#sheet .card') === 'none') fail('sheet did not demote lower chrome only');
  root.classList.add('dlo');
  if (effect('#sheet .card') !== 'none' || effect('#dlg .dcard') === 'none') fail('dialog did not demote the sheet');
  root.classList.add('lm-native-modal');
  if (effect('#dlg .dcard') !== 'none') fail('native dialog left page modal blur active');
  root.classList.remove('lm-native-modal','lm-sheet-open','dlo');
  root.classList.add('lm-fling');
  if (effect('#dock') !== 'none') fail('fling did not demote the dock');
  root.classList.remove('lm-fling');
  root.dataset.glass = 'f1';
  if (!effect('.g1').includes('blur(12px)')) fail('F1 does not use the halved G1 radius');
  root.dataset.glass = 'f2';
  if (effect('.g1') !== 'none') fail('F2 still blurs');
  root.dataset.glass = 'f3';
  if (effect('.g1') !== 'none') fail('F3 still blurs');
}
