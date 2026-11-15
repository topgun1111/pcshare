package com.takipli.oynat.core

/**
 * Port of `tracker/bytetrack.py::ByteTracker`: Kalman (cx, cy, a, h) + two-stage IoU matching.
 * `motion` (3x3 row-major, previous -> current frame) is the camera-compensation hook (Part 8 feeds it).
 */
class ByteTracker(
    private val highThr: Double = 0.5,
    private val lowThr: Double = 0.1,
    newThr: Double? = null,
    private val matchIou: Double = 0.3,
    private val secondIou: Double = 0.5,
    @Suppress("unused") private val unconfirmedIou: Double = 0.3,
    private val maxLost: Int = 30,
    private val minHits: Int = 1,
) {
    private val newThr: Double = newThr ?: highThr

    private class Internal(val id: Int, bbox: BBox, var score: Double) {
        val kf = KalmanBox(bbox)
        var age = 1
        var lost = 0
        var hits = 1
        val bbox: BBox get() = kf.bbox
        fun toTrack() = Track(id, bbox, score, age, lost)
    }

    private var tracks = mutableListOf<Internal>()
    private var nextId = 1

    /** When resuming a scan: new IDs must not collide with earlier samples. */
    fun setNextId(n: Int) {
        nextId = maxOf(nextId, n)
    }

    fun reset() {
        tracks = mutableListOf()
        nextId = 1
    }

    /** Drop all tracks but keep the ID counter (shot cut: old IDs are never reissued). */
    fun clear() {
        tracks = mutableListOf()
    }

    /** Last boxes of live tracks (camera estimation masks them when picking corners). */
    val boxes: List<BBox> get() = tracks.map { it.bbox }

    /** Tracks currently lost (waiting at their predicted position). */
    val lostTracks: List<Track> get() = tracks.filter { it.lost > 0 }.map { it.toTrack() }

    /** Returns the tracks SEEN in this frame. */
    fun update(detections: List<Detection>, motion: DoubleArray? = null): List<Track> {
        val high = detections.filter { it.score >= highThr }
        val low = detections.filter { it.score >= lowThr && it.score < highThr }

        for (t in tracks) {
            t.kf.predict()
            if (motion != null) t.kf.compensate(motion)
            t.age += 1
            t.lost += 1 // reset on match
        }

        val seen = HashSet<Int>()
        fun applyMatches(pool: List<Internal>, dets: List<Detection>, pairs: List<Pair<Int, Int>>) {
            for ((i, j) in pairs) {
                val t = pool[i]
                t.kf.update(dets[j].bbox)
                t.score = dets[j].score
                t.lost = 0
                t.hits += 1
                seen.add(t.id)
            }
        }

        // Stage 1: high-score detections
        val pool = tracks.toList()
        val m1 = match(pool.map { it.bbox }, high.map { it.bbox }, matchIou)
        applyMatches(pool, high, m1.pairs)
        val rest = m1.unmatchedTracks.map { pool[it] }
        val restHigh = m1.unmatchedDets.map { high[it] }

        // Stage 2: low-score detections vs remaining tracks (the heart of ByteTrack)
        val m2 = match(rest.map { it.bbox }, low.map { it.bbox }, secondIou)
        applyMatches(rest, low, m2.pairs)

        // New objects: unmatched high-score detections
        for (d in restHigh) {
            if (d.score >= newThr) {
                val t = Internal(nextId, d.bbox, d.score)
                nextId += 1
                tracks.add(t)
                seen.add(t.id)
            }
        }

        tracks = tracks.filter { it.lost <= maxLost }.toMutableList()
        return tracks.filter { it.id in seen && it.hits >= minHits }.map { it.toTrack() }
    }

    companion object {
        /** Same wiring as the PC app: `ByteTracker(low_thr=cfg.live_det_conf)`. */
        fun fromConfig(cfg: Config) = ByteTracker(lowThr = cfg.liveDetConf)
    }
}
