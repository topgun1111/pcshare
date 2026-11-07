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
sys.exit(1 if bad else 0)
