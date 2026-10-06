package com.lanshare.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import com.lanshare.app.core.Core
import com.lanshare.app.core.Jobs
import com.lanshare.app.core.Smb
import com.lanshare.app.core.WifiPrinters
import com.lanshare.app.core.viaOf
import com.lanshare.app.core.vjoin
import org.json.JSONObject

/**
 * Native replacement for ui.html's print flow (doPrint -> choosePrinter/pickPrinter -> wifiOptions / printOptions), PART A
 * (+ PART B item 1: PCs now open [NativePrintPc], the FinePrint-style dialog).
 *
 * DONE here (no WebView, no JS bridge, no local HTTP round-trip):
 *  - "Print on..." picker: This phone (Android print system), printers on this Wi-Fi + other devices' printers, PCs (LANShare peers + SMB).
 *    Last used target first, "Search for printers" re-scan, results fill in while the dialog is open.
 *  - This phone: files are read straight from the endpoint ([PhonePrint.startSources]) and handed to the Android print framework.
 *  - Simple options dialog for Wi-Fi printers (copies, sides, colour, paper, pages per sheet, border, scaling, page range); live printer
 *    status line (ready / problem / not reachable); Sides / Colour hidden when the printer says it cannot do them; last choice remembered.
 *  - Submit through [FsController.print] -> Jobs.startPrint (same job, progress bar + cancel as copy / zip).
 *
 * DONE in part B item 1 (NativePrintPc.kt): FinePrint-style PC dialog: printer chooser, booklet, odd/even pages, reverse, margins,
 *    custom scale, alignment, auto-rotate, watermark, header / footer, presets. Wi-Fi printers keep the simple dialog [options].
 *
 * DONE in part B items 2-5 (HANDOVER_NATIVE_LIST.md 2t):
 *  - Live page preview strip ([PrintPv]) in both dialogs; picture mode of the PC dialog can turn single pictures.
 *  - Picker with one card per device: how it is connected (Wi-Fi / Mobile data / Tailscale), "N printer(s)" / "No printer access" / offline,
 *    and each PC's printer + status line.
 *  - [startShared]: documents printed from other apps (PcPrintService -> /LANShare Shared); [showPrinters]: view-only picker (drawer "Printers").
 *
 * NOT compiled / NOT device-tested.
 */
class NativePrint(
    private val act: Activity,
    private val ctl: FsController,
    private val c: NlTheme.Cols,
    private val say: (String) -> Unit
) {
    private class Target(val id: String, val name: String, val sub: String, val kind: Int)
    /** kind: 0 Wi-Fi, 1 Tailscale, 2 mobile data, 3 info (blue), 4 off (grey), 5 bad (red) - ui.html CHIPC. */
    private class Chip(val text: String, val kind: Int)
    private class Row(val head: Boolean, val title: String, val sub: String, val chips: List<Chip>, val target: Target?)
    private class Sec(val title: String, val conn: Chip, var acc: Chip?, val items: ArrayList<Row> = ArrayList())

    private companion object {
        const val PHONE = 0
        const val WIFI = 1
        const val PC = 2
        const val HERE_ID = "__here"
        val GREEN = 0xFF4CAF50.toInt()
        val CHIP_FG = intArrayOf(0xFF2E7D32.toInt(), 0xFF6A1B9A.toInt(), 0xFFE65100.toInt(), 0xFF1565C0.toInt(), 0xFF757575.toInt(), 0xFFC62828.toInt())
        val CHIP_BG = intArrayOf(0x262E7D32, 0x266A1B9A, 0x29EF6C00, 0x261565C0, 0x2E787878, 0x24C62828)
        const val SHARED_DIR = "/LANShare Shared"
    }

    private val d: Float = act.resources.displayMetrics.density
    private fun dp(v: Int) = Math.round(v * d)
    private val sp by lazy { act.getSharedPreferences("ls_print_ui", Context.MODE_PRIVATE) }
    private val phone by lazy { PhonePrint(act) }
    private val pcDlg by lazy { NativePrintPc(act, ctl, c, sp) }   // FinePrint-style dialog for PCs (part B, item 1)

    private fun text(t: String, size: Float, col: Int, bold: Boolean = false): TextView = TextView(act).apply {
        text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setTextColor(col)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    // ---------------------------------------------------------------- entry
    /** The Print button of the selection dock: only offered when files are selected (never folders), like ui.html. */
    fun start() {
        if (ctl.selFiles().isEmpty()) { say("Select files first, then pick a printer"); return }
        val set = PrintSet(ctl.dev, ctl.path, ctl.selFiles()) { ctl.clearSel() }
        picker { choose(it, set) }
    }

    /** A document printed from another app through Android's print dialog (PcPrintService) lands in /LANShare Shared: same flow for those files. */
    fun startShared(names: List<String>) {
        if (names.isEmpty()) return
        Thread {
            val found = try { Jobs.ep("local").ls(SHARED_DIR).filter { !it.dir } } catch (_: Throwable) { emptyList() }
            val items = names.mapNotNull { n -> found.firstOrNull { it.name == n } }
            act.runOnUiThread {
                if (items.isEmpty()) { say("The printed document was not found in LANShare Shared"); return@runOnUiThread }
                val set = PrintSet("local", SHARED_DIR, items)
                picker { choose(it, set) }
            }
        }.also { it.isDaemon = true }.start()
    }

    /** View-only "Printers" list (drawer entry of ui.html): shows the devices and printers, choosing one prints nothing. */
    fun showPrinters() = picker(null)

    // ---------------------------------------------------------------- picker ("Print on...")
    private fun chipView(ch: Chip): TextView = text(ch.text, 11f, CHIP_FG[ch.kind]).apply {
        setPadding(dp(7), dp(1), dp(7), dp(1)); maxLines = 1
        background = GradientDrawable().apply { cornerRadius = dp(9).toFloat(); setColor(CHIP_BG[ch.kind]) }
    }

    /** This phone's own connection (ui.html: navigator.connection.type). */
    private fun phoneConn(): Chip = try {
        val cm = act.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        when {
            caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Chip("Mobile data", 2)
            caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Chip("Wi-Fi", 0)
            else -> Chip("This phone", 0)
        }
    } catch (_: Throwable) { Chip("This phone", 0) }

    private fun count(n: Int) = Chip("$n printer" + (if (n == 1) "" else "s"), 3)

    /**
     * One card per device (ui.html pickPrinter): name + how it is connected (Wi-Fi / Mobile data / Tailscale) + what it gives
     * ("N printers", "No printer access", offline, checking...), its printers as tap targets under it. [onPick] == null = view only.
     */
    private fun picker(onPick: ((Target) -> Unit)?) {
        val rows = ArrayList<Row>()
        val last = sp.getString("printTo", "") ?: ""
        val adapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(i: Int) = rows[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun isEnabled(i: Int) = rows[i].target != null
            override fun areAllItemsEnabled() = false
            override fun getView(i: Int, cv: View?, parent: ViewGroup): View {
                val r = rows[i]
                val box = LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(if (r.head) 20 else 36), dp(if (r.head) 12 else 8), dp(20), dp(8))
                }
                val line = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                val on = r.target != null && r.target.id == last
                line.addView(text(r.title, if (r.head) 15f else 14f, if (on) c.ac else c.fg, r.head || on).apply {
                    maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(-2, -2, 0f))
                for (ch in r.chips) line.addView(chipView(ch), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
                box.addView(line)
                if (r.sub.isNotEmpty()) box.addView(text(r.sub, 12f, c.mut))
                return box
            }
        }
        val status = text("Looking for printers\u2026", 12f, c.mut).apply { setPadding(dp(20), dp(4), dp(20), dp(4)) }
        val lv = ListView(act).apply { this.adapter = adapter }
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(lv, LinearLayout.LayoutParams(-1, -2))
        }
        val dlg = AlertDialog.Builder(act).setTitle(if (onPick == null) "Printers" else "Print on\u2026").setView(box)
            .setNegativeButton(if (onPick == null) "Close" else "Cancel", null)
            .setNeutralButton("Search for printers", null)   // listener set after show(): the dialog stays open
            .create()
        lv.setOnItemClickListener { _, _, i, _ ->
            val t = rows.getOrNull(i)?.target ?: return@setOnItemClickListener
            dlg.dismiss()
            if (onPick != null) onPick(t)
        }

        var gen = 0
        fun load(scan: Boolean) {
            val g = ++gen
            status.visibility = View.VISIBLE
            status.text = if (scan) "Searching the network\u2026" else "Looking for printers\u2026"
            Thread {
                // printers on this Wi-Fi (mDNS / port 631 scan) + the printers other LANShare devices see
                class W(val id: String, val name: String, val model: String, val viaId: String)
                val wifi = ArrayList<W>()
                try {
                    val a = if (scan) WifiPrinters.searchNow() else WifiPrinters.listJson(true)
                    try { val rm = WifiPrinters.remoteJson(); for (k in 0 until rm.length()) a.put(rm.get(k)) } catch (_: Throwable) { }
                    for (k in 0 until a.length()) { val o = a.getJSONObject(k); wifi.add(W(o.getString("id"), o.optString("name"), o.optString("model"), o.optString("viaId"))) }
                } catch (_: Throwable) { }
                fun wlabel(w: W) = w.name + (if (w.model.isNotEmpty() && w.model != w.name) " (" + w.model + ")" else "")
                fun wrow(w: W, tag: String) = Row(false, wlabel(w), tag, emptyList(), Target(w.id, w.name, tag, WIFI))

                val secs = ArrayList<Sec>()
                val here = Sec("This phone", phoneConn(), Chip("Android print", 3))
                here.items.add(Row(false, "Any printer (Android print)", "", emptyList(), Target(HERE_ID, "This phone", "Any printer Android print knows", PHONE)))
                secs.add(here)

                // PCs running pcprint.py: SMB shares first, then LANShare devices (ui.html order)
                class P(val id: String, val name: String, val smb: Boolean, val ok: Boolean, val ts: Boolean)
                val peers = ArrayList<P>()
                try { Smb.peers().forEach { s -> peers.add(P(s.optString("id"), s.optString("name"), true, s.optBoolean("ok", true), false)) } } catch (_: Throwable) { }
                try { Core.discOrNull()?.list()?.forEach { p -> peers.add(P(p.id, p.name, false, p.ok, viaOf(p.ip) == "Tailscale")) } } catch (_: Throwable) { }
                val probe = ArrayList<Pair<P, Sec>>()
                for (p in peers) {
                    val sec = Sec(p.name, if (p.ts) Chip("Tailscale", 1) else Chip("Wi-Fi", 0), null)
                    secs.add(sec)
                    if (!p.ok && !p.smb) { sec.acc = Chip("offline", 4); continue }
                    wifi.filter { it.viaId == p.id }.forEach { sec.items.add(wrow(it, "Wi-Fi printer")) }
                    sec.acc = if (sec.items.isNotEmpty()) count(sec.items.size) else Chip("checking\u2026", 4)
                    probe.add(p to sec)
                }
                val lan = wifi.filter { it.viaId.isEmpty() }
                if (lan.isNotEmpty()) {
                    val sec = Sec("Printers on this Wi-Fi", Chip("Wi-Fi", 0), null)
                    lan.forEach { sec.items.add(wrow(it, "Wi-Fi printer")) }
                    sec.acc = count(sec.items.size)
                    secs.add(sec)
                }

                fun flatten(): List<Row> {
                    val out = ArrayList<Row>()
                    // the last used target first inside its card
                    for (sec in secs) {
                        out.add(Row(true, sec.title, "", listOfNotNull(sec.conn, sec.acc), null))
                        out.addAll(sec.items.sortedByDescending { it.target?.id == last })
                    }
                    return out
                }
                act.runOnUiThread {
                    if (g != gen || !dlg.isShowing) return@runOnUiThread
                    rows.clear(); rows.addAll(flatten()); adapter.notifyDataSetChanged()
                    if (lan.isEmpty()) status.text = if (peers.isNotEmpty()) "No printers found on this Wi-Fi yet." else "No devices or printers found yet."
                    else status.visibility = View.GONE
                }
                // each PC: ask its print service which printer it has and how it is (ui.html printInfo), update the card when it answers
                for ((p, sec) in probe) Thread {
                    val r: JSONObject? = try { Jobs.printerStatus(p.id) } catch (_: Throwable) { null }
                    val pr = if (r == null || !r.optBoolean("ok", false) || r.isNull("printer")) "" else r.optString("printer", "")
                    val problem = if (r == null || r.isNull("problem")) "" else r.optString("problem", "")
                    act.runOnUiThread {
                        if (g != gen || !dlg.isShowing) return@runOnUiThread
                        if (pr.isNotEmpty()) sec.items.add(0, Row(false, pr + (if (problem.isNotEmpty()) " \u00b7 $problem" else ""), "PC print service", emptyList(), Target(p.id, p.name, "PC print service", PC)))
                        sec.acc = if (sec.items.isNotEmpty()) count(sec.items.size) else Chip("No printer access", 4)
                        rows.clear(); rows.addAll(flatten()); adapter.notifyDataSetChanged()
                    }
                }.also { it.isDaemon = true }.start()
            }.also { it.isDaemon = true }.start()
        }

        dlg.show()
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { load(true) }
        load(false)
    }

    private fun choose(t: Target, set: PrintSet) {
        sp.edit().putString("printTo", t.id).apply()
        when (t.kind) {
            PHONE -> printHere(set)
            PC -> pcDlg.show(t.id, t.name, set)   // full dialog: printer, presets, booklet, odd/even, margins, scale, watermark, header/footer ...
            else -> pcDlg.show(t.id, t.name, set, true)   // Wi-Fi printer (IPP): the same full dialog; the phone lays the sheets out (WifiCompose)
        }
    }

    // ---------------------------------------------------------------- this phone (Android print framework)
    private fun printHere(set: PrintSet) {
        val dev = set.dev
        if (set.files.isEmpty()) { say("Select files to print"); return }
        val srcs = set.files.map { i ->
            val p = vjoin(set.dir, i.name)
            PhonePrint.Src(i.name) { Jobs.ep(dev).open(p) }
        }
        set.after()
        phone.startSources(srcs)
    }

    // ---------------------------------------------------------------- options (Wi-Fi printer or PC)
    private fun chip(label: String): TextView = text(label, 13f, c.fg, true).apply {
        gravity = Gravity.CENTER; setPadding(dp(14), 0, dp(14), 0); minWidth = dp(40); isClickable = true
    }

    private fun paint(b: TextView, on: Boolean) {
        b.setTextColor(if (on) c.onac else c.fg)
        b.background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(if (on) c.ac else c.cont) }
    }

    /** Defaults; a value equal to its default is not sent (same as ui.html: copies 1, nup 1, border 0, empty = printer default). */
    private val defaults = linkedMapOf("copies" to "1", "duplex" to "", "color" to "", "paper" to "", "fit" to "", "nup" to "1", "border" to "0")

}
