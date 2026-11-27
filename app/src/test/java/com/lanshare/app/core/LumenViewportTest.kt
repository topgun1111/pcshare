package com.lanshare.app.core

import org.junit.Assert.*
import org.junit.Test

class LumenViewportTest {
    @Test fun fittedImageStaysFitted() {
        val v = LumenViewport.image(400, 800, 800, 800, 1000, 1000, .4f, 0f, 200f)!!
        assertEquals(.8f, v.fit, .0001f)
        assertEquals(v.fit, v.scale, .0001f)
        assertEquals(0f, v.x, .001f)
        assertEquals(0f, v.y, .001f)
    }

    @Test fun relativeZoomAndCenterSurviveResize() {
        val v = LumenViewport.image(400, 800, 800, 800, 1000, 1000, 1.2f, -160f, -200f)!!
        assertEquals(3f, v.scale / v.fit, .001f)
        assertEquals(300f, (400f - v.x) / v.scale, .001f)
        assertEquals(500f, (400f - v.y) / v.scale, .001f)
    }

    @Test fun invalidAndEdgeClamped() {
        assertNull(LumenViewport.image(0, 800, 800, 800, 1000, 1000, 1f, 0f, 0f))
        assertNull(LumenViewport.image(400, 800, 800, 800, 1000, 1000, Float.NaN, 0f, 0f))
        val v = LumenViewport.image(400, 800, 800, 800, 1000, 1000, .8f, 300f, 300f)!!
        assertEquals(0f, v.x, .001f)
        assertEquals(0f, v.y, .001f)
    }

    @Test fun proportionalVideoPan() {
        assertEquals(200f, LumenViewport.rescaleOffset(100f, 400, 800), .001f)
        assertEquals(-50f, LumenViewport.rescaleOffset(-100f, 800, 400), .001f)
        assertEquals(100f, LumenViewport.rescaleOffset(100f, 0, 400), .001f)
        assertEquals(0f, LumenViewport.rescaleOffset(Float.NaN, 400, 800), .001f)
    }
}
