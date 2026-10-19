package com.nerfherder.walkthor.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorShapeTest {
    private fun ratio(r: FloatArray) = (r[2] - r[0]) / (r[3] - r[1])
    private fun inside(r: FloatArray) = r.all { it in -0.0001f..1.0001f } && r[0] < r[2] && r[1] < r[3]

    @Test fun shapedKeepsRatioAndStaysInside() {
        for (k in listOf(0.65f, 1f, 2f)) {
            for (r in listOf(floatArrayOf(0f, 0f, 1f, 1f), floatArrayOf(0.8f, 0f, 1f, 0.3f), floatArrayOf(0.1f, 0.6f, 0.3f, 0.95f))) {
                val s = shaped(r, k)
                assertEquals(k, ratio(s), 0.001f)
                assertTrue(inside(s))
            }
        }
    }

    @Test fun cornerDragKeepsRatioAndAnchor() {
        val k = 0.65f
        val start = shaped(floatArrayOf(0.3f, 0.3f, 0.6f, 0.6f), k)
        for (corner in 0..3) {
            for ((dx, dy) in listOf(0.1f to 0f, 0f to 0.1f, -0.2f to -0.05f, 0.9f to 0.9f, -0.9f to -0.9f)) {
                val r = resizeShaped(start, corner, dx, dy, k)
                assertEquals(k, ratio(r), 0.001f)
                assertTrue(inside(r))
                // The opposite corner doesn't move.
                val ax = if (corner == 1 || corner == 3) 0 else 2
                val ay = if (corner == 2 || corner == 3) 1 else 3
                assertEquals(start[ax], r[ax], 0.0001f)
                assertEquals(start[ay], r[ay], 0.0001f)
            }
        }
    }
}
