package com.lanshare.app.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * USB / OTG drives (HANDOVER chapter 18). A memory stick / disk on an OTG cable is mounted by Android as a storage volume (/storage/XXXX-XXXX);
 * LANShare already holds "All files access", so the drive is just another [LocalFs] rooted at that folder: id "otg:XXXX-XXXX", listed with the
 * other places (kind "USB"), and every copy / move / search / thumbnail / viewer that works for a folder works for it. No USB library, no extra permission.
 *
 * Three switches (Settings > USB / OTG drives): [Cfg.otgOn] show the drives at all (default on), [Cfg.otgAuto] ui.html opens a drive when it is
 * plugged in (default off), [Cfg.otgRo] read-only (default off; enforced in [LocalFs.checkWritable], so every write path stops).
 *
 * Differences from the phone's own storage: no recycle bin (deleting on a drive is for good), no name index, never shared with other devices (/p/ routes serve only [Core.local]).
 * Limits: exFAT / NTFS / FAT32 work only as far as the phone's Android mounts them; Android 11+ needs "All files access" ([Core.storageOk]) to read /storage/XXXX-XXXX.
 * A USB stick is told from an SD card by its description ("SD card") and by a USB mass-storage device being attached (see [isUsb]). NOT compiled / NOT device-tested.
 */
object Otg {
    class Drive(val id: String, val name: String, val path: String, val ok: Boolean, val why: String)

    private const val TTL_MS = 1_500L
    private val lock = Any()
    private var cache: List<Drive> = emptyList()
    private var cacheAt = 0L
    private val fsById = HashMap<String, LocalFs>()   // one LocalFs per drive (key = id + path: a re-mounted drive can get another path)
    @Volatile private var started = false

    /** Registers the plug / unplug receivers (once). The list is also re-read every [TTL_MS], so a missed broadcast only delays an update. */
    fun start(ctx: Context) {
        if (started) return
        started = true
        val r = object : BroadcastReceiver() { override fun onReceive(c: Context?, i: Intent?) { invalidate() } }
        try {
            val media = IntentFilter().apply {
                for (a in listOf(Intent.ACTION_MEDIA_MOUNTED, Intent.ACTION_MEDIA_UNMOUNTED, Intent.ACTION_MEDIA_EJECT, Intent.ACTION_MEDIA_REMOVED, Intent.ACTION_MEDIA_BAD_REMOVAL)) addAction(a)
                addDataScheme("file")
            }
            ContextCompat.registerReceiver(ctx, r, media, ContextCompat.RECEIVER_EXPORTED)   // harmless to spoof: all it does is drop a 1.5 s cache
            val usb = IntentFilter().apply { addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
            ContextCompat.registerReceiver(ctx, r, usb, ContextCompat.RECEIVER_EXPORTED)
        } catch (_: Throwable) {}
    }

    fun invalidate() { synchronized(lock) { cacheAt = 0L } }

    /** The USB drives plugged in right now (empty while the switch is off). */
    fun drives(): List<Drive> {
        if (!Cfg.otgOn()) return emptyList()
        val ctx = Core.appCtx ?: return emptyList()
        synchronized(lock) {
            val now = System.currentTimeMillis()
            if (now - cacheAt < TTL_MS) return cache
            cache = try { scan(ctx) } catch (_: Throwable) { emptyList() }
            cacheAt = now
            val live = cache.map { it.id + "|" + it.path }.toSet()
            fsById.keys.retainAll(live)   // an unplugged drive's LocalFs goes with it
            return cache
        }
    }

    private fun scan(ctx: Context): List<Drive> {
        val usbAttached = massStorageAttached(ctx)
        val out = ArrayList<Drive>()
        for (v in Volumes.extra(ctx)) {
            if (!v.removable || !isUsb(v.name, usbAttached)) continue
            val dir = File(v.path)
            val readable = try { dir.list() != null } catch (_: Throwable) { false }
            val why = if (readable) "" else if (Build.VERSION.SDK_INT >= 30 && Core.storageOk == false) "Allow \"All files access\" for LANShare in Android settings" else "Cannot be read (unsupported format or still mounting)"
            val base = v.name.ifBlank { "USB drive" }
            val same = out.count { it.name == base || it.name.startsWith("$base (") }
            out.add(Drive("otg:" + dir.name, if (same == 0) base else "$base (${same + 1})", v.path, readable, why))
        }
        return out
    }

    /** USB stick, not an SD card: the description says USB, or a USB mass-storage device is attached and the description does not say SD card. */
    private fun isUsb(desc: String, usbAttached: Boolean): Boolean {
        if (Regex("usb|otg", RegexOption.IGNORE_CASE).containsMatchIn(desc)) return true
        if (Regex("\\bsd\\b|sd ?card", RegexOption.IGNORE_CASE).containsMatchIn(desc)) return false
        return usbAttached
    }

    private fun massStorageAttached(ctx: Context): Boolean = try {
        (ctx.getSystemService(Context.USB_SERVICE) as UsbManager).deviceList.values.any { d ->
            (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE }
        }
    } catch (_: Throwable) { false }

    /** The browsable place for drive [id], or null when it is gone / the switch is off. Throws [Denied] when it is plugged in but cannot be read. */
    fun fs(id: String): LocalFs? {
        val d = drives().firstOrNull { it.id == id } ?: return null
        if (!d.ok) throw Denied(d.why)
        synchronized(lock) { return fsById.getOrPut(d.id + "|" + d.path) { LocalFs(d.path, d.id, d.name) { Cfg.otgRo() } } }
    }

    /** Entries for /api/peers: shown with the other places (kind "USB"). "auto" / "ro" ride along so ui.html needs no second request. */
    fun peers(): List<JSONObject> = drives().map {
        JSONObject().put("id", it.id).put("name", it.name).put("ip", "").put("ok", it.ok).put("why", it.why)
            .put("seen", System.currentTimeMillis() / 1000.0).put("via", "USB").put("kind", "USB").put("vias", JSONArray(listOf("USB")))
            .put("auto", Cfg.otgAuto()).put("ro", Cfg.otgRo())
    }

    /** GET /api/otg: the three switches + the drives with their free space (Settings > USB / OTG drives). */
    fun status(): JSONObject {
        val arr = JSONArray()
        for (d in drives()) {
            val o = JSONObject().put("id", d.id).put("name", d.name).put("ok", d.ok).put("why", d.why)
            if (d.ok) try { val f = File(d.path); o.put("free", f.usableSpace).put("total", f.totalSpace) } catch (_: Throwable) {}
            arr.put(o)
        }
        return JSONObject().put("on", Cfg.otgOn()).put("auto", Cfg.otgAuto()).put("ro", Cfg.otgRo()).put("drives", arr)
    }

    /** POST /api/otg {key: on | auto | ro, value}. */
    fun set(key: String, value: Boolean) {
        when (key) {
            "on" -> Cfg.setOtgOn(value)
            "auto" -> Cfg.setOtgAuto(value)
            "ro" -> Cfg.setOtgRo(value)
            else -> throw BadReq("unknown USB setting")
        }
        invalidate()
    }
}
