package com.takipli.oynat.core

import kotlinx.serialization.Serializable

/**
 * Shared tuning keys: same names (snake_case in JSON) and defaults as `tracker/config.py::Config`.
 * Only keys the Android app uses; unknown keys are ignored on load. `GoldenTest` pins the defaults
 * against `golden/config.json` (written by `scripts/gen_golden.py`).
 */
@Serializable
data class Config(
    val detConf: Double = 0.25,
    val detIou: Double = 0.45,
    val detSize: Int = 640,
    val liveDetConf: Double = 0.1,
    val offlineFps: Double = 10.0,
    val offlineInterpS: Double = 0.6,
    val offlineAlpha: Double = 0.4,
    val targetFill: Double = 0.30,
    val minZoom: Double = 0.0,
    val maxZoom: Double = 2.5,
    val alphaPos: Double = 0.15,
    val alphaZoom: Double = 0.08,
    val deadZonePos: Double = 0.01,
    val deadZoneZoom: Double = 0.05,
    val lostHoldS: Double = 1.0,
    val lostZoomoutS: Double = 2.0,
    val kfAccel: Double = 0.5,
    val kfMeas: Double = 0.01,
    val gmc: Boolean = true,
    val gmcSide: Int = 480,
    val cutDetect: Boolean = true,
    val cutHist: Double = 0.35,
    val cutDiff: Double = 0.18,
    val pickTol: Double = 0.03,
    val poseConf: Double = 0.3,
    val kpMinConf: Double = 0.35,
    val kpTol: Double = 0.12,
    val kpBox: Double = 0.10,
    val kpHoldS: Double = 1.5,
    val kpFilter: Boolean = true,
    val kpWorld: Boolean = true,
    val kpInfer: Boolean = true,
    // Android-only; provisional, final values are decided by measurement (Part 10/13 and Part 9).
    val partMargin: Double = 0.02,
    val scanFpsPart: Double = 15.0,
)
