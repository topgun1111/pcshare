package com.takipli.oynat.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Part 11: pick (tap / rectangle + preset) -> target -> views. Synthetic doc: one person, 5 samples, 1000 x 1000. */
class PickTest {
    private val cfg = Config()

    // joint -> normalized (x, y); every other joint has confidence 0 (ignored)
    private fun row(): List<Double> {
        val pts = mapOf(0 to (0.30 to 0.15), 9 to (0.22 to 0.50), 15 to (0.27 to 0.85), 16 to (0.33 to 0.85))
        return (0 until N_KPT).flatMap { j -> pts[j]?.let { listOf(it.first, it.second, 0.9) } ?: listOf(0.0, 0.0, 0.0) }
    }

    private val doc = TrackDoc(
        version = 2,
        video = VideoInfo(w = 1000, h = 1000, fps = 10.0, duration = 0.4),
        sampleFps = 10.0,
        samples = (0..4).map { Sample(it * 0.1, d = listOf(listOf(1.0, 0.2, 0.1, 0.4, 0.9, 0.9)), kp = mapOf("1" to row())) },
    )

    @Test
    fun jointMapping() {
        assertEquals(listOf("head", "head", "head", "head", "head"), (0..4).map { presetForJoint(it) })
        assertEquals("torso", presetForJoint(11))
        assertEquals("left_arm", presetForJoint(9))
        assertEquals("right_arm", presetForJoint(10))
        assertEquals("left_foot", presetForJoint(15))
        assertEquals("right_foot", presetForJoint(16))
        assertNull(presetForJoint(17))
        assertTrue((0 until N_KPT).all { presetForJoint(it) in PRESETS })
    }

    @Test
    fun tapOnAnkleBecomesFootPreset() {
        assertEquals(Target(1, preset = "left_foot"), resolvePick(doc, TargetPick(0.0, 0.27, 0.85), cfg))
        assertEquals(Target(1, preset = "right_foot"), resolvePick(doc, TargetPick(0.0, 0.33, 0.86), cfg))
    }

    @Test
    fun exactKeepsSingleJoint() {
        assertEquals(Target(1, part = 15), resolvePick(doc, TargetPick(0.0, 0.27, 0.85, exact = true), cfg))
    }

    @Test
    fun chosenPresetWins() {
        assertEquals(Target(1, preset = "legs"), resolvePick(doc, TargetPick(0.0, 0.30, 0.15, preset = "legs"), cfg))
        assertEquals(Target(1, preset = "person"), resolvePick(doc, TargetPick(0.0, 0.27, 0.85, preset = "person"), cfg))
        // unknown preset name is ignored: the tapped joint decides
        assertEquals(Target(1, preset = "head"), resolvePick(doc, TargetPick(0.0, 0.30, 0.15, preset = "nope"), cfg))
    }

    @Test
    fun tapOnBodyWithoutJointIsWholePerson() {
        assertEquals(Target(1), resolvePick(doc, TargetPick(0.0, 0.30, 0.65), cfg))
    }

    @Test
    fun tapOnBackgroundResolvesToNothing() {
        assertNull(resolvePick(doc, TargetPick(0.0, 0.80, 0.50), cfg))
        assertNull(applyPick(doc, TargetPick(0.0, 0.80, 0.50), cfg))
    }

    @Test
    fun rectangleOverPersonWithPreset() {
        val t = resolvePick(doc, TargetPick(0.0, 0.18, 0.70, x2 = 0.42, y2 = 0.95, preset = "feet"), cfg)
        assertEquals(Target(1, preset = "feet"), t)
    }

    @Test
    fun applyPickFillsTargetAndViews() {
        val out = assertNotNull(applyPick(doc, TargetPick(0.0, 0.27, 0.85), cfg))
        assertEquals(Target(1, preset = "left_foot"), out.target)
        assertTrue(out.views.isNotEmpty())
        assertEquals(doc.samples, out.samples) // samples untouched
        assertNull(doc.target) // input doc is not modified
    }

    @Test
    fun pickJsonRoundTrip() {
        val p = TargetPick(1.5, 0.25, 0.5, preset = "head", exact = true)
        assertEquals(p, Formats.json.decodeFromString<TargetPick>(Formats.json.encodeToString(p)))
        assertEquals(TargetPick(0.0, 0.1, 0.2), Formats.json.decodeFromString<TargetPick>("""{"t":0,"x":0.1,"y":0.2}"""))
    }
}
