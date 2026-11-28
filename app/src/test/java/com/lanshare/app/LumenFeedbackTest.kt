package com.lanshare.app

import android.app.Activity
import android.app.Application
import android.graphics.RectF
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LumenFeedbackTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val host = activity.findViewById<FrameLayout>(android.R.id.content)

    @After fun cleanUp() {
        Lm.stop(activity)
        activity.finish()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun holdShowsHintInTheSecureWindowAndDoesNotActivateTheButton() {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val bar = TestBar(activity)
        var clicks = 0
        bar.onTap = { clicks++ }
        activity.setContentView(bar, FrameLayout.LayoutParams(-1, 60))
        val decor = activity.window.decorView
        decor.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, 480, 800)

        bar.touch(MotionEvent.ACTION_DOWN)
        bar.touch(MotionEvent.ACTION_UP)
        assertEquals(1, clicks)
        bar.touch(MotionEvent.ACTION_DOWN)
        advance(500)
        val text = host.getChildAt(1) as TextView
        assertEquals("Settings", text.text)
        assertSame(host, text.parent)
        bar.touch(MotionEvent.ACTION_UP)
        assertEquals(1, clicks)
        advance(2_000)
        assertEquals(1, host.childCount)
    }

    @Test fun countMessagesReuseTheSnackbarAndClosingActivitiesDoNotUseSystemToast() {
        Lm.snack(activity, "Copied 3 items", Toast.LENGTH_SHORT)
        advance(0)
        val pill = host.getChildAt(host.childCount - 1)
        Lm.snack(activity, "Copied 7 items", Toast.LENGTH_SHORT)
        advance(0)
        assertSame(pill, host.getChildAt(host.childCount - 1))
        assertEquals("Copied 7 items", pill.contentDescription)

        ShadowToast.reset()
        activity.finish()
        Lm.snack(activity, "Private selection", Toast.LENGTH_SHORT)
        advance(0)
        assertNull(ShadowToast.getTextOfLatestToast())
    }

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)

    private class TestBar(activity: Activity) : NlHitView(activity, 1f) {
        init { hits.add(Hit("settings", RectF(0f, 0f, 48f, 48f))) }

        override fun tooltipOf(id: String): String? = if (id == "settings") "Settings" else null

        fun touch(action: Int) {
            val now = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(now, now, action, 24f, 24f, 0)
            try { onTouchEvent(event) } finally { event.recycle() }
        }
    }
}
