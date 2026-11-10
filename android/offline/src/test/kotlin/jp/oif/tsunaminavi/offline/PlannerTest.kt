package jp.oif.tsunaminavi.offline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class PlannerTest {
    @Test
    fun disconnectedGraphNodeIsNotTreatedAsStraightLineRoute() {
        val graph = WalkGraph.of(listOf(34.0 to 135.0, 34.001 to 135.0, 34.002 to 135.0), listOf(Triple(0, 1, 100)))
        val reachable = Shelter("connected", "connected", "", 34.001, 135.0, null, null, false, 1)
        val disconnected = Shelter("disconnected", "disconnected", "", 34.002, 135.0, null, null, false, 2)
        val plan = Planner.plan(34.0, 135.0, 26.0, false, listOf(disconnected, reachable), InundationMap.EMPTY, graph)
        assertEquals("connected", plan.primary.shelter.id)
        assertFailsWith<IllegalArgumentException> {
            Planner.plan(34.0, 135.0, 26.0, false, listOf(disconnected), InundationMap.EMPTY, graph)
        }
    }
    // 経度 135.0〜135.01・緯度 34.0〜34.01 の正方形が「1.0～2.0m」の浸水想定（1 辺 約 1km）
    private val zone = InundationMap.fromMultiPolygons(
        mapOf(
            "1.0～2.0m" to listOf(
                listOf(listOf(listOf(135.0, 34.0), listOf(135.01, 34.0), listOf(135.01, 34.01), listOf(135.0, 34.01), listOf(135.0, 34.0))),
            ),
        ),
    )
    private val user = 34.005 to 135.0095

    // 区域の外（東に約 460m）と、区域の中のビル（約 100m）と公園（約 60m）
    private val outside = Shelter("out", "区域の外のホテル", "", 34.005, 135.0145, null, null, false)
    private val building = Shelter("bld", "近くのビル", "", 34.0059, 135.0095, "1.0～2.0m", 3, false)
    private val park = Shelter("park", "近くの公園", "", 34.0055, 135.0094, "1.0～2.0m", 3, true)
    private val all = listOf(outside, building, park)

    @Test
    fun 区域の中にいると深さが分かる() {
        assertEquals("1.0～2.0m", zone.depthAt(user.first, user.second))
        assertNull(zone.depthAt(34.02, 135.02))
    }

    @Test
    fun 間に合えば区域の外へ() {
        val p = Planner.plan(user.first, user.second, 25.0, false, all, zone)
        assertEquals(Decision.REACH_OUTSIDE_ZONE, p.decision)
        assertEquals("out", p.primary.shelter.id)
    }

    @Test
    fun 遅いと間に合わず近いビルの上へ_公園は選ばない() {
        // 通常: 460m×1.6 ÷ 0.62m/秒 ≒ 19.8 分 ≦ 22 分 ／ 遅い: ÷ 0.5m/秒 ≒ 24.6 分 > 残り 25 − 余裕 3 = 22 分
        val p = Planner.plan(user.first, user.second, 25.0, true, all, zone)
        assertEquals(Decision.GO_UPSTAIRS, p.decision)
        assertEquals("bld", p.primary.shelter.id)
        assertEquals(Planner.SLOW_WALK_SPEED_M_PER_S, p.walkSpeedMPerS)
    }

    @Test
    fun どこも間に合わなければ一番近い所() {
        val p = Planner.plan(user.first, user.second, 3.5, false, all, zone)
        assertEquals(Decision.MAY_NOT_MAKE_IT, p.decision)
    }

    @Test
    fun 区域の外にいる時() {
        val p = Planner.plan(34.02, 135.0145, 1.0, false, listOf(outside), zone)
        assertEquals(Decision.ALREADY_OUTSIDE_ZONE, p.decision)
        assertNull(p.userInundationDepth)
    }

    @Test
    fun 歩く時間は距離かける回り道を速さで割る() {
        val p = Planner.plan(user.first, user.second, 25.0, false, all, zone)
        val expected = Planner.distanceM(user.first, user.second, outside.lat, outside.lon) * Planner.DETOUR / 0.62 / 60.0
        assertEquals(expected, p.primary.walkMin, 1e-9)
    }

    @Test
    fun 圏外の残り時間() {
        val now = 1_000_000_000L
        assertEquals(26.0, Planner.minutesLeftOffline(now, null, now), 1e-9)
        assertEquals(16.0, Planner.minutesLeftOffline(now, null, now - 10 * 60_000L), 1e-9)
        assertEquals(5.0, Planner.minutesLeftOffline(now, now + 5 * 60_000L, now), 1e-9)
        assertTrue(Planner.minutesLeftOffline(now, now - 60_000L, null) == 0.0)
    }
}
