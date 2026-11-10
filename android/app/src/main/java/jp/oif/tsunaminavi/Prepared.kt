package jp.oif.tsunaminavi

import android.content.Context
import java.io.File
import jp.oif.tsunaminavi.offline.Planner
import jp.oif.tsunaminavi.offline.Shelter
import org.json.JSONArray
import org.json.JSONObject

/** 最後に受け取った警報（アプリを開き直しても、圏外でも、到達予想時刻を使えるように保存する）。 */
data class CachedAlert(val kind: String, val arrivalMillis: Long, val isTraining: Boolean, val receivedAt: Long)

/** 通信がある間に、サーバー（Routes API の道のり・Gemini の説明）で前もって出しておいた避難先。 */
data class PreparedPlan(
    val lat: Double,
    val lon: Double,
    val slow: Boolean,
    val preparedAt: Long,
    val alertArrival: Long?,
    val shelter: Shelter,
    val walkMin: Double,
    val decision: String,
    val explanation: String?,
) {
    fun distanceTo(loc: Pair<Double, Double>) = Planner.distanceM(lat, lon, loc.first, loc.second)
}

/** 圏外に備えて用意したものを、端末のファイルに保存する。 */
class PreparedStore(context: Context) {
    private val file = File(context.filesDir, "prepared.json")

    fun load(): Pair<CachedAlert?, List<PreparedPlan>> = runCatching {
        if (!file.exists()) return null to emptyList()
        val o = JSONObject(file.readText())
        val alert = o.optJSONObject("alert")?.let {
            CachedAlert(it.getString("kind"), it.getLong("arrival"), it.getBoolean("training"), it.getLong("received"))
        }
        val arr = o.optJSONArray("plans") ?: JSONArray()
        val plans = (0 until arr.length()).map { i ->
            val p = arr.getJSONObject(i)
            val s = p.getJSONObject("shelter")
            PreparedPlan(
                lat = p.getDouble("lat"),
                lon = p.getDouble("lon"),
                slow = p.getBoolean("slow"),
                preparedAt = p.getLong("at"),
                alertArrival = if (p.isNull("alert")) null else p.getLong("alert"),
                shelter = Shelter(
                    id = s.getString("id"),
                    name = s.getString("name"),
                    address = s.optString("address"),
                    lat = s.getDouble("lat"),
                    lon = s.getDouble("lon"),
                    inundationDepth = if (s.isNull("depth")) null else s.getString("depth"),
                    floorMin = if (s.isNull("floor")) null else s.getInt("floor"),
                    openSpace = false,
                ),
                walkMin = p.getDouble("walk"),
                decision = p.getString("decision"),
                explanation = if (p.isNull("explanation")) null else p.getString("explanation"),
            )
        }
        alert to plans
    }.getOrDefault(null to emptyList())

    fun save(alert: CachedAlert?, plans: List<PreparedPlan>) {
        val o = JSONObject()
        alert?.let {
            o.put("alert", JSONObject().put("kind", it.kind).put("arrival", it.arrivalMillis).put("training", it.isTraining).put("received", it.receivedAt))
        }
        o.put("plans", JSONArray(plans.map { p ->
            JSONObject()
                .put("lat", p.lat).put("lon", p.lon).put("slow", p.slow).put("at", p.preparedAt)
                .put("alert", p.alertArrival ?: JSONObject.NULL)
                .put("walk", p.walkMin).put("decision", p.decision).put("explanation", p.explanation ?: JSONObject.NULL)
                .put("shelter", JSONObject()
                    .put("id", p.shelter.id).put("name", p.shelter.name).put("address", p.shelter.address)
                    .put("lat", p.shelter.lat).put("lon", p.shelter.lon)
                    .put("depth", p.shelter.inundationDepth ?: JSONObject.NULL)
                    .put("floor", p.shelter.floorMin ?: JSONObject.NULL))
        }))
        val tmp = File(file.parentFile, "prepared.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(file)
    }
}
