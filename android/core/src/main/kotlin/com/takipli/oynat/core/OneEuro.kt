package com.takipli.oynat.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max

/** Port of `tracker/smooth.py::OneEuro` (Casiez et al.). `t` in seconds, `minCutoff` in Hz. */
class OneEuro(
    private val minCutoff: Double = 1.5,
    private val beta: Double = 2.0,
    private val dCutoff: Double = 1.0,
) {
    private var x: Double? = null
    private var dx = 0.0
    private var lastT = 0.0

    fun reset() {
        x = null
        dx = 0.0
        lastT = 0.0
    }

    private fun alpha(cutoff: Double, dt: Double): Double {
        val tau = 1.0 / (2.0 * PI * cutoff)
        return 1.0 / (1.0 + tau / max(dt, 1e-6))
    }

    fun update(value: Double, t: Double): Double {
        val prev = x
        if (prev == null) {
            x = value
            lastT = t
            return value
        }
        val dt = t - lastT
        if (dt <= 0.0) return prev
        dx += alpha(dCutoff, dt) * ((value - prev) / dt - dx)
        val cutoff = minCutoff + beta * abs(dx)
        val next = prev + alpha(cutoff, dt) * (value - prev)
        x = next
        lastT = t
        return next
    }
}
