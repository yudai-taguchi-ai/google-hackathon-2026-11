package jp.oif.tsunaminavi.offline

/** パックの中の避難先 1 件。浸水の深さ・階・屋外かどうかはサーバーで計算済みの値をそのまま使う。 */
data class Shelter(
    val id: String,
    val name: String,
    val address: String,
    val lat: Double,
    val lon: Double,
    val inundationDepth: String?,
    val floorMin: Int?,
    val openSpace: Boolean,
    /** 道のグラフで一番近い点（パックに入っている時だけ）。 */
    val graphNode: Int? = null,
)

/** 浸水想定の深さの区分。パックの文字列（全角のチルダ「～」を含む）と同じ並びで、浅い順。 */
val DEPTH_ORDER = listOf("0.01～0.3m", "0.3～1.0m", "1.0～2.0m", "2.0～3.0m", "3.0～4.0m", "4.0～5.0m", "5.0m～")

internal class Polygon(val rings: List<DoubleArray>) {
    private val minLon: Double
    private val maxLon: Double
    private val minLat: Double
    private val maxLat: Double

    init {
        val outer = rings.first()
        var a = Double.MAX_VALUE; var b = -Double.MAX_VALUE; var c = Double.MAX_VALUE; var d = -Double.MAX_VALUE
        for (i in outer.indices step 2) {
            a = minOf(a, outer[i]); b = maxOf(b, outer[i])
            c = minOf(c, outer[i + 1]); d = maxOf(d, outer[i + 1])
        }
        minLon = a; maxLon = b; minLat = c; maxLat = d
    }

    fun contains(lat: Double, lon: Double): Boolean {
        if (lon < minLon || lon > maxLon || lat < minLat || lat > maxLat) return false
        if (!inRing(rings.first(), lat, lon)) return false
        return rings.drop(1).none { inRing(it, lat, lon) }
    }

    private fun inRing(ring: DoubleArray, lat: Double, lon: Double): Boolean {
        var inside = false
        var j = ring.size - 2
        for (i in ring.indices step 2) {
            val xi = ring[i]; val yi = ring[i + 1]
            val xj = ring[j]; val yj = ring[j + 1]
            if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }
}

/**
 * 浸水想定の地図。パックの GeoJSON（深さの区分ごとの MultiPolygon）から作る。
 * 区分の境目で重なった時は、深い方を返す（安全側）。
 */
class InundationMap private constructor(private val areas: List<Pair<String, List<Polygon>>>) {

    fun depthAt(lat: Double, lon: Double): String? =
        areas.filter { (_, polys) -> polys.any { it.contains(lat, lon) } }
            .maxByOrNull { (depth, _) -> DEPTH_ORDER.indexOf(depth) }
            ?.first

    companion object {
        /**
         * @param multiPolygons 深さの区分 → GeoJSON の MultiPolygon の coordinates
         *   （[ポリゴン][輪][点][経度, 緯度]）。data/ 側で JSON から読んで渡す。
         */
        fun fromMultiPolygons(multiPolygons: Map<String, List<List<List<List<Double>>>>>): InundationMap =
            InundationMap(multiPolygons.map { (depth, polys) ->
                depth to polys.map { rings ->
                    Polygon(rings.map { ring -> DoubleArray(ring.size * 2) { k -> ring[k / 2][k % 2] } })
                }
            })

        val EMPTY = InundationMap(emptyList())
    }
}
