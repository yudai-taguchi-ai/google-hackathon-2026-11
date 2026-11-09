package jp.oif.tsunaminavi

import android.app.Application
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.time.OffsetDateTime
import java.util.Locale
import jp.oif.tsunaminavi.offline.Decision
import jp.oif.tsunaminavi.offline.Guidance
import jp.oif.tsunaminavi.offline.GuidanceStep
import jp.oif.tsunaminavi.offline.Planner
import jp.oif.tsunaminavi.offline.Shelter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 撮影の地点（docs/demo-and-verification.md）。屋内で試す時だけ使い、画面に「デモ地点」と出す。 */
private val DEMO_POINT = 34.5813 to 135.4673

data class UiState(
    val online: Boolean = true,
    val switchedToOfflineAt: Long? = null,
    val gps: Pair<Double, Double>? = null,
    val useDemoPoint: Boolean = false,
    val heading: Double? = null,
    val packInfo: String? = null,
    val busy: String? = null,
    val alertKind: String? = null,
    val alertArrivalMillis: Long? = null,
    val isTraining: Boolean = false,
    val shakePressedMillis: Long? = null,
    val active: Boolean = false,
    val slow: Boolean = false,
    val excluded: Set<String> = emptySet(),
    val target: Shelter? = null,
    val targetIsUpstairs: Boolean = false,
    val decision: String? = null,
    val planSource: String? = null,
    val minutesLeft: Double? = null,
    val step: GuidanceStep? = null,
    val explanation: String? = null,
    val answer: String? = null,
    val error: String? = null,
) {
    val location: Pair<Double, Double>? get() = if (useDemoPoint) DEMO_POINT else gps
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = PackStore(app)
    private val connectivity = Connectivity(app)
    private val locationSource = LocationSource(app)
    private val headingSource = HeadingSource(app)
    private var pack: Pack? = null
    private var tts: TextToSpeech? = null
    private var player: MediaPlayer? = null

    private val _state = MutableStateFlow(UiState(online = connectivity.online.value))
    val state: StateFlow<UiState> = _state

    init {
        tts = TextToSpeech(app) { status -> if (status == TextToSpeech.SUCCESS) tts?.language = Locale.US }
        viewModelScope.launch {
            pack = store.load()
            _state.update { it.copy(packInfo = pack?.let(::describe)) }
        }
        viewModelScope.launch {
            connectivity.online.collect { online ->
                _state.update {
                    val switched = if (it.online && !online && it.active) System.currentTimeMillis() else it.switchedToOfflineAt
                    it.copy(online = online, switchedToOfflineAt = if (online) null else switched)
                }
            }
        }
        viewModelScope.launch { locationSource.location.collect { loc -> _state.update { it.copy(gps = loc) } } }
        viewModelScope.launch { headingSource.heading.collect { h -> _state.update { it.copy(heading = h) } } }
        viewModelScope.launch {
            while (true) {
                tick()
                delay(1_000)
            }
        }
    }

    fun onPermissionsGranted() {
        locationSource.start()
        headingSource.start()
    }

    fun setDemoPoint(on: Boolean) = _state.update { it.copy(useDemoPoint = on) }

    fun downloadPack() = viewModelScope.launch {
        _state.update { it.copy(busy = "パックをダウンロード中…", error = null) }
        try {
            store.download("osaka-bay", "en", null)
            pack = store.load()
            _state.update { it.copy(packInfo = pack?.let(::describe), busy = null) }
        } catch (e: Exception) {
            _state.update { it.copy(busy = null, error = "パックを取れませんでした: ${e.message}") }
        }
    }

    /** 警報を受け取る（デモは訓練電文の流し直し）。受け取った到達予想時刻は、圏外になっても使う。 */
    fun receiveAlert(replay: Boolean) = viewModelScope.launch {
        _state.update { it.copy(busy = "警報を確認中…", error = null) }
        try {
            val a = if (replay) Api.postJson("/api/alert/replay") else Api.getJson("/api/alert")
            val arrival = a["arrival_time"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
                ?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() }
            _state.update {
                it.copy(
                    busy = null,
                    alertKind = a["kind"]?.jsonPrimitive?.content,
                    alertArrivalMillis = arrival,
                    isTraining = a["is_training"]?.jsonPrimitive?.content == "true",
                    active = true,
                )
            }
            replan(speak = true)
        } catch (e: Exception) {
            _state.update { it.copy(busy = null, error = "警報を取れませんでした: ${e.message}") }
        }
    }

    /** 「揺れた／警報を見た」ボタン。通信が無くても、保存したパックだけで案内を始める。 */
    fun onShake() {
        _state.update { it.copy(shakePressedMillis = System.currentTimeMillis(), active = true, error = null) }
        replan(speak = true)
    }

    fun onIntent(id: String) {
        val s = _state.value
        when (id) {
            "reselect_slow", "reselect_wheelchair" -> {
                _state.update { it.copy(slow = true) }
                replan(speak = true)
            }
            "reselect", "road_blocked" -> {
                _state.update { st -> st.copy(excluded = st.excluded + listOfNotNull(st.target?.id)) }
                replan(speak = true)
            }
            "ask_floor" -> s.target?.floorMin?.let { speak("Go up to floor $it or higher.") }
        }
        val text = pack?.answers?.get(id)
        _state.update { it.copy(answer = text) }
        playAnswer(id)
    }

    fun reset() = _state.update {
        UiState(online = it.online, gps = it.gps, useDemoPoint = it.useDemoPoint, heading = it.heading, packInfo = it.packInfo)
    }

    private fun replan(speak: Boolean) {
        val s = _state.value
        val loc = s.location ?: run {
            _state.update { it.copy(error = "現在地が取れていません（GPS）") }
            return
        }
        val p = pack
        if (p != null) {
            val left = Planner.minutesLeftOffline(System.currentTimeMillis(), s.alertArrivalMillis, s.shakePressedMillis)
            val shelters = p.shelters.filter { it.id !in s.excluded }
            val plan = Planner.plan(loc.first, loc.second, left, s.slow, shelters, p.inundation)
            setTarget(plan.primary.shelter, plan.decision.apiValue, "端末の中（オフライン）", left)
            if (speak) speakPlan(plan.primary.shelter, plan.decision, plan.primary.walkMin)
        }
        if (s.online && s.excluded.isEmpty()) askServer(loc, speak = speak && p == null)
    }

    private fun askServer(loc: Pair<Double, Double>, speak: Boolean) = viewModelScope.launch {
        try {
            val body = """{"lat":${loc.first},"lon":${loc.second},"lang":"en","slow":${_state.value.slow}}"""
            val res = Api.postJson("/api/plan", body)
            val plan = res["plan"]!!.jsonObject
            val primary = plan["primary"]!!.jsonObject
            val shelter = toShelter(primary)
            val left = res["alert"]!!.jsonObject["minutes_left"]!!.jsonPrimitive.double
            setTarget(shelter, plan["decision"]!!.jsonPrimitive.content, "サーバー（Routes API・Gemini）", left)
            _state.update { it.copy(explanation = res["explanation"]?.jsonObject?.get("message")?.jsonPrimitive?.content) }
            if (speak) speakPlan(shelter, Decision.entries.first { it.apiValue == plan["decision"]!!.jsonPrimitive.content }, primary["walk_min"]!!.jsonPrimitive.double)
        } catch (e: Exception) {
            _state.update { it.copy(error = if (pack == null) "サーバーにつながらず、パックもありません" else null) }
        }
    }

    private fun toShelter(o: JsonObject) = Shelter(
        id = o["id"]!!.jsonPrimitive.content,
        name = o["name"]!!.jsonPrimitive.content,
        address = o["address"]?.jsonPrimitive?.content ?: "",
        lat = o["lat"]!!.jsonPrimitive.double,
        lon = o["lon"]!!.jsonPrimitive.double,
        inundationDepth = o["inundation_depth"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
        floorMin = o["floor_min"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.int,
        openSpace = false,
    )

    private fun setTarget(target: Shelter, decision: String, source: String, minutesLeft: Double) =
        _state.update {
            it.copy(target = target, targetIsUpstairs = target.inundationDepth != null, decision = decision, planSource = source, minutesLeft = minutesLeft)
        }

    private fun tick() {
        val s = _state.value
        val loc = s.location ?: return
        val t = s.target ?: return
        val speed = if (s.slow) Planner.SLOW_WALK_SPEED_M_PER_S else Planner.WALK_SPEED_M_PER_S
        val left = if (s.alertArrivalMillis != null || s.shakePressedMillis != null)
            Planner.minutesLeftOffline(System.currentTimeMillis(), s.alertArrivalMillis, s.shakePressedMillis) else s.minutesLeft
        _state.update { it.copy(step = Guidance.step(loc.first, loc.second, t, s.heading, speed), minutesLeft = left) }
    }

    private fun speakPlan(target: Shelter, decision: Decision, walkMin: Double) {
        val minutes = maxOf(1, walkMin.toInt())
        val text = when (decision) {
            Decision.REACH_OUTSIDE_ZONE -> "Go now to the place on the screen, out of the flood zone. About $minutes minutes on foot. You have time."
            Decision.ALREADY_OUTSIDE_ZONE -> "You are outside the flood zone. Move further inland, away from the sea and rivers."
            Decision.GO_UPSTAIRS -> "Go now to the building on the screen, and go up to floor ${target.floorMin ?: 3} or higher. About $minutes minutes on foot."
            Decision.MAY_NOT_MAKE_IT -> "You may not make it in time. Go up to a high floor of the nearest strong building now."
        }
        speak(text)
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "guide")
    }

    private fun playAnswer(id: String) {
        val f = pack?.audioFile(id) ?: return
        if (!f.exists()) return
        player?.release()
        player = MediaPlayer().apply {
            setDataSource(f.absolutePath)
            prepare()
            start()
        }
    }

    private fun describe(p: Pack) = "保存済み ${p.version}・避難先 ${p.shelters.size} 件・${p.sizeBytes / 1_000_000.0}MB"

    override fun onCleared() {
        tts?.shutdown()
        player?.release()
    }
}
