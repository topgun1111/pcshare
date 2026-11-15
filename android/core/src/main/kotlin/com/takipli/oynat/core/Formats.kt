package com.takipli.oynat.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNamingStrategy

/** Number of COCO joints; `kp` rows are 17 x (x, y, conf), normalized to the frame. */
const val N_KPT = 17

@Serializable
data class VideoInfo(
    val path: String = "",
    val w: Int = 0,
    val h: Int = 0,
    val fps: Double = 0.0,
    val duration: Double = 0.0,
)

/**
 * `part` = COCO joint index (null = whole person); `preset` = body-part group name (`PRESETS`, Part 10; at most one of
 * `part`/`preset`); `linked` = Re-ID chained IDs (PC only).
 */
@Serializable
data class Target(val id: Int, val part: Int? = null, val linked: List<Int> = emptyList(), val preset: String? = null, val free: FreePick? = null)

/**
 * `d` rows: `[id, x1, y1, x2, y2, score]` (normalized); `kp`: track ID (string) -> 51 numbers; `cut`: 1 when the sample
 * starts a new shot; `cam` (Part 10b): `[a, b, tx/h, ty/h]`, background similarity previous sample -> this one
 * (a = scale*cos, b = scale*sin, translation in frame-height units), only when the camera estimate succeeded.
 */
@Serializable
data class Sample(
    val t: Double,
    val d: List<List<Double>> = emptyList(),
    val kp: Map<String, List<Double>>? = null,
    val cut: Int? = null,
    val cam: List<Double>? = null,
)

data class Keypoint(val x: Double, val y: Double, val conf: Double)

/** Joint `joint` of track `trackId` in this sample, or null when absent / malformed. */
fun Sample.keypoint(trackId: Int, joint: Int): Keypoint? {
    val row = kp?.get(trackId.toString()) ?: return null
    if (joint !in 0 until N_KPT || row.size < 3 * N_KPT) return null
    return Keypoint(row[3 * joint], row[3 * joint + 1], row[3 * joint + 2])
}

/** Scan result `track.json`: version 1 (no keypoints) or 2 (`kp`). `emb`/`team` are kept verbatim. */
@Serializable
data class TrackDoc(
    val version: Int,
    val video: VideoInfo,
    val sampleFps: Double = 0.0,
    val samples: List<Sample>,
    val target: Target? = null,
    val views: List<ViewKey> = emptyList(),
    val sig: String? = null,
    val vsig: String? = null,
    val cuts: List<Double> = emptyList(),
    val emb: JsonElement? = null,
    val team: JsonElement? = null,
    val pt: PointTrack? = null,
)

/** Small playback/export file `<video>.views.json` (always version 1, no samples). */
@Serializable
data class ViewsDoc(
    val version: Int,
    val video: VideoInfo = VideoInfo(),
    val target: Target? = null,
    val views: List<ViewKey> = emptyList(),
    val vsig: String? = null,
)

object FormatVersion {
    const val V1 = 1
    const val V2 = 2
    val supported = setOf(V1, V2)
}

@OptIn(ExperimentalSerializationApi::class)
object Formats {
    /** snake_case keys like the PC files; unknown keys ignored; nulls/defaults omitted on write. */
    val json: Json = Json {
        namingStrategy = JsonNamingStrategy.SnakeCase
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    fun parseConfig(text: String): Config = json.decodeFromString(text)

    /** Like `offline.py::load_doc`: only versions 1 and 2; `video` and `samples` are required. */
    fun parseTrack(text: String): TrackDoc {
        val doc = json.decodeFromString<TrackDoc>(text)
        require(doc.version in FormatVersion.supported) { "unsupported track.json version: ${doc.version}" }
        return doc
    }

    fun parseViews(text: String): ViewsDoc {
        val doc = json.decodeFromString<ViewsDoc>(text)
        require(doc.version in FormatVersion.supported) { "unsupported views.json version: ${doc.version}" }
        return doc
    }

    fun encodeTrack(doc: TrackDoc): String = json.encodeToString(doc)

    fun encodeViews(doc: ViewsDoc): String = json.encodeToString(doc)

    /** Like `offline.py::save_views`: drops `samples`, keeps target + views (+ vsig), version 1. */
    fun viewsOf(doc: TrackDoc): ViewsDoc =
        ViewsDoc(FormatVersion.V1, VideoInfo(path = doc.video.path), doc.target, doc.views, doc.vsig)
}
