package com.lanshare.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Instant start: the last screen is drawn natively while the WebView is still loading ui.html.
 *  - onPause: [save] stores the rows of the shown folder (first 120), the palette + list geometry and two small pictures of the page chrome
 *    (everything above and below the list: header, path bar, dock) taken with PixelCopy.
 *  - cold start: [start] reads them on a thread, then (after the first layout) puts the two pictures + the native list on screen.
 *  - the first live answer of the page (nlLayout / nlHide / nlBoot) removes the pictures (after the WebView has painted: postVisualStateCallback).
 * Anything unexpected (size/density/app version changed, no snapshot, API < 26 for pictures) = no splash, old behaviour.
 * NOT compiled / NOT device-tested.
 */
class NlSplash(private val act: Activity, private val nl: NativeList, private val web: WebView, private val root: FrameLayout) {
    private class Data(val key: String, val rows: List<NlRow>, val sig: Int, val pal: String, val lay: String,
                       val ww: Int, val wh: Int, val top: Bitmap?, val bot: Bitmap?)

    @Volatile var live = false          // the page has delivered its own list (native) at least once
    @Volatile private var liveKey = ""
    @Volatile private var liveItems = ""
    private var data: Data? = null
    private var laid = false
    private var applied = false
    private var started = false
    private var done = false
    private val views = ArrayList<View>()
    private val io = Executors.newSingleThreadExecutor()
    private fun dir() = File(act.filesDir, "nlsplash")
    private fun stamp(): Long = try { act.packageManager.getPackageInfo(act.packageName, 0).lastUpdateTime } catch (_: Throwable) { 0L }

    fun noteItems(key: String, items: String) { live = true; liveKey = key; liveItems = items }

    // ------------------------------------------------------------------ show
    fun start() {
        if (!NativeList.ENABLED) return
        started = true
        Thread {
            val d = try { read() } catch (_: Throwable) { null }
            act.runOnUiThread { if (d == null) { done = true } else { data = d; tryApply() } }
        }.start()
        web.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, oR: Int, ob: Int) {
                if (v.width > 0 && v.height > 0) { v.removeOnLayoutChangeListener(this); laid = true; tryApply() }
            }
        })
    }

    private fun read(): Data? {
        val f = File(dir(), "state.json"); if (!f.isFile) return null
        val j = JSONObject(f.readText())
        val dm = act.resources.displayMetrics
        if (j.optInt("v") != 1 || j.optLong("upd") != stamp() || j.optInt("dpi") != dm.densityDpi) return null
        val items = j.getString("items"); val key = j.getString("key")
        val rows = NativeList.parseRows(items, "local")
        if (rows.isEmpty()) return null
        val top = File(dir(), "top.jpg").takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }
        val bot = File(dir(), "bot.jpg").takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }
        return Data(key, rows, items.hashCode(), j.getString("pal"), j.getString("lay"), j.getInt("ww"), j.getInt("wh"), top, bot)
    }

    private fun tryApply() {
        val d = data ?: return
        if (!laid || done || applied) return
        applied = true
        if (web.width != d.ww || web.height != d.wh) { done = true; return }       // other orientation / window size: skip
        nl.setPalette(d.pal)
        nl.setItems(d.key, d.rows, emptySet(), d.sig)
        nl.layout(try { JSONObject(d.lay).put("o", JSONArray()).toString() } catch (_: Throwable) { d.lay })   // no FAB / toast holes yet: those belong to the page
        d.top?.let { add(it, 0) }
        d.bot?.let { b -> add(b, web.height - b.height) }
    }

    private fun add(bm: Bitmap, y: Int) {
        val v = ImageView(act).apply { setImageBitmap(bm); scaleType = ImageView.ScaleType.FIT_XY; isClickable = true }
        root.addView(v, FrameLayout.LayoutParams(web.width, bm.height).also { it.topMargin = y })
        views.add(v)
    }

    /** The page answered (live list, DOM list, or finished loading): remove the pictures once the WebView has painted. */
    fun dismiss() {
        if (done && views.isEmpty()) return
        done = true
        if (!live && applied) nl.hide()                  // page uses its own DOM rows (empty folder, other view): drop the stale native rows
        if (views.isEmpty()) return
        val vs = ArrayList(views); views.clear()
        val rm = Runnable { vs.forEach { root.removeView(it) } }
        if (Build.VERSION.SDK_INT >= 23) web.postVisualStateCallback(System.nanoTime(), object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) { rm.run() }
        }) else web.postDelayed(rm, 150)
        web.postDelayed(rm, 1500)                         // safety
    }

    // ------------------------------------------------------------------ save
    fun save() {
        if (!NativeList.ENABLED || (started && !done)) return          // splash still showing: it is older than what we would save
        val s = nl.snapshot() ?: return
        if (s.key != liveKey || liveItems.isEmpty()) return
        try { if (JSONObject(s.lay).optInt("sq") == 1) return } catch (_: Throwable) { return }   // search box open: chrome is not the normal one
        val w = web.width; val h = web.height
        if (w <= 0 || h <= 0) return
        val items = liveItems; val key = s.key
        val stamp = stamp(); val dpi = act.resources.displayMetrics.densityDpi
        val top = s.top.coerceIn(0, h); val bot = (h - s.bottom).coerceIn(0, h)
        var topBm: Bitmap? = null; var botBm: Bitmap? = null
        val left = AtomicInteger(0)
        fun finish() {
            io.execute {
                try {
                    val d = dir().also { it.mkdirs() }
                    fun jpg(n: String, bm: Bitmap?) {
                        val f = File(d, n)
                        if (bm == null) { f.delete(); return }
                        val t = File(d, "$n.tmp"); t.outputStream().use { bm.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                        if (!t.renameTo(f)) { f.delete(); t.renameTo(f) }
                    }
                    jpg("top.jpg", topBm); jpg("bot.jpg", botBm)
                    val a = JSONArray(items); val cut = JSONArray(); for (i in 0 until minOf(120, a.length())) cut.put(a.getJSONObject(i).also { o -> o.remove("p"); o.remove("s"); o.remove("t") })   // no thumbnail paths: Core may not be ready at start
                    val j = JSONObject().put("v", 1).put("upd", stamp).put("dpi", dpi).put("ww", w).put("wh", h)
                        .put("key", key).put("pal", s.pal).put("lay", s.lay).put("items", cut.toString())
                    val t = File(d, "state.json.tmp"); t.writeText(j.toString())
                    val f = File(d, "state.json"); if (!t.renameTo(f)) { f.delete(); t.renameTo(f) }
                } catch (_: Throwable) { }
            }
        }
        if (Build.VERSION.SDK_INT < 26 || (top <= 0 && bot <= 0)) { finish(); return }     // no pictures: the list alone is still shown
        val loc = IntArray(2); web.getLocationInWindow(loc)
        val hd = Handler(Looper.getMainLooper())
        fun grab(y: Int, hh: Int, set: (Bitmap?) -> Unit) {
            if (hh <= 0) { set(null); if (left.decrementAndGet() == 0) finish(); return }
            try {
                val bm = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888)
                PixelCopy.request(act.window, Rect(loc[0], loc[1] + y, loc[0] + w, loc[1] + y + hh), bm, { r ->
                    set(if (r == PixelCopy.SUCCESS) bm else null)
                    if (left.decrementAndGet() == 0) finish()
                }, hd)
            } catch (_: Throwable) { set(null); if (left.decrementAndGet() == 0) finish() }
        }
        left.set(2)
        grab(0, top) { topBm = it }
        grab(h - bot, bot) { botBm = it }
    }
}
