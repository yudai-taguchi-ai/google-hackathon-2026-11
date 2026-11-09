package jp.oif.tsunaminavi.offline

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** どう逃げるかの判定。サーバー（backend/app/planner.py）の decision と同じ値。 */
enum class Decision(val apiValue: String) {
    REACH_OUTSIDE_ZONE("reach_outside_zone"),
    ALREADY_OUTSIDE_ZONE("already_outside_zone"),
    GO_UPSTAIRS("go_upstairs"),
    MAY_NOT_MAKE_IT("may_not_make_it"),
}

enum class OptionType { OUTSIDE_ZONE, GO_UPSTAIRS }

data class Option(
    val shelter: Shelter,
    val walkM: Double,
    val walkMin: Double,
    val reachable: Boolean,
    val type: OptionType,
)

data class Plan(
    val decision: Decision,
    val primary: Option,
    val alternatives: List<Option>,
    val userInundationDepth: String?,
    val minutesLeft: Double,
    val walkSpeedMPerS: Double,
)

/**
 * 圏外の時に、保存したパックだけで避難先を選ぶ。サーバーの planner.py と同じ手順。
 * 違いは歩く距離だけで、圏外では Routes API が使えないので「直線距離 × 1.3」の目安を使う。
 */
object Planner {
    const val WALK_SPEED_M_PER_S = 0.62
    const val SLOW_WALK_SPEED_M_PER_S = 0.5
    const val DETOUR = 1.3
    const val SAFETY_MARGIN_MIN = 3.0
    const val CANDIDATES = 12
    const val ARRIVAL_DEFAULT_MIN = 26.0

    fun plan(
        lat: Double,
        lon: Double,
        minutesLeft: Double,
        slow: Boolean,
        shelters: List<Shelter>,
        inundation: InundationMap,
    ): Plan {
        require(shelters.isNotEmpty()) { "避難先が 1 件もありません（パックが空）" }
        val userDepth = inundation.depthAt(lat, lon)
        val byDistance = shelters.sortedBy { distanceM(lat, lon, it.lat, it.lon) }
        val outsidePool = byDistance.filter { it.inundationDepth == null }.take(CANDIDATES)
        val upstairsPool = if (userDepth == null) emptyList() else
            byDistance.filter { it.inundationDepth != null && !it.openSpace }.take(CANDIDATES)

        val speed = if (slow) SLOW_WALK_SPEED_M_PER_S else WALK_SPEED_M_PER_S
        val budget = minutesLeft - SAFETY_MARGIN_MIN
        val options = (outsidePool + upstairsPool).map { s ->
            val meters = distanceM(lat, lon, s.lat, s.lon) * DETOUR
            val minutes = meters / speed / 60.0
            Option(
                shelter = s,
                walkM = meters,
                walkMin = minutes,
                reachable = minutes <= budget,
                type = if (s.inundationDepth == null) OptionType.OUTSIDE_ZONE else OptionType.GO_UPSTAIRS,
            )
        }

        val reachable = options.filter { it.reachable }
        val outside = reachable.filter { it.type == OptionType.OUTSIDE_ZONE }.sortedBy { it.walkMin }
        val upstairs = reachable.filter { it.type == OptionType.GO_UPSTAIRS }.sortedBy { it.walkMin }
        val (primary, decision) = when {
            outside.isNotEmpty() -> outside.first() to Decision.REACH_OUTSIDE_ZONE
            userDepth == null -> options.minBy { it.walkMin } to Decision.ALREADY_OUTSIDE_ZONE
            upstairs.isNotEmpty() -> upstairs.first() to Decision.GO_UPSTAIRS
            else -> options.minBy { it.walkMin } to Decision.MAY_NOT_MAKE_IT
        }
        val alternatives = (outside + upstairs).filter { it !== primary }.take(2)
        return Plan(decision, primary, alternatives, userDepth, minutesLeft, speed)
    }

    /**
     * 圏外の時の「到達までの残り分」。
     * 気象庁の到達予想時刻を一度でも受け取っていればそれを使い、無ければ「揺れた」を押した時刻 + 26 分。
     */
    fun minutesLeftOffline(nowMillis: Long, cachedArrivalMillis: Long?, shakePressedMillis: Long?): Double {
        val arrival = cachedArrivalMillis
            ?: shakePressedMillis?.let { it + (ARRIVAL_DEFAULT_MIN * 60_000).toLong() }
            ?: (nowMillis + (ARRIVAL_DEFAULT_MIN * 60_000).toLong())
        return maxOf(0.0, (arrival - nowMillis) / 60_000.0)
    }

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }
}
