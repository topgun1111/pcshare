package com.lanshare.app.core

import android.util.Base64
import com.lanshare.app.core.office.DocxHtml
import com.lanshare.app.core.office.OfficeOpen
import com.lanshare.app.core.office.XlsxHtml
import java.io.File

/** Part 3 leftover: one office file -> a full HTML page for the PC web UI (`/api/office`). Read-only, same renderers as OfficeViewerActivity. */
object OfficeWeb {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** [sheetLink]: URL prefix the sheet tabs append the sheet index to (e.g. `/api/office?dev=local&path=%2Fa.xlsx&sheet=`). */
    fun page(f: File, sheet: Int, sheetLink: String): String = try {
        OfficeOpen(f).use { o ->
            if (!o.isSheet) DocxHtml.render(o.readDoc(), title = f.name, image = { im ->
                val b = o.image(im.part)
                if (b == null || b.size > 3_000_000) null else {
                    val mime = when (im.part.substringAfterLast('.').lowercase()) { "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; "webp" -> "image/webp"; "svg" -> "image/svg+xml"; else -> null }
                    if (mime == null) null else "data:$mime;base64," + Base64.encodeToString(b, Base64.NO_WRAP)
                }
            })
            else {
                val tabs = o.sheets.filter { !it.hidden }.map { it.index to it.name }
                val cur = (tabs.firstOrNull { it.first == sheet } ?: tabs.firstOrNull())?.first
                if (cur == null) msg("No visible sheets.")
                else XlsxHtml.render(o.readSheet(cur), tabs, cur, o.styles).replace("href=\"lsoffice://sheet/", "href=\"" + esc(sheetLink))
            }
        }
    } catch (e: Throwable) {
        val m = e.message.orEmpty()
        msg(if (m.contains("password", true) || m.contains("zip", true) || m.contains("end header", true)) "Cannot open: file is password protected or not a valid Office file." else "Cannot open: $m")
    }

    private fun msg(t: String) = "<!doctype html><meta charset=utf-8><body style=\"font:15px sans-serif;padding:16px\">" + esc(t) + "</body>"
}
