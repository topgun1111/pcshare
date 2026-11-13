package com.lanshare.app.core

import com.lanshare.app.vf
import com.lanshare.app.vt

import com.lanshare.app.core.vault.VaultSession
import java.util.UUID

/** "Lock" from the file manager: encrypts this phone's selected files/folders into the unlocked vault (root level), then removes the originals.
 *  The job text is neutral (no names / paths anywhere: label, cur, problems, error) so progress cards and notifications leak nothing. */
object VaultLockJob {
    /** "ok:<jobId>" | "locked" (vault locked or missing: the UI opens the vault screen) | "bad" (nothing lockable). */
    fun start(paths: List<String>): String {
        val st = VaultSession.store
        if (st == null || !st.isOpen) return "locked"
        val files = ArrayList<java.io.File>()
        for (p in paths) {
            val f = try { Core.local.lreal(p) } catch (_: Exception) { continue } // preserve the final component; don't resolve a user-selected symlink
            if (f.exists() && !Core.local.isLink(f)) files.add(f) // links are deliberately left untouched
        }
        if (files.isEmpty()) return "bad"
        val jid = UUID.randomUUID().toString().replace("-", "").take(8)
        Jobs.prune()
        val job = Job(if (files.size == 1) vt("Encrypting 1 item") else vf("Encrypting %s items", files.size))
        job.bytes = false
        job.total = files.size.toLong()
        Jobs.all[jid] = job
        Thread({ work(job, files) }, "vlock-$jid").also { it.isDaemon = true }.start()
        return "ok:$jid"
    }

    private fun work(job: Job, files: List<java.io.File>) {
        var failed = 0
        try {
            for ((i, f) in files.withIndex()) {
                if (job.cancel) { job.state = "cancel"; return }
                val st = VaultSession.store
                if (st == null || !st.isOpen) { job.error = vt("Vault locked"); job.state = "error"; return }   // auto-lock mid-job: originals not yet handled stay untouched
                try {
                    if (f.isDirectory) st.lockFolder(f, null, true, { job.cancel }) else st.lockFile(f, null, true)
                } catch (_: Exception) { failed++; job.addProblem(vt("An item could not be locked")) }
                job.done = (i + 1).toLong(); job.failed = failed; job.files = i + 1 - failed
            }
            if (failed == 0) job.state = "done" else { job.error = "$failed item(s) could not be locked; originals kept"; job.state = "error" }
        } catch (_: Throwable) {
            if (job.cancel) job.state = "cancel" else { job.error = vt("Locking stopped"); job.state = "error" }
        } finally {
            if (job.state == "run") { job.state = "error"; if (job.error == null) job.error = "stopped unexpectedly" }
            job.end = System.currentTimeMillis()
        }
    }
}
