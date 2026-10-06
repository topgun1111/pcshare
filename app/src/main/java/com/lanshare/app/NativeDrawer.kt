package com.lanshare.app

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.lanshare.app.core.Core
import com.lanshare.app.core.Smb
import com.lanshare.app.core.viaOf

/**
 * The side drawer of the old WebView UI, native: tabs (Folders / Favorites / Recent), Home + Main storage with the used-space meter,
 * Devices, Quick folders (Edit: rename / up / down / remove / add current folder / reset), Favorites, Recent, footer (Add IP / SMB / Settings).
 * Opens with the menu button or a swipe from the left edge; closes with a swipe left, a tap outside, or Back.
 * NOT compiled / NOT device-tested.
 */
class NativeDrawer(
    private val act: Activity,
    private val c: NlTheme.Cols,
    private val ctl: FsController,
    private val devName: (String) -> String,
    private val askInput: (title: String, init: String, ok: String, f: (String) -> Unit) -> Unit,
    private val onAddIp: () -> Unit,
    private val onSmb: () -> Unit,
    private val onSettings: () -> Unit,
    private val onPrinters: () -> Unit,
    private val toast: (String) -> Unit
) {
    private val d = act.resources.displayMetrics.density
    private fun dp(v: Int) = Math.round(v * d)

    private val scrim = View(act).apply { setBackgroundColor(Color.BLACK); alpha = 0f; visibility = View.GONE; setOnClickListener { close() } }
    private val width = Math.min(dp(320), (act.resources.displayMetrics.widthPixels * 0.85f).toInt())
    private val tabs = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
    private val body = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(act).apply { addView(body) }
    private val foot = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(c.cont) }
    private var tab = Prefs.drawerTab.coerceIn(0, 2)
    private var edit = false
    var isOpen = false; private set

    /** Swipe left on the panel closes it. */
    private val panel = object : LinearLayout(act) {
        private var x0 = 0f; private var y0 = 0f
        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x0 = e.x; y0 = e.y }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.x - x0; val dy = e.y - y0
                    if (dx < -dp(40) && Math.abs(dx) > Math.abs(dy) * 1.2f) { close(); return true }
                }
            }
            return false
        }
    }.apply {
        orientation = LinearLayout.VERTICAL; setBackgroundColor(c.bg); elevation = dp(8).toFloat(); visibility = View.GONE
        addView(tabs, LinearLayout.LayoutParams(-1, dp(48)))
        addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(foot, LinearLayout.LayoutParams(-1, dp(52)))
    }

    /** Adds scrim, panel and the left-edge swipe zone on top of [frame]. */
    fun attach(frame: FrameLayout) {
        frame.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        frame.addView(panel, FrameLayout.LayoutParams(width, -1))
        panel.translationX = -width.toFloat()
        val edge = View(act)
        var x0 = 0f; var y0 = 0f
        edge.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x0 = e.rawX; y0 = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - x0; val dy = e.rawY - y0
                    if (dx > dp(50) && Math.abs(dx) > Math.abs(dy) * 1.2f) { x0 = Float.MAX_VALUE; open() }
                }
            }
            true
        }
        frame.addView(edge, FrameLayout.LayoutParams(dp(14), -1, Gravity.START))
    }

    fun open() {
        if (isOpen) return
        isOpen = true; render()
        scrim.visibility = View.VISIBLE; panel.visibility = View.VISIBLE
        slide(true)
    }

    fun close() {
        if (!isOpen) return
        isOpen = false; edit = false
        slide(false)
    }

    private fun slide(show: Boolean) {
        val a = ValueAnimator.ofFloat(if (show) 0f else 1f, if (show) 1f else 0f).setDuration(220)
        a.addUpdateListener {
            val t = it.animatedValue as Float
            panel.translationX = -width * (1f - t); scrim.alpha = 0.4f * t
        }
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(an: android.animation.Animator) { if (!show) { scrim.visibility = View.GONE; panel.visibility = View.GONE } }
        })
        a.start()
    }

    /** Rebuilds the open drawer (peers changed, folder changed, favourites changed). */
    fun refresh() { if (isOpen) render() }

    // ---------------------------------------------------------------- building blocks
    private fun text(t: String, sp: Float, col: Int, bold: Boolean = false) = TextView(act).apply {
        text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(col)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun dot(warn: Boolean): View = View(act).apply {
        background = GradientDrawable().also { it.shape = GradientDrawable.OVAL; it.setColor(if (warn) c.warn else 0xFF2E7D32.toInt()) }
    }

    private fun glyph(g: String, col: Int = c.mut) = text(g, 20f, col).apply { gravity = Gravity.CENTER }

    private class Act(val label: String, val off: Boolean, val f: () -> Unit)

    private fun item(lead: View, title: String, sub: String?, on: Boolean, meter: Int? = null, xs: List<Act> = emptyList(), f: () -> Unit): View {
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(56)
            setPadding(dp(16), dp(6), dp(8), dp(6))
            if (on) setBackgroundColor(c.sel)
            isClickable = true; setOnClickListener { f() }
        }
        row.addView(lead, LinearLayout.LayoutParams(dp(36), dp(36)).also { it.rightMargin = dp(16) })
        val t = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        t.addView(text(title, 16f, c.fg, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        if (!sub.isNullOrEmpty()) t.addView(text(sub, 12f, c.mut).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        if (meter != null) t.addView(ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = meter },
            LinearLayout.LayoutParams(-1, dp(4)).also { it.topMargin = dp(4) })
        row.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        xs.forEach { a ->
            row.addView(text(a.label, 18f, c.fg).apply {
                gravity = Gravity.CENTER; alpha = if (a.off) 0.25f else 1f
                if (!a.off) setOnClickListener { a.f() }
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
        }
        return row
    }

    private fun sep() = View(act).apply { setBackgroundColor(c.bd) }
    private fun addSep() = body.addView(sep(), LinearLayout.LayoutParams(-1, dp(1)).also { it.topMargin = dp(8); it.bottomMargin = dp(8) })

    private fun section(title: String, action: Pair<String, () -> Unit>? = null) {
        val h = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(4), dp(8), dp(4)) }
        h.addView(text(title.uppercase(), 12f, c.mut, true).apply { letterSpacing = 0.08f }, LinearLayout.LayoutParams(0, -2, 1f))
        if (action != null) h.addView(text(action.first, 13f, c.ac, true).apply { setPadding(dp(12), dp(8), dp(12), dp(8)); setOnClickListener { action.second() } })
        body.addView(h)
    }

    private fun none(t: String) = body.addView(text(t, 14f, c.mut).apply { setPadding(dp(16), dp(12), dp(16), dp(12)) })

    private fun footBtn(label: String, f: () -> Unit) = foot.addView(
        text(label, 14f, c.fg, true).apply { gravity = Gravity.CENTER; isClickable = true; setOnClickListener { f() } },
        LinearLayout.LayoutParams(0, -1, 1f))

    private fun go(dev: String, path: String) {
        if (dev != "local" && !peerOk(dev)) { toast("\u26A0 That device is not available right now"); return }
        close(); ctl.open(dev, path)
    }

    private fun peerOk(id: String): Boolean {
        try { Core.discOrNull()?.list()?.firstOrNull { it.id == id }?.let { return it.ok } } catch (_: Throwable) { }
        try { if (Smb.peers().any { it.optString("id") == id }) return true } catch (_: Throwable) { }
        return false
    }

    private fun here(dev: String, path: String) = ctl.dev == dev && ctl.path == path

    private fun folderLead(name: String) = glyph("\uD83D\uDCC1")

    // ---------------------------------------------------------------- render
    private fun render() {
        // tabs
        tabs.removeAllViews()
        listOf("Folders", "Favorites", "Recent").forEachIndexed { n, t ->
            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setOnClickListener { tab = n; Prefs.drawerTab = n; edit = false; render() } }
            col.addView(text(t, 14f, if (tab == n) c.ac else c.mut, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, 0, 1f))
            col.addView(View(act).apply { setBackgroundColor(if (tab == n) c.ac else Color.TRANSPARENT) }, LinearLayout.LayoutParams(-1, dp(2)))
            tabs.addView(col, LinearLayout.LayoutParams(0, -1, 1f))
        }
        val keep = scroll.scrollY
        body.removeAllViews(); foot.removeAllViews()
        when (tab) { 0 -> folders(); 1 -> favorites(); else -> recent() }
        scroll.post { scroll.scrollTo(0, keep) }
    }

    private fun folders() {
        val used = if (ctl.dev == "local" && ctl.used != null) ctl.used else Prefs.used
        body.addView(item(glyph("\u2302"), "Home", "Main storage root", here("local", "/")) { go("local", "/") })
        body.addView(item(glyph("\u25A4"), "Main storage", if (ctl.dev == "local") "" else "Open on this device",
            ctl.dev == "local" && ctl.path != "/", used) { go("local", if (ctl.dev == "local") ctl.path else "/") })
        addSep()
        section("Devices")
        var n = 0
        try {
            Core.discOrNull()?.list()?.forEach { p ->
                n++
                val via = viaOf(p.ip)
                body.addView(item(dot(!p.ok), p.name, if (!p.ok) "not answering" else if (via.isNotEmpty()) via else "online", ctl.dev == p.id) { go(p.id, "/") })
            }
        } catch (_: Throwable) { }
        try {
            Smb.peers().forEach { s ->
                val id = s.optString("id"); if (id.isEmpty()) return@forEach
                n++
                body.addView(item(dot(false), s.optString("name").ifEmpty { id }, "SMB share", ctl.dev == id) { go(id, "/") })
            }
        } catch (_: Throwable) { }
        if (n == 0) none("Searching for devices\u2026")
        body.addView(item(glyph("\uD83D\uDDA8"), "Printers", "View only", false) { close(); onPrinters() })
        addSep()
        section("Quick folders", (if (edit) "Done" else "Edit") to { edit = !edit; render() })
        val q = Prefs.quick().toMutableList()
        q.forEachIndexed { i, f ->
            val sub = if (f.dev == "local") "" else devName(f.dev).let { if (it != f.dev) it else f.dn }
            val xs = if (!edit) emptyList() else listOf(
                Act("\u25B2", i == 0) { val m = q.toMutableList(); m.add(i - 1, m.removeAt(i)); Prefs.setQuick(m); render() },
                Act("\u25BC", i == q.size - 1) { val m = q.toMutableList(); m.add(i + 1, m.removeAt(i)); Prefs.setQuick(m); render() },
                Act("\u2715", false) { val m = q.toMutableList(); m.removeAt(i); Prefs.setQuick(m); render() })
            body.addView(item(folderLead(f.name), f.name, sub, !edit && here(f.dev, f.path), null, xs) {
                if (!edit) go(f.dev, f.path)
                else askInput("Rename shortcut", f.name, "Save") { nn ->
                    val m = q.toMutableList(); m[i] = Prefs.Loc(f.dev, f.path, nn.trim().take(40), f.dn); Prefs.setQuick(m); render()
                }
            })
        }
        if (edit) {
            val dup = q.any { it.dev == ctl.dev && it.path == ctl.path }
            val atRoot = ctl.path == "/"
            body.addView(item(glyph("\uFF0B"), "Add current folder",
                if (atRoot) "Open a folder first" else Prefs.baseName(ctl.path) + (if (ctl.dev == "local") "" else " \u00b7 " + devName(ctl.dev)), false) {
                if (atRoot) toast("Open the folder you want first, then add it")
                else if (dup) toast("Already in the list")
                else { Prefs.setQuick(q + Prefs.Loc(ctl.dev, ctl.path, Prefs.baseName(ctl.path), if (ctl.dev == "local") "" else devName(ctl.dev))); render() }
            }.also { if (dup || atRoot) it.alpha = 0.4f })
            body.addView(item(glyph("\u21BA"), "Reset to default", null, false) {
                Prefs.setQuick(listOf("Download", "DCIM", "Movies", "Pictures", "Music", "Documents").map { Prefs.Loc("local", "/$it", it) }); render()
            })
        }
        footBtn("Add IP") { close(); onAddIp() }
        footBtn("SMB") { close(); onSmb() }
        footBtn("Settings") { close(); onSettings() }
    }

    private fun favorites() {
        val cur = Prefs.isFav(ctl.dev, ctl.path)
        body.addView(item(glyph(if (cur) "\u2605" else "\u2606", 0xFFE2AC5F.toInt()), if (cur) "Remove this folder" else "Add this folder",
            Prefs.baseName(ctl.path).ifEmpty { devName(ctl.dev) } + (if (ctl.dev == "local") "" else " \u00b7 " + devName(ctl.dev)), false) {
            Prefs.toggleFav(ctl.dev, ctl.path, devName(ctl.dev)); render()
        })
        addSep()
        val l = Prefs.favs()
        if (l.isEmpty()) none("No favorites yet.\nTap the star above to pin the current folder.")
        l.forEach { f ->
            val dn = if (f.dev == "local") "This device" else devName(f.dev).let { if (it != f.dev) it else f.dn }
            body.addView(item(folderLead(f.name), f.name, dn + " \u00b7 " + f.path, here(f.dev, f.path), null,
                listOf(Act("\u2715", false) { Prefs.toggleFav(f.dev, f.path, f.dn); render() })) { go(f.dev, f.path) })
        }
    }

    private fun recent() {
        val l = Prefs.history()
        if (l.isEmpty()) none("Folders you open will show up here.")
        l.forEach { h ->
            val dn = if (h.dev == "local") "This device" else devName(h.dev)
            body.addView(item(folderLead(h.name), Prefs.baseName(h.path).ifEmpty { h.name }, dn + " \u00b7 " + h.path, here(h.dev, h.path), null,
                listOf(Act("\u2715", false) { Prefs.removeHistory(h.dev, h.path); render() })) { go(h.dev, h.path) })
        }
        if (l.isNotEmpty()) footBtn("Clear history") { Prefs.clearHistory(); render() }
    }
}
