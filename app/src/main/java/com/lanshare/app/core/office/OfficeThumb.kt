package com.lanshare.app.core.office

import java.io.File
import java.util.zip.ZipFile

/** Part 7: thumbnail = the preview image the producing app embedded (docx/xlsx: docProps/thumbnail.*, odt/ods: Thumbnails/thumbnail.png). No android.*; null when absent (e.g. files made by this app, .doc/.xls). */
object OfficeThumb {
    private val NAMES = listOf("docProps/thumbnail.jpeg", "docProps/thumbnail.jpg", "docProps/thumbnail.png", "Thumbnails/thumbnail.png")
    private const val MAX = 2_000_000L

    fun embedded(f: File): ByteArray? = try {
        if (!f.isFile || f.length() < 4 || f.length() > (200L shl 20)) null else ZipFile(f).use { z ->
            NAMES.firstNotNullOfOrNull { n -> z.getEntry(n)?.takeIf { it.size in 1..MAX } }?.let { e -> z.getInputStream(e).use { it.readBytes() } }
        }
    } catch (_: Throwable) { null }

    fun mime(b: ByteArray) = if (b.size > 3 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte()) "image/png" else "image/jpeg"
}
