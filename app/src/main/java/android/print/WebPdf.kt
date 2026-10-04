// Deliberately in package android.print: PrintDocumentAdapter.LayoutResultCallback / WriteResultCallback have package-private
// constructors, so this is the only way to drive a WebView's print adapter into a plain PDF file without the print dialog.
package android.print

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object WebPdf {
    /** Renders [html] (no scripts, no network) to an A4 PDF at [out]. Blocks the calling (worker) thread until done. */
    fun render(ctx: Context, html: String, out: File, marginMils: Int = 400, timeoutMs: Long = 90_000) {
        val latch = CountDownLatch(1)
        val err = AtomicReference<Throwable?>(null)
        val main = Handler(Looper.getMainLooper())
        val view = AtomicReference<WebView?>(null)
        fun finish(e: Throwable?) {
            if (e != null) err.compareAndSet(null, e)
            main.post { try { view.get()?.destroy() } catch (_: Throwable) {} }
            latch.countDown()
        }
        main.post {
            try {
                val wv = WebView(ctx)
                view.set(wv)
                wv.settings.apply {
                    javaScriptEnabled = false
                    blockNetworkLoads = true
                    allowFileAccess = false
                    allowContentAccess = false
                }
                wv.webViewClient = object : WebViewClient() {
                    private var started = false
                    override fun onPageFinished(v: WebView, url: String?) {
                        if (started) return
                        started = true
                        main.postDelayed({ write(v, out, marginMils) { e -> finish(e) } }, 300)   // let images / layout settle
                    }
                }
                wv.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            } catch (e: Throwable) { finish(e) }
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) { main.post { try { view.get()?.destroy() } catch (_: Throwable) {} }; throw IOException("the page took too long to render") }
        err.get()?.let { throw if (it is IOException) it else IOException(it.message ?: it.javaClass.simpleName, it) }
        if (!out.isFile || out.length() == 0L) throw IOException("the page produced an empty PDF")
    }

    private fun write(v: WebView, out: File, marginMils: Int, done: (Throwable?) -> Unit) {
        try {
            val attrs = PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .setResolution(PrintAttributes.Resolution("pdf", "pdf", 300, 300))
                .setMinMargins(PrintAttributes.Margins(marginMils, marginMils, marginMils, marginMils))
                .build()
            val ad = v.createPrintDocumentAdapter("print")
            ad.onStart()
            ad.onLayout(null, attrs, null, object : PrintDocumentAdapter.LayoutResultCallback() {
                override fun onLayoutFinished(info: PrintDocumentInfo?, changed: Boolean) {
                    try {
                        val pfd = ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
                        ad.onWrite(arrayOf(PageRange.ALL_PAGES), pfd, android.os.CancellationSignal(), object : PrintDocumentAdapter.WriteResultCallback() {
                            override fun onWriteFinished(pages: Array<out PageRange>?) {
                                try { pfd.close() } catch (_: Throwable) {}
                                try { ad.onFinish() } catch (_: Throwable) {}
                                done(null)
                            }
                            override fun onWriteFailed(error: CharSequence?) {
                                try { pfd.close() } catch (_: Throwable) {}
                                done(IOException("PDF write failed: $error"))
                            }
                            override fun onWriteCancelled() {
                                try { pfd.close() } catch (_: Throwable) {}
                                done(IOException("PDF write cancelled"))
                            }
                        })
                    } catch (e: Throwable) { done(e) }
                }
                override fun onLayoutFailed(error: CharSequence?) { done(IOException("page layout failed: $error")) }
                override fun onLayoutCancelled() { done(IOException("page layout cancelled")) }
            }, null)
        } catch (e: Throwable) { done(e) }
    }
}
