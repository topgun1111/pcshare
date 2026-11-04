package com.lanshare.app

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.lanshare.app.core.Job
import com.lanshare.app.core.PdfBuilder
import com.lanshare.app.core.PdfCacheCodec
import com.lanshare.app.core.PdfPageCache
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch

/** Message codes between [PdfBuildClient] (app process) and [PdfBuildService] (`:pdfbuild` process). */
internal object PB {
    const val START = 1; const val FOCUS = 2; const val CANCEL = 3                      // client -> service
    const val TOTAL = 10; const val CUR = 11; const val PAGE = 12; const val DONE = 13; const val ERR = 14   // service -> client
}

/**
 * Runs the PDF page-cache build in its own process (`android:process=":pdfbuild"`): when PdfRenderer / native memory blows up
 * there, only this process dies. [PdfBuildClient] sees that, restarts the build (pages already written are kept) and gives up
 * on a page that crashed the process twice. One build at a time (the client queue is serial).
 */
class PdfBuildService : Service() {
    @Volatile private var cancelled = false
    @Volatile private var focus = 0
    private val h = Handler(Looper.getMainLooper()) { m ->
        when (m.what) {
            PB.START -> start(m)
            PB.FOCUS -> focus = m.arg1
            PB.CANCEL -> cancelled = true
        }
        true
    }
    private val me = Messenger(h)
    override fun onBind(i: Intent?): IBinder = me.binder

    private fun start(m: Message) {
        val to = m.replyTo ?: return
        val d = m.data
        cancelled = false; focus = d.getInt("focus", 0)
        val pdf = File(d.getString("pdf") ?: return); val dir = File(d.getString("dir") ?: return)
        fun send(what: Int, a1: Int = 0, a2: Int = 0, txt: String? = null) {
            try { to.send(Message.obtain(null, what, a1, a2).also { if (txt != null) it.data = Bundle().apply { putString("t", txt) } }) } catch (_: Throwable) { }
        }
        Thread({
            PdfBuilder.run(applicationContext, pdf, dir, d.getInt("cw"), d.getInt("maxW"), d.getInt("maxPx"), d.getInt("q", PdfCacheCodec.PAGE_Q), object : PdfBuilder.Sink {
                override fun total(n: Int) = send(PB.TOTAL, n)
                override fun cur(i: Int) = send(PB.CUR, i)
                override fun page(i: Int, done: Int) = send(PB.PAGE, i, done)
                override fun finished(complete: Boolean) = send(PB.DONE, if (complete) 1 else 0, if (cancelled) 1 else 0)
                override fun error(msg: String) = send(PB.ERR, txt = msg)
            }, { cancelled }, { focus })
        }, "pdf-build").also { it.isDaemon = true }.start()
    }
}

/**
 * One queued build, app-process side: binds the service, mirrors its progress into the [Job] (notification) and the page
 * listeners, forwards focus / cancel, and survives a crash of the service process (restart, suspect page -> `bl`).
 */
internal class PdfBuildClient(
    private val app: Context, private val pdf: File, private val dir: File, private val name: String,
    private val cw: Int, private val maxW: Int, private val maxPx: Int, private val job: Job,
) {
    companion object {
        val active = ConcurrentHashMap<String, PdfBuildClient>()
        private const val MAX_NO_PROGRESS = 6                 // restarts in a row without a single new page, then give up
    }

    private val ui = Handler(Looper.getMainLooper())
    private val latch = CountDownLatch(1)
    private val reply = Messenger(Handler(Looper.getMainLooper()) { m -> onReply(m); true })
    @Volatile private var out: Messenger? = null
    private var conn: ServiceConnection? = null
    private var cur = -1
    private var noProgress = 0
    private val crashes = HashMap<Int, Int>()
    private var ended = false
    private var lastFocus = -1

    /** Blocks the queue thread until this build is over (done / error / cancel / gave up). */
    private val quality = PdfPageCache.quality()          // read once in the app process (the :pdfbuild process has no settings)

    fun runBlocking() {
        if (job.cancel) { job.state = "cancel"; return }
        active[dir.path] = this
        try {
            ui.post { if (!bind()) fallback() }
            latch.await()
        } finally { active.remove(dir.path) }
    }

    fun focus(page: Int) { if (page == lastFocus) return; lastFocus = page; send(PB.FOCUS, page) }

    private fun send(what: Int, a1: Int = 0, data: Bundle? = null) {
        try { out?.send(Message.obtain(null, what, a1, 0).also { it.replyTo = reply; if (data != null) it.data = data }) } catch (_: Throwable) { }
    }

    private fun bind(): Boolean {
        val c = object : ServiceConnection {
            override fun onServiceConnected(n: ComponentName, b: IBinder) {
                if (ended) return
                out = Messenger(b); cur = -1
                send(PB.START, 0, Bundle().apply {
                    putString("pdf", pdf.path); putString("dir", dir.path); putInt("cw", cw); putInt("maxW", maxW); putInt("maxPx", maxPx); putInt("q", quality)
                    putInt("focus", PdfPageCache.focusOf(dir))
                })
                pollCancel()
            }
            override fun onServiceDisconnected(n: ComponentName) = died()
            override fun onBindingDied(n: ComponentName) = died()
        }
        conn = c
        return try { app.bindService(Intent(app, PdfBuildService::class.java), c, Context.BIND_AUTO_CREATE) } catch (_: Throwable) { false }
    }

    private fun unbind() { out = null; conn?.let { try { app.unbindService(it) } catch (_: Throwable) { } }; conn = null }

    private fun pollCancel() {
        if (ended) return
        if (job.cancel) { send(PB.CANCEL); return }
        ui.postDelayed({ pollCancel() }, 600)
    }

    /** The service process is gone (native crash / killed for memory): remember the suspect page, restart. */
    private fun died() {
        if (ended || out == null) return                      // already handled (both callbacks can fire)
        unbind()
        if (job.cancel) { end("cancel", null, false); return }
        val c = cur
        if (c >= 0) {
            val k = (crashes[c] ?: 0) + 1; crashes[c] = k
            if (k >= 2) PdfPageCache.addBlocked(dir, c)       // crashed the renderer twice: give this page up, go on with the others
        }
        if (++noProgress > MAX_NO_PROGRESS) { end("error", "The PDF renderer keeps crashing on this file", false); return }
        job.label = "Caching PDF: $name  (restarting)"
        ui.postDelayed({ if (!ended && !bind()) fallback() }, 700)
    }

    /** Service not available at all: build in this process like before (no isolation). */
    private fun fallback() {
        unbind()
        Thread({
            PdfBuilder.run(app, pdf, dir, cw, maxW, maxPx, quality, object : PdfBuilder.Sink {
                override fun total(n: Int) { ui.post { onTotal(n) } }
                override fun cur(i: Int) {}
                override fun page(i: Int, done: Int) { ui.post { onPage(i, done) } }
                override fun finished(complete: Boolean) { ui.post { end(if (job.cancel) "cancel" else "done", null, complete) } }
                override fun error(msg: String) { ui.post { end("error", msg, false) } }
            }, { job.cancel }, { PdfPageCache.focusOf(dir) })
        }, "pdf-build-local").also { it.isDaemon = true; it.start() }
    }

    private fun onTotal(n: Int) { job.total = n.toLong() }
    private fun onPage(i: Int, done: Int) {
        noProgress = 0
        job.done = done.toLong(); job.label = "Caching PDF: $name  $done/${job.total}"
        PdfPageCache.fireOnPage(dir, i)
    }

    private fun onReply(m: Message) {
        if (ended) return
        when (m.what) {
            PB.TOTAL -> onTotal(m.arg1)
            PB.CUR -> cur = m.arg1
            PB.PAGE -> { cur = -1; onPage(m.arg1, m.arg2) }
            PB.DONE -> end(if (m.arg2 == 1 || job.cancel) "cancel" else "done", null, m.arg1 == 1)
            PB.ERR -> end("error", m.data?.getString("t"), false)
        }
    }

    private fun end(state: String, err: String?, complete: Boolean) {
        if (ended) return
        ended = true
        unbind()
        when (state) {
            "done" -> { job.label = "PDF ready: $name"; job.state = "done" }
            "cancel" -> job.state = "cancel"
            else -> { job.error = err ?: "Cannot cache this PDF"; job.state = "error" }
        }
        job.end = System.currentTimeMillis()
        PdfPageCache.fireOnFinished(dir, complete)
        latch.countDown()
    }
}
