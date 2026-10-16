package com.lanshare.app.core

import java.io.IOException
import java.security.SecureRandom

/**
 * ROOT SUPPORT, part 1: running commands through `su`.
 *
 * Nothing here runs unless the user switched "Root access" on in Settings ([Cfg.rootEnabled]) AND the root manager (Magisk / KernelSU / ...) granted
 * this app: [check] runs `su -c id` once (the grant prompt shows up then) and remembers the answer until the app restarts or [check] is called again.
 *
 * Every command is a fresh `su -c <script>` process (no long-lived root shell: nothing to leak, nothing to hang). Arguments are always put in
 * single quotes with [q]. [RootFs] builds the file operations on top of this.
 *
 * SECURITY: the HTTP server of the app listens on the loopback / LAN and any app of the phone can call 127.0.0.1. Root must never be reachable
 * that way without the user's own screen: [token] is a random string made per process and handed to the page only through the JS bridge
 * (`LSAndroid.rootToken()`, the page stores it in a cookie); [Routes] marks every request as trusted only when it carries it, and [Jobs.ep] refuses
 * dev "root" for a request that is not trusted ([untrusted]). The /p/ routes (other LANShare devices) never serve root at all.
 * NOT compiled / NOT device-tested.
 */
object RootShell {
    class Out(val code: Int, val out: String, val err: String)

    @Volatile private var state = 0          // 0 = not asked yet, 1 = granted, -1 = not available / denied
    @Volatile var why = ""                   // human text for state -1
    private val rnd = SecureRandom()
    /** Random per process; see the SECURITY note above. */
    val token: String = ByteArray(16).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    /** Set by [Routes.handle] on the thread of an HTTP request that did NOT present [token]: such a request may never use dev "root". */
    val untrusted = ThreadLocal<Boolean>()

    private val fsInstance by lazy { RootFs() }

    fun granted(): Boolean = state == 1

    /** "granted" | "denied" | "unknown" - for the Settings row. */
    fun stateName(): String = when (state) { 1 -> "granted"; -1 -> "denied"; else -> "unknown" }

    /** Single-quote [s] for sh: a'b -> 'a'\''b'. */
    fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * Asks su for root (may show the manager's grant prompt, so it waits up to 40 s). Returns true when `id` printed uid=0.
     * Blocking: call it from a request / worker thread, never from the UI thread.
     */
    @Synchronized
    fun check(): Boolean {
        try {
            val r = exec("id", 40)
            if (r.code == 0 && r.out.contains("uid=0")) { state = 1; why = ""; return true }
            state = -1
            why = if (r.code == 0) "su answered, but not as root (" + r.out.trim().take(80) + ")"
                  else "Root access was denied" + (if (r.err.isNotBlank()) ": " + r.err.trim().lines().last().take(120) else "")
        } catch (e: IOException) {
            state = -1
            why = "No su found - this phone is not rooted (or the root manager hides it from this app)"
        } catch (e: Exception) {
            state = -1
            why = errText(e)
        }
        return false
    }

    fun forget() { state = 0; why = "" }

    /** Starts `su -c <script>`; the caller owns the process (stdin is open: close it when the script reads nothing). */
    fun start(script: String): Process = ProcessBuilder("su", "-c", script).start()

    /** Runs a script and collects both outputs. Killed after [secs] seconds ([Out.code] = -1 then). */
    fun exec(script: String, secs: Int = 30): Out {
        val p = start(script)
        try { p.outputStream.close() } catch (_: IOException) {}
        val err = StringBuilder()
        val et = Thread { try { p.errorStream.bufferedReader().forEachLine { if (err.length < 4000) err.append(it).append('\n') } } catch (_: IOException) {} }
        et.isDaemon = true; et.start()
        var killed = false
        val killer = Thread { try { Thread.sleep(secs * 1000L); killed = true; p.destroy() } catch (_: InterruptedException) {} }
        killer.isDaemon = true; killer.start()
        val out = try { p.inputStream.readBytes().toString(Charsets.UTF_8) } catch (_: IOException) { "" }
        val code = try { p.waitFor() } catch (_: InterruptedException) { -1 }
        killer.interrupt()
        try { et.join(500) } catch (_: InterruptedException) {}
        return Out(if (killed) -1 else code, out, err.toString())
    }

    /** Adds the token to a URL of this app's own server that points at root ("dev=root"): the native viewers / players / share code open such URLs themselves, without the page's cookie. */
    fun sign(url: String): String = if (url.contains("dev=root")) url + (if (url.contains('?')) "&" else "?") + "rt=" + token else url

    /** The root file system, or an exception when root is off / not granted / the request is not from this app's own screen. */
    fun fs(): RootFs {
        if (!Cfg.rootEnabled()) throw Denied("Root access is switched off (Settings > Root access)")
        if (untrusted.get() == true) throw Denied("Root is only available from the app's own screen")
        if (!granted()) throw Denied(if (why.isNotEmpty()) why else "Root access is not granted yet (Settings > Root access)")
        return fsInstance
    }
}
