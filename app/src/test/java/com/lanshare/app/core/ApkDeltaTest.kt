package com.lanshare.app.core

import org.junit.Assert.*
import org.junit.Test
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

class ApkDeltaTest {
    @Test fun networkPolicy() {
        assertFalse(ApkDelta.preferDelta(onWifi = true))
        assertTrue(ApkDelta.preferDelta(onWifi = false))
        assertFalse(ApkDelta.preferDelta(onWifi = false, allowMobileFull = true))
        assertFalse(ApkDelta.preferDelta(onWifi = true, allowMobileFull = true))
    }

    @Test fun roundTripAndRejectInvalid() {
        val dir = Files.createTempDirectory("apk-delta").toFile()
        try {
            val base = File(dir, "base").apply { writeText("0123456789") }
            val target = File(dir, "target").apply { writeText("456new012") }
            val patch = File(dir, "patch")
            val out = File(dir, "out")
            val bh = ApkDelta.sha256(base)
            val th = ApkDelta.sha256(target)
            fun fixture(offset: Long = 4) {
                DataOutputStream(GZIPOutputStream(patch.outputStream())).use {
                    it.writeBytes("PCDELTA1")
                    it.write(MessageDigest.getInstance("SHA-256").digest(base.readBytes()))
                    it.write(MessageDigest.getInstance("SHA-256").digest(target.readBytes()))
                    it.writeLong(target.length())
                    it.writeByte(0); it.writeLong(offset); it.writeInt(3)
                    it.writeByte(1); it.writeInt(3); it.writeBytes("new")
                    it.writeByte(0); it.writeLong(0); it.writeInt(3)
                    it.writeByte(2)
                }
            }
            fun reject(action: () -> Unit) {
                try { action(); fail("invalid patch accepted") } catch (_: IOException) { }
                assertFalse(out.exists())
            }
            fixture()
            ApkDelta.apply(base, patch, out, bh, th, target.length())
            assertArrayEquals(target.readBytes(), out.readBytes())
            reject { ApkDelta.apply(base, patch, out, bh, "0".repeat(64), target.length()) }
            fixture(99)
            reject { ApkDelta.apply(base, patch, out, bh, th, target.length()) }
            fixture()
            reject { ApkDelta.apply(base, patch, out, bh, th, target.length()) { true } }
            patch.writeBytes(patch.readBytes().copyOf(12))
            reject { ApkDelta.apply(base, patch, out, bh, th, target.length()) }
        } finally { dir.deleteRecursively() }
    }
}
