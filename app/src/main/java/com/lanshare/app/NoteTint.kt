package com.lanshare.app

import android.content.Context

/** Bildirim vurgu rengi: sadece lite surumde mor (brand_lite); full surumde 0 = sistemin kendi rengi (davranis degismez). */
object NoteTint {
    fun lite(c: Context): Int = if (BuildConfig.LITE) c.getColor(R.color.brand_lite) else 0
}
