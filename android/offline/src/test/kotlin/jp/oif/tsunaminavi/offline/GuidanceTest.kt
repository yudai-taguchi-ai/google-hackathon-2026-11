package jp.oif.tsunaminavi.offline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GuidanceTest {
    @Test
    fun 方角() {
        assertEquals(0.0, Guidance.bearingDeg(34.0, 135.0, 34.01, 135.0), 0.01)
        assertEquals(90.0, Guidance.bearingDeg(34.0, 135.0, 34.0, 135.01), 0.1)
        assertEquals(Compass.N, Guidance.compass(10.0))
        assertEquals(Compass.NE, Guidance.compass(40.0))
        assertEquals(Compass.N, Guidance.compass(350.0))
    }

    @Test
    fun 端末の向きから見た角度() {
        assertEquals(90.0, Guidance.relativeDeg(90.0, 0.0), 1e-9)
        assertEquals(-90.0, Guidance.relativeDeg(0.0, 90.0), 1e-9)
        assertEquals(-20.0, Guidance.relativeDeg(350.0, 10.0), 1e-9)
    }

    @Test
    fun 着いたかどうか() {
        val s = Shelter("x", "x", "", 34.0, 135.0, null, null, false)
        assertTrue(Guidance.step(34.0001, 135.0, s, null, 0.62).arrived)
        assertFalse(Guidance.step(34.001, 135.0, s, null, 0.62).arrived)
    }
}
