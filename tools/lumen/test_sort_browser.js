/* Execute the actual WebView Sort function in a short, narrow modal viewport. */
function checkSort(errors) {
  const styles = [...document.querySelectorAll('style')].map(node => node.outerHTML).join('');
  const frame = document.createElement('iframe');
  frame.style.cssText = 'display:block;width:320px;height:420px;border:0';
  document.body.appendChild(frame);
  const doc = frame.contentDocument;
  const win = frame.contentWindow;
  doc.open();
  doc.write('<!doctype html>'+styles+'<div id="dlg" style="display:none"></div>');
  doc.close();
  doc.documentElement.classList.add('lm-large-text');
  for (let size=8;size<=38;size++) doc.documentElement.style.setProperty('--fs-'+size, size*2+'px');
  const S = {sort:'type',asc:false};
  const SORTS = {
    none:{t:'No sort'}, name:{t:'File and folder name'}, size:{t:'File or folder size'},
    date:{t:'Date last modified'}, type:{t:'File extension and type'}
  };
  const changes = [];
  const env = {
    NLSH:false, S, SORTS,
    $:q=>doc.querySelector(q),
    E:(tag,cls='',text='')=>Object.assign(doc.createElement(tag),{className:cls,textContent:text}),
    requestAnimationFrame:cb=>cb(), setPref:value=>changes.push(value)
  };
  const source = document.getElementById('sort-source').textContent;
  const open = new Function(...Object.keys(env),source+';return openSort;')(...Object.values(env));
  const orders = [['none',true],['name',true],['name',false],['size',true],['size',false],
    ['date',true],['date',false],['type',true],['type',false]];
  const fail = reason => errors.push('Responsive Sort: '+reason);
  open();
  const list = doc.querySelector('.sort-choices');
  let options = [...list.querySelectorAll('[role=radio]')];
  if (options.length !== orders.length || options.slice(0,-1).some(b=>b.getAttribute('aria-checked')==='true') ||
      options.at(-1)?.getAttribute('aria-checked') !== 'true') fail('wrong choices or checked state');
  if (list.scrollHeight <= list.clientHeight || list.scrollTop === 0) fail('tall list did not scroll to the checked item');
  const cancel = doc.querySelector('.dact button');
  if (cancel.getBoundingClientRect().bottom > win.innerHeight + 1) fail('Cancel is below the viewport');
  options[0]?.focus();
  options[0]?.dispatchEvent(new win.KeyboardEvent('keydown',{key:'End',bubbles:true}));
  if (doc.activeElement !== options.at(-1)) fail('End did not focus the last option');
  cancel.click();
  if (changes.length || doc.getElementById('dlg').style.display !== 'none') fail('Cancel applied a sort or left the dialog open');
  orders.forEach(([sort,asc],i)=>{
    open();
    options = [...doc.querySelectorAll('.sort-choices [role=radio]')];
    options[i]?.click();
    const received = changes[i];
    if (received?.sort !== sort || received?.asc !== asc) fail('wrong callback at option '+i);
  });
  frame.remove();
}
