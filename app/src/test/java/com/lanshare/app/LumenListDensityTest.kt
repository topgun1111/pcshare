package com.lanshare.app

import android.app.Application
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LumenListDensityTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test fun activePullKeepsItsThresholdUntilTheGestureEnds() {
        val pull = NlPullLayout(context, 1f)
        val child = FrameLayout(context).apply { isClickable = true }
        pull.addView(child, FrameLayout.LayoutParams(-1, -1))
        pull.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
        pull.layout(0, 0, 360, 600)
        var ticks = 0
        pull.onThreshold = { ticks++ }

        assertTrue(touch(pull, MotionEvent.ACTION_DOWN, 0f))
        pull.densityChanged(2f)
        touch(pull, MotionEvent.ACTION_MOVE, 175f)
        assertEquals(1, ticks)
        touch(pull, MotionEvent.ACTION_CANCEL, 175f)

        pull.densityChanged(Float.NaN)
        assertTrue(touch(pull, MotionEvent.ACTION_DOWN, 0f))
        touch(pull, MotionEvent.ACTION_MOVE, 175f)
        assertEquals(1, ticks)
        touch(pull, MotionEvent.ACTION_MOVE, 335f)
        assertEquals(2, ticks)
        touch(pull, MotionEvent.ACTION_CANCEL, 335f)
    }

    @Test fun changingDensityKeepsAnActiveScrubberDrag() {
        val bar = PdfScrollBar(context, 1f)
        bar.layout(0, 0, 360, 600)
        bar.fraction = 0.5f
        bar.flash()
        assertTrue(touch(bar, MotionEvent.ACTION_DOWN, 300f))

        bar.densityChanged(2f)
        assertTrue(bar.dragging)
        assertEquals(0.5f, bar.fraction, 0.001f)
        touch(bar, MotionEvent.ACTION_MOVE, 450f)
        assertTrue(bar.fraction > 0.5f)
        touch(bar, MotionEvent.ACTION_UP, 450f)
    }

    @Test fun rowsKeepTouchAndTextHeightAcrossViewportDensity() {
        assertEquals(48f, nlRowHeight(48f, 24f, 1f), 0f)
        assertEquals(120f, nlRowHeight(60f, 88f, 2f), 0f)
        assertEquals(176f, nlRowHeight(72f, 152f, 2f), 0f)
    }

    private fun touch(view: View, action: Int, y: Float): Boolean {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, 24f, y, 0)
        return try { view.dispatchTouchEvent(event) } finally { event.recycle() }
    }
}
