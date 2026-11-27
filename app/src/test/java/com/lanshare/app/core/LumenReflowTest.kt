package com.lanshare.app.core

import org.junit.Assert.*
import org.junit.Test

class LumenReflowTest {
    @Test fun regularRowsAndGalleryCells() {
        assertEquals(1, LumenReflow.rowIndex(1, 2, 3, 12))
        assertEquals(2, LumenReflow.rowIndex(2, 2, 3, 12))
        assertEquals(8, LumenReflow.rowIndex(4, 2, 3, 12))
        assertEquals(7, LumenReflow.rowIndex(3, 2, 5, 12))
        assertEquals(0, LumenReflow.rowIndex(0, 0, 5, 1))
    }

    @Test fun invalidAndOverflowPositions() {
        assertEquals(-1, LumenReflow.rowIndex(-1, 2, 3, 12))
        assertEquals(-1, LumenReflow.rowIndex(6, 2, 3, 12))
        assertEquals(-1, LumenReflow.rowIndex(0, 0, 0, 12))
        assertEquals(-1, LumenReflow.rowIndex(0, 0, 3, 0))
        assertEquals(-1, LumenReflow.rowIndex(Int.MAX_VALUE, 0, 8, Int.MAX_VALUE))
    }

    @Test fun sameFolderDifferentViews() {
        assertTrue(LumenReflow.sameLocation("local|list|/Pictures", "local|grid|/Pictures"))
        assertTrue(LumenReflow.sameLocation("local|list|/a|b", "local|grid|/a|b"))
        assertFalse(LumenReflow.sameLocation("local|list|/Pictures", "peer|grid|/Pictures"))
        assertFalse(LumenReflow.sameLocation("local|list|/Pictures", "local|grid|/Other"))
        assertFalse(LumenReflow.sameLocation("local|list|/Pictures", "local|grid|N:/Pictures"))
        assertFalse(LumenReflow.sameLocation("", ""))
    }
}
