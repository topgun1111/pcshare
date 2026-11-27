package com.lanshare.app.core

import org.junit.Assert.*
import org.junit.Test

class LumenCoordinatesTest {
    @Test fun currentViewport() {
        assertEquals(3f, LumenCoordinates.scale(1200, 400.0, 3f), .0001f)
        assertEquals(2.5f, LumenCoordinates.scale(1000, 400.0, 2.5f), .0001f)
        assertEquals(2.99f, LumenCoordinates.scale(1196, 400.0, 3f), .0001f)
    }
    @Test fun staleOrInvalidViewport() {
        assertEquals(3f, LumenCoordinates.scale(1200, 800.0, 3f), .0001f)
        assertEquals(3f, LumenCoordinates.scale(0, 400.0, 3f), .0001f)
        assertEquals(3f, LumenCoordinates.scale(1200, Double.NaN, 3f), .0001f)
        assertEquals(3f, LumenCoordinates.scale(1200, 0.0, 3f), .0001f)
        assertEquals(1f, LumenCoordinates.scale(0, 0.0, Float.NaN), .0001f)
    }
}
