package com.lanshare.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionTest {
    @Test fun comparesStableAndPreviewVersionsAcrossEditions() {
        val versions = listOf(
            "v1.0.539", "v2.538", "v2.539", "preview-v2.539-9-1",
            "preview-v2.539-9-2", "preview-v2.539-10-1", "v2.540"
        )
        for ((i, current) in versions.withIndex()) {
            for (newer in versions.drop(i + 1)) {
                assertTrue(UpdateVersion.compare(newer, current) > 0)
                assertTrue(UpdateVersion.compare(current, newer) < 0)
            }
        }
        assertEquals(0, UpdateVersion.compare("preview-v2.539-10-16", "preview-v2.539-10-16-a4df928-lite"))
        assertEquals(0, UpdateVersion.compare("v2.539", "2.539-a4df928-lite"))
        assertTrue(UpdateVersion.isPreview("preview-v2.539-10-16-a4df928-lite"))
        assertFalse(UpdateVersion.isPreview("2.539-a4df928-lite"))
    }

    @Test fun filtersPreviewAndDraftReleases() {
        val preview = "preview-v2.539-1-1"
        assertTrue(UpdateVersion.accepts(preview, false, true, true))
        assertFalse(UpdateVersion.accepts(preview, false, true, false))
        assertFalse(UpdateVersion.accepts(preview, true, true, true))
        assertFalse(UpdateVersion.accepts(preview, false, false, true))
        assertFalse(UpdateVersion.accepts("v2.539", false, true, true))
        assertTrue(UpdateVersion.accepts("v2.539", false, false, false))
        assertFalse(UpdateVersion.accepts("", false, false, true))
    }
}
