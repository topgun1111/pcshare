package com.lanshare.app

import android.content.Context

/** Notification accent colour: lite = purple (brand_lite), full = orange (brand). Always set, so the two apps are told apart in the shade. */
object NoteTint {
    fun lite(c: Context): Int = c.getColor(if (BuildConfig.LITE) R.color.brand_lite else R.color.brand)
}
