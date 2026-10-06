package com.lanshare.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.lanshare.app.core.Core
import com.lanshare.app.core.Smb
import com.lanshare.app.core.errText
import org.json.JSONArray

/**
 * Native replacement for ui.html `openSettings` + `openSmb` (WebView-free Settings).
 *  Settings: show hidden files, theme (Light / Dark / Follow system), SMB shares, this device's addresses.
 *  SMB (PC drives): the saved PCs (Remove), PCs found on the network (port 445 scan + NetBIOS name, Rescan, Add), manual Add PC
 *  (address, user, password, display name), Connect = tests the login first ([Smb.add]) and saves it; same wording as ui.html.
 * Settings are stored where the native screen reads them ([Prefs]); the SMB list lives in the core config ([Smb] / Cfg), shared with the server.
 *
 * NOT compiled / NOT device-tested.
 */
class NativeSettings(
    private val act: Activity,
    private val c: NlTheme.Cols,
    private val currentDev: () -> String,
    private val goLocal: () -> Unit,
    private val hiddenChanged: () -> Unit,
    private val say: (String, Boolean) -> Unit,
    private val smbChanged: () -> Unit
) {
    private val d: Float = act.resources.displayMetrics.density
    private fun dp(v: Int) = Math.round(v * d)

    private fun tv(t: String, size: Float, col: Int, bold: Boolean = false): TextView = TextView(act).apply {
        text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setTextColor(col)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun btn(label: String, fill: Boolean = false, danger: Boolean = false, f: () -> Unit): Button = Button(act).apply {
        text = label; isAllCaps = false; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(if (fill) c.onac else if (danger) 0xFFB3261E.toInt() else c.fg)
        background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(if (fill) c.ac else c.cont) }
        minHeight = 0; minimumHeight = dp(36); setPadding(dp(14), 0, dp(14), 0)
        setOnClickListener { f() }
    }

    /** Title + small grey line (ui.html `.set` row). */
    private fun row(title: String, sub: String): LinearLayout = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(10), dp(4), dp(10))
        addView(tv(title, 16f, c.fg, true))
        if (sub.isNotEmpty()) addView(tv(sub, 12f, c.mut))
    }

    private fun box(): LinearLayout = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }

    // ---------------------------------------------------------------- Settings
    fun show() {
        val col = box()
        var dlg: AlertDialog? = null

        // Show hidden files
        val hid = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val sw = Switch(act).apply { isChecked = Prefs.hidden }
        sw.setOnCheckedChangeListener { _, on -> if (on != Prefs.hidden) { Prefs.hidden = on; hiddenChanged(); say(if (on) "Showing hidden files" else "Hiding hidden files", false) } }
        hid.addView(row("Show hidden files", "Files and folders starting with a dot (.)"), LinearLayout.LayoutParams(0, -2, 1f))
        hid.addView(sw)
        col.addView(hid)

        // Theme: tap = next (Light -> Dark -> Follow system); the screen restarts to repaint
        val names = mapOf("light" to "Light", "dark" to "Dark", "auto" to "Follow system")
        val th = row("Theme", names[Prefs.theme] ?: "Light")
        th.isClickable = true
        th.setOnClickListener {
            Prefs.theme = when (Prefs.theme) { "light" -> "dark"; "dark" -> "auto"; else -> "light" }
            dlg?.dismiss()
            act.recreate()
        }
        col.addView(th)

        // SMB shares
        val smbSub = "Browse all drives of a PC or NAS"
        val sm = row("SMB shares", smbSub)
        sm.isClickable = true
        sm.setOnClickListener { dlg?.dismiss(); smb() }
        col.addView(sm)
        Thread {
            val n = try { Smb.status().length() } catch (_: Throwable) { 0 }
            act.runOnUiThread { if (n > 0) (sm.getChildAt(1) as TextView).text = n.toString() + (if (n == 1) " PC" else " PCs") }
        }.also { it.isDaemon = true }.start()

        // This device
        val ips = try { Core.discOrNull()?.ownIps?.sorted()?.joinToString(", ") ?: "" } catch (_: Throwable) { "" }
        if (ips.isNotEmpty()) col.addView(row("This device", ips))

        dlg = AlertDialog.Builder(act).setTitle("Settings").setView(ScrollView(act).apply { addView(col) })
            .setPositiveButton("Done", null).create()
        dlg.show()
    }

    // ---------------------------------------------------------------- SMB (PC drives)
    private fun smb() {
        val col = box()
        col.addView(tv("Add a PC or NAS: enter its IP address (e.g. 192.168.1.20) and a Windows account on it. All its drives (C\$, D\$ \u2026) and shared folders are listed; the PC appears next to your devices at the top. Drives like C\$ need an administrator account.",
            13f, c.mut).apply { setPadding(dp(4), 0, dp(4), dp(8)) })
        val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val found = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list); col.addView(found)

        var cur: JSONArray = JSONArray()
        var lastFound: JSONArray? = null     // null = scanning
        var scanGen = 0
        var dialog: AlertDialog? = null

        fun field(hint: String, pw: Boolean = false): EditText = EditText(act).apply {
            this.hint = hint; setSingleLine(); setTextColor(c.fg); setHintTextColor(c.mut)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            inputType = InputType.TYPE_CLASS_TEXT or (if (pw) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        }
        val fHost = field("Server address (e.g. 192.168.1.20)")
        val fUser = field("Username (empty = guest)")
        val fPass = field("Password", pw = true)
        val fName = field("Display name (optional)")
        val form = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(0, dp(8), 0, dp(8))
            addView(fHost); addView(fUser); addView(fPass); addView(fName)
        }
        col.addView(form)

        lateinit var add: Button
        lateinit var save: Button

        fun hostsOf(a: JSONArray): Set<String> = (0 until a.length()).map { a.getJSONObject(it).optString("host").substringBefore(':') }.toSet()

        lateinit var drawFound: () -> Unit
        lateinit var scanNow: () -> Unit
        fun drawList() {
            list.removeAllViews()
            if (cur.length() == 0) list.addView(tv("No PCs yet", 13f, c.mut).apply { setPadding(dp(4), dp(8), dp(4), dp(8)) })
            for (k in 0 until cur.length()) {
                val x = cur.getJSONObject(k)
                val line = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                val share = x.optString("share"); val user = x.optString("user")
                line.addView(row(x.optString("name"), "\\\\" + x.optString("host") + (if (share.isNotEmpty()) "\\" + share else "") + (if (user.isNotEmpty()) " \u00b7 $user" else " \u00b7 guest")),
                    LinearLayout.LayoutParams(0, -2, 1f))
                line.addView(btn("Remove", danger = true) {
                    val id = x.optString("id")
                    Thread {
                        try { Smb.remove(id) } catch (e: Throwable) { act.runOnUiThread { say("\u26A0 " + errText(e), true) }; return@Thread }
                        act.runOnUiThread { if (currentDev() == id) goLocal(); smbChanged(); cur = try { Smb.status() } catch (_: Throwable) { JSONArray() }; drawList(); drawFound() }
                    }.also { it.isDaemon = true }.start()
                })
                list.addView(line)
            }
        }
        drawFound = {
            found.removeAllViews()
            val hd = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val left = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(10), dp(4), dp(6)) }
            left.addView(tv("Found on the network", 16f, c.fg, true))
            if (lastFound == null) left.addView(tv("Scanning for PCs\u2026 (up to 20 s)", 12f, c.mut))
            hd.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
            hd.addView(btn("Rescan") { }.also { b -> b.isEnabled = lastFound != null; b.setOnClickListener { scanNow() } })
            found.addView(hd)
            val lf = lastFound
            if (lf != null) {
                val have = hostsOf(cur)
                val fl = (0 until lf.length()).map { lf.getJSONObject(it) }.filter { it.optString("ip") !in have }
                if (fl.isEmpty()) found.addView(tv(if (lf.length() > 0) "All PCs found are already added" else "No PC with file sharing found. Use Add PC to type an address.", 12f, c.mut).apply { setPadding(dp(4), 0, dp(4), dp(6)) })
                for (f in fl) {
                    val line = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    val nm = f.optString("name")
                    line.addView(row(nm.ifEmpty { f.optString("ip") }, "\\\\" + f.optString("ip")), LinearLayout.LayoutParams(0, -2, 1f))
                    line.addView(btn("Add", fill = true) {
                        fHost.setText(f.optString("ip")); fName.setText(nm); fUser.setText(""); fPass.setText("")
                        form.visibility = View.VISIBLE; add.visibility = View.GONE; save.visibility = View.VISIBLE
                        fUser.requestFocus()
                        say("Enter the PC\u2019s Windows username and password", true)
                    })
                    found.addView(line)
                }
            }
        }
        scanNow = {
            val g = ++scanGen
            lastFound = null; drawFound()
            Thread {
                val r = try { Core.discOrNull()?.smbScan() ?: JSONArray() } catch (_: Throwable) { JSONArray() }
                act.runOnUiThread { if (g == scanGen && dialog?.isShowing == true) { lastFound = r; drawFound() } }
            }.also { it.isDaemon = true }.start()
        }
        add = btn("Add PC", fill = true) { form.visibility = View.VISIBLE; add.visibility = View.GONE; save.visibility = View.VISIBLE; fHost.requestFocus() }
        save = btn("Connect", fill = true) {
            val host = fHost.text.toString()
            if (host.isBlank()) { say("Enter the PC\u2019s IP address", true); return@btn }
            val user = fUser.text.toString(); val pass = fPass.text.toString(); val name = fName.text.toString()
            save.isEnabled = false; save.text = "Connecting\u2026"
            Thread {
                val err = try { Smb.add(host, user, pass, name); null } catch (e: Throwable) { errText(e) }
                act.runOnUiThread {
                    save.isEnabled = true; save.text = "Connect"
                    if (err != null) { say("\u26A0 $err", true); return@runOnUiThread }
                    for (f in listOf(fHost, fUser, fPass, fName)) f.setText("")
                    form.visibility = View.GONE; save.visibility = View.GONE; add.visibility = View.VISIBLE
                    say("Connected!", false)
                    smbChanged()
                    cur = try { Smb.status() } catch (_: Throwable) { JSONArray() }
                    drawList(); drawFound()
                }
            }.also { it.isDaemon = true }.start()
        }
        save.visibility = View.GONE
        val acts = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, dp(8), 0, 0) }
        acts.addView(btn("Done") { dialog?.dismiss(); show() }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
        acts.addView(add, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
        acts.addView(save)
        col.addView(acts)

        cur = try { Smb.status() } catch (_: Throwable) { JSONArray() }
        drawList(); drawFound()
        val dlg = AlertDialog.Builder(act).setTitle("SMB (PC drives)").setView(ScrollView(act).apply { addView(col) }).create()
        dialog = dlg
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dlg.show()
        scanNow()
    }
}
