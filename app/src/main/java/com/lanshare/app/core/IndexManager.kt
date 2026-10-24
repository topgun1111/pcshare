package com.lanshare.app.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import com.lanshare.app.NlModel
import com.lanshare.app.core.usearch.Delta
import com.lanshare.app.core.usearch.DeltaStats
import com.lanshare.app.core.usearch.HiddenFilter
import com.lanshare.app.core.usearch.IndexStore
import com.lanshare.app.core.usearch.NameIndex
import com.lanshare.app.core.usearch.ScanState
import com.lanshare.app.core.usearch.UserRules
import com.lanshare.app.core.usearch.WalkRules
import com.lanshare.app.core.usearch.Walker
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Compile-time kill switch of Universal Search (decision D10). The runtime switch is Cfg.usOn() ("us_on"). */
object UniversalSearch { const val ENABLED = true }

/**
 * Owner of the name index (chapter 16, Part 3): keeps it loaded, built and CORRECT without the user ever pressing refresh. One daemon thread.
 *
 * Work, in priority order (the first one that is ready runs):
 *  1. paths  - folders touched by the app's own operations ([changed]), batched 1.5 s, cheap, runs even on battery.
 *  2. full   - first build, or after a settings change / manual Rescan / weekly deep refresh while charging. Builds a fresh index; when an old complete
 *              one exists it keeps answering until the new one replaces it. A first-ever build is published at once (results grow while it runs).
 *  3. delta  - changed-folder walk ([Delta.syncVolume]) at start, on app resume, 20 s after a MediaStore.Files change, and for volumes that appeared / disappeared.
 * Full and delta wait while the battery is below 20 % and not charging, or (setting us_charge) whenever not charging. The CPU wake lock is the one
 * Core.holdAwake already holds for the life of the process; this class takes none of its own.
 * Persistence: <filesDir>/usearch/names.bin (IndexStore) + state.json (complete flag, times, count), saved at most every 2 minutes when dirty and at the end of a scan;
 * a first-ever build also checkpoints every minute (a restart then shows the partial index and rebuilds).
 */
object IndexManager {
    private const val TAG = "UsIndex"
    private const val START_DELAY = 10_000L        // heavy work waits a little after the first screen
    private const val OWN_DEBOUNCE = 1_500L
    private const val OBS_DEBOUNCE = 20_000L
    private const val RESUME_DELAY = 3_000L
    private const val RESUME_MIN_GAP = 2 * 60_000L
    private const val LOWBAT_WAIT = 15 * 60_000L
    private const val SAVE_EVERY = 120_000L
    private const val CHECKPOINT = 60_000L
    private const val DEEP_EVERY = 7 * 86_400_000L
    private const val DIRTY_MAX = 2_000

    enum class State { OFF, IDLE, SCANNING, PAUSED }

    @Volatile private var ix: NameIndex? = null
    @Volatile private var state = State.OFF
    @Volatile private var phase = ""
    @Volatile private var waitReason = ""
    @Volatile private var lastError = ""
    @Volatile private var paused = false
    @Volatile private var complete = false
    @Volatile private var scan: ScanState? = null
    @Volatile private var prevCount = 0
    @Volatile private var lastFull = 0L
    @Volatile private var lastDelta = 0L
    @Volatile private var lastSave = 0L
    @Volatile private var lastDeltaStats: DeltaStats? = null

    private val lock = Object()
    private val saveLock = Any()   // names.bin.tmp must never be written by two threads at once (checkpoint thread vs final save)
    private val linkFn: (File) -> Boolean = { f -> isLink(f) }
    private val dirty = LinkedHashSet<String>()
    private var dirtyAt = 0L
    private var wantFull = false
    private var wantDelta = false
    private var deltaDueAt = 0L
    private var needLoad = true
    private var unsaved = false
    private var notBefore = 0L
    private var started = false
    private var ctx: Context? = null

    // ---------------------------------------------------------------- public API

    fun enabled(): Boolean = UniversalSearch.ENABLED && Cfg.usOn()

    /** The index to search (null while off or not loaded yet). Fetch it again for every query: a rebuild swaps the object. */
    fun index(): NameIndex? = if (enabled()) ix else null

    /** True once a full scan has finished for this index (before that, results are partial). */
    fun isComplete(): Boolean = complete

    /** Call once at app start (Core late-init, after the first screen). Safe to call again. */
    fun start(app: Context) {
        synchronized(lock) { if (started) return; started = true }
        if (!UniversalSearch.ENABLED) return
        try {
            ctx = app.applicationContext
            notBefore = System.currentTimeMillis() + START_DELAY
            val t = HandlerThread("us-observer").also { it.isDaemon = true; it.start() }
            val h = Handler(t.looper)
            val obs = object : ContentObserver(h) { override fun onChange(selfChange: Boolean) { requestDelta(OBS_DEBOUNCE) } }
            try { app.contentResolver.registerContentObserver(MediaStore.Files.getContentUri("external"), true, obs) } catch (_: Throwable) {}
            try {   // charger plugged in: waiting work may go on
                app.registerReceiver(object : BroadcastReceiver() { override fun onReceive(c: Context?, i: Intent?) { poke() } }, IntentFilter(Intent.ACTION_POWER_CONNECTED))
            } catch (_: Throwable) {}
            Thread({ loop() }, "us-index").also { it.isDaemon = true; it.start() }
        } catch (t: Throwable) { Log.w(TAG, "start failed: " + errText(t)); lastError = errText(t) }
    }

    /** The app came to the front: look for changes made by other apps (at most every 2 minutes). */
    fun onResume() {
        if (!started || !enabled() || !complete) return
        if (System.currentTimeMillis() - lastDelta > RESUME_MIN_GAP) requestDelta(RESUME_DELAY)
    }

    /**
     * Own file operations call this for every path they created, changed, moved or deleted (before or after: only the PARENT folder is re-read, later, batched).
     * Never throws, never blocks. Moves call it for the source and the target.
     */
    fun changed(path: String) {
        try {
            if (!started || ix == null) return
            val par = File(path).parentFile ?: return
            synchronized(lock) {
                dirty.add(par.path); dirtyAt = System.currentTimeMillis()
                if (dirty.size > DIRTY_MAX) { dirty.clear(); wantDelta = true; deltaDueAt = dirtyAt + OWN_DEBOUNCE }
                lock.notifyAll()
            }
        } catch (_: Throwable) {}
    }
    fun changed(f: File) = changed(f.path)

    /** Throw the index away and build it again (settings change, "Rescan"). The old index keeps answering until the new one is ready. */
    fun rescan() { synchronized(lock) { wantFull = true; notBefore = 0L; lock.notifyAll() }; scan?.cancel() }

    fun pause(on: Boolean) { paused = on; if (on) scan?.cancel() else rescanIfNeeded(); poke() }
    private fun rescanIfNeeded() { synchronized(lock) { if (!complete) wantFull = true } }

    fun setEnabled(on: Boolean) {
        Cfg.setUsOn(on)
        if (!on) { scan?.cancel(); try { saveNow() } catch (_: Throwable) {}; ix = null; needLoad = true; complete = false; state = State.OFF; synchronized(lock) { dirty.clear() } }
        poke()
    }
    fun setCharging(on: Boolean) { Cfg.setUsCharge(on); poke() }
    fun setIncluded(l: List<String>) { Cfg.setUsIncluded(l); rescan() }
    fun setExcluded(l: List<String>) { Cfg.setUsExcluded(l); rescan() }

    /** Hide-button filter for results (virtual paths pushed by the page into NlModel.hidden). Apply it to every hit: the list changes without a rescan. */
    fun hiddenFilter(): HiddenFilter = HiddenFilter(Core.local.root.path, NlModel.hidden)

    /** Snapshot for GET /api/uindex and the Settings page. */
    fun status(): JSONObject {
        val x = ix
        val sc = scan
        val o = JSONObject().put("enabled", enabled()).put("state", state.name.lowercase()).put("phase", phase).put("paused", paused)
            .put("complete", complete).put("charge", Cfg.usCharge()).put("wait", waitReason).put("error", lastError)
            .put("lastFull", lastFull).put("lastDelta", lastDelta).put("lastSave", lastSave)
            .put("included", JSONArray(Cfg.usIncluded())).put("excluded", JSONArray(Cfg.usExcluded()))
        synchronized(lock) { o.put("pending", dirty.size) }
        if (x != null) o.put("entries", x.count()).put("dirs", x.dirCount()).put("dead", x.deadCount()).put("epoch", x.epoch()).put("bytes", x.approxBytes())
        if (sc != null) {
            val n = sc.files + sc.dirs
            o.put("scanFiles", sc.files).put("scanDirs", sc.dirs).put("unreadable", sc.unreadable).put("current", sc.current)
            if (phase == "full") o.put("pct", if (prevCount > 0) minOf(99L, n * 100 / prevCount) else -1L)
        }
        lastDeltaStats?.let { o.put("deltaChecked", it.dirsChecked).put("deltaChanged", it.dirsChanged).put("deltaAdded", it.added).put("deltaRemoved", it.removed).put("deltaUpdated", it.updated) }
        return o
    }

    // ---------------------------------------------------------------- rules and volumes

    private fun abs(v: String): String = File(Core.local.root, v.trimStart('/')).path

    private fun rules(): WalkRules = UserRules(
        include = Cfg.usIncluded().map { abs(it) }, exclude = Cfg.usExcluded().map { abs(it) },
        omitNames = setOf(".thumbnails", ".cache", Bin.NAME))

    private fun isLink(f: File): Boolean = try { Core.local.isLink(f) } catch (_: Throwable) { false }

    private fun volumeRoots(): List<File> {
        val out = ArrayList<File>()
        out.add(Core.local.root)
        try { for (v in Volumes.extra(ctx!!)) { val f = File(v.path); if (f.isDirectory && out.none { it.path == f.path }) out.add(f) } } catch (_: Throwable) {}
        return out
    }

    // ---------------------------------------------------------------- power

    /** (battery percent or -1, charging). Same reading as ImgSearch. */
    private fun power(): Pair<Int, Boolean> = try {
        val i = ctx?.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lvl = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1; val sc = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val st = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        (if (lvl >= 0 && sc > 0) lvl * 100 / sc else -1) to (st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL)
    } catch (_: Throwable) { -1 to false }

    /** Reason to wait ("" = go): below 20 % and not charging, or setting us_charge and not charging. */
    private fun powerWait(): String {
        val (lvl, chg) = power()
        if (chg) return ""
        if (Cfg.usCharge()) return "waiting for the charger"
        if (lvl in 0..19) return "battery below 20 %"
        return ""
    }

    // ---------------------------------------------------------------- scheduler

    private fun poke() { synchronized(lock) { lock.notifyAll() } }

    private fun requestDelta(delay: Long) {
        synchronized(lock) { wantDelta = true; deltaDueAt = System.currentTimeMillis() + delay; lock.notifyAll() }
    }

    private fun loop() {
        while (true) {
            try { step() } catch (t: Throwable) {
                Log.w(TAG, "step failed: " + errText(t)); lastError = errText(t)
                try { Thread.sleep(30_000) } catch (_: InterruptedException) {}
            }
        }
    }

    private fun step() {
        var job = 0   // 1 full, 2 delta, 3 paths, 4 load
        synchronized(lock) {
            val now = System.currentTimeMillis()
            var waitMs = 60_000L
            if (!enabled()) { state = State.OFF; phase = ""; waitMs = 5 * 60_000L; dirty.clear() }
            else if (Core.storageOk == false) { state = State.IDLE; waitReason = "no storage access"; waitMs = 30_000L }
            else if (paused) { state = State.PAUSED; waitReason = "paused"; phase = "" }
            else if (needLoad) job = 4
            else {
                val full = wantFull || ix == null || !complete
                if (complete && !wantFull && now - lastFull > DEEP_EVERY && power().second) wantFull = true
                val pathsReady = dirty.isNotEmpty() && now - dirtyAt >= OWN_DEBOUNCE
                val deltaReady = wantDelta && now >= deltaDueAt
                if (pathsReady && ix != null) job = 3
                else if ((full || deltaReady) && now >= notBefore) {
                    val w = powerWait()
                    if (w.isNotEmpty()) { state = State.PAUSED; waitReason = w; phase = ""; waitMs = LOWBAT_WAIT }
                    else job = if (full || wantFull) 1 else 2
                } else {
                    state = State.IDLE; waitReason = ""; phase = ""
                    if (dirty.isNotEmpty()) waitMs = minOf(waitMs, maxOf(200L, OWN_DEBOUNCE - (now - dirtyAt)))
                    if (wantDelta) waitMs = minOf(waitMs, maxOf(200L, deltaDueAt - now))
                    if ((full || deltaReady) && now < notBefore) waitMs = minOf(waitMs, maxOf(200L, notBefore - now))
                    if (unsaved) waitMs = minOf(waitMs, maxOf(500L, SAVE_EVERY - (now - lastSave)))
                }
            }
            if (job == 0) { try { lock.wait(waitMs) } catch (_: InterruptedException) {} }
        }
        when (job) {
            4 -> doLoad()
            3 -> doPaths()
            2 -> doDelta()
            1 -> doFull()
        }
        maybeSave(false)
    }

    // ---------------------------------------------------------------- jobs

    private fun dataDir(): File = File(ctx!!.filesDir, "usearch")

    private fun doLoad() {
        state = State.SCANNING; phase = "load"
        try {
            val m = readMeta()
            val loaded = IndexStore.load(File(dataDir(), "names.bin"))
            synchronized(lock) {
                needLoad = false; lastSave = System.currentTimeMillis()
                if (loaded != null) {
                    ix = loaded; complete = m.optBoolean("complete", false)
                    lastFull = m.optLong("lastFull", 0L); lastDelta = m.optLong("lastDelta", 0L); prevCount = m.optInt("count", loaded.count())
                    if (complete) { wantDelta = true; deltaDueAt = System.currentTimeMillis() + 5_000 } else wantFull = true
                } else { ix = null; complete = false; wantFull = true }
            }
        } finally { phase = "" }
    }

    private fun doFull() {
        val c = ctx ?: return
        val st = ScanState(); scan = st
        state = State.SCANNING; phase = "full"; lastError = ""
        synchronized(lock) { wantFull = false }
        val fresh = NameIndex(maxOf(1024, prevCount + prevCount / 8))
        val progressive = !complete   // no complete index to keep answering: publish the growing one at once
        if (progressive) ix = fresh
        val done = AtomicBoolean(false)
        val ck = if (progressive) Thread({
            while (!done.get()) {
                try { Thread.sleep(CHECKPOINT) } catch (_: InterruptedException) { return@Thread }
                synchronized(saveLock) {
                    if (done.get() || st.cancelled) return@Thread
                    try { IndexStore.save(fresh, File(dataDir(), "names.bin")); writeMeta(false, fresh) } catch (_: Throwable) {}
                }
            }
        }, "us-checkpoint").also { it.isDaemon = true; it.start() } else null
        try {
            val rules = rules()
            for (v in volumeRoots()) {
                if (st.cancelled) break
                try { Walker(fresh, rules, linkFn, st).scanVolume(v) } catch (t: Throwable) { Log.w(TAG, "scan " + v.path + ": " + errText(t)); lastError = v.path + ": " + errText(t) }
            }
            done.set(true); ck?.interrupt()
            if (!st.cancelled && enabled()) {
                ix = fresh; complete = true; lastFull = System.currentTimeMillis(); lastDelta = lastFull; prevCount = fresh.count()
                synchronized(lock) { unsaved = true }
                maybeSave(true)
            }
        } finally { done.set(true); ck?.interrupt(); scan = null; phase = "" }
    }

    private fun doDelta() {
        val cur = ix ?: return
        val st = ScanState(); scan = st
        state = State.SCANNING; phase = "delta"
        synchronized(lock) { wantDelta = false }
        val stats = DeltaStats()
        try {
            val rules = rules()
            val vols = volumeRoots()
            val roots = ArrayList<Int>()
            cur.forEachLive { if (cur.parentOf(it) == NameIndex.NONE) roots.add(it) }
            for (r in roots) { val p = cur.pathOf(r); if (vols.none { it.path == p }) { stats.removed += cur.remove(r) } }   // volume gone (SD card removed)
            for (v in vols) {
                if (st.cancelled) break
                if (cur.findRoot(v.path) == NameIndex.NONE) { val b = cur.count(); Walker(cur, rules, linkFn, st).scanVolume(v); stats.added += cur.count() - b }   // volume appeared
                else {
                    val s = Delta.syncVolume(cur, v, rules, linkFn, st)
                    stats.dirsChecked += s.dirsChecked; stats.dirsChanged += s.dirsChanged; stats.added += s.added; stats.removed += s.removed; stats.updated += s.updated
                }
            }
            if (!st.cancelled) { lastDelta = System.currentTimeMillis(); lastDeltaStats = stats }
            if (stats.added + stats.removed + stats.updated > 0 || stats.dirsChanged > 0) synchronized(lock) { unsaved = true }
        } finally { scan = null; phase = "" }
    }

    private fun doPaths() {
        val cur = ix ?: return
        val batch = synchronized(lock) { val l = ArrayList(dirty); dirty.clear(); l }
        state = State.SCANNING; phase = "paths"
        try {
            val rules = rules()
            var any = false
            for (p in batch) { if (Delta.syncDir(cur, File(p), rules, linkFn)) any = true }
            if (any) synchronized(lock) { unsaved = true }
        } finally { phase = "" }
    }

    // ---------------------------------------------------------------- persistence

    private fun readMeta(): JSONObject = try { JSONObject(File(dataDir(), "state.json").readText()) } catch (_: Throwable) { JSONObject() }

    private fun writeMeta(done: Boolean, x: NameIndex) {
        try {
            val d = dataDir(); d.mkdirs()
            val tmp = File(d, "state.json.tmp")
            tmp.writeText(JSONObject().put("v", 1).put("complete", done).put("lastFull", lastFull).put("lastDelta", lastDelta).put("count", x.count()).toString())
            val f = File(d, "state.json")
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (_: Throwable) {}
    }

    private fun maybeSave(force: Boolean) {
        val x = ix ?: return
        val now = System.currentTimeMillis()
        synchronized(lock) { if (!unsaved || (!force && now - lastSave < SAVE_EVERY)) return; unsaved = false }
        try {
            if (x.deadCount() > maxOf(10_000, x.count() / 4)) x.compact()   // renumbers ids and bumps epoch (Part 5 remaps its content store)
            synchronized(saveLock) {
                IndexStore.save(x, File(dataDir(), "names.bin"))
                writeMeta(complete, x)
            }
            lastSave = System.currentTimeMillis()
        } catch (t: Throwable) { Log.w(TAG, "save failed: " + errText(t)); lastError = "save: " + errText(t); synchronized(lock) { unsaved = true } }
    }

    private fun saveNow() { synchronized(lock) { unsaved = true }; maybeSave(true) }
}
