package com.takipli.oynat

/** Encoder output of ONE shown frame for the "Any object" smart select (`sam` build); the `pose` build never creates one. */
class SamFrameCache(
    val hr0: FloatArray, val hr0Shape: LongArray,
    val hr1: FloatArray, val hr1Shape: LongArray,
    val embed: FloatArray, val embedShape: LongArray,
)

/** One object found at a tap: `inside[y * size + x]` on a size x size grid; box and area are normalized (0..1). */
class SamMask(val size: Int, val inside: BooleanArray, val x1: Double, val y1: Double, val x2: Double, val y2: Double, val area: Double)
