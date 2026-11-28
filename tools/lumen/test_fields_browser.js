function checkFields(errors) {
  const source = document.getElementById('field-source').textContent;
  const make = (tag, cls, label) => Object.assign(document.createElement(tag), {
    className: cls || '', textContent: label || ''
  });
  const field = new Function('E', 'ic', source+';return lmField;')(make, () => make('span'));
  const fail = reason => errors.push('Shared field: '+reason);
  const original = make('input', 'tf');
  original.type = 'password';
  original.value = 'fixture';
  const container = make('div');
  container.append(original);
  document.body.append(container);
  let edits = 0;
  container.addEventListener('input', () => edits++);
  const wrapper = field(original, 'Password');
  const clear = wrapper.querySelector('.clr'), eye = wrapper.querySelector('.eye');
  if (wrapper.parentNode !== container || original.parentNode !== wrapper ||
      original.getAttribute('aria-label') !== 'Password') fail('wrapping lost the original input or name');
  for (const button of [clear, eye]) {
    button.focus();
    const bounds = button.getBoundingClientRect();
    if (document.activeElement !== button || button.tabIndex !== 0 ||
        bounds.width < 48 || bounds.height < 48) fail('field action lacks keyboard focus or touch area');
  }
  eye.click();
  if (original.type !== 'text' || eye.getAttribute('aria-pressed') !== 'true') fail('password reveal did not update its state');
  eye.click();
  if (original.type !== 'password' || eye.getAttribute('aria-label') !== 'Show password') fail('password conceal did not reset its state');
  original.readOnly = true;
  clear.click();
  if (original.value !== 'fixture' || edits || getComputedStyle(clear).display !== 'none') fail('clear modified a read-only field');
  original.readOnly = false;
  original.disabled = true;
  eye.click();
  if (original.type !== 'password' || getComputedStyle(eye).display !== 'none') fail('disabled password can be revealed');
  original.disabled = false;
  clear.click();
  if (original.value || edits !== 1 || document.activeElement !== original ||
      getComputedStyle(clear).display !== 'none') fail('clear did not restore focus and emit exactly one input event');
  container.remove();
}

function checkSegments(errors) {
  const source = document.getElementById('segment-source').textContent;
  const {segSync, segMove} = new Function(source+';return {segSync,segMove};')();
  const group = document.createElement('div');
  group.className = 'seg';
  const buttons = ['First', 'Disabled', 'Last'].map(label => {
    const button = document.createElement('button');
    button.textContent = label;
    group.append(button);
    return button;
  });
  document.body.append(group);
  const [first, disabled, last] = buttons;
  disabled.disabled = true;
  first.classList.add('on');
  let actions = 0;
  for (const button of buttons) button.onclick = () => {
    actions++;
    for (const other of buttons) other.classList.toggle('on', other === button);
    segSync(group);
  };
  group.addEventListener('keydown', segMove);
  segSync(group);
  first.focus();
  first.dispatchEvent(new KeyboardEvent('keydown', {key:'ArrowRight',bubbles:true,cancelable:true}));
  if (actions !== 1 || document.activeElement !== last || last.getAttribute('aria-pressed') !== 'true' ||
      first.getAttribute('aria-pressed') !== 'false') errors.push('Segment arrow navigation did not skip a disabled choice or update the state');
  last.dispatchEvent(new KeyboardEvent('keydown', {key:'Home',bubbles:true,cancelable:true}));
  if (actions !== 2 || document.activeElement !== first) errors.push('Segment Home did not activate the first option');
  group.remove();
}
