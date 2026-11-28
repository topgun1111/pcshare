function checkMotion(errors) {
  const source = document.getElementById('motion-source').textContent;
  const scope = {}, prefs = {}, root = document.documentElement;
  let local = '0', native = true, system = false, hint = 0;
  const saved = root.dataset.rm;
  const match = () => ({matches:system});
  const failure = reason => errors.push('Motion: '+reason);
  const motion = new Function('window','LSAndroid','LD','matchMedia','lmHint','LMF',
    source+';return motionApply;')(scope,prefs,()=>local,match,()=>({rm:hint}),{});
  prefs.reduceMotionGet=()=>native;
  scope.LSAndroid=prefs;
  root.classList.add('tx');
  if(!motion()||root.dataset.rm!=='1'||root.classList.contains('tx'))
    failure('app preference did not cancel the active theme transition');
  native=false;system=true;
  if(!motion()) failure('system media setting did not override the app preference');
  system=false;hint=1;
  if(!motion()) failure('native system flag did not override the app preference');
  hint=0;local='1';
  if(motion()||'rm' in root.dataset) failure('stale browser preference overrode the native preference');
  delete scope.LSAndroid;
  if(!motion()) failure('browser-only preference no longer works');
  if(saved===undefined)delete root.dataset.rm;else root.dataset.rm=saved;
}
