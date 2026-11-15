package com.takipli.oynat.core

// ---- App updates: version codes and release-asset names (the HTTP / install part lives in the app module). ----

/**
 * versionCode of a CI build: `versionCode(version) * 1000 + build` (build = CI run number, 0..999; 0 locally).
 * `app/build.gradle.kts` uses the same formula. Every CI build therefore has a higher code than the previous one of the
 * same version, and any later VERSION is higher than every build of an earlier one. Bump VERSION before 1000 builds.
 */
fun updateVersionCode(version: String, build: Int): Int = versionCode(version) * 1000 + build.coerceIn(0, 999)

private val UPDATE_ASSET = Regex("""^TakipliOynat-Android-([a-z]+)-(\d+\.\d+\.\d+)-b(\d+)\.apk$""")

/** `TakipliOynat-Android-<engine>-<X.Y.Z>-b<N>.apk` for this `engine` -> (label "X.Y.Z-bN", versionCode); else null. */
fun parseUpdateAsset(name: String, engine: String): Pair<String, Int>? {
    val m = UPDATE_ASSET.matchEntire(name) ?: return null
    if (m.groupValues[1] != engine) return null
    val build = m.groupValues[3].toIntOrNull() ?: return null
    return "${m.groupValues[2]}-b$build" to updateVersionCode(m.groupValues[2], build)
}
