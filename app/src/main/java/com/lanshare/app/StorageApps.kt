package com.lanshare.app

import android.app.AppOpsManager
import android.app.Activity
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.os.storage.StorageManager

/*
 * Apps tab of the Storage Analyzer: size of every installed app (app code, data, cache) as Android itself reports it
 * (StorageStatsManager). This never opens another app's folders - it only asks the system for the numbers, so it needs no root.
 * Needs: "Usage access" (PACKAGE_USAGE_STATS, switched on once in system settings), API 26+, QUERY_ALL_PACKAGES to see all apps (Android 11+).
 * NOT compiled on a device here.
 */

internal class AppRec(
    val pkg: String, val label: String, val ver: String,
    val code: Long, val data: Long, val cache: Long,      // data INCLUDES cache (StorageStats semantics)
    val system: Boolean, val icon: Bitmap?
) {
    val total: Long get() = code + data
    val userData: Long get() = (data - cache).coerceAtLeast(0L)
}

internal object AppsUtil {
    val supported: Boolean get() = Build.VERSION.SDK_INT >= 26

    fun hasUsage(c: Context): Boolean = try {
        val ao = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val m = if (Build.VERSION.SDK_INT >= 29) ao.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        else ao.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        m == AppOpsManager.MODE_ALLOWED
    } catch (_: Throwable) { false }

    fun openUsageSettings(a: Activity) {
        try { a.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:" + a.packageName))) }
        catch (_: Throwable) { try { a.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) } catch (_: Throwable) {} }
    }

    /** The system's App info page of [pkg] (Storage -> Clear cache / Clear data, Uninstall). */
    fun openAppInfo(a: Activity, pkg: String) {
        try { a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))) } catch (_: Throwable) {}
    }

    private fun icon(d: Drawable?, px: Int): Bitmap? {
        if (d == null) return null
        return try {
            val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            val c = Canvas(b); d.setBounds(0, 0, px, px); d.draw(c); b
        } catch (_: Throwable) { null }
    }

    /** Blocking (run it on a worker thread). Empty when unsupported or not allowed. [iconPx] = icon size in pixels. */
    fun load(c: Context, iconPx: Int): List<AppRec> {
        if (!supported || !hasUsage(c)) return emptyList()
        val pm = c.packageManager
        val ssm = c.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
        val me = Process.myUserHandle()
        val out = ArrayList<AppRec>()
        val apps = try { pm.getInstalledApplications(0) } catch (_: Throwable) { emptyList<ApplicationInfo>() }
        for (ai in apps) {
            try {
                val st = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, ai.packageName, me)
                val sys = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                val ver = try { pm.getPackageInfo(ai.packageName, 0).versionName ?: "" } catch (_: Throwable) { "" }
                val lab = try { ai.loadLabel(pm).toString() } catch (_: Throwable) { ai.packageName }
                val ic = icon(try { ai.loadIcon(pm) } catch (_: Throwable) { null }, iconPx)
                out.add(AppRec(ai.packageName, lab, ver, st.appBytes, st.dataBytes, st.cacheBytes, sys, ic))
            } catch (_: Throwable) { /* app not measurable (other profile, removed meanwhile) */ }
        }
        return out
    }
}
