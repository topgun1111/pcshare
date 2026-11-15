package com.takipli.oynat.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateTest {
    @Test
    fun parsesReleaseAssetNamesForTheOwnEngineOnly() {
        assertEquals("1.2.0-b45" to 10_200_045, parseUpdateAsset("TakipliOynat-Android-sam-1.2.0-b45.apk", "sam"))
        assertNull(parseUpdateAsset("TakipliOynat-Android-pose-1.2.0-b45.apk", "sam"))
        assertNull(parseUpdateAsset("TakipliOynat-Android-sam-1.2.0-debug.apk", "sam"))
        assertNull(parseUpdateAsset("TakipliOynat-Android-sam-1.2.0.apk", "sam"))
    }

    @Test
    fun buildNumberOrdersBuildsAndVersions() {
        assertEquals(10_200_999, updateVersionCode("1.2.0", 5000)) // capped
        assertTrue(updateVersionCode("1.2.0", 46) > updateVersionCode("1.2.0", 45))
        assertTrue(updateVersionCode("1.2.1", 0) > updateVersionCode("1.2.0", 999))
    }
}
