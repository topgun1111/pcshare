package com.lanshare.app.core

/** PERF-DEBUG (temporary): startup timestamps (SystemClock.elapsedRealtime) shown in Settings -> Debug: performance. Delete with the other PERF-DEBUG code. */
object PerfMarks {
    val marks = java.util.concurrent.ConcurrentHashMap<String, Long>()
    fun mark(k: String) { marks.putIfAbsent(k, android.os.SystemClock.elapsedRealtime()) }
}
