/* Uses the same Chromium document and stylesheet as the rendered contrast check. */
function checkLargeText(errors) {
  const styles = [...document.querySelectorAll('style')].map(node => node.outerHTML).join('');
  const toolbar = `
    <header id="top"><div class="bar mainb"><div class="ttl"><h1>Shared folders and files</h1></div>
      ${['Menu', 'Search', 'View options', 'Refresh', 'Split screen', 'Settings'].map(title =>
        '<button class="ibtn" aria-label="'+title+'"></button>').join('')}</div>
      <div id="toolrow"><div id="sum"><b>1,234 documents and folders</b><small>58 local folders</small></div>
      <button id="newb">Newest files</button><button id="sortb">Last modified date</button></div>
    </header>`;
  const dock = `<div id="dock"><div id="bar">
    ${['Select all items', 'Open folder', 'Copy selected', 'Share files', 'More actions'].map(title =>
      '<button class="ib"><span class="ii"></span><span>'+title+'</span></button>').join('')}
    </div></div>`;
  const settings = `<div id="sheet" style="display:flex"><div class="card">
    <div class="set"><div class="t"><b>Interface text and accessibility</b>
      <small>Choose a larger size for labels, descriptions and file names.</small>
      <select aria-label="Font"><option>Default typeface</option></select></div></div>
    <div class="dact"><button class="tbtn fill">Apply text size changes</button><button class="tbtn">Restore default settings</button></div>
    </div></div>`;
  for (const width of [320, 600, 960]) {
    const viewport = document.createElement('iframe');
    viewport.style.cssText = 'display:block;width:'+width+'px;height:560px;border:0';
    document.body.appendChild(viewport);
    const doc = viewport.contentDocument;
    doc.open();
    doc.write('<!doctype html>'+styles+'<body>'+toolbar+dock+settings+'</body>');
    doc.close();
    const root = doc.documentElement;
    const win = viewport.contentWindow;
    for (const scale of [100, 130, 160, 200]) {
      root.classList.toggle('lm-large-text', scale >= 130);
      for (let size=8;size<=40;size++) root.style.setProperty('--fs-'+size, size*scale/100+'px');
      for (const [style,size] of [['labelL',14],['labelM',12],['titleM',16],['titleL',20]]) {
        root.style.setProperty('--t-'+style+'-size', size*scale/100+'px');
      }
      const fail = reason => errors.push('Large text '+width+'px at '+scale+'%: '+reason);
      const header = doc.querySelector('#top');
      const sheet = doc.querySelector('#sheet');
      sheet.style.display = 'none';
      if (scale >= 130) {
        for (const button of header.querySelectorAll('button')) {
          const rect = button.getBoundingClientRect();
          if (rect.width <= 0 || rect.right > win.innerWidth + 1) fail(button.outerHTML+' is outside the toolbar');
        }
        const actions = doc.querySelector('#dock #bar');
        const last = actions.lastElementChild;
        last.focus();
        actions.scrollLeft = actions.scrollWidth;
        const box = actions.getBoundingClientRect();
        if (last.getBoundingClientRect().right > box.right + 1) fail('last dock action cannot be reached');
        if (last.scrollHeight > last.clientHeight + 1) fail('dock label is clipped');
      }
      sheet.style.display = 'flex';
      const card = sheet.firstElementChild;
      const save = card.querySelector('button');
      save.focus();
      if (doc.activeElement !== save || save.scrollHeight > save.clientHeight + 1) fail('settings Apply action cannot be read or focused');
      if (card.scrollWidth > card.clientWidth + 1) fail('settings card scrolls horizontally');
    }
    viewport.remove();
  }
}
