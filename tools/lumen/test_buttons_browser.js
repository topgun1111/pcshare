function checkButtons(errors) {
  const source = document.getElementById('button-state-source').textContent;
  const busy = new Function(source+';return lmButtonBusy;')();
  const fail = reason => errors.push('Shared button: '+reason);
  const root = document.documentElement;
  root.dataset.theme = 'win11light';
  const button = document.createElement('button');
  button.className = 'tbtn fill';
  button.textContent = 'Keep the action label readable';
  document.body.appendChild(button);
  const before = button.getBoundingClientRect();
  let clicks = 0;
  button.onclick = () => clicks++;
  if (!busy(button,true) || busy(button,true)) fail('busy transitions are not idempotent');
  button.click();
  const active = button.getBoundingClientRect();
  if (clicks || !button.disabled || button.getAttribute('aria-busy') !== 'true') fail('busy action can still be activated');
  if (active.width !== before.width || active.height !== before.height ||
      button.textContent !== 'Keep the action label readable') fail('loading changed the label or layout');
  if (getComputedStyle(button,'::after').animationName !== 'lmButtonWait') fail('loading progress indicator is missing');
  root.setAttribute('data-rm','');
  if (getComputedStyle(button,'::after').animationName !== 'none') fail('loading still animates with Reduce motion');
  root.removeAttribute('data-rm');
  busy(button,false);
  button.click();
  if (clicks !== 1 || button.disabled || button.hasAttribute('aria-busy')) fail('completion lost the original action');
  button.disabled = true;
  busy(button,true,false);
  busy(button,false);
  if (!button.disabled || button.hasAttribute('aria-busy')) fail('an independently disabled button was enabled');
  button.disabled = false;
  button.focus();
  if (document.activeElement !== button) fail('keyboard focus cannot reach the button');
  button.remove();
}
