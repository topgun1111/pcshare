package com.lanshare.app

import org.json.JSONObject

/**
 * Plain-words view of the print log (Logs screen, "Simple" view).
 * One log line (PrintLogActivity.Ln) -> one short sentence + an optional "what to try" tip, or null = not worth showing to a beginner
 * (polling, tool checks, spool counters ...). Warnings and errors are NEVER hidden, even for events this file does not know:
 * they get the event name in words plus a tip when the text matches a known cause. The raw lines stay available in the Technical view / Share / Copy.
 * Never throws.
 */
object PlainLog {
    /** kind: 'i' normal, 'o' good news, 'w' warning, 'e' problem. */
    class Item(val kind: Char, val text: String, val tip: String? = null)

    private const val GENERIC_TIP = "If this keeps happening, switch to Technical view, tap Share and send the log to the developer."

    fun of(l: PrintLogActivity.Ln): Item? {
        val o = l.o ?: return Item(kindOf(l.lvl), l.raw.take(200))   // not JSON: kept as it is (never lost)
        return try { build(l, o) } catch (_: Throwable) { Item(kindOf(l.lvl), humanize(l.ev) + ".") }
    }

    fun kindOf(lvl: String): Char = when (lvl) { "e" -> 'e'; "w" -> 'w'; else -> 'i' }

    private fun build(l: PrintLogActivity.Ln, o: JSONObject): Item? {
        val ev = l.ev
        val why = explain(errText(o))
        val secs = secText(o)
        return when (ev) {
            "start" -> Item('i', "The print service on the PC started" + str(o, "version").let { if (it.isEmpty()) "" else " (version $it)" } + ".")
            "printers" -> {
                val names = nameList(o, "printers")
                if (names.isEmpty()) Item('w', "The PC found no printers.", "Add or switch on a printer on the PC (Windows Settings > Printers).")
                else Item('i', "Printers on the PC: " + names.joinToString(", ") + ".")
            }
            "received" -> Item('i', "Got \"" + nm(str(o, "name").ifEmpty { str(o, "file") }) + "\" from the phone" + sizeText(o) + ".")
            "queued" -> {
                val w = o.optInt("waiting", 0)
                Item('i', if (w > 0) "Waiting in the queue ($w before it)." else "Next in line to print.")
            }
            "plan" -> Item('i', "Getting \"" + nm(str(o, "path")) + "\" ready for printing.")
            "job-start" -> Item('i', "Started printing \"" + nm(str(o, "name")) + "\"" + str(o, "printer").let { if (it.isEmpty()) "" else " on $it" } + ".")
            "send-pdf" -> {
                val x = ArrayList<String>()
                val c = o.optInt("copies", 1); if (c > 1) x.add("$c copies")
                if (o.optBoolean("duplex", false) || str(o, "duplex").let { it.isNotEmpty() && it != "false" && it != "0" && it != "none" && it != "simplex" }) x.add("two-sided")
                Item('i', "Sending the pages to the printer" + (if (x.isEmpty()) "" else " (" + x.joinToString(", ") + ")") + ".")
            }
            "job-end" -> {
                val warn = str(o, "warning")
                if (o.optBoolean("ok", true)) Item(if (warn.isEmpty()) 'o' else 'w', "Printing finished$secs.", if (warn.isEmpty()) null else "The printer said: " + warn.take(200) + (explain(warn)?.let { " - $it" } ?: ""))
                else Item('e', "Printing failed$secs.", why ?: GENERIC_TIP)
            }
            "job-failed" -> Item('e', "Printing failed" + str(o, "shown").let { if (it.isEmpty()) "." else ": $it" }, why ?: GENERIC_TIP)
            "batch-ready" -> {
                val exp = o.optInt("expected", 0); val got = o.optInt("received", 0)
                if (o.optInt("missing", 0) > 0 || l.lvl != "i") Item('w', "Only $got of $exp pictures arrived.", "Send the missing pictures again.")
                else Item('i', "All $exp pictures arrived.")
            }
            "duplicate-key" -> Item('w', "The same print was sent twice; the second one was ignored.", "This is normal after a Wi-Fi hiccup. Check the printer did not print it twice.")
            "printer-unknown" -> Item('e', "The PC does not know a printer called \"" + str(o, "asked") + "\".", "Pick the printer again in the print settings, and check it is installed on the PC.")
            "printer-renamed" -> Item('i', "Printer \"" + str(o, "asked") + "\" was matched to \"" + str(o, "used") + "\".")
            "printer-diag" -> Item('w', "The printer reported a problem.", why ?: "Check the printer: power, paper, ink or toner, cables or Wi-Fi.")
            "watch-done" -> Item('o', "The printer has taken the job" + secs + ".")
            "watch-issue" -> Item('w', "The printer has a problem" + str(o, "issue").let { if (it.isEmpty()) "." else ": " + it.take(160) }, explain(str(o, "issue")) ?: "Check the printer: power, paper, ink or toner, cables or Wi-Fi.")
            "watch-stalled" -> Item('w', "Printing looks stuck (no progress for " + o.optInt("stall", 0) + " seconds).", "Check the printer is on, has paper and ink or toner, and nothing is jammed.")
            "spooler-down" -> Item('w', "The Windows print service (Print Spooler) was stopped. Trying to start it again.")
            "spooler-after" -> if (l.lvl == "e") Item('e', "The Windows print service (Print Spooler) is not running.", "Restart the PC, or start \"Print Spooler\" in Windows Services.") else null
            "spooler-start-failed" -> Item('e', "Could not start the Windows print service (Print Spooler).", "Restart the PC, or start \"Print Spooler\" in Windows Services.")
            "print-cmd-failed" -> Item('w', "The print command did not work. Trying another way.")
            "fallback-start" -> Item('w', "Switching to a backup way of printing.", explain(str(o, "why")))
            "fallback-ok" -> Item('o', "The backup way of printing worked.")
            "fallback-failed" -> Item('e', "The backup way of printing failed too.", why ?: GENERIC_TIP)
            "send-pdf-missing" -> Item('e', "The file to print was not found on the PC.", "Send it from the phone again.")
            "layout-failed" -> Item('w', "Could not arrange the pictures on the page. Using the plain way instead.")
            "plain-failed" -> Item('e', "Could not prepare the file for printing.", why ?: GENERIC_TIP)
            "image-convert-failed" -> Item('w', "Could not convert a picture for printing.", why)
            "office-convert-failed" -> Item('w', "Could not turn the Word, Excel or PowerPoint file into a printable page.", "Check Word, Excel, PowerPoint or LibreOffice works on the PC and has no window waiting for an answer.")
            "printer-diag-failed" -> Item('e', "Could not ask the printer what is wrong.", why)
            "watch-failed" -> Item('w', "Could not follow the print progress.", why)
            "upload-failed" -> Item('e', "The file did not arrive completely" + (if (o.has("got") && o.has("of")) " (" + o.optLong("got") / 1024 + " of " + o.optLong("of") / 1024 + " KB)" else "") + ".", "Check the Wi-Fi and send it again.")
            "http-drop" -> Item('w', "A connection from the phone was cut off.", "Usually harmless (a Wi-Fi hiccup). If printing failed, send it again.")
            "http-error" -> Item('e', "The PC had a problem answering the phone.", why ?: GENERIC_TIP)
            "http" -> if (o.optInt("code", 0) >= 500) Item('w', "The PC answered the phone with an error (code " + o.optInt("code") + ").", GENERIC_TIP) else null
            "run-timeout" -> Item('e', "A helper program on the PC took too long and was stopped.", why ?: GENERIC_TIP)
            "run-exception" -> Item('e', "A helper program on the PC could not start.", why ?: GENERIC_TIP)
            "run-done" -> if (o.optInt("rc", 0) != 0) Item('w', "A helper program on the PC reported a problem (code " + o.optInt("rc") + ").", explain(str(o, "err") + " " + str(o, "out"))) else null
            "worker-error" -> Item('e', "The print service had an unexpected problem but keeps running.", why ?: GENERIC_TIP)
            "thread-crash", "uncaught" -> Item('e', "A part of the print service stopped unexpectedly.", "Close the print window on the PC and start pcprint.py again.")
            "log" -> {
                val m = str(o, "msg")
                if (l.lvl == "i" || m.startsWith("PRINT FAILED") || m.startsWith("WARNING")) null   // already told by job-failed / job-end
                else Item(kindOf(l.lvl), m.take(200), explain(m))
            }
            // quiet bookkeeping: nothing a beginner can act on
            "tools", "run", "watch", "spool-before", "printer-matched", "printer-info", "printer-diag-skipped", "batch-slot", "cleanup" -> null
            else -> when (l.lvl) {
                "e" -> Item('e', humanize(ev) + ".", why ?: GENERIC_TIP)
                "w" -> Item('w', humanize(ev) + ".", why)
                else -> null
            }
        }
    }

    /** Known causes by words in the error text -> what to try. null = nothing known. */
    fun explain(text: String): String? {
        val t = text.lowercase()
        if (t.isBlank()) return null
        fun has(vararg w: String) = w.any { t.contains(it) }
        return when {
            has("timed out", "timeout", "sockettimeout") -> "The other side took too long to answer. Check the Wi-Fi and try again."
            has("refused", "failed to connect", "connectexception") -> "Could not connect. Make sure the LANShare print service (pcprint.py) is running on the PC."
            has("unreachable", "unknownhost", "no route", "getaddrinfo", "name or service") -> "The PC cannot be found. The phone and the PC must be on the same Wi-Fi or network."
            has("offline", "not connected", "disconnected") -> "The printer looks switched off or disconnected. Turn it on and check the Wi-Fi or cable."
            has("jam") -> "Paper jam: open the printer and remove the stuck paper."
            has("out of paper", "paper out", "no paper", "paperout") -> "Put paper in the printer."
            has("toner", "ink") -> "Check the ink or toner."
            has("door", "cover open") -> "Close the printer cover."
            has("access is denied", "permission", "winerror 5", "denied") -> "Windows did not allow this. Check the printer's permissions on the PC."
            has("no space", "disk full", "enospc") -> "The disk is full. Free some space and try again."
            has("spooler") -> "Restart \"Print Spooler\" in Windows Services, or restart the PC."
            has("sumatra") -> "The PDF printing helper (SumatraPDF) could not be used. Check it is available on the PC."
            has("memoryerror", "out of memory") -> "The PC ran out of memory. Close some programs and try again."
            has("not found", "no such file", "filenotfound") -> "A file or program was not found. Send the file again."
            else -> null
        }
    }

    private fun errText(o: JSONObject): String {
        val sb = StringBuilder()
        for (k in arrayOf("shown", "why", "err", "error", "issue", "msg", "text")) sb.append(str(o, k)).append(' ')
        o.optJSONObject("exc")?.let { sb.append(str(it, "type")).append(' ').append(str(it, "msg")).append(' ').append(str(it, "stderr")) }
        return sb.toString()
    }

    /** A JSON value as text; JSON null / missing = "" (org.json would give "null"). */
    private fun str(o: JSONObject, k: String): String {
        val v = o.opt(k)
        return if (v == null || v == JSONObject.NULL) "" else v.toString()
    }

    private fun nm(s: String): String = s.trim().substringAfterLast('/').substringAfterLast('\\').ifEmpty { "a file" }

    private fun secText(o: JSONObject): String {
        val s = o.optDouble("sec", -1.0)
        return if (s >= 0) " (" + (Math.round(s * 10) / 10.0) + " s)" else ""
    }

    private fun sizeText(o: JSONObject): String {
        val b = o.optLong("bytes", -1L)
        return when {
            b < 0 -> ""
            b >= 1048576 -> " (" + (Math.round(b / 104857.6) / 10.0) + " MB)"
            else -> " (" + maxOf(1L, b / 1024) + " KB)"
        }
    }

    /** Names out of a JSON array of strings or of objects with a "name". */
    private fun nameList(o: JSONObject, k: String): List<String> {
        val a = o.optJSONArray(k) ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until a.length()) {
            val v = a.opt(i)
            val s = if (v is JSONObject) str(v, "name") else v?.toString() ?: ""
            if (s.isNotEmpty() && s != "null") out.add(s)
        }
        return out
    }

    private fun humanize(ev: String): String =
        ev.replace('-', ' ').replace('_', ' ').trim().ifEmpty { "Event" }.replaceFirstChar { it.uppercase() }
}
