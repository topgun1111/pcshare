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
sys.exit(1 if bad or _x else 0)
