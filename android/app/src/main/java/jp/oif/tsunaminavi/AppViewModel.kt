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
import jp.oif.tsunaminavi.offline.IntentClassifier
import jp.oif.tsunaminavi.offline.KeywordIntent
import jp.oif.tsunaminavi.offline.Planner
import jp.oif.tsunaminavi.offline.Shelter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 撮影の地点（docs/demo-and-verification.md）。屋内で試す時だけ使い、画面に「Demo location」と出す。 */
private val DEMO_POINT = 34.5813 to 135.4673

/** サーバーの答えに乗り換えるのは、歩く時間がこれ以上短い時だけ（docs/modes.md ルール 1）。 */
private const val ADOPT_IF_SHORTER_MIN = 2.0
private const val ALERT_POLL_MS = 15_000L
private const val LIVE_SERVER_TIMEOUT_MS = 8_000L

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
    val targetWalkMin: Double? = null,
    val targetIsUpstairs: Boolean = false,
    val decision: String? = null,
    val planSource: String? = null,
    val minutesLeft: Double? = null,
    val step: GuidanceStep? = null,
    val explanation: String? = null,
    val notice: String? = null,
    val answer: String? = null,
    val error: String? = null,
    val liveActive: Boolean = false,
    val heard: String? = null,
    /** 気象庁の電文を最後に見た結果（画面に出す）。 */
    val jmaLine: String? = null,
    val listening: Boolean = false,
) {
    val location: Pair<Double, Double>? get() = if (useDemoPoint) DEMO_POINT else gps
    val timeBasis: String get() = if (alertArrivalMillis != null) "JMA arrival time" else "shaking + 26 min (estimate)"
}

/** 計算した避難先 1 件（端末かサーバーのどちらで計算したか付き）。 */
private data class Choice(val shelter: Shelter, val walkMin: Double, val decision: String, val source: String, val explanation: String?)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = PackStore(app)
    private val connectivity = Connectivity(app)
    private val locationSource = LocationSource(app)
    private val headingSource = HeadingSource(app)
    private var pack: Pack? = null
    private var tts: TextToSpeech? = null
    private var player: MediaPlayer? = null
    private val live = LiveAssistant(::handleLiveCall)
    private val speech = Speech(app)

    /** 圏外で話した言葉を頼みごとに振り分ける。今はキーワード方式。FunctionGemma の学習が済んだら差し替える。 */
    private val classifier: IntentClassifier = KeywordIntent
    val liveAvailable: Boolean get() = TsunamiApp.firebaseReady
    val debugSecret: String? get() = TsunamiApp.currentDebugSecret(getApplication())

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
                val wasOnline = _state.value.online
                _state.update {
                    val switched = if (it.online && !online && it.active) System.currentTimeMillis() else it.switchedToOfflineAt
                    it.copy(online = online, switchedToOfflineAt = if (online) null else switched)
                }
                if (!online && live.active) stopLive()
                if (online && !wasOnline) pollAlertOnce()
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
        viewModelScope.launch {
            while (true) {
                if (_state.value.online) pollAlertOnce()
                delay(ALERT_POLL_MS)
            }
        }
    }

    fun onPermissionsGranted() {
        locationSource.start()
        headingSource.start()
    }

    fun setDemoPoint(on: Boolean) = _state.update { it.copy(useDemoPoint = on) }

    fun downloadPack() = viewModelScope.launch {
        _state.update { it.copy(busy = "Saving offline data… / データを保存中", error = null) }
        try {
            store.download("osaka-bay", "en", null)
            pack = store.load()
            _state.update { it.copy(packInfo = pack?.let(::describe), busy = null) }
        } catch (e: Exception) {
            _state.update { it.copy(busy = null, error = "Could not save data / 保存できませんでした: ${e.message}") }
        }
    }

    /** デモ用: 訓練電文を「今」出たことにする。受け取りは 15 秒ごとの確認でも行う。 */
    fun replayAlert() = viewModelScope.launch {
        try {
            applyAlert(Api.postJson("/api/alert/replay"))
        } catch (e: Exception) {
            _state.update { it.copy(error = "Could not reach the server / サーバーにつながりません: ${e.message}") }
        }
    }

    private suspend fun pollAlertOnce() {
        runCatching {
            val a = Api.getJson("/api/alert")
            _state.update { it.copy(jmaLine = jmaLine(a)) }
            applyAlert(a)
        }
    }

    private fun jmaLine(a: JsonObject): String {
        val checked = a["jma_checked_at"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
            ?.let { OffsetDateTime.parse(it).atZoneSameInstant(java.time.ZoneId.of("Asia/Tokyo")).toLocalTime().withNano(0) }
        val err = a["jma_error"]?.takeIf { it !is JsonNull }
        val live = a["source"]?.jsonPrimitive?.content == "jma_live" && a["active"]?.jsonPrimitive?.content == "true"
        return when {
            err != null -> "JMA: could not check (last $checked) / 気象庁の電文を確認できません"
            live -> "JMA: ${a["kind"]?.jsonPrimitive?.content} for Osaka (checked $checked) / 気象庁の発表"
            else -> "JMA: no tsunami warning for Osaka (checked $checked) / 気象庁: 大阪府に津波の警報・注意報なし"
        }
    }

    /** 警報を受け取ったら到達予想時刻を保存し（圏外になっても使う）、案内中でなければ自動で始める。 */
    private fun applyAlert(a: JsonObject) {
        if (a["active"]?.jsonPrimitive?.content != "true") return
        val arrival = a["arrival_time"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
            ?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() } ?: return
        val wasActive = _state.value.active
        _state.update {
            it.copy(
                alertKind = a["kind"]?.jsonPrimitive?.content,
                alertArrivalMillis = arrival,
                isTraining = a["is_training"]?.jsonPrimitive?.content == "true",
                active = true,
            )
        }
        if (!wasActive) {
            speak("Tsunami warning. Evacuate now.")
            startGuidance()
        }
    }

    /** 「揺れた／警報を見た」ボタン。警報を待たず、保存したパックだけで案内を始める。 */
    fun onShake() {
        _state.update { it.copy(shakePressedMillis = it.shakePressedMillis ?: System.currentTimeMillis(), active = true, error = null) }
        startGuidance()
    }

    /** ルール 1: 端末ですぐ選んで案内し、サーバーの答えは「歩く時間が 2 分以上短い」時だけ採る。 */
    private fun startGuidance() {
        val loc = _state.value.location ?: run {
            _state.update { it.copy(error = "Waiting for GPS / 現在地を取得中") }
            return
        }
        val local = computeLocal(loc)
        if (local != null) {
            setChoice(local)
            speakPlan(local)
        }
        if (_state.value.online) viewModelScope.launch {
            val server = runCatching { askServer(loc, explain = true) }.getOrNull() ?: return@launch
            val current = _state.value
            val better = current.target == null || server.walkMin + ADOPT_IF_SHORTER_MIN <= (current.targetWalkMin ?: Double.MAX_VALUE)
            if (better) {
                setChoice(server)
                if (current.target != null && current.target.id != server.shelter.id) {
                    _state.update { it.copy(notice = "Updated with a more accurate walking route / より正確な道のりで選び直しました") }
                    speak("Updated with a more accurate walking route.")
                }
                if (current.target == null) speakPlan(server)
            } else {
                _state.update { it.copy(planSource = "${it.planSource} (checked by server)") }
            }
        }
    }

    /** ルール 3: 選び直しは、通信があればサーバー（Routes の道のり）、無ければ端末。 */
    private suspend fun reselect(speakResult: Boolean) {
        val loc = _state.value.location ?: return
        val choice = if (_state.value.online) runCatching { askServer(loc, explain = false) }.getOrNull() ?: computeLocal(loc)
        else computeLocal(loc)
        choice ?: return
        setChoice(choice)
        if (speakResult) speakPlan(choice)
    }

    fun onIntent(id: String) = viewModelScope.launch { handleIntent(id, fromLive = false) }

    private suspend fun handleIntent(id: String, fromLive: Boolean) {
        val target = _state.value.target
        when (id) {
            "reselect_slow", "reselect_wheelchair" -> {
                _state.update { it.copy(slow = true) }
                reselect(speakResult = !fromLive)
            }
            "reselect", "road_blocked" -> {
                _state.update { st -> st.copy(excluded = st.excluded + listOfNotNull(st.target?.id)) }
                reselect(speakResult = !fromLive)
            }
            "ask_floor" -> if (!fromLive && target != null) speak("Go up to floor ${target.floorMin ?: 3} or higher.")
        }
        _state.update { it.copy(answer = pack?.answers?.get(id)) }
        if (!fromLive && id != "ask_floor" && !id.startsWith("reselect") && id != "road_blocked") playAnswer(id)
    }

    /** 押している間だけ聞く（指を離したら止めて、聞いた文字を頼みごとに振り分ける）。 */
    fun startListening() {
        tts?.stop()
        player?.stop()
        _state.update { it.copy(listening = true, heard = null) }
        speech.start(
            "en-US",
            onPartial = { text -> _state.update { it.copy(heard = text) } },
            onFinal = { text -> handleHeard(text) },
        )
    }

    fun stopListening() = speech.stop()

    private fun handleHeard(text: String?) {
        _state.update { it.copy(listening = false, heard = text) }
        if (text.isNullOrBlank()) {
            _state.update { it.copy(answer = "Could not hear. Hold the button while speaking, or use the buttons. / 聞き取れませんでした。押したまま話すか、ボタンで選んでください") }
            return
        }
        val id = classifier.classify(text)
        if (id == "other") {
            _state.update { it.copy(answer = "Please choose a button below. / 下のボタンから選んでください") }
            speak("Please choose a button on the screen.")
        } else {
            onIntent(id)
        }
    }

    fun toggleLive() = viewModelScope.launch {
        if (live.active) {
            stopLive()
            return@launch
        }
        try {
            tts?.stop()
            player?.stop()
            live.start()
            _state.update { it.copy(liveActive = true, error = null) }
        } catch (e: Exception) {
            _state.update { it.copy(liveActive = false, error = "Gemini Live could not start / 始められませんでした: ${e.message}") }
        }
    }

    private fun stopLive() = viewModelScope.launch {
        live.stop()
        _state.update { it.copy(liveActive = false) }
    }

    /** Gemini Live が呼ぶ関数。選び直しはアプリが公式データで行い、その結果だけを返す（ルール 3）。 */
    private fun handleLiveCall(name: String, mobility: String?): Map<String, String> {
        val id = when (name) {
            "reselect" -> if (mobility == "slow" || mobility == "wheelchair") "reselect_slow" else "reselect"
            else -> name
        }
        if (id != "get_guidance") runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(LIVE_SERVER_TIMEOUT_MS) { handleIntent(id, fromLive = true) }
        }
        val s = _state.value
        val t = s.target ?: return mapOf("error" to "no evacuation target yet")
        val out = mutableMapOf(
            "target_name" to t.name,
            "go_upstairs" to s.targetIsUpstairs.toString(),
            "floor_min" to (t.floorMin ?: 3).toString(),
            "walk_minutes" to (s.targetWalkMin?.toInt() ?: 0).toString(),
            "minutes_left" to (s.minutesLeft?.toInt() ?: 0).toString(),
            "time_basis" to s.timeBasis,
            "walking_speed" to if (s.slow) "slow" else "normal",
        )
        s.answer?.takeIf { id !in setOf("get_guidance", "reselect", "reselect_slow", "road_blocked") }?.let { out["answer"] = it }
        return out
    }

    fun reset() = _state.update {
        UiState(online = it.online, gps = it.gps, useDemoPoint = it.useDemoPoint, heading = it.heading, packInfo = it.packInfo)
    }

    private fun minutesLeftNow(): Double {
        val s = _state.value
        return Planner.minutesLeftOffline(System.currentTimeMillis(), s.alertArrivalMillis, s.shakePressedMillis)
    }

    private fun computeLocal(loc: Pair<Double, Double>): Choice? {
        val p = pack ?: return null
        val s = _state.value
        val plan = Planner.plan(loc.first, loc.second, minutesLeftNow(), s.slow, p.shelters.filter { it.id !in s.excluded }, p.inundation, p.graph)
        val how = if (plan.distanceSource == "walk_graph") "walking-road graph" else "straight line × 1.6"
        return Choice(plan.primary.shelter, plan.primary.walkMin, plan.decision.apiValue, "device (offline data, $how)", null)
    }

    private suspend fun askServer(loc: Pair<Double, Double>, explain: Boolean): Choice {
        val s = _state.value
        val exclude = s.excluded.joinToString(",") { "\"$it\"" }
        val body = """{"lat":${loc.first},"lon":${loc.second},"lang":"en","slow":${s.slow},"explain":$explain,""" +
            """"arrival_ms":${s.alertArrivalMillis},"shake_at_ms":${s.shakePressedMillis},"exclude_ids":[$exclude]}"""
        val res = Api.postJson("/api/plan", body)
        val plan = res["plan"]!!.jsonObject
        val primary = plan["primary"]!!.jsonObject
        return Choice(
            shelter = toShelter(primary),
            walkMin = primary["walk_min"]!!.jsonPrimitive.double,
            decision = plan["decision"]!!.jsonPrimitive.content,
            source = "server (Routes API walking distance)",
            explanation = res["explanation"]?.takeIf { it !is JsonNull }?.jsonObject?.get("message")?.jsonPrimitive?.content,
        )
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

    private fun setChoice(c: Choice) = _state.update {
        it.copy(
            target = c.shelter,
            targetWalkMin = c.walkMin,
            targetIsUpstairs = c.shelter.inundationDepth != null,
            decision = c.decision,
            planSource = c.source,
            explanation = c.explanation ?: if (it.target?.id == c.shelter.id) it.explanation else null,
            minutesLeft = minutesLeftNow(),
        )
    }

    private fun tick() {
        val s = _state.value
        val loc = s.location ?: return
        val t = s.target ?: return
        val speed = if (s.slow) Planner.SLOW_WALK_SPEED_M_PER_S else Planner.WALK_SPEED_M_PER_S
        _state.update { it.copy(step = Guidance.step(loc.first, loc.second, t, s.heading, speed), minutesLeft = minutesLeftNow()) }
    }

    private fun speakPlan(c: Choice) {
        val minutes = maxOf(1, c.walkMin.toInt())
        val text = when (Decision.entries.firstOrNull { it.apiValue == c.decision }) {
            Decision.REACH_OUTSIDE_ZONE -> "Go now to the place on the screen, out of the flood zone. About $minutes minutes on foot. You have time."
            Decision.ALREADY_OUTSIDE_ZONE -> "You are outside the flood zone. Move further inland, away from the sea and rivers."
            Decision.GO_UPSTAIRS -> "Go now to the building on the screen, and go up to floor ${c.shelter.floorMin ?: 3} or higher. About $minutes minutes on foot."
            Decision.MAY_NOT_MAKE_IT, null -> "You may not make it in time. Go up to a high floor of the nearest strong building now."
        }
        speak(text)
    }

    /** ルール 4: 声は 1 つずつ。Gemini Live が話している間は、読み上げと保存した音声を止める。 */
    private fun speak(text: String) {
        if (_state.value.liveActive) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "guide")
    }

    private fun playAnswer(id: String) {
        if (_state.value.liveActive) return
        val f = pack?.audioFile(id) ?: return
        if (!f.exists()) return
        tts?.stop()
        player?.release()
        player = MediaPlayer().apply {
            setDataSource(f.absolutePath)
            prepare()
            start()
        }
    }

    private fun describe(p: Pack) = "Saved ${p.version} · ${p.shelters.size} shelters · ${if (p.graph != null) "walking roads ${p.graph.size} points · " else ""}${"%.1f".format(p.sizeBytes / 1_000_000.0)} MB"

    override fun onCleared() {
        viewModelScope.launch { live.stop() }
        tts?.shutdown()
        player?.release()
        speech.destroy()
    }
}
