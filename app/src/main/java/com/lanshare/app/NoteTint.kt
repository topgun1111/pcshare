package com.lanshare.app

import android.content.Context

/** Notification accent colour: lite = purple (brand_lite), full = orange (brand). Always set, so the two apps are told apart in the shade. */
object NoteTint {
    /** App name for notifications and printer names: "LANShare" or "LANShare Lite". */
    val appName: String get() = if (BuildConfig.LITE) "LANShare Lite" else "LANShare"
    fun lite(c: Context): Int = c.getColor(if (BuildConfig.LITE) R.color.brand_lite else R.color.brand)
}
