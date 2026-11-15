package com.takipli.oynat

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.takipli.oynat.core.FreePick
import com.takipli.oynat.core.PointTrack
import com.takipli.oynat.core.TrackDoc

/** `pose` build: no SAM. The real implementation lives in src/sam (same signatures). */
object SamEngine {
    val lastRun: String = ""
    fun hasModels(ctx: Context): Boolean = false
    fun hasMemory(ctx: Context): Boolean = false
    fun npuEnabled(ctx: Context): Boolean = false
    fun setNpuEnabled(ctx: Context, on: Boolean) = Unit

    @Suppress("UNUSED_PARAMETER")
    suspend fun track(ctx: Context, uri: Uri, doc: TrackDoc, pick: FreePick, onProgress: (Int, Int, Double) -> Unit): PointTrack? = null

    @Suppress("UNUSED_PARAMETER")
    suspend fun encodeFrame(ctx: Context, bmp: Bitmap): SamFrameCache? = null

    @Suppress("UNUSED_PARAMETER")
    suspend fun segmentAt(ctx: Context, cache: SamFrameCache, x: Double, y: Double): SamMask? = null

    @Suppress("UNUSED_PARAMETER")
    suspend fun bench(ctx: Context, bmp: Bitmap, runs: Int, onProgress: (String) -> Unit): SamBenchReport? = null
}
