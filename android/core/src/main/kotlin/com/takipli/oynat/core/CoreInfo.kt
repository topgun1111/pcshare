package com.takipli.oynat.core

/** Marker so the app can show that the `core` module is linked. Parts 2+ add the real code. */
object CoreInfo {
    fun label(): String = "ok"
}

/**
 * Android versionCode from the shared repo `VERSION` file: major*10000 + minor*100 + patch.
 * `app/build.gradle.kts` uses the same formula; `VersionTest` pins it (1.2.0 -> 10200).
 */
fun versionCode(version: String): Int {
    val parts = version.trim().split(".").map { s -> s.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
    return parts.getOrElse(0) { 0 } * 10000 + parts.getOrElse(1) { 0 } * 100 + parts.getOrElse(2) { 0 }
}
