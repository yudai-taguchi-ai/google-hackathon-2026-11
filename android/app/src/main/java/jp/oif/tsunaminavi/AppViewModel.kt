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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
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
/** 言語 → 聞き取り・読み上げの言語。 */
private val SPEECH_LANG = mapOf("en" to "en-US", "ja" to "ja-JP", "zh" to "zh-CN", "ko" to "ko-KR")
private val LANG_NAME = mapOf("en" to "English", "ja" to "Japanese", "zh" to "Simplified Chinese", "ko" to "Korean")
/** 前もって用意した避難先を数え直す間隔と、使ってよい古さ・近さ。 */
private const val PREPARE_REFRESH_MS = 15 * 60_000L
private const val PREPARED_MAX_AGE_MS = 6 * 60 * 60_000L
private const val PREPARED_NEAR_M = 150.0
/** 受け取った警報を覚えておくのは、到達予想時刻から 1 時間後まで。 */
private const val ALERT_KEEP_AFTER_ARRIVAL_MS = 60 * 60_000L

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
    /** 避難先を選んだ時の、そこまでの直線距離（残りの歩く時間を、選んだ時の道のりから割合で出すため）。 */
    val targetStraightM: Double? = null,
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
    /** Gemini Live が聞き取った言葉と、Gemini が話した言葉（画面に出して、聞き違いを見えるようにする）。 */
    val liveHeard: String? = null,
    val liveSaid: String? = null,
    val heard: String? = null,
    /** 気象庁の電文を最後に見た結果（画面に出す）。 */
    val jmaLine: String? = null,
    val listening: Boolean = false,
    val speechError: String? = null,
    val offlineSpeech: String? = null,
    /** 端末の中の聞き分けモデルの状態と、最後に聞き分けたのはどちらか。 */
    val intentModel: String? = null,
    val heardBy: String? = null,
    /** 聞き取る言語（en-US / ja-JP）。 */
    val speechLang: String = "en-US",
    /** 聞き取った頼みごと。確かめてから動く（聞き間違いで避難先が変わらないように）。 */
    val pendingIntent: String? = null,
    /** 警報が届いた瞬間の全画面（押すか 5 秒で閉じ、裏では案内がもう始まっている）。 */
    val takeover: Boolean = false,
    /** 本人が「着いた・もう高い所にいる」と押した。 */
    val arrivedManually: Boolean = false,
    /** 選び直す前の避難先（前と後を並べて見せる）。 */
    val previous: Previous? = null,
    /** 端末に保存した道と浸水域から描く地図（圏外・デモ地点でも出す）。 */
    val map: MiniMapData? = null,
    /** 最初の画面で出す、今いる所からの避難先。 */
    val nearby: NearbyPlace? = null,
    /** 泊まる所と、そこからの避難先。 */
    val stay: Pair<Double, Double>? = null,
    val stayPlace: NearbyPlace? = null,
    /** 警報を受け取った時刻と、それが保存しておいたもの（開き直し・圏外）かどうか。 */
    val alertReceivedAt: Long? = null,
    val alertFromCache: Boolean = false,
    /** 圏外に備えて、裏で用意した時刻と、用意している最中か。 */
    val preparedAt: Long? = null,
    val preparing: Boolean = false,
) {
    val arrived: Boolean get() = arrivedManually || step?.arrived == true
    val location: Pair<Double, Double>? get() = if (useDemoPoint) DEMO_POINT else gps

    /** 残りの歩く時間（避難の速さ）。選んだ時の道のりの時間を、今の残りの直線距離の割合で減らす。表示する歩く時間はこれ 1 つだけ。 */
    val remainingWalkMin: Double? get() {
        val total = targetWalkMin ?: return null
        val start = targetStraightM ?: return total
        val now = step?.distanceM ?: return total
        return if (start <= 1.0) 0.0 else total * (now / start).coerceIn(0.0, 1.0)
    }
    /** 画面・声・説明の言語（en / ja / zh / ko）。聞き取る言語から決まる。 */
    val lang: String get() = speechLang.substringBefore('-')
    val timeBasis: String get() = if (alertArrivalMillis != null) "JMA arrival time" else "shaking + 26 min (estimate)"
}

/** 選び直す前の避難先。walkMin は「今の歩く速さ」で数え直した時間。 */
data class Previous(val name: String, val walkMin: Double, val reason: String)

/** 前もって出しておく避難先（揺れる前に見せる）。 */
data class NearbyPlace(val name: String, val walkMin: Double, val upstairs: Boolean, val floorMin: Int?)

/** 地図に描くもの。roads は（緯度1, 経度1, 緯度2, 経度2）の並び、flood は（深さの区分, [経度, 緯度, …]）。 */
class MiniMapData(
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
    val roads: FloatArray,
    val flood: List<Pair<String, DoubleArray>>,
    val route: List<Pair<Double, Double>>,
    val onRoads: Boolean,
    val targetId: String,
)

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
    private val live = LiveAssistant(::handleLiveCall, ::onLiveTranscript)

    private fun onLiveTranscript(user: String?, model: String?) = _state.update {
        it.copy(
            liveHeard = user?.takeIf { t -> t.isNotBlank() }?.let { t -> ((it.liveHeard ?: "") + t).takeLast(160) } ?: it.liveHeard,
            liveSaid = if (user != null && user.isNotBlank()) "" else model?.let { t -> ((it.liveSaid ?: "") + t).takeLast(240) } ?: it.liveSaid,
        )
    }
    @Volatile private var planning = false
    /** この案内で、最初の計算をもう始めたか（座標が更新されるたびにやり直さないため）。 */
    @Volatile private var guidanceStarted = false
    private var dismissedArrival: Long? = null
    private val speech = Speech(app)

    /** 圏外で話した言葉を頼みごとに振り分ける。今はキーワード方式。FunctionGemma の学習が済んだら差し替える。 */
    private val classifier: IntentClassifier = KeywordIntent
    private val onDevice = OnDeviceIntent(app)
    val liveAvailable: Boolean get() = TsunamiApp.firebaseReady
    val debugSecret: String? get() = TsunamiApp.currentDebugSecret(getApplication())

    private val _state = MutableStateFlow(UiState(online = connectivity.online.value))
    val state: StateFlow<UiState> = _state

    private val prefs = app.getSharedPreferences("tsunami", android.content.Context.MODE_PRIVATE)
    private val preparedStore = PreparedStore(app)
    @Volatile private var cachedAlert: CachedAlert? = null
    @Volatile private var preparedPlans: List<PreparedPlan> = emptyList()
    private val prepareLock = kotlinx.coroutines.sync.Mutex()
    private var lastNearbyAt: Pair<Double, Double>? = null

    init {
        prefs.getString("lang", null)?.let { code -> _state.update { it.copy(speechLang = SPEECH_LANG[code] ?: "en-US") } }
        tts = TextToSpeech(app) { status -> if (status == TextToSpeech.SUCCESS) applyTtsLanguage() }
        viewModelScope.launch {
            onDevice.load()
            _state.update { it.copy(intentModel = onDevice.status) }
        }
        viewModelScope.launch {
            pack = store.load()
            loadPrepared()
            val stay = prefs.getString("stay", null)?.split(",")?.let { it[0].toDouble() to it[1].toDouble() }
            _state.update { it.copy(packInfo = pack?.let(::describe), stay = stay) }
            refreshStay()
            refreshNearby()
        }
        viewModelScope.launch {
            connectivity.online.collect { online ->
                val wasOnline = _state.value.online
                _state.update {
                    val switched = if (it.online && !online && it.active) System.currentTimeMillis() else it.switchedToOfflineAt
                    it.copy(online = online, switchedToOfflineAt = if (online) null else switched)
                }
                if (!online && live.active) stopLive()
                if (online && !wasOnline) {
                    pollAlertOnce()
                    viewModelScope.launch { prepareInBackground(force = false) }
                }
            }
        }
        viewModelScope.launch {
            locationSource.location.collect { loc ->
                _state.update { it.copy(gps = loc) }
                refreshNearby()
                // 案内を始めた時に現在地がまだ無かった場合は、取れた時点で計算する
                val s = _state.value
                if (loc != null && s.active && s.target == null && !planning && !guidanceStarted) startGuidance()
            }
        }
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
        // 通信がある間は、圏外に備える材料（データ・最新の警報・ここからの避難先）を裏でそろえておく
        viewModelScope.launch {
            delay(5_000)
            while (true) {
                prepareInBackground(force = false)
                delay(60_000)
            }
        }
    }

    fun onPermissionsGranted() {
        locationSource.start()
        headingSource.start()
        checkOfflineSpeech()
    }

    fun setDemoPoint(on: Boolean) {
        _state.update { it.copy(useDemoPoint = on) }
        lastNearbyAt = null
        refreshNearby()
    }

    fun closeTakeover() = _state.update { it.copy(takeover = false) }

    /** 「着いた」「もう丈夫な建物の高い階にいる」。 */
    fun markArrived() = _state.update { it.copy(takeover = false, active = true, arrivedManually = true) }

    /** 着いた画面から案内に戻る（まだ着いていなかった時）。 */
    fun backToGuide() = _state.update { it.copy(arrivedManually = false) }

    fun clearPrevious() = _state.update { it.copy(previous = null) }

    /** 今いる所を「泊まる所」として覚え、そこからの避難先を前もって出す。 */
    fun setStayHere() {
        val loc = _state.value.location ?: return
        prefs.edit().putString("stay", "${loc.first},${loc.second}").apply()
        _state.update { it.copy(stay = loc) }
        refreshStay()
        viewModelScope.launch { prepareInBackground(force = false) }
    }

    fun clearStay() {
        prefs.edit().remove("stay").apply()
        _state.update { it.copy(stay = null, stayPlace = null) }
    }


    private fun preview(loc: Pair<Double, Double>): NearbyPlace? {
        val p = pack ?: return null
        val plan = Planner.plan(loc.first, loc.second, Planner.ARRIVAL_DEFAULT_MIN, false, p.shelters, p.inundation, p.graph)
        val sh = plan.primary.shelter
        return NearbyPlace(sh.name, plan.primary.walkMin, sh.inundationDepth != null, sh.floorMin)
    }

    /** 最初の画面の「今いる所からの避難先」。80 m 以上動いた時だけ数え直す。 */
    private fun refreshNearby() {
        val loc = _state.value.location ?: return
        if (_state.value.active || pack == null) return
        lastNearbyAt?.let { if (Planner.distanceM(it.first, it.second, loc.first, loc.second) < 80) return }
        lastNearbyAt = loc
        viewModelScope.launch {
            val place = withContext(Dispatchers.Default) { runCatching { preview(loc) }.getOrNull() }
            _state.update { it.copy(nearby = place) }
        }
    }

    private fun refreshStay() {
        val stay = _state.value.stay ?: return
        viewModelScope.launch {
            val place = withContext(Dispatchers.Default) { runCatching { preview(stay) }.getOrNull() }
            _state.update { it.copy(stayPlace = place) }
        }
    }

    /** 保存した道のグラフと浸水域から、今いる所 → 避難先の地図を作る。 */
    private fun buildMap(loc: Pair<Double, Double>, t: Shelter): MiniMapData? {
        val p = pack ?: return null
        val g = p.graph
        var route: List<Pair<Double, Double>>? = null
        if (g != null) {
            val from = g.nearest(loc.first, loc.second)
            val to = t.graphNode ?: g.nearest(t.lat, t.lon)?.first
            if (from != null && to != null && from.second <= Planner.GRAPH_SNAP_MAX_M) {
                route = g.path(from.first, to, 8_000.0)?.map { g.lat(it) to g.lon(it) }?.let { listOf(loc) + it + listOf(t.lat to t.lon) }
            }
        }
        val line = route ?: listOf(loc, t.lat to t.lon)
        var minLat = line.minOf { it.first }
        var maxLat = line.maxOf { it.first }
        var minLon = line.minOf { it.second }
        var maxLon = line.maxOf { it.second }
        val padLat = maxOf((maxLat - minLat) * 0.25, 0.0015)
        val padLon = maxOf((maxLon - minLon) * 0.25, 0.0018)
        minLat -= padLat; maxLat += padLat; minLon -= padLon; maxLon += padLon
        return MiniMapData(
            minLat, maxLat, minLon, maxLon,
            roads = g?.segmentsIn(minLat, maxLat, minLon, maxLon) ?: FloatArray(0),
            flood = p.inundation.ringsIn(minLat, maxLat, minLon, maxLon),
            route = line,
            onRoads = route != null,
            targetId = t.id,
        )
    }

    private fun refreshMap() {
        val s = _state.value
        val loc = s.location ?: return
        val t = s.target ?: return
        if (s.map?.targetId == t.id) return
        viewModelScope.launch {
            val m = withContext(Dispatchers.Default) { runCatching { buildMap(loc, t) }.getOrNull() }
            if (_state.value.target?.id == t.id) _state.update { it.copy(map = m) }
        }
    }

    /** 圏外用の英語の音声データが端末にあるかを確かめ、無ければ（通信がある時に）ダウンロードを頼む。 */
    fun checkOfflineSpeech() {
        runCatching {
            speech.prepareOffline(_state.value.speechLang) { status -> _state.update { it.copy(offlineSpeech = status) } }
        }.onFailure { e -> _state.update { it.copy(offlineSpeech = "cannot check (${e.message})") } }
    }

    fun downloadPack() = viewModelScope.launch {
        _state.update { it.copy(busy = msg("Saving offline data…", "データを保存中…", "正在保存离线数据…", "오프라인 데이터 저장 중…"), error = null) }
        try {
            store.download("osaka-bay", _state.value.lang, null)
            pack = store.load()
            _state.update { it.copy(packInfo = pack?.let(::describe), busy = null) }
            lastNearbyAt = null
            refreshNearby()
            refreshStay()
            // 圏外で話しかけられるよう、英語の音声データも一緒に準備する
            checkOfflineSpeech()
        } catch (e: Exception) {
            _state.update { it.copy(busy = null, error = msg("Could not save data", "保存できませんでした", "无法保存数据", "데이터를 저장할 수 없습니다") + ": ${e.message}") }
        }
    }

    /** デモ用: 訓練電文を「今」出たことにする。受け取りは 15 秒ごとの確認でも行う。 */
    fun replayAlert() = viewModelScope.launch {
        try {
            applyAlert(Api.postJson("/api/alert/replay"))
        } catch (e: Exception) {
            _state.update { it.copy(error = msg("Could not reach the server", "サーバーにつながりません", "无法连接服务器", "서버에 연결할 수 없습니다") + ": ${e.message}") }
        }
    }

    private suspend fun pollAlertOnce() {
        runCatching {
            val a = Api.getJson("/api/alert")
            _state.update { it.copy(jmaLine = jmaLine(a)) }
            if (a["active"]?.jsonPrimitive?.content == "false" && a["jma_error"].let { it == null || it is JsonNull }) forgetAlert()
            applyAlert(a)
        }
    }

    private fun jmaLine(a: JsonObject): String {
        val checked = a["jma_checked_at"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
            ?.let { OffsetDateTime.parse(it).atZoneSameInstant(java.time.ZoneId.of("Asia/Tokyo")).toLocalTime().withNano(0) }
        val err = a["jma_error"]?.takeIf { it !is JsonNull }
        val live = a["source"]?.jsonPrimitive?.content == "jma_live" && a["active"]?.jsonPrimitive?.content == "true"
        return when {
            err != null -> msg("JMA: could not check (last $checked)", "気象庁の電文を確認できません（最後 $checked）", "日本气象厅: 无法确认（上次 $checked）", "일본 기상청: 확인할 수 없음 (마지막 $checked)")
            live -> msg("JMA: ${a["kind"]?.jsonPrimitive?.content} for Osaka (checked $checked)", "気象庁: 大阪府に${a["kind"]?.jsonPrimitive?.content}（$checked 確認）", "日本气象厅: 大阪府发布了警报（$checked 确认）", "일본 기상청: 오사카부에 경보 발령 ($checked 확인)")
            else -> msg("JMA: no tsunami warning for Osaka (checked $checked)", "気象庁: 大阪府に津波の警報・注意報なし（$checked 確認）", "日本气象厅: 大阪府没有海啸警报（$checked 确认）", "일본 기상청: 오사카부에 쓰나미 경보 없음 ($checked 확인)")
        }
    }

    /** 警報を受け取ったら到達予想時刻を保存し（圏外になっても使う）、案内中でなければ自動で始める。 */
    private fun applyAlert(a: JsonObject) {
        if (a["active"]?.jsonPrimitive?.content != "true") return
        val arrival = a["arrival_time"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
            ?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() } ?: return
        if (arrival == dismissedArrival) return
        val wasActive = _state.value.active
        _state.update {
            it.copy(
                alertKind = a["kind"]?.jsonPrimitive?.content,
                alertArrivalMillis = arrival,
                isTraining = a["is_training"]?.jsonPrimitive?.content == "true",
                active = true,
            )
        }
        val isNew = cachedAlert?.arrivalMillis != arrival
        if (isNew) {
            val received = System.currentTimeMillis()
            cachedAlert = CachedAlert(a["kind"]?.jsonPrimitive?.content ?: "津波警報", arrival, _state.value.isTraining, received)
            _state.update { it.copy(alertReceivedAt = received, alertFromCache = false) }
            viewModelScope.launch {
                withContext(Dispatchers.IO) { runCatching { preparedStore.save(cachedAlert, preparedPlans) } }
                // 警報が変わったら、残り時間が変わるので、避難先を用意し直す
                prepareInBackground(force = true)
            }
        } else if (_state.value.alertFromCache) {
            _state.update { it.copy(alertFromCache = false) }
        }
        if (!wasActive) {
            _state.update { it.copy(takeover = true) }
            say("Tsunami warning. Evacuate now.", "津波警報です。今すぐ避難してください。", "海啸警报。请立即避难。", "쓰나미 경보입니다. 지금 바로 대피하세요.")
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
            _state.update { it.copy(error = msg("Waiting for GPS… guidance starts automatically", "現在地を取得中。取れたら自動で始めます", "正在获取位置… 获取后会自动开始引导", "위치를 확인하는 중… 확인되면 자동으로 안내를 시작합니다")) }
            return
        }
        if (pack == null && !_state.value.online) {
            _state.update { it.copy(error = msg("No offline data saved. Save it on Wi-Fi first.", "データが保存されていません。Wi-Fi のある所で保存してください", "尚未保存离线数据。请先在有 Wi-Fi 的地方保存。", "오프라인 데이터가 없습니다. 먼저 Wi-Fi에서 저장하세요.")) }
            return
        }
        planning = true
        guidanceStarted = true
        _state.update { it.copy(error = null, busy = msg("Finding a shelter…", "避難先を計算中…", "正在寻找避难地点…", "대피 장소를 찾는 중…")) }
        viewModelScope.launch {
            val local = preparedFor(loc, _state.value.slow)
                ?: withContext(Dispatchers.Default) { runCatching { computeLocal(loc) }.getOrNull() }
            if (local != null) {
                setChoice(local)
                speakPlan(local)
            }
            _state.update { it.copy(busy = null) }
            planning = false
            if (_state.value.online) askServerAndMaybeAdopt(loc)
        }
    }

    private fun askServerAndMaybeAdopt(loc: Pair<Double, Double>) = viewModelScope.launch {
            val server = runCatching { askServer(loc, explain = true) }.getOrNull() ?: return@launch
            val current = _state.value
            val better = current.target == null || server.walkMin + ADOPT_IF_SHORTER_MIN <= (current.targetWalkMin ?: Double.MAX_VALUE)
            if (better) {
                setChoice(server)
                if (current.target != null && current.target.id != server.shelter.id) {
                    _state.update { it.copy(notice = msg("Updated with a more accurate walking route", "より正確な道のりで選び直しました", "已按更准确的步行路线重新选择", "더 정확한 도보 경로로 다시 골랐습니다")) }
                    say("Updated with a more accurate walking route.", "より正確な道のりで選び直しました。", "已按更准确的步行路线重新选择。", "더 정확한 도보 경로로 다시 골랐습니다.")
                }
                if (current.target == null) speakPlan(server)
            } else {
                _state.update { st -> if (st.planSource?.endsWith("(checked by server)") == true) st else st.copy(planSource = "${st.planSource} (checked by server)") }
            }
        }

    /** ルール 3: 選び直しも、まず端末ですぐ選ぶ（サーバーは 10 秒ほどかかり、Gemini Live が待ちきれない）。通信があれば後からサーバーで確かめる。 */
    private suspend fun reselect(speakResult: Boolean) {
        val loc = _state.value.location ?: return
        val choice = preparedFor(loc, _state.value.slow)
            ?: withContext(Dispatchers.Default) { runCatching { computeLocal(loc) }.getOrNull() }
            ?: (if (_state.value.online) runCatching { askServer(loc, explain = false) }.getOrNull() else null)
            ?: return
        setChoice(choice)
        if (_state.value.online) askServerAndMaybeAdopt(loc)
        if (speakResult) speakPlan(choice)
    }

    fun onIntent(id: String) = viewModelScope.launch { handleIntent(id, fromLive = false) }

    /** 選び直した結果を、変わったか・同じままかが分かる文にする。 */
    private fun reselectNote(beforeId: String?): String? {
        val s = _state.value
        val t = s.target ?: return null
        val min = s.targetWalkMin?.toInt() ?: 0
        val pace = if (s.slow) "0.5" else "0.62"
        return if (t.id == beforeId) msg(
            "Checked again at $pace m/s: ${t.name} is still the best place (about $min min).",
            "選び直しました。${t.name} のままが最善です（約 $min 分）",
            "已按 $pace 米/秒重新检查: ${t.name} 仍是最佳地点（约 $min 分钟）。",
            "$pace m/초로 다시 확인: ${t.name}이(가) 여전히 가장 좋은 장소입니다 (약 ${min}분).",
        )
        else msg(
            "New place: ${t.name} (about $min min at $pace m/s).",
            "新しい避難先: ${t.name}（約 $min 分）",
            "新的避难地点: ${t.name}（约 $min 分钟）",
            "새 대피 장소: ${t.name} (약 ${min}분)",
        )
    }

    private suspend fun handleIntent(id: String, fromLive: Boolean) {
        val target = _state.value.target
        val beforeWalk = _state.value.targetWalkMin
        val beforeSlow = _state.value.slow
        var note: String? = null
        when (id) {
            "reselect_slow", "reselect_wheelchair" -> {
                _state.update { it.copy(slow = true) }
                reselect(speakResult = !fromLive)
                note = reselectNote(target?.id)
            }
            "reselect", "road_blocked" -> {
                _state.update { st -> st.copy(excluded = st.excluded + listOfNotNull(st.target?.id)) }
                reselect(speakResult = !fromLive)
                note = reselectNote(target?.id)
            }
            "ask_floor" -> if (!fromLive && target != null) say("Go up to floor ${target.floorMin ?: 3} or higher.", "${target.floorMin ?: 3}階以上に上がってください。", "请上到 ${target.floorMin ?: 3} 楼以上。", "${target.floorMin ?: 3}층 이상으로 올라가세요.")
        }
        if (id in setOf("reselect_slow", "reselect_wheelchair", "reselect", "road_blocked") && target != null) {
            val now = _state.value
            val prev = if (now.target?.id == target.id || beforeWalk == null) null else Previous(
                name = target.name,
                walkMin = if (now.slow && !beforeSlow) beforeWalk * Planner.WALK_SPEED_M_PER_S / Planner.SLOW_WALK_SPEED_M_PER_S else beforeWalk,
                reason = if (id == "road_blocked" || id == "reselect") "blocked" else "slow",
            )
            _state.update { it.copy(previous = prev) }
        }
        _state.update { it.copy(answer = note ?: pack?.answers?.get(id)) }
        if (!fromLive && id != "ask_floor" && !id.startsWith("reselect") && id != "road_blocked") playAnswer(id)
    }

    /** 押している間だけ聞く（指を離したら止めて、聞いた文字を頼みごとに振り分ける）。 */
    fun startListening() {
        if (live.active) return
        tts?.stop()
        runCatching { player?.stop() }
        _state.update { it.copy(listening = true, heard = null, speechError = null) }
        speech.start(
            _state.value.speechLang,
            offline = !_state.value.online,
            onPartial = { text -> _state.update { it.copy(heard = text) } },
            onFinal = { text, error -> handleHeard(text, error) },
        )
    }

    fun stopListening() = speech.stop()

    private fun handleHeard(text: String?, error: String?) {
        _state.update { it.copy(listening = false, heard = text, speechError = error) }
        if (text.isNullOrBlank()) {
            _state.update { it.copy(answer = msg("Could not hear", "聞き取れませんでした", "没有听清", "알아듣지 못했습니다") + (error?.let { e -> " ($e)" } ?: "") + msg(". Use the buttons.", "。ボタンで選んでください", "。请使用按钮。", ". 버튼을 사용하세요.")) }
            return
        }
        viewModelScope.launch {
            if (onDevice.ready) _state.update { it.copy(busy = msg("Understanding on this device…", "端末の中で聞き分けています…", "正在本机上理解…", "이 기기에서 이해하는 중…")) }
            val byModel = onDevice.classify(text)
            _state.update { it.copy(busy = null) }
            val id = byModel ?: classifier.classify(text)
            val by = if (byModel != null) "FunctionGemma (on device)" else "keywords"
            android.util.Log.i("TsunamiNavi", "heard '$text' -> $id by $by")
            _state.update { it.copy(heardBy = by) }
            routeHeard(id)
        }
    }

    private fun routeHeard(id: String) {
        if (id == "other") {
            _state.update { it.copy(answer = msg("Please choose a button below.", "下のボタンから選んでください", "请从下方按钮中选择。", "아래 버튼에서 골라 주세요."), pendingIntent = null) }
            say("Please choose a button on the screen.", "画面のボタンから選んでください。", "请在屏幕上选择一个按钮。", "화면의 버튼에서 골라 주세요.")
        } else {
            // すぐには動かず、「これでよいか」を確かめる
            _state.update { it.copy(pendingIntent = id, answer = null) }
            say("Is this right? Tap yes or no.", "これでよいですか？はいか、いいえを押してください。", "这样对吗？请点击是或否。", "이게 맞나요? 예 또는 아니요를 누르세요.")
        }
    }

    /** 日本語の画面で出す公式の答え（サーバーの答えと同じ文を同梱した）。 */
    private val jaAnswers: Map<String, String> by lazy {
        runCatching {
            val text = getApplication<Application>().assets.open("answers_ja.json").bufferedReader().use { it.readText() }
            Json.parseToJsonElement(text).jsonObject.mapValues { it.value.jsonPrimitive.content }
        }.getOrDefault(emptyMap())
    }

    /**
     * 言語を変える。画面・読み上げ・聞き取りはすぐ変わり、圏外用のデータ（答えの文と音声）と
     * 前もって用意した避難先の説明は、その言語で裏で取り直す。
     */
    fun setLang(code: String) {
        val speech = SPEECH_LANG[code] ?: return
        prefs.edit().putString("lang", code).apply()
        _state.update { it.copy(speechLang = speech) }
        _state.update { it.copy(packInfo = pack?.let(::describe)) }
        applyTtsLanguage()
        checkOfflineSpeech()
        preparedPlans = emptyList()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { preparedStore.save(cachedAlert, preparedPlans) } }
            prepareInBackground(force = true)
        }
    }

    @Volatile private var ttsLangOk = true

    /** 読み上げの言語を合わせる。その言語の声が端末に無い時は英語で読む。 */
    private fun applyTtsLanguage() {
        val r = tts?.setLanguage(Locale.forLanguageTag(_state.value.speechLang)) ?: return
        ttsLangOk = r >= TextToSpeech.LANG_AVAILABLE
        if (!ttsLangOk) tts?.language = Locale.US
    }

    /** 選んだ言語の文を返す（アプリが出す案内・エラー）。 */
    private fun msg(en: String, ja: String, zh: String, ko: String) = when (_state.value.lang) {
        "ja" -> ja
        "zh" -> zh
        "ko" -> ko
        else -> en
    }

    /** 選んだ言語で読み上げる（その言語の声が無ければ英語）。 */
    private fun say(en: String, ja: String, zh: String, ko: String) = speak(if (ttsLangOk) msg(en, ja, zh, ko) else en)

    /** 聞き取った頼みごとを確かめた結果。はい → その頼みごとを実行／いいえ → ボタンで選んでもらう。 */
    fun confirmIntent(yes: Boolean) {
        val id = _state.value.pendingIntent ?: return
        _state.update { it.copy(pendingIntent = null) }
        if (yes) onIntent(id)
        else _state.update { it.copy(answer = msg("OK. Please choose a button below.", "わかりました。下のボタンから選んでください", "好的。请从下方按钮中选择。", "알겠습니다. 아래 버튼에서 골라 주세요.")) }
    }

    fun toggleLive() = viewModelScope.launch {
        if (live.active) {
            stopLive()
            return@launch
        }
        try {
            tts?.stop()
            player?.stop()
            speech.cancel()
            _state.update { it.copy(listening = false, pendingIntent = null, heard = null, speechError = null) }
            _state.update { it.copy(liveHeard = null, liveSaid = null) }
            live.start(liveSituation())
            _state.update { it.copy(liveActive = true, error = null) }
        } catch (e: Exception) {
            val detail = generateSequence(e as Throwable?) { it.cause }.take(4).joinToString(" ← ") { "${it::class.simpleName}: ${it.message}" }
            android.util.Log.e("TsunamiNavi", "Gemini Live failed", e)
            _state.update { it.copy(liveActive = false, error = msg("Gemini Live could not start", "Gemini Live を始められませんでした", "无法启动 Gemini Live", "Gemini Live를 시작할 수 없습니다") + ": $detail") }
        }
    }

    /** Gemini Live に最初から渡す、今の状況（関数を呼ばなくても正しい前提で話せるように）。 */
    private fun liveSituation(): String {
        val s = _state.value
        val warning = when (s.alertKind) {
            "大津波警報" -> "Major Tsunami Warning (JMA)"
            "津波警報" -> "Tsunami Warning (JMA)"
            "津波注意報" -> "Tsunami Advisory (JMA)"
            null -> "No tsunami warning received. The user felt strong shaking, so they should evacuate now without waiting for a warning."
            else -> "${s.alertKind} (JMA)"
        }
        val t = s.target
        return buildList {
            add("- Warning: $warning")
            if (s.isTraining) add("- This is a training telegram (drill), not a real warning.")
            if (t != null) {
                add("- Evacuation place: ${t.name}${if (t.address.isNotBlank()) " (${t.address})" else ""}")
                add(if (s.targetIsUpstairs) "- Go up to floor ${t.floorMin ?: 3} or higher of that building." else "- That place is outside the expected flood zone.")
                s.remainingWalkMin?.let { add("- Walking time at evacuation pace (${if (s.slow) "0.5" else "0.62"} m/s): about ${it.toInt()} min") }
            } else add("- Evacuation place: not decided yet. Call get_guidance.")
            s.minutesLeft?.let { add("- Time until the tsunami may arrive: about ${it.toInt()} min (basis: ${s.timeBasis})") }
            add("- Walking pace: ${if (s.slow) "slow (cannot walk fast)" else "normal"}")
            add("- The user chose ${LANG_NAME[s.lang] ?: "English"} in the app. Always speak in ${LANG_NAME[s.lang] ?: "English"}.")
            pack?.answers?.takeIf { it.isNotEmpty() }?.let { answers ->
                add("- Official answers you may read out (call the matching function first when the user asks):")
                answers.forEach { (id, text) -> add("  - $id: $text") }
            }
        }.joinToString("\n")
    }

    private fun stopLive() = viewModelScope.launch {
        live.stop()
        _state.update { it.copy(liveActive = false) }
    }

    /** Gemini Live が呼ぶ関数。選び直しはアプリが公式データで行い、その結果だけを返す（ルール 3）。 */
    private fun handleLiveCall(name: String, mobility: String?): Map<String, String> {
        val beforeId = _state.value.target?.id
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
            "changed" to (t.id != beforeId).toString(),
        )
        s.answer?.takeIf { id !in setOf("get_guidance", "reselect", "reselect_slow", "road_blocked") }?.let { out["answer"] = it }
        return out
    }

    fun reset() = _state.update {
        guidanceStarted = false
        dismissedArrival = it.alertArrivalMillis
        UiState(
            online = it.online, gps = it.gps, useDemoPoint = it.useDemoPoint, heading = it.heading, packInfo = it.packInfo,
            speechLang = it.speechLang, offlineSpeech = it.offlineSpeech, intentModel = it.intentModel, jmaLine = it.jmaLine,
            nearby = it.nearby, stay = it.stay, stayPlace = it.stayPlace, preparedAt = it.preparedAt,
            alertKind = it.alertKind.takeIf { _ -> cachedAlert != null }, alertArrivalMillis = cachedAlert?.arrivalMillis,
            isTraining = cachedAlert?.isTraining ?: false, alertReceivedAt = cachedAlert?.receivedAt, alertFromCache = cachedAlert != null,
        )
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

    private suspend fun loadPrepared() {
        val (alert, plans) = withContext(Dispatchers.IO) { preparedStore.load() }
        val now = System.currentTimeMillis()
        cachedAlert = alert?.takeIf { it.arrivalMillis + ALERT_KEEP_AFTER_ARRIVAL_MS > now }
        preparedPlans = plans.filter { now - it.preparedAt < PREPARED_MAX_AGE_MS }
        _state.update { st ->
            val c = cachedAlert
            val withAlert = if (c != null && st.alertArrivalMillis == null) st.copy(
                alertKind = c.kind, alertArrivalMillis = c.arrivalMillis, isTraining = c.isTraining,
                alertReceivedAt = c.receivedAt, alertFromCache = true,
            ) else st
            withAlert.copy(preparedAt = preparedPlans.maxOfOrNull { it.preparedAt })
        }
    }

    /** 気象庁が「警報なし」と確認できた時は、保存していた警報を消す。 */
    private fun forgetAlert() {
        if (cachedAlert == null) return
        cachedAlert = null
        viewModelScope.launch(Dispatchers.IO) { runCatching { preparedStore.save(null, preparedPlans) } }
        _state.update { if (!it.active && it.alertFromCache) it.copy(alertKind = null, alertArrivalMillis = null, alertFromCache = false) else it }
    }

    /**
     * 通信がある間に裏で行う準備。画面は待たせない。
     * 1. 圏外用のデータが無ければ保存する
     * 2. 今いる所と泊まる所から、普通の速さ・ゆっくりの 2 通りで、サーバーに避難先と Gemini の説明を出してもらい、保存する
     *    （15 分たつか、150 m 動くか、警報が変わったら出し直す）
     */
    private suspend fun prepareInBackground(force: Boolean) {
        if (!_state.value.online || !prepareLock.tryLock()) return
        try {
            if (pack == null || pack?.lang != _state.value.lang) {
                runCatching {
                    store.download("osaka-bay", _state.value.lang, null)
                    pack = store.load()
                    _state.update { it.copy(packInfo = pack?.let(::describe)) }
                    lastNearbyAt = null
                    refreshNearby()
                    refreshStay()
                }
            }
            val s = _state.value
            val spots = listOfNotNull(s.location, s.stay)
            val now = System.currentTimeMillis()
            val alertKey = s.alertArrivalMillis
            val needed = spots.flatMap { loc -> listOf(false, true).map { loc to it } }.filter { (loc, slow) ->
                force || preparedPlans.none { p ->
                    p.slow == slow && p.distanceTo(loc) < PREPARED_NEAR_M && now - p.preparedAt < PREPARE_REFRESH_MS && p.alertArrival == alertKey
                }
            }
            if (needed.isEmpty()) return
            _state.update { it.copy(preparing = true) }
            val fresh = needed.mapNotNull { (loc, slow) ->
                if (!_state.value.online) return@mapNotNull null
                runCatching { askServer(loc, explain = true, slow = slow, excluded = emptySet()) }.getOrNull()?.let { c ->
                    PreparedPlan(loc.first, loc.second, slow, System.currentTimeMillis(), alertKey, c.shelter, c.walkMin, c.decision, c.explanation)
                }
            }
            if (fresh.isEmpty()) return
            preparedPlans = (fresh + preparedPlans.filterNot { old -> fresh.any { it.slow == old.slow && old.distanceTo(it.lat to it.lon) < PREPARED_NEAR_M } }).take(8)
            withContext(Dispatchers.IO) { runCatching { preparedStore.save(cachedAlert, preparedPlans) } }
            _state.update { it.copy(preparedAt = fresh.maxOf { p -> p.preparedAt }) }
        } finally {
            _state.update { it.copy(preparing = false) }
            prepareLock.unlock()
        }
    }

    /** 前もって用意した避難先のうち、今いる所の近くで、今の残り時間でも間に合うもの。 */
    private fun preparedFor(loc: Pair<Double, Double>, slow: Boolean): Choice? {
        val now = System.currentTimeMillis()
        val excluded = _state.value.excluded
        val p = preparedPlans
            .filter { it.slow == slow && it.distanceTo(loc) < PREPARED_NEAR_M && now - it.preparedAt < PREPARED_MAX_AGE_MS && it.shelter.id !in excluded }
            .minByOrNull { it.distanceTo(loc) } ?: return null
        if (p.walkMin + Planner.SAFETY_MARGIN_MIN > minutesLeftNow()) return null
        val at = java.time.Instant.ofEpochMilli(p.preparedAt).atZone(java.time.ZoneId.systemDefault()).toLocalTime().withSecond(0).withNano(0)
        return Choice(p.shelter, p.walkMin, p.decision, "prepared online at $at (Routes API walking distance)", p.explanation)
    }

    private suspend fun askServer(
        loc: Pair<Double, Double>,
        explain: Boolean,
        slow: Boolean = _state.value.slow,
        excluded: Set<String> = _state.value.excluded,
    ): Choice {
        val s = _state.value
        val exclude = excluded.joinToString(",") { "\"$it\"" }
        val body = """{"lat":${loc.first},"lon":${loc.second},"lang":"${s.lang}","slow":$slow,"explain":$explain,""" +
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

    private fun setChoice(c: Choice) {
        setChoiceState(c)
        refreshMap()
    }

    private fun setChoiceState(c: Choice) = _state.update {
        it.copy(
            target = c.shelter,
            targetWalkMin = c.walkMin,
            targetStraightM = it.location?.let { (la, lo) -> Planner.distanceM(la, lo, c.shelter.lat, c.shelter.lon) },
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
        val floor = c.shelter.floorMin ?: 3
        when (Decision.entries.firstOrNull { it.apiValue == c.decision }) {
            Decision.REACH_OUTSIDE_ZONE -> say(
                "Go now to the place on the screen, out of the flood zone. About $minutes minutes on foot. You have time.",
                "今すぐ画面の避難先へ向かってください。浸水想定区域の外です。歩いて約${minutes}分。間に合います。",
                "请立即前往屏幕上的地点，那里在浸水预想区域之外。步行约 $minutes 分钟，来得及。",
                "지금 화면의 장소로 가세요. 침수 예상 구역 밖입니다. 걸어서 약 ${minutes}분, 늦지 않습니다.",
            )
            Decision.ALREADY_OUTSIDE_ZONE -> say(
                "You are outside the flood zone. Move further inland, away from the sea and rivers.",
                "浸水想定区域の外にいます。海や川から離れ、さらに内陸へ移動してください。",
                "您在浸水预想区域之外。请远离大海和河流，继续往内陆走。",
                "침수 예상 구역 밖에 있습니다. 바다와 강에서 멀어져 더 내륙으로 이동하세요.",
            )
            Decision.GO_UPSTAIRS -> say(
                "Go now to the building on the screen, and go up to floor $floor or higher. About $minutes minutes on foot.",
                "今すぐ画面の建物へ向かい、${floor}階以上に上がってください。歩いて約${minutes}分です。",
                "请立即前往屏幕上的建筑，上到 $floor 楼以上。步行约 $minutes 分钟。",
                "지금 화면의 건물로 가서 ${floor}층 이상으로 올라가세요. 걸어서 약 ${minutes}분입니다.",
            )
            Decision.MAY_NOT_MAKE_IT, null -> say(
                "You may not make it in time. Go up to a high floor of the nearest strong building now.",
                "間に合わない恐れがあります。今すぐ近くの丈夫な建物の高い階へ上がってください。",
                "可能来不及。请立即上到附近坚固建筑的高层。",
                "늦을 수 있습니다. 지금 바로 가까운 튼튼한 건물의 높은 층으로 올라가세요.",
            )
        }
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

    private fun describe(p: Pack) = msg("Saved", "保存済み", "已保存", "저장됨") + " ${p.version} · " +
        msg("${p.shelters.size} shelters", "避難先 ${p.shelters.size} 件", "避难地点 ${p.shelters.size} 个", "대피 장소 ${p.shelters.size}곳") + " · " +
        (if (p.graph != null) msg("walking roads ${p.graph.size} points", "歩ける道 ${p.graph.size} 点", "步行道路 ${p.graph.size} 点", "보행 도로 ${p.graph.size}개 지점") + " · " else "") +
        "%.1f MB".format(p.sizeBytes / 1_000_000.0) + " · " + (LANG_NAME[p.lang] ?: p.lang)

    override fun onCleared() {
        viewModelScope.launch { live.stop() }
        tts?.shutdown()
        player?.release()
        speech.destroy()
    }
}
