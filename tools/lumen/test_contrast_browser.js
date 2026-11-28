/* Runs inside a headless Chromium document using the app's actual stylesheet. */
const output = document.getElementById('contrast-results');
const root = document.documentElement;
const errors = [];
const testThemes = JSON.parse(document.getElementById('contrast-themes').textContent);
const canvas = document.createElement('canvas').getContext('2d', {willReadFrequently:true});
const rgb = color => {
  canvas.clearRect(0, 0, 1, 1);
  canvas.fillStyle = '#ff00ff';
  canvas.fillStyle = color;
  canvas.fillRect(0, 0, 1, 1);
  return [...canvas.getImageData(0, 0, 1, 1).data];
};
const cssColors = css => css.match(/#[0-9a-f]{6}\b|rgba?\([^)]*\)|color\([^)]*\)/gi) || [];
const contrast = (fg, bg) => {
  const lum = c => c.slice(0,3).map(v => v/255).map(v => v <= .04045 ? v/12.92 : ((v+.055)/1.055)**2.4)
    .reduce((sum,v,i) => sum+v*[.2126,.7152,.0722][i],0);
  const a=lum(fg), b=lum(bg);
  return (Math.max(a,b)+.05)/(Math.min(a,b)+.05);
};
const stops = selector => {
  const s = getComputedStyle(document.querySelector(selector));
  return (s.backgroundImage === 'none' ? [s.backgroundColor] : cssColors(s.backgroundImage)).map(rgb);
};
const scheme = location.hash.slice(1);

const customFixtures = [
  ['custom light', 50, 8, 'light'],
  ['custom dark', 290, 8, 'dark'],
  ['custom AMOLED', 120, 4, 'amoled']
];
for (const [name, hue, tint, mode] of testThemes.map(name => [name]).concat(customFixtures)) {
  root.dataset.theme = mode ? 'custom' : name;
  if (mode) {
    root.toggleAttribute('data-cdark', mode !== 'light');
    const palette = lmFromInputs(hue,tint,mode).d;
    for (const [key,value] of Object.entries(palette)) root.style.setProperty('--'+key,value);
  }
  for (const level of ['f0','f1','f2','f3']) {
    root.dataset.glass = level;
    const css = getComputedStyle(root);
    const token = key => rgb(css.getPropertyValue('--'+key).trim());
    const minimum = /^hc[ld]$/.test(name) ? 7 : 4.5;
    if (name === 'auto' && (token('canvas')[0] < 128) !== (scheme === 'dark'))
      errors.push('Auto did not resolve to the '+scheme+' canvas');
    const fail = (selector, reason) => errors.push(name+'/'+level+' '+selector+' '+reason);
    for (const selector of ['.g1','.g2','.g3','#dlg .dcard','#sheet .card','#dock','#gal .gb','#toast','#jp','.dmn']) {
      const backgrounds = stops(selector);
      for (const bg of backgrounds) {
        if (level === 'f3' && bg[3] !== 255) fail(selector,'F3 is translucent');
        const composites = [0,255].map(backdrop => bg.slice(0,3).map(c => Math.round((c*bg[3]+backdrop*(255-bg[3]))/255)));
        if (['ink','inkMut'].some(key => composites.some(c => contrast(token(key),c) < minimum)))
          fail(selector,'glass contrast below '+minimum);
      }
      if (!backgrounds.length) fail(selector,'missing readable fill');
    }
    for (const selector of ['.row.sel','.chip.on','.seg button.on','.tbtn.fill','.tbtn.tonal','.tbtn.dngf']) {
      const element = document.querySelector(selector);
      const foreground = rgb(getComputedStyle(element).color);
      for (const bg of stops(selector)) {
        if (bg[3] !== 255 || contrast(foreground,bg) < minimum) fail(selector,'label contrast below '+minimum);
        if (selector.endsWith('.on') || selector === '.row.sel') {
          for (const key of ['canvas','surface','surfaceHi','surfaceLo']) {
            if (contrast(token(key),bg) < 1.5) fail(selector,'selection contrast below 1.5 against '+key);
          }
        }
      }
    }
  }
}
if (scheme === 'light') {
  checkLargeText(errors);
  checkSort(errors);
  checkGlass(errors);
  checkButtons(errors);
  checkFields(errors);
}
output.textContent = errors.length ? JSON.stringify(errors.slice(0,50)) : 'PASS';
