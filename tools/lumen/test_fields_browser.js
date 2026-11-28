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
