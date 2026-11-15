package com.takipli.oynat.core

import kotlinx.serialization.Serializable

// ---- Part 11: what the user picked on the first frame, resolved to {person ID, part} once the scan has IDs. ----

/**
 * A pick made on the frame shown at time `t`: normalized point (`x`, `y`) or, with `x2`/`y2`, a rectangle. `preset`
 * = body part chosen in the UI (null = automatic: the tapped joint decides). `exact` = keep a tapped joint as a
 * single-joint target instead of mapping it to its preset group.
 */
@Serializable
data class TargetPick(
    val t: Double,
    val x: Double,
    val y: Double,
    val x2: Double? = null,
    val y2: Double? = null,
    val preset: String? = null,
    val exact: Boolean = false,
    /** Part 16: follow this exact spot (not a joint/person); `zoom` = log2 chosen by the user. */
    val free: Boolean = false,
    val zoom: Double? = null,
    /** Part 18: freehand region polygons `[x, y]` normalized (free = true); x/y = centre of their bounds. */
    val shapes: List<List<List<Double>>> = emptyList(),
)

/**
 * Tapped joint -> preset group (Part 11 UI decision, provisional): head 0-4, torso (shoulders, hips), left/right arm
 * (elbow, wrist), left/right leg (knee), left/right foot (ankle). Subject's left/right (COCO convention).
 */
fun presetForJoint(j: Int): String? = when (j) {
    in 0..4 -> "head"
    5, 6, 11, 12 -> "torso"
    7, 9 -> "left_arm"
    8, 10 -> "right_arm"
    13 -> "left_leg"
    14 -> "right_leg"
    15 -> "left_foot"
    16 -> "right_foot"
    else -> null
}

/**
 * Pick -> target: person from the tap/rectangle (`resolveTap` / `resolveRect`); a preset chosen in the UI wins;
 * otherwise a tapped joint becomes its preset group (or the single joint when `exact`); no joint = whole person.
 * Null when no person is at the picked point.
 */
fun resolvePick(doc: TrackDoc, pick: TargetPick, cfg: Config): Target? {
    if (pick.free) { // Part 16: any spot; the person under it (if any) is only the anchor
        val id = resolveTap(doc, pick.t, pick.x, pick.y, cfg)?.first
        if (pick.shapes.isNotEmpty()) return Target(id ?: -1, free = regionPick(pick.t, pick.shapes, id).copy(zoom = pick.zoom))
        return Target(id ?: -1, free = FreePick(pick.t, pick.x, pick.y, id, pick.zoom))
    }
    val hit = if (pick.x2 != null && pick.y2 != null) resolveRect(doc, pick.t, pick.x, pick.y, pick.x2, pick.y2, cfg)
    else resolveTap(doc, pick.t, pick.x, pick.y, cfg)
    val (id, joint) = hit ?: return null
    if (pick.preset != null && pick.preset in PRESETS) return Target(id, preset = pick.preset)
    if (joint == null) return Target(id)
    if (pick.exact) return Target(id, part = joint)
    return presetForJoint(joint)?.let { Target(id, preset = it) } ?: Target(id, part = joint)
}

/** Scan doc + pick -> the same doc with `target` and `views` filled in (no rescan), or null when nobody is at the pick. */
fun applyPick(doc: TrackDoc, pick: TargetPick, cfg: Config): TrackDoc? {
    val target = resolvePick(doc, pick, cfg) ?: return null
    return doc.copy(target = target, views = buildTargetViews(doc, target, cfg))
}
