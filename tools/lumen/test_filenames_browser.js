function checkFileNames(errors) {
  const source = document.getElementById('filename-source').textContent;
  const make = (tag, cls, label) => Object.assign(document.createElement(tag), {
    className: cls || '', textContent: label || ''
  });
  const filename = new Function('E', source+';return lmFileName;')(make);
  const fail = reason => errors.push('Filename layout: '+reason);
  const list = make('div');
  list.id = 'list';
  list.style.width = '240px';
  const row = make('div', 'row');
  const caption = make('div', 'nm');
  const name = 'Very long project title '.repeat(12) + 'report.pdf';
  const label = filename(name, false);
  caption.append(label);
  row.append(caption);
  list.append(row);
  document.body.append(list);
  const stem = label.querySelector('.file-stem');
  const suffix = label.querySelector('.file-extension');
  if (label.textContent !== name || label.title !== name) fail('full file name was changed');
  if (!suffix || suffix.textContent !== '.pdf') fail('final extension was lost');
  for (const mode of ['v-compact', 'v-grid']) {
    list.className = mode;
    const style = getComputedStyle(stem);
    const inside = suffix.getBoundingClientRect().right <= label.getBoundingClientRect().right + 1;
    if (!inside || suffix.scrollWidth > suffix.clientWidth + 1) fail(mode+' clips the extension');
    if (mode === 'v-compact' && (stem.scrollWidth <= stem.clientWidth || row.offsetHeight < 48))
      fail('compact name does not truncate inside a 48 px row');
    if (mode === 'v-grid' && (style.webkitLineClamp !== '2' ||
        stem.scrollHeight <= stem.clientHeight ||
        Math.abs(stem.getBoundingClientRect().height - parseFloat(style.lineHeight)*2) > 1))
      fail('grid name does not wrap and truncate within two lines');
  }
  for (const [plain, directory] of [['.nomedia', false], ['LICENSE', false], ['trailing.', false], ['Project.2026', true]]) {
    const slot = filename(plain, directory);
    if (slot.textContent !== plain || slot.querySelector('.file-extension')) fail('split '+plain);
  }
  const badge = make('span', 'dupb', 'duplicate?');
  label.append(badge);
  if (badge.getBoundingClientRect().right > label.getBoundingClientRect().right + 1)
    fail('duplicate badge is clipped next to a standard extension');
  list.remove();
}
