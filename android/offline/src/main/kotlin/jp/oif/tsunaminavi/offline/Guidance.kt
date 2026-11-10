package jp.oif.tsunaminavi.offline

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** 8 方位。画面の文字と、読み上げる言葉の元にする。 */
enum class Compass(val ja: String, val en: String, val zh: String, val ko: String) {
    N("北", "north", "北", "북"), NE("北東", "northeast", "东北", "북동"), E("東", "east", "东", "동"), SE("南東", "southeast", "东南", "남동"),
    S("南", "south", "南", "남"), SW("南西", "southwest", "西南", "남서"), W("西", "west", "西", "서"), NW("北西", "northwest", "西北", "북서"),
}

/**
 * 1 回分の案内。
 * @property relativeDeg 端末の向きから見た避難先の角度（-180〜180、右がプラス）。向きが分からない時は null
 */
data class GuidanceStep(
    val distanceM: Double,
    val bearingDeg: Double,
    val relativeDeg: Double?,
    val compass: Compass,
    val remainingMin: Double,
    val arrived: Boolean,
)

/** 圏外の時の案内。地図は使わず、避難先への方角の矢印と、残りの距離だけを出す。 */
object Guidance {
    /** この距離より近づいたら「着いた」にする（GPS の誤差を見込む）。 */
    const val ARRIVED_WITHIN_M = 25.0

    fun step(
        lat: Double,
        lon: Double,
        target: Shelter,
        headingDeg: Double?,
        walkSpeedMPerS: Double,
    ): GuidanceStep {
        val straight = Planner.distanceM(lat, lon, target.lat, target.lon)
        val bearing = bearingDeg(lat, lon, target.lat, target.lon)
        return GuidanceStep(
            distanceM = straight,
            bearingDeg = bearing,
            relativeDeg = headingDeg?.let { relativeDeg(bearing, it) },
            compass = compass(bearing),
            remainingMin = straight * Planner.DETOUR / walkSpeedMPerS / 60.0,
            arrived = straight <= ARRIVED_WITHIN_M,
        )
    }

    /** 北を 0 度として時計回りに、今いる所から見た相手の方角（0〜360）。 */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** 端末が向いている方角から見て、相手が何度右（プラス）・左（マイナス）にあるか。 */
    fun relativeDeg(bearingDeg: Double, headingDeg: Double): Double {
        val d = ((bearingDeg - headingDeg) % 360.0 + 360.0) % 360.0
        return if (d > 180.0) d - 360.0 else d
    }

    fun compass(bearingDeg: Double): Compass =
        Compass.entries[((bearingDeg / 45.0).roundToInt()) % 8]
}
