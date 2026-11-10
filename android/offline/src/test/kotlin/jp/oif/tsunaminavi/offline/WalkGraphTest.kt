package jp.oif.tsunaminavi.offline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WalkGraphTest {
    // 0 ─ 1 ─ 2 の道（各 100m）と、0 から 2 への遠回り 0 ─ 3 ─ 2（各 500m）
    private val g = WalkGraph.of(
        listOf(34.0 to 135.0, 34.0009 to 135.0, 34.0018 to 135.0, 34.0 to 135.005),
        listOf(Triple(0, 1, 100), Triple(1, 2, 100), Triple(0, 3, 500), Triple(3, 2, 500)),
    )

    @Test
    fun 一番短い道の長さ() {
        assertEquals(mapOf(2 to 200.0), g.distances(0, setOf(2), 10_000.0))
    }

    @Test
    fun 上限を超えたら探さない() {
        assertTrue(g.distances(0, setOf(2), 150.0).isEmpty())
    }

    @Test
    fun 一番近い点() {
        assertEquals(1, g.nearest(34.00091, 135.00001)?.first)
    }

    @Test
    fun 避難先までの距離に道のグラフを使う() {
        val s = Shelter("s", "s", "", 34.0018, 135.0, null, null, false, graphNode = 2)
        val p = Planner.plan(34.0, 135.0, 30.0, false, listOf(s), InundationMap.EMPTY, g)
        assertEquals("walk_graph", p.distanceSource)
        assertEquals(200.0, p.primary.walkM, 1.0)
    }

    @Test
    fun 地図に描く道の点の並び() {
        assertEquals(listOf(0, 1, 2), g.path(0, 2, 10_000.0))
        assertEquals(null, g.path(0, 2, 150.0))
    }

    @Test
    fun 範囲の中の道は重ねずに返す() {
        val seg = g.segmentsIn(33.99, 34.01, 134.99, 135.01)
        assertEquals(4 * 4, seg.size)
    }
}
