package com.lanshare.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfPasswordException
import com.shockwave.pdfium.PdfiumCore
import java.io.File
import java.io.IOException

/**
 * One open PDF for [PdfViewerActivity]. The viewer calls render() one at a time (its own lock); close() is called under that lock too.
 */
internal interface PdfDoc {
    val pageCount: Int
    /** Page size in points (1/72 in) as [width, height], or null when unreadable. */
    fun pageSize(i: Int): FloatArray?
    /** Draws page [i] scaled to fill [bm] (the caller has already painted it white). Throws when the page cannot be drawn. */
    fun render(i: Int, bm: Bitmap)
    fun close()
}

/**
 * PDF engines. PDFium (the engine of Chrome, bundled: com.github.barteksc:pdfium-android) is tried first: it opens far more files
 * than the Android framework renderer (damaged cross-reference tables, odd fonts / images, unusual page boxes). If PDFium cannot open
 * a file, or cannot open / draw a single page, the framework [PdfRenderer] is used instead (for that file or just that page).
 * A password-protected file throws [SecurityException] (the viewer shows its "password-protected" message).
 */
internal object PdfEngine {
    private const val TAG = "PdfEngine"
    @Volatile private var core: PdfiumCore? = null

    private fun core(ctx: Context): PdfiumCore =
        core ?: synchronized(this) { core ?: PdfiumCore(ctx.applicationContext).also { core = it } }

    fun open(ctx: Context, f: File): PdfDoc {
        var why: Throwable? = null
        try {
            return openPdfium(ctx, f)
        } catch (e: PdfPasswordException) {
            throw SecurityException("password")
        } catch (t: Throwable) {          // includes UnsatisfiedLinkError (native library missing on this CPU)
            why = t
            Log.w(TAG, "PDFium could not open " + f.name + ": " + t)
        }
        try {
            return FrameworkDoc.open(f)
        } catch (e: Exception) {
            if (e !is SecurityException && why != null) Log.w(TAG, "framework renderer failed too: $e")
            throw e
        }
    }

    private fun openPdfium(ctx: Context, f: File): PdfDoc {
        val c = core(ctx)
        val p = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        var doc: PdfDocument? = null
        try {
            doc = c.newDocument(p)
            val n = c.getPageCount(doc)
            if (n <= 0) throw IOException("PDFium: no pages")
            return PdfiumDoc(c, doc, n, f, ctx.resources.displayMetrics.densityDpi.coerceAtLeast(60))
        } catch (t: Throwable) {
            try { if (doc != null) c.closeDocument(doc) else p.close() } catch (_: Throwable) {}
            throw t
        }
    }
}

private class PdfiumDoc(
    private val c: PdfiumCore, private val doc: PdfDocument, override val pageCount: Int,
    private val file: File, private val dpi: Int
) : PdfDoc {
    private val opened = BooleanArray(pageCount)     // PDFium keeps a page open until the document is closed: open each page ONCE
    private var fw: FrameworkDoc? = null
    private var closed = false

    private fun fallback(): FrameworkDoc = fw ?: FrameworkDoc.open(file).also { fw = it }

    private fun openPage(i: Int): Boolean {
        if (opened[i]) return true
        val ptr = c.openPage(doc, i)
        if (ptr == 0L) return false
        opened[i] = true
        return true
    }

    override fun pageSize(i: Int): FloatArray? {
        try {
            val s = c.getPageSize(doc, i)        // pixels at the display dpi, does not need the page to be open
            if (s != null && s.width > 0 && s.height > 0) return floatArrayOf(s.width * 72f / dpi, s.height * 72f / dpi)
        } catch (t: Throwable) { Log.w("PdfEngine", "page " + (i + 1) + ": PDFium size failed: " + t) }
        return try { fallback().pageSize(i) } catch (_: Throwable) { null }
    }

    override fun render(i: Int, bm: Bitmap) {
        if (closed) throw IOException("closed")
        var failure: Throwable? = null
        try {
            if (openPage(i)) {
                c.renderPageBitmap(doc, bm, i, 0, 0, bm.width, bm.height, true)    // true = also draw annotations / form fields
                return
            }
            failure = IOException("PDFium cannot open page " + (i + 1))
        } catch (e: OutOfMemoryError) { throw e
        } catch (t: Throwable) { failure = t }
        Log.w("PdfEngine", "page " + (i + 1) + ": PDFium failed (" + failure + "), trying the framework renderer")
        fallback().render(i, bm)
    }

    override fun close() {
        if (closed) return
        closed = true
        try { c.closeDocument(doc) } catch (_: Throwable) {}
        try { fw?.close() } catch (_: Throwable) {}
        fw = null
    }
}

private class FrameworkDoc(private val p: ParcelFileDescriptor, private val r: PdfRenderer) : PdfDoc {
    override val pageCount: Int = r.pageCount

    override fun pageSize(i: Int): FloatArray? {
        val pg = r.openPage(i)
        try { return floatArrayOf(pg.width.toFloat(), pg.height.toFloat()) } finally { pg.close() }
    }

    override fun render(i: Int, bm: Bitmap) {
        val pg = r.openPage(i)
        try { pg.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) } finally { pg.close() }
    }

    override fun close() {
        try { r.close() } catch (_: Throwable) {}
        try { p.close() } catch (_: Throwable) {}
    }

    companion object {
        fun open(f: File): FrameworkDoc {
            val p = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
            try { return FrameworkDoc(p, PdfRenderer(p)) } catch (t: Throwable) { try { p.close() } catch (_: Throwable) {}; throw t }
        }
    }
}
