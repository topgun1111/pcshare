#!/usr/bin/env bash
# Static checks of the Android-side glue of Universal Search (things the JVM self-test cannot reach). Run from the repo root.
# Prints "ok <name>" / "FAIL <name>"; exit 1 when anything failed.
A=app/src/main/java/com/lanshare/app
fails=0
ok()   { echo "ok   $1"; }
bad()  { echo "FAIL $1"; fails=$((fails+1)); }
has()  { if grep -q -E -- "$2" "$3" 2>/dev/null; then ok "$1"; else bad "$1 (pattern '$2' not in $3)"; fi; }
file() { if [ -f "$2" ]; then ok "$1"; else bad "$1 ($2 missing)"; fi; }

# ---- 1. files
for f in Fold NameIndex IndexStore Walker Kinds QueryParser Matcher Ranker Delta Rules TextExtractor ContentMatch ImgQuery UsView UsExport ArcNames; do file "file core/usearch/$f.kt" "$A/core/usearch/$f.kt"; done
for f in core/IndexManager core/UsSearch core/ContentStore core/UsImageProvider UsActivity Shortcuts core/office/DocExtractor; do file "file $f.kt" "$A/$f.kt"; done

# ---- 2. the pure packages must stay Android-free (CI compiles them on a plain JVM)
if grep -rn '^import android\.' "$A/core/usearch" "$A/core/office" >/dev/null 2>&1; then bad "no android.* import in core/usearch or core/office: $(grep -rln '^import android\.' $A/core/usearch $A/core/office | tr '\n' ' ')"; else ok "no android.* import in core/usearch or core/office"; fi
if grep -rn 'java\.time' "$A/core/usearch" | grep -v -E '^[^:]+:[0-9]+:\s*(/?\*|//)|without java\.time|no java\.time' >/dev/null 2>&1; then bad "no java.time in core/usearch (minSdk 24)"; else ok "no java.time in core/usearch (minSdk 24)"; fi

# ---- 3. manifest + routes + bridge
has "manifest declares UsActivity" 'UsActivity' app/src/main/AndroidManifest.xml
has "route GET /api/usearch" '"usearch" -> if \(ex.method == "GET"\)' $A/core/Routes.kt
has "route /api/uindex" '"uindex"' $A/core/Routes.kt
has "route /api/rusearch (other phone)" '"rusearch"' $A/core/Routes.kt
has "peer route /p/usearch" 'Universal Search on THIS phone' $A/core/Routes.kt
has "uindex ops: rescan pause on included excluded" '"rescan"' $A/core/Routes.kt
has "uindex ops: content settings" '"contentrebuild"' $A/core/Routes.kt
has "bridge openUsearch" 'fun openUsearch' $A/MainActivity.kt
has "bridge usearchOk" 'fun usearchOk' $A/MainActivity.kt
has "IndexManager started in Core" 'IndexManager.start' $A/core/Core.kt
has "IndexManager.onResume in MainActivity" 'IndexManager.onResume' $A/MainActivity.kt
for h in LocalFs Bin Jobs; do has "freshness hook in $h" 'IndexManager.changed' $A/core/$h.kt; done
has "indexer yields to file jobs" 'a file job is running' $A/core/IndexManager.kt
has "home-screen shortcut" 'us:search' $A/Shortcuts.kt

# ---- 4. screen features present
for k in statusDlg exportList runQuery saveDlg optDlg showHome refreshBanner; do has "UsActivity.$k" "fun $k" $A/UsActivity.kt; done
has "UsActivity: two list view types" 'getViewTypeCount\(\) = 2' $A/UsActivity.kt
has "menu: index status" 'Index status' $A/UsActivity.kt
has "menu: share results list" 'Share results list' $A/UsActivity.kt

# ---- 5. every Cfg key used by the search exists
for k in usOn usCharge usIncluded usExcluded usContent usContentMaxMb usContentBudgetMb usContentExtsText usImg usAiThreshold; do has "Cfg.$k" "fun $k" $A/core/Cfg.kt; done

# ---- 6. ui.html (entry points + JavaScript syntax)
has "ui.html: usOpen" 'function usOpen' app/src/main/assets/ui.html
has "ui.html: browser fallback usWeb" 'function usWeb' app/src/main/assets/ui.html
has "ui.html: drawer item" 'Search everything' app/src/main/assets/ui.html
if command -v node >/dev/null; then
  node -e "const s=require('fs').readFileSync('app/src/main/assets/ui.html','utf8');require('fs').writeFileSync('/tmp/ui_check.js',[...s.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/g)].map(x=>x[1]).join('\n'))"
  if node --check /tmp/ui_check.js 2>/tmp/ui_err.txt; then ok "ui.html JavaScript parses"; else bad "ui.html JavaScript syntax: $(head -3 /tmp/ui_err.txt | tr '\n' ' ')"; fi
else bad "node not available"; fi

# ---- 7. self-test bookkeeping: MIN in the old workflow must not exceed what exists
n=$(grep -c -E '\b(check|eq)\(' tools/usearch_selftest/Main.kt tools/usearch_selftest/E2E.kt | awk -F: '{s+=$2} END{print s}')
if [ "$n" -ge 250 ]; then ok "self-test has $n check call sites"; else bad "self-test has only $n check call sites"; fi

# ---- 8. brace balance of each new Kotlin file (cheap typo catcher)
for f in $A/core/usearch/UsExport.kt $A/core/usearch/ArcNames.kt $A/core/UsSearch.kt $A/UsActivity.kt $A/core/IndexManager.kt $A/Shortcuts.kt tools/usearch_selftest/E2E.kt tools/usearch_selftest/Main.kt; do
  o=$(tr -cd '{' < "$f" | wc -c); c=$(tr -cd '}' < "$f" | wc -c)
  if [ "$o" -eq "$c" ]; then ok "braces balanced $(basename $f)"; else bad "braces unbalanced $(basename $f): { $o  } $c"; fi
done
echo "static checks: FAIL=$fails"
[ "$fails" -eq 0 ]
