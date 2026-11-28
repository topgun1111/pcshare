function checkAccessibleNavigation(errors) {
  const fail = reason => errors.push('Accessible navigation: '+reason);
  const source = id => document.getElementById(id).textContent;
  const {setup, selected} = new Function(source('row-a11y-source')+
    ';return {setup:lmRowA11y,selected:lmRowSelected};')();
  const row = document.createElement('div');
  row.className='row';
  document.body.append(row);
  let invoked=0;
  const name='Rapor — çok uzun dosya adı'.repeat(4)+'.pdf';
  setup(row,name,()=>invoked++);
  row.focus();
  if(row.getAttribute('role')!=='button'||row.getAttribute('aria-label')!==name||
      document.activeElement!==row) fail('file name or keyboard focus was lost');
  row.dispatchEvent(new KeyboardEvent('keydown',{key:'F10',shiftKey:true,repeat:true}));
  const options = new KeyboardEvent('keydown',{key:'F10',shiftKey:true,cancelable:true});
  row.dispatchEvent(options);
  if(invoked!==1||!options.defaultPrevented) fail('selection action is unavailable from the keyboard');
  row.classList.add('sel');
  selected(row,true);
  if(row.getAttribute('aria-pressed')!=='true') fail('selected state is missing');
  row.classList.remove('sel');
  selected(row,true);
  if(row.getAttribute('aria-pressed')!=='false') fail('unselected option state is missing');
  selected(row,false);
  if(row.hasAttribute('aria-pressed')) fail('browsing permanently advertises a toggle button');
  row.remove();

  const announce = new Function(source('job-a11y-source')+';return lmJobProgress;')();
  let previous = new Map(), speech = [];
  const update = (status,pct,locale='en',paused=false) => {
    previous=announce([{id:'job',state:status,pct,label:'Transfer',paused}],previous,
      (line,urgent)=>speech.push({line,urgent}),locale);
  };
  update('run',-1);
  update('run',12);
  update('run',18);
  update('run',29);
  update('run',29,'en',true);
  update('done',100,'tr');
  if(speech.map(s=>s.line).join('|')!==
      'Transfer · In progress|Transfer · 10%|Transfer · 20%|Transfer · Paused|Transfer · Tamamlandı')
    fail('progress repeats between thresholds or omits a status');
  speech=[];
  update('error',-1);
  if(speech.length!==1||!speech[0].urgent) fail('failure was announced without urgency');

  const modal = document.getElementById('dlg');
  const card = modal.querySelector('.dcard');
  const query = (selector,root=document) => root.querySelector(selector);
  const all = (selector,root=document) => Array.from(root.querySelectorAll(selector));
  const shown = el => !!el?.getClientRects().length;
  const sync = new Function('q','qa','vis','requestAnimationFrame', source('modal-focus-source')+
    ';return modalSync;')(query,all,shown,run=>run());
  const opener = document.createElement('button');
  opener.textContent='Open';
  document.body.append(opener);
  opener.focus();
  const title = document.createElement('h3');
  title.textContent='Dosyayı sil';
  const remove = document.createElement('button');
  remove.className='tbtn dng';
  remove.textContent='Sil';
  const cancel = document.createElement('button');
  cancel.className='tbtn';
  cancel.textContent='İptal';
  card.append(title,remove,cancel);
  const original = modal.style.display;
  modal.style.display='flex';
  sync(modal);
  if(card.getAttribute('role')!=='alertdialog'||card.getAttribute('aria-labelledby')!==title.id)
    fail('destructive dialog did not announce its title');
  if(document.activeElement!==cancel) fail('destructive dialog did not focus Cancel');
  modal.style.display='none';
  sync(modal);
  if(document.activeElement!==opener) fail('closing the dialog did not restore focus');
  modal.style.display=original;
  card.textContent='';
  for(const key of ['role','aria-labelledby','aria-modal','tabindex'])card.removeAttribute(key);
  opener.remove();
}


function checkDevicesPicker(errors) {
  const sheet=document.createElement('div');
  document.body.append(sheet);
  const state={dev:'local',peers:[]};
  const element=(tag,cls,text='')=>{
    const el=document.createElement(tag);
    el.className=cls;
    el.textContent=text;
    return el;
  };
  let scans=0,opened='',smb=false;
  const picker=new Function('$','E','S','devsOnly','peerSub','batTxt','connTxt','agoTxt','ic','openDev','addIp','openSmb','api','pollPeers',
    document.getElementById('devices-picker-source').textContent+
    ';return {open:devPicker,poll:ok=>updateDevicePicker?.(ok)};')(
      ()=>sheet,element,state,()=>state.peers,p=>({t:p.ok?'online':'offline'}),()=>'',p=>p.via,()=>'',()=>element('i',''),
      id=>opened=id,()=>{},()=>smb=true,()=>{scans++;return Promise.resolve()},()=>{});
  const status=()=>sheet.querySelector('[role=status]');
  picker.open(true);
  if(scans!==1||!status().textContent.includes('Searching'))errors.push('Devices: opening did not start a scan');
  picker.poll(false);
  if(!status().textContent.includes('Could not load'))errors.push('Devices: failed poll looks like an empty scan');
  picker.poll(true);
  if(!status().textContent.includes('No devices found yet'))errors.push('Devices: empty polling result did not stop searching');
  state.peers=[{id:'smb:pc',name:'Windows PC',via:'Tailscale',kind:'SMB',ok:true}];
  picker.poll(true);
  const peer=()=>sheet.querySelector('[data-peer="smb:pc"]');
  if(!status().hidden||!peer()?.textContent.includes('Windows PC'))errors.push('Devices: a late Tailscale peer is absent from the open sheet');
  peer()?.focus();
  state.peers[0].ok=false;
  picker.poll(true);
  if(document.activeElement!==peer()||!peer()?.textContent.includes('offline'))errors.push('Devices: reachability refresh lost focus or status');
  peer()?.click();
  if(opened!=='smb:pc'||sheet.style.display!=='none')errors.push('Devices: a discovered PC cannot be opened');
  picker.open(true);
  Array.from(sheet.querySelectorAll('button')).find(b=>b.textContent==='SMB Shares')?.click();
  if(!smb)errors.push('Devices: Windows setup is not reachable');
  sheet.remove();
}
