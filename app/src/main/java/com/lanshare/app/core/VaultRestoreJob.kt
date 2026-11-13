package com.lanshare.app.core

import com.lanshare.app.vf
import com.lanshare.app.vt

import com.lanshare.app.core.vault.VaultRestore
import com.lanshare.app.core.vault.VaultSession
import java.io.File
import java.util.UUID

/** "Restore" in vault mode: decrypts the selected vault items back to the place they were locked from ([VaultRestore]); the vault copy goes only after the written file was verified.
 *  Like [VaultLockJob] the job text is neutral (no names / paths) so progress cards and notifications leak nothing. */
object VaultRestoreJob {
    /** Where items without a recorded origin (share sheet / picker imports) are put: Download/<name>/... */
    const val FALLBACK = "Vault restored"

    /** [vpaths] = virtual vault paths ("/Folder/file"). "ok:<jobId>" | "locked" | "bad". */
    fun start(vpaths: List<String>): String {
        val st = VaultSession.store
        if (st == null || !st.isOpen) return "locked"
        val fs = VaultFs()
        val ids = ArrayList<String>()
        for (p in vpaths) { val n = try { fs.nodeAtOrNull(p) } catch (_: Exception) { null }; if (n != null) ids.add(n.id) }
        if (vpaths.any { vnorm(it) == "/" }) { ids.clear(); st.list(null).forEach { ids.add(it.id) } }   // "/" = restore everything
        if (ids.isEmpty()) return "bad"
        val jid = UUID.randomUUID().toString().replace("-", "").take(8)
        Jobs.prune()
        val job = Job(if (ids.size == 1) vt("Restoring 1 item") else vf("Restoring %s items", ids.size))
        job.bytes = false
        job.total = ids.size.toLong()
        Jobs.all[jid] = job
        Thread({ work(job, ids) }, "vrestore-$jid").also { it.isDaemon = true }.start()
        return "ok:$jid"
    }

    private fun work(job: Job, ids: List<String>) {
        val ctx = Core.appCtx
        val fallback = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), FALLBACK)
        val scan = ArrayList<String>()
        try {
            var failed = 0; var done = 0
            for ((i, id) in ids.withIndex()) {
                if (job.cancel) { job.state = "cancel"; return }
                val st = VaultSession.store
                if (st == null || !st.isOpen) { job.error = vt("Vault locked"); job.state = "error"; return }   // auto-lock mid-job: what is not restored yet stays encrypted in the vault
                val r = VaultRestore.run(st, listOf(id), fallback, { job.cancel }, { f -> scan.add(f.path) })
                done += r.restored; failed += r.failed
                r.problems.forEach { job.addProblem(vt("An item could not be restored") + ": " + it) }
                job.done = (i + 1).toLong(); job.failed = failed; job.files = done
            }
            if (failed == 0) job.state = "done" else { job.error = "$failed item(s) could not be restored; they are still in the vault"; job.state = "error" }
        } catch (_: Throwable) {
            if (job.cancel) job.state = "cancel" else { job.error = vt("Restoring stopped"); job.state = "error" }
        } finally {
            if (ctx != null && scan.isNotEmpty()) try { android.media.MediaScannerConnection.scanFile(ctx, scan.toTypedArray(), null, null) } catch (_: Throwable) { }   // Gallery / Downloads see the files again
            com.lanshare.app.VaultMode.dirty = true
            if (job.state == "run") { job.state = "error"; if (job.error == null) job.error = "stopped unexpectedly" }
            job.end = System.currentTimeMillis()
        }
    }
}
