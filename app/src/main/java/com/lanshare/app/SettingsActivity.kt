package com.lanshare.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.lanshare.app.core.*
import java.util.concurrent.Executors

/**
 * Native settings: device name, storage permission, SMB shares (add / edit / remove), network diagnostics, about.
 * Programmatic views like [BrowserActivity]; everything talks to [Cfg] / [Smb] / [Core] in-process.
 */
class SettingsActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(2) { r -> Thread(r, "settings").also { it.isDaemon = true } }
    private lateinit var box: LinearLayout
    private var cBg = 0; private var cCard = 0; private var cFg = 0; private var cMut = 0; private var cDiv = 0
    private val cAccent = 0xFF0D8F7E.toInt()
    private var alive = true

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Core.url == null) { startActivity(Intent(this, LauncherActivity::class.java)); finish(); return }
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        cBg = if (night) 0xFF121314.toInt() else 0xFFF4F5F6.toInt()
        cCard = if (night) 0xFF1E2022.toInt() else 0xFFFFFFFF.toInt()
        cFg = if (night) 0xFFEDEDED.toInt() else 0xFF1B1B1B.toInt()
        cMut = if (night) 0xFF9AA0A6.toInt() else 0xFF5F6368.toInt()
        cDiv = if (night) 0xFF2C2F31.toInt() else 0xFFE3E5E8.toInt()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(cBg) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(0xFF1C1C1E.toInt()) }
        top.addView(TextView(this).apply {
            text = "←"; textSize = 22f; gravity = Gravity.CENTER; setTextColor(0xFFFFFFFF.toInt()); setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(52), dp(56)))
        top.addView(TextView(this).apply { text = "Settings"; textSize = 18f; setTypeface(null, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt()) })
        root.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(24)) }
        root.addView(ScrollView(this).apply { addView(box) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        render()
    }

    override fun onResume() { super.onResume(); if (::box.isInitialized) render() }
    override fun onDestroy() { alive = false; super.onDestroy() }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun header(t: String) = TextView(this).apply {
        text = t.uppercase(); textSize = 12f; setTypeface(null, Typeface.BOLD); setTextColor(cAccent); setPadding(dp(8), dp(18), dp(8), dp(6))
    }

    private fun row(title: String, sub: String?, click: (() -> Unit)? = null): LinearLayout {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(cCard); setStroke(1, cDiv) }
            if (click != null) setOnClickListener { click() }
        }
        r.addView(TextView(this).apply { text = title; textSize = 15f; setTypeface(null, Typeface.BOLD); setTextColor(cFg) })
        if (!sub.isNullOrEmpty()) r.addView(TextView(this).apply { text = sub; textSize = 12.5f; setTextColor(cMut) })
        return r
    }

    private fun add(v: View) = box.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })

    private fun render() {
        box.removeAllViews()

        add(header("This device"))
        add(row("Device name", Cfg.name + "\nOther LANShare devices see this name. Tap to change.") { renameDialog() })
        add(row("Device ID", Cfg.id))

        add(header("Storage"))
        if (Core.storageOk == false) {
            add(row("All files access: OFF", "Without it LANShare cannot see most files. Tap to allow.") {
                try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
                catch (_: Exception) { toast(STORAGE_MSG) }
            })
        } else add(row("All files access: OK", null))

        add(header("SMB shares (Windows PC / NAS)"))
        val shares = try { Smb.status() } catch (_: Exception) { null }
        if (shares == null || shares.length() == 0) add(row("No SMB shares", "Add the address of a PC or NAS to browse its drives."))
        else for (i in 0 until shares.length()) {
            val o = shares.getJSONObject(i)
            val id = o.getString("id")
            val who = o.optString("user", "").ifEmpty { "guest" }
            add(row(o.getString("name"), o.optString("host") + " · " + who + (if (o.optBoolean("ok", true)) "" else " · not reachable")) { editSmb(id) })
        }
        add(row("＋ Add SMB share", null) { smbDialog(null) })

        add(header("Network"))
        val d = Core.discOrNull()
        val ifs = try { d?.ifaces ?: emptyList() } catch (_: Exception) { emptyList() }
        add(row("This phone's addresses",
            if (ifs.isEmpty()) "No Wi-Fi / hotspot network found - connect to a network (or switch the hotspot on)."
            else ifs.joinToString("\n") { it.ip + "/" + it.net.prefix } + "\nServer: " + (Core.url ?: "-")))
        val peers = try { d?.list() ?: emptyList() } catch (_: Exception) { emptyList() }
        add(row("LANShare devices found: ${peers.size}",
            if (peers.isEmpty()) "None yet. Both devices must be on the same Wi-Fi / hotspot with LANShare open." else peers.joinToString("\n") { it.name + " · " + it.ip + ":" + it.port + (if (it.ok) "" else " · offline") }))
        add(row("Scan the network again", "Refresh addresses and look for devices.") { Core.rescan(); toast("Scanning…"); ui.postDelayed({ if (alive) render() }, 2500) })
        add(row("Add LANShare device by IP", "If automatic discovery cannot see a device.") { ipDialog() })

        add(header("About"))
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" }
        add(row("LANShare $ver", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MODEL}"))
    }

    private fun field(box: LinearLayout, hint: String, initial: String = "", pwd: Boolean = false): EditText {
        val et = EditText(this).apply {
            this.hint = hint; setSingleLine(); setText(initial)
            inputType = if (pwd) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        box.addView(et)
        return et
    }

    private fun form(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }

    private fun renameDialog() {
        val f = form()
        val et = field(f, "Device name", Cfg.name)
        AlertDialog.Builder(this).setTitle("Device name").setView(f)
            .setPositiveButton("Save") { _, _ ->
                val n = et.text.toString().trim().take(40)
                if (n.isNotEmpty()) {
                    Cfg.name = n; Cfg.nameCustom = true; Cfg.save()
                    try { Core.discOrNull()?.announce() } catch (_: Exception) {}
                    render()
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun ipDialog() {
        val f = form()
        val et = field(f, "IP address, e.g. 192.168.1.20")
        AlertDialog.Builder(this).setTitle("Add LANShare device").setView(f)
            .setPositiveButton("Add") { _, _ ->
                val ip = et.text.toString().trim()
                if (ip.isEmpty()) return@setPositiveButton
                toast("Looking for a LANShare device…")
                io.execute {
                    val err = try { if (Core.disc.addIp(ip)) null else "no LANShare device found at that address" } catch (e: Exception) { errText(e) }
                    ui.post { if (!alive) return@post; toast(err ?: "Device added"); render() }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun editSmb(id: String) {
        val c = Smb.cfg(id) ?: run { render(); return }
        AlertDialog.Builder(this).setTitle(c.optString("name"))
            .setItems(arrayOf("Edit", "Remove")) { _, w -> if (w == 0) smbDialog(id) else removeSmb(id, c.optString("name")) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun removeSmb(id: String, name: String) {
        AlertDialog.Builder(this).setTitle("Remove “$name”?").setMessage("The saved login is deleted from this phone. Files on the PC are not touched.")
            .setPositiveButton("Remove") { _, _ ->
                io.execute { try { Smb.remove(id) } catch (_: Exception) {}; ui.post { if (alive) render() } }
            }.setNegativeButton("Cancel", null).show()
    }

    /** [id] null = new share, otherwise edit (empty password field keeps the saved password). */
    private fun smbDialog(id: String?) {
        val old = id?.let { Smb.cfg(it) }
        val f = form()
        val host = field(f, "PC address (IP, optional :port)", old?.optString("host") ?: "")
        val user = field(f, "Username (empty = guest)", old?.optString("user") ?: "")
        val pass = field(f, if (old != null) "Password (empty = keep saved)" else "Password", "", true)
        val name = field(f, "Display name (optional)", old?.optString("name") ?: "")
        AlertDialog.Builder(this).setTitle(if (old != null) "Edit SMB share" else "Add SMB share").setView(f)
            .setPositiveButton(if (old != null) "Save" else "Connect") { _, _ ->
                val h = host.text.toString(); val u = user.text.toString(); val pw = pass.text.toString(); val n = name.text.toString()
                toast("Connecting…")
                io.execute {
                    val err = try {
                        if (old != null && id != null) Smb.update(id, h, u, if (pw.isEmpty() && u == old.optString("user")) null else pw, n) else Smb.add(h, u, pw, n)
                        null
                    } catch (e: Exception) { errText(e) }
                    ui.post {
                        if (!alive) return@post
                        if (err == null) render()
                        else AlertDialog.Builder(this).setTitle("Could not connect").setMessage(err).setPositiveButton("OK", null).show()
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }
}
