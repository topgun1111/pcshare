package com.lanshare.app

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Smart actions for text read from a picture (HANDOVER_OCR.md roadmap 5): links, phone numbers, e-mails, "Save as .txt". */
object OcrSmart {
    enum class Kind { LINK, MAIL, PHONE }

    class Hit(val kind: Kind, val text: String) {
        fun label() = when (kind) { Kind.LINK -> "Open link"; Kind.MAIL -> "Write e-mail"; Kind.PHONE -> "Call" } + ": " + text
        fun intent(): Intent = when (kind) {
            Kind.LINK -> Intent(Intent.ACTION_VIEW, Uri.parse(if (text.contains("://")) text else "https://$text"))
            Kind.MAIL -> Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$text"))
            Kind.PHONE -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + text.filter { it.isDigit() || it == '+' }))
        }
    }

    private val mailRe = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val linkRe = Regex("(?:https?://|www\\.)[^\\s<>\"']+|\\b[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.(?:com|net|org|edu|gov|io|info|tr|gd|app|me)(?:/[^\\s<>\"']*)?\\b", RegexOption.IGNORE_CASE)
    private val phoneRe = Regex("(?<![\\w@])\\+?\\d[\\d ()./-]{7,}\\d")

    /** Links, e-mails and phone numbers in [text] (no duplicates, in order of appearance). */
    fun find(text: String): List<Hit> {
        val out = ArrayList<Pair<Int, Hit>>()
        val taken = ArrayList<IntRange>()
        fun free(r: IntRange) = taken.none { it.first <= r.last && r.first <= it.last }
        for (m in mailRe.findAll(text)) { taken.add(m.range); out.add(m.range.first to Hit(Kind.MAIL, m.value)) }
        for (m in linkRe.findAll(text)) {
            if (!free(m.range)) continue
            taken.add(m.range)
            out.add(m.range.first to Hit(Kind.LINK, m.value.trimEnd('.', ',', ';', ':', ')', '!', '?')))
        }
        for (m in phoneRe.findAll(text)) {
            if (!free(m.range)) continue
            val digits = m.value.count { it.isDigit() }
            if (digits < 7 || digits > 15) continue
            out.add(m.range.first to Hit(Kind.PHONE, m.value.trim()))
        }
        return out.sortedBy { it.first }.map { it.second }.distinctBy { it.kind to it.text }
    }

    /** Saves [text] as a .txt file in Downloads (Android 10+) or the app's own files folder. Returns a short description of where it went. */
    fun saveTxt(ctx: Context, text: String): String {
        val name = "picture-text-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: throw IllegalStateException("Cannot create the file")
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: throw IllegalStateException("Cannot write the file")
            return "Downloads/$name"
        }
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        File(dir, name).writeText(text, Charsets.UTF_8)
        return name
    }
}
