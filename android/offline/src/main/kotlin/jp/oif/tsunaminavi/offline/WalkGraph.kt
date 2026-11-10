package jp.oif.tsunaminavi.offline

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.PriorityQueue

/**
 * 歩ける道のグラフ（OpenStreetMap から作った交差点と道の長さ。backend/scripts/prepare_graph.py）。
 * 圏外でも、直線距離ではなく道に沿った距離で「間に合うか」を出すために使う。
 * 地図データ: © OpenStreetMap contributors（ODbL）
 */
class WalkGraph private constructor(
    private val coords: FloatArray,
    private val offsets: IntArray,
    private val targets: IntArray,
    private val lengths: IntArray,
) {
    val size: Int get() = offsets.size - 1
    private val grid = HashMap<Long, MutableList<Int>>()

    init {
        for (i in 0 until size) grid.getOrPut(cellKey(coords[2 * i].toDouble(), coords[2 * i + 1].toDouble())) { mutableListOf() }.add(i)
    }

    fun lat(i: Int): Double = coords[2 * i].toDouble()
    fun lon(i: Int): Double = coords[2 * i + 1].toDouble()

    /** 一番近い点と、そこまでの直線距離。近く（約 600m 四方）に点が無ければ null。 */
    fun nearest(lat: Double, lon: Double): Pair<Int, Double>? {
        val cy = Math.floor(lat / CELL).toLong()
        val cx = Math.floor(lon / CELL).toLong()
        var best = -1
        var bestD = Double.MAX_VALUE
        for (dy in -1L..1L) for (dx in -1L..1L) {
            for (i in grid[key(cy + dy, cx + dx)] ?: continue) {
                val d = Planner.distanceM(lat, lon, lat(i), lon(i))
                if (d < bestD) {
                    best = i
                    bestD = d
                }
            }
        }
        return if (best < 0) null else best to bestD
    }

    /** source から各 goal までの道の長さ（m）。全部の goal に着くか、limitM を超えたら止める。届かない goal は入らない。 */
    fun distances(source: Int, goals: Set<Int>, limitM: Double): Map<Int, Double> {
        val dist = HashMap<Int, Double>()
        val left = goals.toMutableSet()
        val queue = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        dist[source] = 0.0
        queue.add(0.0 to source)
        val found = HashMap<Int, Double>()
        while (queue.isNotEmpty() && left.isNotEmpty()) {
            val (d, u) = queue.poll()
            if (d > (dist[u] ?: Double.MAX_VALUE)) continue
            if (d > limitM) break
            if (left.remove(u)) found[u] = d
            for (k in offsets[u] until offsets[u + 1]) {
                val v = targets[k]
                val nd = d + lengths[k]
                if (nd < (dist[v] ?: Double.MAX_VALUE)) {
                    dist[v] = nd
                    queue.add(nd to v)
                }
            }
        }
        return found
    }


    /** source から goal までの道の点の並び（地図に道を描くため）。limitM までに着かなければ null。 */
    fun path(source: Int, goal: Int, limitM: Double): List<Int>? {
        val dist = HashMap<Int, Double>()
        val prev = HashMap<Int, Int>()
        val queue = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        dist[source] = 0.0
        queue.add(0.0 to source)
        while (queue.isNotEmpty()) {
            val (d, u) = queue.poll()
            if (d > (dist[u] ?: Double.MAX_VALUE)) continue
            if (d > limitM) return null
            if (u == goal) return generateSequence(goal) { prev[it] }.toList().reversed()
            for (k in offsets[u] until offsets[u + 1]) {
                val v = targets[k]
                val nd = d + lengths[k]
                if (nd < (dist[v] ?: Double.MAX_VALUE)) {
                    dist[v] = nd
                    prev[v] = u
                    queue.add(nd to v)
                }
            }
        }
        return null
    }

    /** 範囲の中にある道を、線分（緯度1, 経度1, 緯度2, 経度2）の並びで返す。地図の背景に使う。 */
    fun segmentsIn(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, max: Int = 4000): FloatArray {
        val out = ArrayList<Float>()
        val y0 = Math.floor(minLat / CELL).toLong(); val y1 = Math.floor(maxLat / CELL).toLong()
        val x0 = Math.floor(minLon / CELL).toLong(); val x1 = Math.floor(maxLon / CELL).toLong()
        loop@ for (y in y0..y1) for (x in x0..x1) for (u in grid[key(y, x)] ?: continue) {
            for (k in offsets[u] until offsets[u + 1]) {
                val v = targets[k]
                if (v < u && (lat(v) in minLat..maxLat && lon(v) in minLon..maxLon)) continue
                out.add(coords[2 * u]); out.add(coords[2 * u + 1]); out.add(coords[2 * v]); out.add(coords[2 * v + 1])
                if (out.size >= max * 4) break@loop
            }
        }
        return out.toFloatArray()
    }

    companion object {
        private const val CELL = 0.003

        private fun cellKey(lat: Double, lon: Double) = key(Math.floor(lat / CELL).toLong(), Math.floor(lon / CELL).toLong())
        private fun key(y: Long, x: Long) = (y shl 32) xor (x and 0xffffffffL)

        /** prepare_graph.py が書いた形式（"WG01"・点の数・辺の数・座標・CSR）を読む。 */
        fun fromBytes(bytes: ByteArray): WalkGraph {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { b.get(it) }
            require(String(magic) == "WG01") { "道のグラフの形式が違う" }
            val n = b.int
            val m = b.int
            val coords = FloatArray(2 * n) { b.float }
            val offsets = IntArray(n + 1) { b.int }
            val targets = IntArray(m) { b.int }
            val lengths = IntArray(m) { b.short.toInt() and 0xffff }
            return WalkGraph(coords, offsets, targets, lengths)
        }

        /** テスト用: 点の座標と（点, 点, 長さ）の辺から作る。 */
        fun of(points: List<Pair<Double, Double>>, edges: List<Triple<Int, Int, Int>>): WalkGraph {
            val adj = List(points.size) { mutableListOf<Pair<Int, Int>>() }
            for ((u, v, l) in edges) {
                adj[u].add(v to l)
                adj[v].add(u to l)
            }
            val offsets = IntArray(points.size + 1)
            for (i in points.indices) offsets[i + 1] = offsets[i] + adj[i].size
            return WalkGraph(
                FloatArray(2 * points.size) { k -> (if (k % 2 == 0) points[k / 2].first else points[k / 2].second).toFloat() },
                offsets,
                adj.flatten().map { it.first }.toIntArray(),
                adj.flatten().map { it.second }.toIntArray(),
            )
        }
    }
}
