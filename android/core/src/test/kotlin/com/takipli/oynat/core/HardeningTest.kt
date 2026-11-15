package com.takipli.oynat.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HardeningTest {
    @Test
    fun classifiesMedia3AndIoErrors() {
        assertEquals(ErrKind.CODEC, classifyError("ERROR_CODE_DECODING_FORMAT_UNSUPPORTED", "Decoding failed", null))
        assertEquals(ErrKind.CODEC, classifyError("ERROR_CODE_ENCODER_INIT_FAILED", null, null))
        assertEquals(ErrKind.CODEC, classifyError(null, "decoder delivered no image", "IllegalStateException"))
        assertEquals(ErrKind.VIDEO_ACCESS, classifyError("ERROR_CODE_IO_FILE_NOT_FOUND", null, null))
        assertEquals(ErrKind.VIDEO_ACCESS, classifyError(null, "Permission Denial: opening provider", "SecurityException"))
        assertEquals(ErrKind.VIDEO_ACCESS, classifyError(null, "no video track", "IllegalArgumentException"))
        assertEquals(ErrKind.NO_SPACE, classifyError(null, "write failed: ENOSPC (No space left on device)", "IOException"))
        assertEquals(ErrKind.OTHER, classifyError(null, "index out of bounds", "IndexOutOfBoundsException"))
        assertEquals(ErrKind.OTHER, classifyError(null, null, null))
    }

    @Test
    fun missingOnnxIsAModelProblemNotAVideoProblem() {
        assertEquals(ErrKind.MODEL_MISSING, classifyError(null, "models/yolov8s-pose.onnx", "java.io.FileNotFoundException"))
        assertEquals(ErrKind.VIDEO_ACCESS, classifyError(null, "/storage/emulated/0/a.mp4 (No such file or directory)", "java.io.FileNotFoundException"))
        // no space beats everything: a full disk also breaks the model copy
        assertEquals(ErrKind.NO_SPACE, classifyError(null, "models/x.onnx: No space left on device", "FileNotFoundException"))
    }

    @Test
    fun exportBytesFollowBitrateAndDuration() {
        // 8 Mbit/s = 1 MB/s; 100 s -> 100e6 * 1.15 + 16 MiB
        assertEquals(115_000_000L + 16L * 1024 * 1024, exportBytesNeeded(8_000_000, 100.0))
        assertEquals(16L * 1024 * 1024, exportBytesNeeded(8_000_000, 0.0))
        assertEquals(16L * 1024 * 1024, exportBytesNeeded(8_000_000, -5.0))
    }

    @Test
    fun spaceCheckCountsOneOrTwoVolumes() {
        val mb = 1024L * 1024
        val need = 500 * mb
        assertTrue(enoughSpaceForExport(1200 * mb, 1200 * mb, need))          // same volume: 2 x need = 1000 MB
        assertFalse(enoughSpaceForExport(900 * mb, 900 * mb, need))           // same volume, only 1.8 x
        assertTrue(enoughSpaceForExport(900 * mb, 5000 * mb, need))           // two volumes: one copy each
        assertFalse(enoughSpaceForExport(400 * mb, 5000 * mb, need))
        assertFalse(enoughSpaceForExport(5000 * mb, 400 * mb, need))
        assertTrue(enoughSpaceForExport(1000 * mb, 1002 * mb, need))          // within 4 MB = same volume, 1000 >= 1000
    }

    @Test
    fun batteryWarning() {
        assertTrue(lowBattery(15, charging = false))
        assertFalse(lowBattery(15, charging = true))
        assertFalse(lowBattery(20, charging = false))
        assertFalse(lowBattery(80, charging = false))
        assertFalse(lowBattery(-1, charging = false)) // unknown level: no warning
    }

    @Test
    fun logTrimKeepsWholeLinesFromTheEnd() {
        val text = "aaaa\nbbbb\ncccc\ndddd"
        assertEquals(text, trimLog(text, 100))
        assertEquals("cccc\ndddd", trimLog(text, 10))   // cut lands on the newline after "bbbb": it goes whole
        assertEquals("cccc\ndddd", trimLog(text, 9))    // cut lands exactly on a line start: kept
        assertEquals("dddd", trimLog(text, 8))          // cut inside "cccc": that line goes whole
        assertEquals("dddd", trimLog(text, 4))
        assertEquals("zzz", trimLog("zzzzzzzz", 3))     // one long line: plain tail
    }

    @Test
    fun logLineIsOneLine() {
        val s = logLine("2026-11-14 12:00:00", "export", ErrKind.CODEC, "a\r\nb  c")
        assertEquals("2026-11-14 12:00:00 | export | CODEC | a b c", s)
        assertEquals(1, s.lines().size)
        assertTrue(logLine("t", "w", ErrKind.OTHER, "x".repeat(2000)).length < 700)
    }
}
