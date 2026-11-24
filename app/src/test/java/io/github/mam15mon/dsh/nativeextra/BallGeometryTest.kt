package io.github.mam15mon.dsh.nativeextra

import org.junit.Assert.*
import org.junit.Test

class BallGeometryTest {
    @Test fun dragCannotEnterSystemBarsOrLeaveScreen() {
        val area = BallGeometry(20, 140, 1200, 2700)
        assertEquals(20, area.x(-100, 200))
        assertEquals(1000, area.x(3000, 200))
        assertEquals(140, area.y(-100, 200))
        assertEquals(2500, area.y(5000, 200))
    }
    @Test fun rotationPreservesRelativeFreePosition() {
        val portrait = BallGeometry(20, 140, 1200, 2700)
        val landscape = BallGeometry(140, 20, 2700, 1200)
        val x = portrait.fromX(.44f, 160); val y = portrait.fromY(.37f, 160)
        val rx = portrait.ratioX(x, 160); val ry = portrait.ratioY(y, 160)
        assertEquals(.44f, landscape.ratioX(landscape.fromX(rx, 160), 160), .002f)
        assertEquals(.37f, landscape.ratioY(landscape.fromY(ry, 160), 160), .002f)
        assertTrue(x > portrait.left && x < portrait.right - 160)
    }
    @Test fun resizingPreservesRelativePositionAndBounds() {
        val area = BallGeometry(20, 140, 1200, 2700)
        for (size in listOf(32, 52, 96, 300)) {
            val x = area.fromX(.9f, size)
            assertEquals(.9f, area.ratioX(x, size), .002f)
            assertTrue(x + size <= area.right)
        }
    }
    @Test fun tinyDisplayAndExtremeRatiosRemainFinite() {
        val area = BallGeometry(10, 20, 30, 40)
        assertEquals(10, area.fromX(2f, 100))
        assertEquals(20, area.fromY(-1f, 100))
        assertEquals(0f, area.ratioX(100, 100), 0f)
        assertEquals(0f, area.ratioY(100, 100), 0f)
    }
}
