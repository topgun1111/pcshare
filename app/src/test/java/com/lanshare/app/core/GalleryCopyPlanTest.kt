package com.lanshare.app.core

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class GalleryCopyPlanTest {
    @Test fun bulkAlbumUsesOneListingAndPreservesSizes() {
        var requests = 0
        val plan = GalleryCopyPlan { dir ->
            assertEquals("/DCIM/Camera", dir)
            requests++
            (0 until 1000).map { Item("photo $it.jpg", false, it.toLong(), 0L) }
        }
        for (i in 0 until 1000) {
            assertEquals(listOf(WalkItem("", false, i.toLong())), plan.walk("/DCIM/Camera/photo $i.jpg"))
        }
        assertEquals(1, requests)
    }

    @Test fun differentAlbumsAreListedIndependently() {
        val requested = ArrayList<String>()
        val plan = GalleryCopyPlan { dir ->
            requested.add(dir)
            listOf(Item("same.jpg", false, if (dir == "/A") 8L else 12L, 0L))
        }
        assertEquals(8L, plan.walk("/A/same.jpg").single().size)
        assertEquals(12L, plan.walk("/B/same.jpg").single().size)
        assertEquals(listOf("/A", "/B"), requested)
    }

    @Test fun missingAndDirectoryEntriesAreExplicitErrors() {
        val plan = GalleryCopyPlan { listOf(Item("folder.jpg", true, 0L, 0L)) }
        try { plan.walk("/A/missing.jpg"); fail("missing file accepted") } catch (_: NotFound) {}
        try { plan.walk("/A/folder.jpg"); fail("directory accepted") } catch (_: BadReq) {}
        try { plan.walk("/"); fail("root accepted") } catch (_: BadReq) {}
    }

    @Test fun failedListingIsNotRepeatedForEveryFile() {
        var attempts = 0
        val plan = GalleryCopyPlan { attempts++; throw IOException("offline") }
        repeat(100) { i ->
            try { plan.walk("/A/$i.jpg"); fail("offline listing accepted") }
            catch (e: IOException) { assertEquals("offline", e.message) }
        }
        assertEquals(1, attempts)
    }
}
