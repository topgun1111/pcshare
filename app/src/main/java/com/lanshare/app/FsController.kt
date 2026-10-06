package com.lanshare.app

import android.os.Handler
import android.os.Looper
import com.lanshare.app.core.Clip
import com.lanshare.app.core.Core
import com.lanshare.app.core.INBOX
import com.lanshare.app.core.Item
import com.lanshare.app.core.Job
import com.lanshare.app.core.Jobs
import com.lanshare.app.core.arcSplit
import com.lanshare.app.core.errText
import com.lanshare.app.core.isArcName
import com.lanshare.app.core.vbase
import com.lanshare.app.core.vdir
import com.lanshare.app.core.vjoin
import com.lanshare.app.core.vnorm
import java.util.concurrent.Executors

/**
 * Native replacement for ui.html's brain (S, go/load/loadMain, selection, setClip/doPaste/doDelete/doRename/doMkdir/doZip/doExtract/doSend, track).
 * Talks to Jobs / Clip / Endpoint directly: no HTTP, no WebView, no JSON round-trip.
 *
 * Threading: every public method is called on the main thread; listing and file work run on [io]; every [Listener] callback
 * and every state change happens on the main thread again, so views can read the fields without locks.
 *
 * NOT compiled / NOT device-tested (no Android SDK in the authoring environment). Nothing references this class yet:
 * wiring it into MainActivity / the Nl* views is the next step.
 */
class FsController(private val ui: Listener) {

    interface Listener {
        /** dev / path / items / err / used changed (a navigation finished, a refresh brought a new listing). */
        fun onList()
        /** The selection changed (names in [sel]). */
        fun onSelection()
        /** The clipboard ([Clip]) changed. */
        fun onClip()
        /** Snackbar text. [long] = 4.5 s instead of 2.8 s. */
        fun onToast(msg: String, long: Boolean)
        /** Progress of the running job; [pct] null = unknown, [state] = run | done | cancel | error. Called ~3x/s while running. */
        fun onProgress(label: String, pct: Int?, state: String)
    }

    // ---------------------------------------------------------------- state (main thread only)
    var dev: String = "local"; private set
    var path: String = "/"; private set
    var items: List<Item> = emptyList(); private set
    /** Share of this phone's main storage in use (the "70% USED" pill), null = unknown / not this phone. */
    var used: Int? = null; private set
    /** Last listing problem; non-null while the controller is retrying. */
    var err: String? = null; private set
    var loading = false; private set
    val sel = LinkedHashSet<String>()
    /** Active name search below [path] (null = normal folder listing). Result names are relative to [path], so vjoin(path, name) is always the real file. */
    var query: String? = null; private set
    /** The search stopped at its result / time limit. */
    var queryPartial = false; private set
    private var sgen = 0

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool { r -> Thread(r, "fsctl").also { it.isDaemon = true } }
    private var gen = 0                       // bumped by every navigation: a slow answer for an old folder is dropped
    private val back = ArrayList<Pair<String, String>>()   // (dev, path) to return to
    private var retry: Runnable? = null
    @Volatile private var trackedJob: String? = null

    // ---------------------------------------------------------------- navigation
    fun go(p: String) = nav(dev, vnorm(p), push = true)
    fun openDev(id: String) = nav(id, "/", push = true)
    /** Jump to any device + folder (favourites, quick folders, history). */
    fun open(d: String, p: String) = nav(d, vnorm(p), push = true)
    fun refresh() { val q = query; if (q != null) search(q) else load(keepSel = true) }

    /** Parent folder; inside an archive "/a.zip!/x" goes to "/a.zip!", and the archive root "/a.zip!" to the folder holding the file (ui.html pdir). */
    fun parent(p: String = path): String {
        val n = vnorm(p)
        return if (n.endsWith("!")) vdir(n.dropLast(1)) else vdir(n)
    }

    fun up() { if (path != "/") nav(dev, parent(), push = true) }

    /** System back: false = nothing to go back to (the activity decides: close the app). */
    fun back(): Boolean {
        if (sel.isNotEmpty()) { clearSel(); return true }
        val b = back.removeLastOrNull() ?: return if (path != "/") { up(); true } else false
        nav(b.first, b.second, push = false)
        return true
    }

    private fun nav(d: String, p: String, push: Boolean) {
        if (push && (d != dev || p != path)) { back.add(dev to path); if (back.size > 100) back.removeAt(0) }
        dev = d; path = p
        query = null; queryPartial = false; sgen++     // a search ends with every navigation
        if (sel.isNotEmpty()) { sel.clear(); ui.onSelection() }
        load(keepSel = false)
    }

    /** Recursive name search below the current folder (any device: this phone, another phone, SMB, archives). Empty text = back to the folder. */
    fun search(q: String) {
        val t = q.trim()
        if (t.isEmpty()) { clearSearch(); return }
        query = t
        retry?.let { main.removeCallbacks(it) }; retry = null
        gen++                                         // drops a folder listing still on its way
        val g = ++sgen; val d = dev; val p = path
        if (sel.isNotEmpty()) { sel.clear(); ui.onSelection() }
        loading = true
        io.execute {
            val res = try { Result.success(Jobs.ep(d).search(p, t)) } catch (e: Throwable) { Result.failure(e) }
            main.post {
                if (g != sgen || query != t || d != dev || p != path) return@post
                loading = false
                res.onSuccess { r ->
                    val base = if (p == "/") "" else vnorm(p)
                    items = r.items.map { x ->
                        val full = x.path ?: vjoin(p, x.name)
                        x.copy(name = if (base.isNotEmpty() && full.startsWith("$base/")) full.substring(base.length + 1) else full.trimStart('/'))
                    }
                    queryPartial = r.partial; err = null
                    ui.onList()
                }.onFailure { e -> ui.onToast("\u26A0 " + errText(e), true); ui.onList() }
            }
        }
    }

    /** Leave the search: the folder listing comes back. */
    fun clearSearch() {
        if (query == null) return
        query = null; queryPartial = false; sgen++
        load(keepSel = false)
    }

    private fun load(keepSel: Boolean) {
        retry?.let { main.removeCallbacks(it) }; retry = null
        val g = ++gen
        val d = dev
        val p = path
        loading = true
        io.execute {
            val res = try { Result.success(list(d, p)) } catch (e: Throwable) { Result.failure(e) }
            main.post {
                if (g != gen) return@post
                loading = false
                res.onSuccess { (l, u) ->
                    items = l; used = u; err = null
                    if (keepSel && sel.isNotEmpty()) {   // a refresh after a file operation: keep the names that still exist
                        val names = l.mapTo(HashSet()) { it.name }
                        if (sel.retainAll(names)) ui.onSelection()
                    }
                    ui.onList()
                }.onFailure { e ->
                    val m = errText(e)
                    if (Regex("unreachable|offline|paused|timed out|connection|lost", RegexOption.IGNORE_CASE).containsMatchIn(m)) {
                        err = m                               // transient: keep what is on screen and retry (ui.html loadMain)
                        ui.onList()
                        retry = Runnable { if (g == gen) load(keepSel) }.also { main.postDelayed(it, 3000) }
                    } else {
                        err = null; items = emptyList()
                        ui.onToast("\u26A0 $m", true)
                        ui.onList()
                    }
                }
            }
        }
    }

    /** Same listing and order as Routes "ls": folders first, then by lower-case name; archive paths go through the endpoint (ArcEp). */
    private fun list(d: String, p: String): Pair<List<Item>, Int?> {
        val l = (if (d == "local" && arcSplit(p) == null) Core.local.ls(p, false) else Jobs.ep(d).ls(p))
            .map { it to it.name.lowercase() }
            .sortedWith(compareBy<Pair<Item, String>>({ !it.first.dir }, { it.second }))
            .map { it.first }
        var u: Int? = null
        if (d == "local") try {
            val r = Core.local.root
            val total = r.totalSpace
            if (total > 0) u = Math.round((total - r.usableSpace) * 100.0 / total).toInt()
        } catch (_: Exception) {}
        return l to u
    }

    // ---------------------------------------------------------------- selection
    fun selItems(): List<Item> = items.filter { it.name in sel }
    fun selPaths(): List<String> = selItems().map { vjoin(path, it.name) }
    fun inArchive() = arcSplit(path) != null
    fun isArc(i: Item) = !i.dir && isArcName(i.name)

    fun toggle(name: String) { if (!sel.remove(name)) sel.add(name); ui.onSelection() }
    fun select(name: String) { if (sel.add(name)) ui.onSelection() }
    fun selectAll() { sel.clear(); items.forEach { sel.add(it.name) }; ui.onSelection() }
    /** Select exactly these names ("Select all" of the visible rows: hidden files stay out). */
    fun selectOnly(names: Collection<String>) { sel.clear(); sel.addAll(names); ui.onSelection() }
    fun clearSel() { if (sel.isNotEmpty()) { sel.clear(); ui.onSelection() } }

    // ---------------------------------------------------------------- clipboard
    fun copy(cut: Boolean) {
        val ps = selPaths()
        if (ps.isEmpty()) return
        Clip.set(if (cut) "cut" else "copy", dev, ps)
        ui.onToast((if (cut) "Cut " else "Copied ") + ps.size + " item(s) - open a folder and tap Paste", false)
        clearSel(); ui.onClip()
    }

    fun clearClip() { Clip.clear(); ui.onClip() }

    fun paste() {
        if (Clip.isEmpty()) { ui.onToast("\u26A0 clipboard is empty", true); return }
        val cut = Clip.op == "cut"
        val src = Clip.dev ?: return
        val ps = Clip.paths
        val d = dev
        val p = path
        job({ Jobs.start(src, ps, d, p, cut, if (cut) "Moving" else "Copying") }) { ok -> if (ok) { Clip.clear(); ui.onClip() } }
    }

    // ---------------------------------------------------------------- file operations
    fun delete() {
        val d = dev; val ps = selPaths()
        if (ps.isEmpty()) return
        job({ Jobs.startDelete(d, ps) }) { clearSel() }
    }

    fun mkdir(name: String) = quick { Jobs.ep(dev).mkdir(vjoin(path, name.trim())) }

    fun rename(old: String, to: String) {
        val n = to.trim()
        if (n.isEmpty() || n == old) return
        quick { Jobs.ep(dev).rename(vjoin(path, old), n) }
    }

    fun zip(name: String) {
        val d = dev; val dir = path; val ps = selPaths()
        val n = name.trim().let { if (it.endsWith(".zip", true)) it else "$it.zip" }
        job({ Jobs.startZip(d, ps, dir, n) }) { clearSel() }
    }

    /**
     * Unpack into the open folder: archives selected in a folder, or (inside an archive) the selected parts of it.
     * Same rewrite as Routes "extract": an archive file path gets the "!" suffix so the copy job reads it as a folder.
     */
    fun extract() {
        val d = dev; val dir = path
        val ps = selPaths().map { if (arcSplit(it) == null && isArcName(vbase(it))) "$it!" else it }
        if (ps.isEmpty()) return
        job({ Jobs.start(d, ps, d, dir, false, "Extracting") }) { clearSel() }
    }

    /** "Send to ...": files land in the other device's storage root (INBOX). */
    fun send(toDev: String) {
        val d = dev; val ps = selPaths()
        if (ps.isEmpty()) return
        job({ Jobs.start(d, ps, toDev, INBOX, false, "Sending") }) { clearSel() }
    }

    /** Send an explicit file list of device [srcDev] to [toDev]'s INBOX (share-sheet "LANShare Send": files copied to /LANShare Shared). [done] runs once the job has ended. */
    fun sendPaths(srcDev: String, paths: List<String>, toDev: String, done: () -> Unit = {}) {
        if (paths.isEmpty()) return
        job({ Jobs.start(srcDev, paths, toDev, INBOX, false, "Sending") }) { done() }
    }

    /** Selected files only (folders are skipped): what "Print on this phone" and the print dialogs list. */
    fun selFiles(): List<Item> = selItems().filter { !it.dir }

    /**
     * Print the selection on a PC (pcprint.py) or a Wi-Fi printer (IPP): [toId] = peer / SMB id or "wifi:..." / "rwifi:..." printer id,
     * [opts] = the options chosen in the print dialog (same keys as ui.html: copies, duplex, color, paper, fit, range, nup, border ...).
     * Folders stay in the path list: the print job expands them. Same flow as ui.html doPrint: the job is followed like any other.
     */
    fun print(toId: String, opts: org.json.JSONObject?) {
        val d = dev; val ps = selPaths()
        if (ps.isEmpty()) return
        job({ Jobs.startPrint(d, ps, toId, opts) }) { clearSel() }
    }

    /** Same as [print] for an explicit file list of device [srcDev] (print intake from other apps: files of /LANShare Shared, not the selection). [done] runs once the job has ended. */
    fun printPaths(srcDev: String, paths: List<String>, toId: String, opts: org.json.JSONObject?, done: () -> Unit = {}) {
        if (paths.isEmpty()) return
        job({ Jobs.startPrint(srcDev, paths, toId, opts) }) { done() }
    }

    fun cancelJob() { trackedJob?.let { Jobs.all[it]?.cancel = true } }

    // ---------------------------------------------------------------- plumbing
    /** Short blocking operation (mkdir / rename): runs off the main thread, then reloads the folder. */
    private fun quick(f: () -> Unit) {
        io.execute {
            val e = try { f(); null } catch (x: Throwable) { errText(x) }
            main.post {
                if (e != null) ui.onToast("\u26A0 $e", true)
                clearSel()
                refresh()
            }
        }
    }

    /** Start a background job ([start] returns its id), follow it until it ends, then reload the folder. [done](ok) runs on the main thread. */
    private fun job(start: () -> String, done: (Boolean) -> Unit = {}) {
        io.execute {
            val id = try { start() } catch (x: Throwable) {
                main.post { ui.onToast("\u26A0 " + errText(x), true) }
                return@execute
            }
            trackedJob = id
            var j: Job? = Jobs.all[id]
            var last = ""
            while (j != null) {
                val st = j.state
                val pct = if (j.total > 0) (j.done * 100 / j.total).toInt().coerceIn(0, 100) else null
                val label = j.label
                val key = "$st|$pct|$label"
                if (key != last) { last = key; main.post { ui.onProgress(label, if (st == "run") pct else null, st) } }
                if (st != "run") break
                try { Thread.sleep(300) } catch (_: InterruptedException) { break }
                j = Jobs.all[id]
            }
            if (trackedJob == id) trackedJob = null
            val fin: Job? = j
            val ok = fin?.state == "done"
            val msg = when (fin?.state) {
                "done" -> fin?.note ?: "Done"
                "cancel" -> "Cancelled"
                else -> "\u26A0 " + (fin?.error ?: "failed")
            }
            main.post {
                ui.onToast(msg, fin?.state == "error")
                done(ok)
                refresh()
            }
        }
    }
}
