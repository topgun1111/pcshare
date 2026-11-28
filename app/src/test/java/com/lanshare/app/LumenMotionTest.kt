package com.lanshare.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.VibratorManager
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LumenMotionTest {
    @Test fun secureViewerLaunchUsesTheImmediateFallback() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val thumbnail = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try {
            Settings.Global.putFloat(activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            LumenPrefs.setReduceMotion(activity, false)
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            val decor = activity.window.decorView
            decor.layout(0, 0, 320, 480)
            NlHero.launch(activity, decor, RectF(0f, 0f, 16f, 16f), 0f, thumbnail, Intent(activity, ImageViewerActivity::class.java))

            val started = requireNotNull(shadowOf(activity).nextStartedActivity)
            assertEquals(ImageViewerActivity::class.java.name, started.component?.className)
            assertFalse(started.hasExtra("com.lanshare.app.HERO_TRANSITION"))
            assertFalse(thumbnail.isRecycled)
        } finally {
            thumbnail.recycle()
            controller.pause().stop().destroy()
        }
    }

    @Test fun hapticEffectsAndPatternsFollowBothSettings() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        val vibrator = shadowOf(manager.defaultVibrator)
        vibrator.setHasVibrator(true)
        Haptics.init(context)

        fun setSystem(enabled: Boolean) {
            Settings.System.putInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, if (enabled) 1 else 0)
        }
        setSystem(false)
        Haptics.play("140")
        Haptics.pattern(longArrayOf(0, 140))
        assertFalse(vibrator.isVibrating)

        setSystem(true)
        LumenPrefs.setHaptics(context, false)
        Haptics.play("140")
        assertFalse(vibrator.isVibrating)

        LumenPrefs.setHaptics(context, true)
        Haptics.play("140")
        assertTrue(vibrator.isVibrating)
        assertEquals(140L, vibrator.milliseconds)
    }

    @Test fun changingReduceMotionSettlesAnExistingDockExit() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val dock = NlDockView(activity, 1f)
        Settings.Global.putFloat(activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        LumenPrefs.setReduceMotion(activity, false)
        activity.setContentView(dock)
        dock.leave()
        assertTrue(dock.isLeaving)

        Settings.Global.putFloat(activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        assertTrue(LumenPrefs.reduceMotion(activity))
        dock.motionChanged(LumenPrefs.reduceMotion(activity))
        assertFalse(dock.isLeaving)
        assertEquals(View.GONE, dock.visibility)
        assertEquals(0f, dock.translationY, 0f)
        assertEquals(1f, dock.alpha, 0f)
        activity.finish()
    }
}
