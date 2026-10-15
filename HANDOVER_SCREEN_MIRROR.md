# HANDOVER - PC screen mirror ("code window" in the app's print dialog)

**Status 2026-10-15: PC side (pcprint.py v28) done and tested on Linux; APP SIDE WRITTEN (Jobs.pcScreen, route `pcscreen`, `openPcScreen` + button + CSS in ui.html) - NOT compiled / NOT device-tested.** The spec and code are below (kept for reference). Nothing here was compiled or run on Android / Windows.

## Goal
In the app's print dialog (next to the existing **Print queue ›** link) a new **PC screen ›** button opens a dark, monospace "code window" that mirrors what the `LANShare Print` window on the PC shows: the status line (`Running · Tailscale ... · port 8799 · printer: ...`) and the live, colour-coded log. Read-only.

## PC side - `pcprint.py` v28 (replace the file on the PC, run it once; the service restarts itself)
New endpoint, same access rule as `/ping` and `/jobs` (LAN / Tailscale clients only, 403 otherwise):

`GET /screen?pos=N[&lines=K]`
```json
{ "ok": true, "version": "28", "host": "DESKTOP-X",
  "pos": 4821,            // byte position in log.txt to send back next time
  "reset": true,          // true = "clear your view, these lines are the start" (first open, log rotated, viewer too far behind)
  "lines": [ {"t": "2026-10-15 18:36:53 printed 1 pictures", "k": "ok"}, ... ],
  "status": { "dot": "green|amber", "text": "Running  ·  Tailscale 100.x  ·  port 8799  ·  printer: L1250 ...",
              "printer": "...", "problem": null, "busy": null, "queued": 0 } }
```
- `k` is the colour class, identical to the PC window: `ok` (green, "printed"), `fail` (red: FAILED / WARNING / CRASH / ERROR / cannot listen), `dim` (grey: ready / stopped), `""` (normal).
- First call: `pos` missing / `-1` -> last 150 lines (`lines=` 1..500) and `reset:true`. Later calls: only new complete lines (a half-written last line waits for the next poll). If the log file was rotated (500 KB limit) or the viewer is >256 KB behind, the server answers with `reset:true` and the tail again.
- Service stopped = nothing answers (the app shows "PC not reachable"); an old pcprint.py answers **404** on `/screen` (the app shows "update pcprint.py"). `/ping` now has `"screen": true`.
- Window and endpoint share `log_tag()` / `status_parts()` / `ips_label()`, so colours and the status text can never drift apart. The window's behaviour is unchanged (it still reads the file itself).
- Privacy: the log contains file names and IPs - visible to anything that can already reach port 8799 (`/jobs` shows the names too). No write access, no actions.

Tested: first load, empty poll, incremental lines with tags (incl. Turkish characters), half-written line, log rotation, far-behind viewer (reset to the last 150), `lines=`, bad `pos`. Not run: the Tk window after the refactor (py_compile only) - open the window once and check the status line + colours.

Note: the `pcprint.py` in the zip you sent already had an uncommitted change (window always follows the newest line); it is kept.
Also noticed (not touched): this pcprint.py has no `waiting` list in `/jobs` and no `POST /cancel`, but the app's Print queue screen (`openPrintQueue`, `Jobs.pcQueue`/`pcCancel`) expects v22 behaviour - it will show "Update pcprint.py" until that is added.

## App side - to do (three small edits, one per session if needed)
Follow the existing Print-queue pattern exactly (it already polls a PC every 2 s).

### 1. `core/Jobs.kt` - next to `pcQueue()` (~line 230)
```kotlin
/** pcprint.py 28+: what the PC window shows. {ok, old?, status:{dot,text}, lines:[{t,k}], pos, reset}. pos < 0 = from the start. */
fun pcScreen(dev: String, pos: Long): JSONObject {
    val (ip, _) = try { printTarget(dev) } catch (e: BadReq) { throw e } catch (e: IOException) { return JSONObject().put("ok", false).put("why", errText(e)) }
    return try {
        val r = Http.request(ip, PRINT_PORT, "GET", "/screen?pos=$pos", emptyMap(), 4000)
        try {
            if (r.status == 404) return JSONObject().put("ok", true).put("old", true)
            if (r.status != 200) return JSONObject().put("ok", false)
            JSONObject(String(r.readUpTo(1 shl 20), Charsets.UTF_8)).put("ok", true)
        } finally { r.close() }
    } catch (_: Exception) { JSONObject().put("ok", false) }
}
```
### 2. `core/Routes.kt` - under `"pcqueue"` (~line 138)
```kotlin
"pcscreen" -> return ex.json(Jobs.pcScreen(ex.q("dev"), (q["pos"] ?: "-1").toLongOrNull() ?: -1L))   // PC window mirror
```
(No change to `InProc.ROUTES`: like `pcqueue` it goes over the normal HTTP path.)

### 3. `assets/ui.html`
**Button** - right after the existing `ql` line in the print dialog (~line 2457, same condition: PC printers only):
```js
const sl=E('button','pql','PC screen ›');sl.style.marginLeft='14px';sl.setAttribute('aria-label','Show the output window of '+pc.name);sl.onclick=e=>{e.stopPropagation();openPcScreen(pc)};pt.append(sl)
```
**Window** - new function next to `openPrintQueue` (reuses its bottom-sheet look; own id `pcs`):
```js
function openPcScreen(pc){
  let o=document.getElementById('pcs');if(!o){o=E('div');o.id='pcs';document.body.append(o)}
  o.textContent='';o.style.display='flex';
  let alive=true,tm=0,pos=-1,fails=0,n=0;
  const close=()=>{alive=false;clearTimeout(tm);o.style.display='none';o.onclick=null};o.onclick=e=>{if(e.target===o)close()};
  const card=E('div','card'),hd=E('div','pqh'),cb=E('button','tbtn','Close'),dot=E('span','pcd'),st=E('div','pcst','Connecting…'),log=E('div','pcl');cb.onclick=close;
  hd.append(E('h3','',pc.name||'PC'),cb);card.append(E('div','handle'),hd,E('div','pcb'),log);card.children[2].append(dot,st);o.append(card);
  const add=l=>{const d=E('div','pcln'+(l.k?' '+l.k:''),l.t);log.append(d);if(++n>1000){log.firstChild.remove();n--}};
  const draw=r=>{
    if(!r||!r.ok){dot.className='pcd red';st.textContent=(r&&r.why)||'PC not reachable - is pcprint.py running?';return}
    if(r.old){dot.className='pcd red';st.textContent='Update pcprint.py on the PC (needs version 28 or newer)';return}
    dot.className='pcd '+r.status.dot;st.textContent=r.status.text;
    const atEnd=log.scrollTop+log.clientHeight>=log.scrollHeight-24;   /* follow the newest line only if the user is at the bottom */
    if(r.reset){log.textContent='';n=0}
    r.lines.forEach(add);pos=r.pos;if(atEnd||r.reset)log.scrollTop=log.scrollHeight};
  const tick=()=>{if(!alive)return;
    api('GET','/api/pcscreen?dev='+enc(pc.id)+'&pos='+pos,null,6000).then(r=>{fails=0;if(alive)draw(r)}).catch(()=>{if(alive&&++fails>1)draw(null)})
      .then(()=>{if(alive)tm=setTimeout(tick,1000)})};
  tick()}
```
**CSS** - next to the `#pq` rules (~line 502); `#pq` styles can be reused by giving `#pcs` the same rules:
```css
#pq,#pcs{...same as #pq...}   /* simplest: add #pcs to every #pq selector (502-505) */
.pcb{display:flex;align-items:center;gap:8px;margin:2px 0 6px}.pcst{font-size:12px;color:var(--mut);word-break:break-word}
.pcd{width:10px;height:10px;border-radius:50%;background:#888;flex:none}.pcd.green{background:#4ade80}.pcd.amber{background:#fbbf24}.pcd.red{background:#f87171}
.pcl{background:#0b0f17;color:#d7dde8;border-radius:12px;padding:10px;height:60vh;overflow:auto;font:11px/1.45 ui-monospace,Consolas,monospace;white-space:pre-wrap;word-break:break-all}
.pcln.ok{color:#6ee7a0}.pcln.fail{color:#ff7b7b}.pcln.dim{color:#7d8aa3}
```
(`E`, `api`, `enc` are the helpers `openPrintQueue` already uses; `api` rejects on HTTP errors / timeouts - hence the `catch`.)

## Device checklist
1. Copy `pcprint.py` v28 to the PC, run it (window opens; status line + colours look as before). Phone on the same Wi-Fi or tailnet.
2. App -> print dialog for that PC -> **PC screen ›**: last ~150 lines appear at once, status line matches the PC window.
3. Print something from the phone: new lines show up within ~1 s, "printed" green, failures red; scroll up = view stops jumping, scroll to the bottom = follows again.
4. Stop the service from the PC window: the app shows "PC not reachable" after ~2 s; Start again: it recovers by itself.
5. Old pcprint.py (< 28): "Update pcprint.py" message.
6. Close the sheet: polling stops (no more `/screen` hits in the network log).

## Ideas, not done
- Long-poll / SSE instead of 1 s polling (only worth it if the polling load matters; the PC window itself polls the file every 0.7 s).
- A "Copy log" button, or a "Clear queue" button in the app window (`POST /clear` is localhost-only today).
