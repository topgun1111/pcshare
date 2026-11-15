package com.takipli.oynat.core

import kotlin.math.abs

/** Part 15: pure helpers for error messages, storage/battery preflight and the error log (no Android imports). */

/** What went wrong, in terms the user can act on. The app maps each kind to a string. */
enum class ErrKind { NO_SPACE, MODEL_MISSING, VIDEO_ACCESS, CODEC, OTHER }

/**
 * Classifies an error from its Media3 `errorCodeName` (or null), message and class name.
 * Order matters: a missing `.onnx` is also a FileNotFoundException, so the model check comes before the video check.
 */
fun classifyError(codeName: String?, message: String?, className: String? = null): ErrKind {
    val t = listOfNotNull(codeName, message, className).joinToString(" ").lowercase()
    return when {
        "enospc" in t || "no space left" in t || "not enough space" in t || "disk full" in t || "insufficient storage" in t -> ErrKind.NO_SPACE
        ".onnx" in t && ("filenotfound" in t || "no such file" in t || "not found" in t || "missing" in t) -> ErrKind.MODEL_MISSING
        "io_file_not_found" in t || "io_no_permission" in t || "securityexception" in t || "permission denial" in t ||
            "filenotfound" in t || "no such file" in t || "open failed" in t || "no video track" in t -> ErrKind.VIDEO_ACCESS
        "format_unsupported" in t || "decoder_init" in t || "encoder_init" in t || "decoding_failed" in t || "encoding_failed" in t ||
            "mediacodec" in t || "codec" in t || "no decoder" in t || "no encoder" in t || "decoder delivered no image" in t -> ErrKind.CODEC
        else -> ErrKind.OTHER
    }
}

/** Bytes an export of [durationS] seconds at [bitrate] bit/s may need: payload + 15 % container/overshoot + 16 MB slack. */
fun exportBytesNeeded(bitrate: Int, durationS: Double): Long =
    (bitrate.toDouble() / 8.0 * durationS.coerceAtLeast(0.0)).toLong() * 115 / 100 + 16L * 1024 * 1024

/**
 * The export is written to the app cache and then copied to Movies. On one volume (nearly always `/data`) both copies
 * exist at once, so twice the size is needed; on two volumes each needs one copy. Two free-space readings within 4 MB
 * of each other count as one volume.
 */
fun enoughSpaceForExport(freeCache: Long, freeMovies: Long, need: Long): Boolean =
    if (abs(freeCache - freeMovies) < 4L * 1024 * 1024) minOf(freeCache, freeMovies) >= 2 * need
    else freeCache >= need && freeMovies >= need

/** Warn before long work: battery below [warnBelow] % and not charging. */
fun lowBattery(percent: Int, charging: Boolean, warnBelow: Int = 20): Boolean = percent in 0 until warnBelow && !charging

/** Keeps the end of [text] within [maxChars], cutting at a line start so no half line remains. */
fun trimLog(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    val cut = text.length - maxChars
    if (text[cut - 1] == '\n') return text.substring(cut) // the cut falls exactly on a line start
    val nl = text.indexOf('\n', cut)
    return if (nl < 0) text.substring(cut) else text.substring(nl + 1)
}

/** One log entry: `time | where | KIND | message` (newlines in the message become ` / `, capped at 600 chars). */
fun logLine(time: String, where: String, kind: ErrKind, message: String): String =
    "$time | $where | $kind | " + message.replace("\r", "").replace('\n', ' ').replace("  ", " ").take(600)
