#!/usr/bin/env python3
"""Lumen T2 (token lint) + T3 (dialog audit) report for ui.html. Usage: python3 tools/lumen/lint.py [--max-raw N]
Counts raw values in the page CSS outside the generated /*LUMEN:BEGIN*/ block and the theme-flavour rules.
Report only: exit 1 only when native alert / confirm / prompt calls exist (T3) or --max-raw is exceeded."""
import re,sys,os
P=os.path.join(os.path.dirname(__file__),'..','..','app','src','main','assets','ui.html')
s=open(P,encoding='utf-8').read()
s=re.sub(r'/\*LUMEN:BEGIN\*/.*?/\*LUMEN:END\*/','',s,flags=re.S)
def strip_fn(x):
    for _ in range(4):x=re.sub(r'(?:calc|env|var|min|max|clamp)\([^()]*\)','',x)
    return x
css=''.join(re.findall(r'<style[^>]*>(.*?)</style>',s,re.S))
css=re.sub(r'/\*.*?\*/','',css,flags=re.S)
css=re.sub(r'[^{}]*\[data-theme[^{]*\{[^}]*\}','',css)          # per-theme flavour rules keep their own look
rep={}
rep['radius px (not var)']=len(re.findall(r'border-radius:\s*(?!var\(|0\b|inherit)[^;}]*\d+(?:px|%)',css))
rep['spacing px >=3 (not var/calc)']=len(re.findall(r'(?:padding|margin|gap)[a-z-]*:\s*[^;}]*\b(?:[3-9]|\d\d+)px',strip_fn(css)))
rep['font-size px (not var)']=len(re.findall(r'font-size:\s*\d+(?:\.\d+)?px',css))
rep['duration ms/s (not var)']=len(re.findall(r'(?:transition|animation)[a-z-]*:[^;}]*\b\d*\.?\d+m?s\b',re.sub(r'var\([^)]*\)','',css)))
rep['hex colours']=len(re.findall(r'#[0-9a-fA-F]{3,8}\b',css))
js=''.join(re.findall(r'<script(?![^>]*src)[^>]*>(.*?)</script>',s,re.S))
nat={k:len(re.findall(r'(?<![\w.$])'+k+r'\(',js)) for k in('alert','confirm','prompt')}
print('T2 raw values in page CSS (lower is better; per-theme rules excluded):')
for k,v in rep.items():print(f'  {k:34s}{v:6d}')
print('T3 dialogs: dlg() call sites',len(re.findall(r'\bdlg\(',js)),'| native',nat)
bad=sum(nat.values())
mx=int(sys.argv[sys.argv.index('--max-raw')+1]) if '--max-raw' in sys.argv else None
if mx is not None and sum(rep.values())>mx:print('over --max-raw');bad+=1
# A3: static icon-only <button data-i=..> with no aria-label / title and no entry in the LB name table
_lb=set(re.findall(r"(\w+):'",(re.search(r'const LB=\{(.*?)\};',s,re.S) or [0,''])[1]))
_un=[]
for _a in re.findall(r'<button\b([^>]*)>\s*</button>',s):
    _m=re.search(r'data-i=["\']?(\w+)',_a)
    if _m and not re.search(r'aria-label|title=',_a) and _m.group(1) not in _lb:_un.append(_m.group(1))
print('A3 icon-only buttons without a name (add to LB):',len(_un),sorted(set(_un)))

# A8: every page dlg({...}) must end up with a hero path: kind / hero given, an input prompt (no hero by design), or danger + delete-like words (auto trash)
_raw=open(P,encoding='utf-8').read()
_dl=[]
for _m in re.finditer(r'dlg\(\{',_raw):
    _j=_m.end()-1;_d=0
    while True:
        _c=_raw[_j]
        if _c=='{':_d+=1
        elif _c=='}':
            _d-=1
            if _d==0:break
        _j+=1
    _b=_raw[_m.start():_j+1];_dl.append(_b)
def _ok(b):
    if re.search(r'\b(kind|hero|input)\s*:',b):return True
    return bool(re.search(r'\bdanger\s*:',b) and re.search(r'delete|remove|erase',b,re.I))
_bad=[re.sub(r'\s+',' ',b)[:70] for b in _dl if not _ok(b)]
print('A8 page dlg() with a kind / hero / input / auto-trash:',len(_dl)-len(_bad),'of',len(_dl),'| without:',len(_bad),_bad)
# A8 native: AppPal.dlg() chains that are plain notices (message, no list / view / progress) must pass kind()
_nat=os.path.join(os.path.dirname(__file__),'..','..','app','src','main','java','com','lanshare','app')
_nb=[]
for _f in sorted(os.listdir(_nat)):
    if not _f.endswith('.kt') or _f=='AppPal.kt':continue
    _L=open(os.path.join(_nat,_f),encoding='utf-8').read().split('\n')
    for _i,_l in enumerate(_L):
        if 'AppPal.dlg(' in _l:
            _ch=_l;_k=_i
            while 'show()' not in _ch and 'create()' not in _ch and _k<_i+8:_k+=1;_ch+=_L[_k]
            if '.kind(' not in _ch and re.search(r'setMessage\(',_ch) and not re.search(r'setItems|setSingleChoice|setMultiChoice|setView|setCancelable\(false\)',_ch):_nb.append(f'{_f}:{_i+1}')
print('A8 native notice dialogs without kind():',len(_nb),_nb)
# V5: every dark preset (canvas luminance < .18) must sit in the static dark selector lists UI4 / UI5 / UI6
import json
_t=json.load(open(os.path.join(os.path.dirname(__file__),'tokens.json')))
def _lum(h):
    h=h.lstrip('#');r,g,b=[int(h[i:i+2],16)/255 for i in(0,2,4)];f=lambda c:c/12.92 if c<=.03928 else((c+.055)/1.055)**2.4
    return .2126*f(r)+.7152*f(g)+.0722*f(b)
_dk={k for k,v in _t['themes'].items() if _lum(v['canvas'])<.18}
for _n in('UI4','UI5','UI6'):
    _a=_raw.index(f'/*LUMEN-{_n}:BEGIN*/');_z=_raw.index(f'/*LUMEN-{_n}:END*/')
    _best=max((set(re.findall(r'data-theme=([a-z0-9]+)',x)) for x in re.findall(r'([^{}]*?)\{',_raw[_a:_z]) if 'data-theme=' in x),key=len)
    print(f'V5 {_n} dark presets missing from the selector list:',sorted(_dk-_best))
# P8 native: TextView sizes set by hand (should be 0; use LumenTypography.fit / snap). Paint / canvas text and user-sized editor text are excluded.
_hand=[]
for _f in sorted(os.listdir(_nat)):
    if not _f.endswith('.kt') or _f=='LumenTokens.kt':continue
    for _i,_l in enumerate(open(os.path.join(_nat,_f),encoding='utf-8').read().split('\n')):
        if re.search(r'(?<![\w.])(?:\w+\.)?textSize\s*=\s*[0-9.]+f(?!\s*\*)|setTextSize\(\s*(?:android\.util\.)?TypedValue\.COMPLEX_UNIT_SP\s*,\s*[0-9.]+f',_l) and 'Paint' not in _l and 'sizeSp' not in _l:_hand.append(f'{_f}:{_i+1}')
print('P8 native TextViews with a hand-set literal size:',len(_hand),_hand)

# A9: semantic dialog action hierarchy. Alternative-danger styling must never promote the primary action to danger;
# notice acknowledgements must have a quiet-action classifier in both implementations; Vault must not bypass AppPal.
_sem=[]
if not re.search(r'const dng=!!o\.danger\s*;',_raw):_sem.append('HTML primary danger must depend only on o.danger')
if re.search(r'const dng=!!\(o\.danger\s*\|\|\s*o\.altDanger\)',_raw):_sem.append('HTML altDanger must not affect primary danger')
if not re.search(r'function lmDismissAction\(',_raw):_sem.append('HTML dismiss-action classifier missing')
_lm=open(os.path.join(_nat,'Lm.kt'),encoding='utf-8').read()
if not re.search(r'private fun isDismissAction\(',_lm) or not re.search(r'isDismissAction\(pos\.text',_lm):_sem.append('Native dismiss-action classifier missing or unused')
for _f in sorted(os.listdir(_nat)):
    if not _f.endswith('.kt') or _f=='AppPal.kt':continue
    _src=open(os.path.join(_nat,_f),encoding='utf-8').read()
    if re.search(r'AlertDialog\.Builder\s*\(',_src):_sem.append(f'{_f}: raw AlertDialog.Builder bypasses AppPal')
_app_pal=open(os.path.join(_nat,'AppPal.kt'),encoding='utf-8').read()
if 'LumenGlass.dialogFill(d.window, c, secure)' not in _app_pal:_sem.append('Shared native dialog glass is not connected')
_nl_sheets=open(os.path.join(_nat,'NlSheets.kt'),encoding='utf-8').read()
if any(marker not in _app_pal for marker in ('flags and WindowManager.LayoutParams.FLAG_SECURE != 0',
        'if (secure) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)', 'prepareDialogWindow(d.window, c)')):
    _sem.append('Secure host flag not inherited by shared dialogs')
if any(marker not in _nl_sheets for marker in ('AppPal.prepareDialogWindow(dl.window, act)',
        'LumenGlass.dialogFill(dl.window, act, secure)')):
    _sem.append('Native sheets bypass the secure dialog policy')
if 'if (secure) null else diagnostic' not in _app_pal:_sem.append('Secure diagnostics not suppressed')
if "o.altDanger?' alt-danger'" not in _raw:_sem.append('HTML destructive alternative is not quiet error text')
if 'dismissPrimary -> styleButton(pos, 0, quiet, c, backdrop = dialogFill)' not in _lm:_sem.append('Native dismissal is not a quiet action')
if 'kind("update")' not in open(os.path.join(_nat,'UpdateCheck.kt'),encoding='utf-8').read():_sem.append('Update hero not selected')
print('A9 semantic dialog rules:', 'PASS' if not _sem else 'FAIL '+ '; '.join(_sem))

# D1: ONE light / dark rule everywhere (gamma-space canvas luma < 0.4: ui.html lmDarkCanvas, derive.is_dark, AppPal.isDark).
# tokens.json darkThemes must equal the presets that rule calls dark; every dark preset needs color-scheme:dark and the picker flag "d":1;
# native code must not decide light / dark with calculateLuminance(..) vs 0.5 (linear light disagrees with the page on mid-tone canvases).
import json as _json,derive as _dv
_T=_json.load(open(os.path.join(os.path.dirname(__file__),'tokens.json'),encoding='utf-8'))
_dk=sorted(n for n,v in _T['themes'].items() if _dv.is_dark(v['canvas']))
_dmis=[]
if sorted(_T.get('darkThemes',[]))!=_dk:_dmis.append('tokens.json darkThemes != computed '+str(sorted(set(_dk)^set(_T.get('darkThemes',[])))))
_cs={}
for _m in re.finditer(r'([^{}]*)\{([^{}]*)\}',_raw):
    if 'color-scheme:dark' in _m.group(2):
        for _n in re.findall(r'data-theme=([a-z0-9]+)\]',_m.group(1)):_cs[_n]=1
_miss=[n for n in _dk if n not in _cs and n!='dark']
if _miss:_dmis.append('dark presets without color-scheme:dark: '+str(_miss))
_pk=dict((a,b) for a,b in re.findall(r'\{"id":"([a-z0-9]+)","n":"[^"]+",(?:"d":(\d),)?"sw"',_raw) and [(x[0],x[1] or '0') for x in re.findall(r'\{"id":"([a-z0-9]+)","n":"[^"]+",(?:"d":(\d),)?"sw"',_raw)])
_pbad=[n for n,d in _pk.items() if n in _T['themes'] and (d=='1')!=(n in _dk)]
if _pbad:_dmis.append('picker d flag disagrees: '+str(_pbad))
for _f in sorted(os.listdir(_nat)):
    if not _f.endswith('.kt'):continue
    for _i,_l in enumerate(open(os.path.join(_nat,_f),encoding='utf-8').read().split('\n')):
        if re.search(r'calculateLuminance\([^)]*\)\s*[<>]=?\s*0?\.5',_l):_dmis.append(f'{_f}:{_i+1} light/dark by linear luminance vs 0.5 (use AppPal.isDark / isLight)')
print('D1 one light/dark rule:', 'PASS' if not _dmis else 'FAIL '+'; '.join(_dmis))

_x=[b for b in (_bad,_nb,_sem,_dmis) if b]
if '--theme-consistency-regression' in sys.argv:
    errs=[]
    checks={
        'LumenNativeTheme.kt':('fun preserveFeatureColors(', 'if (featureColors.containsKey(view)) return', 'fun readableInk('),
        'TextEditorActivity.kt':('LumenNativeTheme.preserveFeatureColors(root)', 'LumenNativeTheme.readableInk(lmE.ink, bg)'),
        'StorageActivity.kt':('LumenNativeTheme.preserveFeatureColors(b)', 'LumenNativeTheme.readableInk(if (on)', 'minimumHeight = dp(48)'),
        'PrintLogActivity.kt':('LumenButtons.Role.OUTLINE', 'isFillViewport = true; addView(bar)'),
        'ImageEditActivity.kt':('minimumHeight = dp(48)', 'LumenButtons.Role.PRIMARY'),
        'PdfViewerActivity.kt':('minimumHeight = dp(48)', 'LumenButtons.Role.PRIMARY', 'LumenButtons.Role.TEXT'),
        'UsActivity.kt':('LumenNativeTheme.preserveFeatureColors(c)', 'c.isSelected = on', 'minimumHeight = dp(72f)', 'layoutActions(right - left)', 'overflow[index].run()'),
        'OfficeViewerActivity.kt':('glyphBtn("search", "Find")', 'glyphBtn("back", "Back")'),
        'VaultViewActivity.kt':('Math.round(16 * d)', 'setTextIsSelectable(false)'),
        'VaultText.kt':('LumenNativeTheme.bars(this, pal.bg)', 'IME_FLAG_NO_PERSONALIZED_LEARNING', 'customSelectionActionModeCallback = none')
    }
    for name,markers in checks.items():
        source=open(os.path.join(_nat,name),encoding='utf-8').read()
        for marker in markers:
            if marker not in source:errs.append(name+': '+marker)
    for marker in ("cb.setAttribute('role','switch')", "cb.setAttribute('aria-labelledby',id)", "h.setAttribute('role','heading')"):
        if marker not in _raw:errs.append('Settings accessibility: '+marker)
    print('Theme consistency regression:', 'PASS' if not errs else 'FAIL '+'; '.join(errs))
    sys.exit(1 if errs else 0)
if '--performance-regression' in sys.argv:
    errs=[]
    for marker in ("h.saver===1)return{step:'f2'",'const endFling=','lm-fling:is([data-glass=f0],[data-glass=f1])'):
        if marker not in _raw:errs.append('Missing web performance invariant: '+marker)
    if ',lmfrost var(' in _raw:errs.append('Animated blur kernel reintroduced')
    checks={'NlBackdrop.kt':('scrolling[target.rootView] == true','fun setScrolling','effectMaterial[target] != material','!LumenPrefs.saver(c)','postInvalidateOnAnimation()'),'NativeList.kt':('override fun onScrollStateChanged','SCROLL_STATE_IDLE','NlBackdrop.setScrolling(rv, false)'),'NlChrome.kt':('NlBackdrop.unregister(dock)','postInvalidateDelayed(33L)'),'PlayerWidgets.kt':('shaderPal !== pal','if (field != v)'),'PlayerActivity.kt':('override fun onBoundsChange','paint.shader = shader'),'NlListFx.kt':('postInvalidateDelayed(33L)','windowVisibility == View.VISIBLE')}
    for name,markers in checks.items():
        source=open(os.path.join(_nat,name),encoding='utf-8').read()
        for marker in markers:
            if marker not in source:errs.append(name+': '+marker)
    print('Performance regression:', 'PASS' if not errs else 'FAIL '+'; '.join(errs))
    sys.exit(1 if errs else 0)
if '--accessibility-regression' in sys.argv:
    errs=[]
    for marker in ('LUMEN-A11Y-MODALS','const modalState=new WeakMap()','const topModal=','aria-labelledby','lm-large-text','uiEffectiveText()','tx0,200','const sayTimers=[0,0]'):
        if marker not in _raw:errs.append('Missing web accessibility invariant: '+marker)
    checks={'UiScale.kt':('MAX_TEXT = 200','fun effectiveText('),'MainActivity.kt':('settings.textZoom = UiScale.effectiveText','fun uiEffectiveText()'),'NlA11y.kt':('IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS','!ViewCompat.hasAccessibilityDelegate(root)'),'NlChrome.kt':('ebt.dispatchKeyEvent(event)','ebt.onFocusChanged('),'NlDrawer.kt':('ebt.dispatchKeyEvent(event)','ebt.onFocusChanged('),'NativeList.kt':('ebt.dispatchKeyEvent(event)','nlRowHeight(base, textBlock, d)','n.stateDescription'),'NlListFx.kt':('maxOf(48f * density, baseDp * density, textHeight + 12f * density)',),'Lm.kt':('NlA11y.heading(tv)','fontScale >= 1.3f'),'NlViewerBar.kt':('!exploring()','if (exploring()) return')}
    for name,markers in checks.items():
        source=open(os.path.join(_nat,name),encoding='utf-8').read()
        for marker in markers:
            if marker not in source:errs.append(name+': '+marker)
    print('Accessibility regression:', 'PASS' if not errs else 'FAIL '+'; '.join(errs))
    sys.exit(1 if errs else 0)
if '--split-button-regression' in sys.argv:
    errs=[]
    for marker in ('LUMEN-SPLIT:BEGIN','aria-expanded','aria-haspopup','lmSplitPlacement','sc._close=done','window.removeEventListener','lmSplit(\'New folder\'','lmSplit(\'Add files or folders…\'','lmSplit(\'Save this layout\''):
        if marker not in _raw:errs.append('Missing split button invariant: '+marker)
    print('Split button regression:', 'PASS' if not errs else 'FAIL '+'; '.join(errs))
    sys.exit(1 if errs else 0)
if '--player-regression' in sys.argv:
    widgets=open(os.path.join(_nat,'PlayerWidgets.kt'),encoding='utf-8').read()
    player=open(os.path.join(_nat,'PlayerActivity.kt'),encoding='utf-8').read()
    errs=[]
    for marker in ('LumenGlass.chromeFill','LumenGrad.primary','LumenIcons.SW','LumenTypography.LABEL_M','ACTION_SET_PROGRESS','KEYCODE_DPAD_RIGHT','pal.buffered','val bg = android.graphics.Color.BLACK'):
        if marker not in widgets:errs.append('Missing player widget invariant: '+marker)
    for marker in ('NlViewerBar.scrim()','LumenTypography.TITLE_M','LumenPrefs.reduceMotion(this)','isTouchExplorationEnabled','HorizontalScrollView','lp(dp(56), dp(56), gap)','val bs = dp(48)'):
        if marker not in player:errs.append('Missing player chrome invariant: '+marker)
    print('Player design regression:', 'PASS' if not errs else 'FAIL '+'; '.join(errs))
    sys.exit(1 if errs else 0)
if '--system-bars-regression' in sys.argv:
    theme=open(os.path.join(_nat,'LumenNativeTheme.kt'),encoding='utf-8').read()
    errs=[]
    for marker in ('private data class BarPolicy','barPolicies[activity]','ColorUtils.calculateContrast(Color.BLACK, opaque)','Build.VERSION.SDK_INT < 26','if (isClear(activity)) return','if (!stage) styleTree(root, lm)'):
        if marker not in theme:errs.append('Missing system bar invariant: '+marker)
    names=('MainActivity.kt','PlayerActivity.kt','ImageViewerActivity.kt','ImageEditActivity.kt','PdfViewerActivity.kt','StorageActivity.kt','TextEditorActivity.kt','OfficeViewerActivity.kt','UsActivity.kt','UsIndexActivity.kt','InboxActivity.kt','PrintLiveActivity.kt','PrintLogActivity.kt','VaultViewActivity.kt','VaultPlay.kt','VaultPdf.kt','VaultOffice.kt')
    for name in names:
        source=open(os.path.join(_nat,name),encoding='utf-8').read()
        if not re.search(r'LumenNativeTheme\.(?:bars|pageBars)\(',source):errs.append('Missing explicit bar policy: '+name)
        if re.search(r'\bwindow\.(?:statusBarColor|navigationBarColor)\s*=|\bisAppearanceLight(?:Status|Navigation)Bars\s*=',source):errs.append('Competing bar writer: '+name)
    print('System bar regression:', 'PASS' if not errs else 'FAIL '+'; '.join(errs))
    sys.exit(1 if errs else 0)
if '--button-regression' in sys.argv:
    theme=open(os.path.join(_nat,'LumenNativeTheme.kt'),encoding='utf-8').read()
    errs=[]
    for marker in ('object LumenButtons', 'if (b is android.widget.CompoundButton) return', 'current === stock', 'LumenTypography.LABEL_L', '>= 4.5', 'LumenGrad.primary(lm)', 'LumenGrad.danger(lm)'):
        if marker not in theme:errs.append('Missing native button invariant: '+marker)
    if 'is Button -> LumenButtons.refresh(view, lm)' not in theme:errs.append('Global button role routing missing')
    if 'LumenButtons.apply(b, role, label = text, backdrop = backdrop)' not in _lm:errs.append('Shared dialog button role routing missing')
    for name in ('VaultActivity.kt','VaultImportActivity.kt','VaultText.kt','ErrorLogsActivity.kt','StorageActivity.kt','InboxActivity.kt','UsIndexActivity.kt','PrintLiveActivity.kt'):
        if 'LumenButtons.apply(' not in open(os.path.join(_nat,name),encoding='utf-8').read():errs.append('Button role missing: '+name)
    print('Native button regression:', 'PASS' if not errs else 'FAIL '+'; '.join(errs))
    sys.exit(1 if errs else 0)
if '--snackbar-regression' in sys.argv:
    # Only the central no-Activity fallback is allowed; report all native bypasses recursively.
    _toast_bypass=[]
    for _root,_dirs,_files in os.walk(_nat):
        for _file in _files:
            if not _file.endswith('.kt'):continue
            _path=os.path.join(_root,_file)
            _source=open(_path,encoding='utf-8').read()
            for _i,_line in enumerate(_source.splitlines(),1):
                if re.search(r'\bToast\s*\.\s*makeText\s*\(',_line) and os.path.relpath(_path,_nat)!='Lm.kt':
                    _toast_bypass.append(f'{os.path.relpath(_path,_nat)}:{_i}')
    if len(re.findall(r'\bToast\s*\.\s*makeText\s*\(',_lm))!=1:_toast_bypass.append('Lm must contain exactly one platform fallback')
    if "if(tr&&vis($('#tp')))" not in _raw:_toast_bypass.append('Native mirror must not replace interactive snackbars')
    print('Shared snackbar regression:', 'PASS' if not _toast_bypass else 'FAIL '+ '; '.join(_toast_bypass))
    sys.exit(1 if _toast_bypass else 0)
if '--dialog-regression' in sys.argv:
    # Gate shared dialog invariants and raw browser alerts; other pre-existing lint findings remain visible in the report.
    sys.exit(1 if bad or _sem else 0)
sys.exit(1 if bad or _x else 0)
