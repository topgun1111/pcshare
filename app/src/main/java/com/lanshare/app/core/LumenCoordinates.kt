package com.lanshare.app.core

/** CSS viewport geometry must be consistent with the current Android window, not an old fold state. */
object LumenCoordinates {
    fun scale(nativeWidth: Int, cssWidth: Double, configured: Float): Float {
        val fallback = configured.takeIf { it.isFinite() && it > 0f } ?: 1f
        if (nativeWidth <= 0 || !cssWidth.isFinite() || cssWidth <= 0.0) return fallback
        val ratio = nativeWidth / cssWidth
        // Ignore a stale or incompatible viewport width rather than magnifying the whole overlay.
        if (!ratio.isFinite() || ratio <= 0.0 || kotlin.math.abs(ratio - fallback) > fallback * 0.05) return fallback
        return ratio.toFloat()
    }
}
