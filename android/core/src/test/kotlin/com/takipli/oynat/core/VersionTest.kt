package com.takipli.oynat.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VersionTest {
    @Test
    fun versionCodeFollowsFormula() {
        assertEquals(10200, versionCode("1.2.0"))
        assertEquals(10203, versionCode("1.2.3\n"))
        assertEquals(20000, versionCode("2.0"))
        assertEquals(10200, versionCode("1.2.0-beta"))
        assertEquals(0, versionCode(""))
    }

    @Test
    fun coreModuleIsLinked() {
        assertEquals("ok", CoreInfo.label())
    }

    /** The shared repo VERSION (two levels above this module) must be X.Y.Z so versionName/versionCode stay valid. */
    @Test
    fun repoVersionFileIsWellFormed() {
        val f = java.io.File("../../VERSION")
        if (!f.isFile) return // module built outside the repo
        val v = f.readText().trim()
        assertTrue(Regex("""\d+\.\d+\.\d+""").matches(v), "VERSION is '$v'")
        assertTrue(versionCode(v) >= 10000, "versionCode ${versionCode(v)}")
    }
}
