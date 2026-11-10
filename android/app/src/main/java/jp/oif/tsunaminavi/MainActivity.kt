@file:OptIn(ExperimentalMaterial3Api::class)

package jp.oif.tsunaminavi

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { TsunamiTheme { App() } }
    }
}

/** 困ったことのボタン 1 つ（4 言語の名前つき）。 */
private data class Need(val id: String, val en: String, val ja: String, val zh: String, val ko: String)

/** 困ったことのボタン。id は Gemini Live・FunctionGemma と同じ頼みごとの一覧（docs/decisions.md）。 */
private val INTENTS = listOf(
    Need("reselect_slow", "Can't walk fast", "走れない（子ども・高齢者）", "不能快走（儿童・老人）", "빨리 걸을 수 없어요 (아이·노인)"),
    Need("reselect_wheelchair", "Wheelchair / stroller", "車いす・ベビーカー", "轮椅 / 婴儿车", "휠체어 / 유모차"),
    Need("road_blocked", "Road is blocked", "道がふさがっている", "道路被堵", "길이 막혔어요"),
    Need("ask_floor", "How high should I go?", "何階まで上がる？", "要上到几楼？", "몇 층까지 올라가요?"),
    Need("lost_family", "Lost my family", "家族とはぐれた", "和家人走散了", "가족을 잃어버렸어요"),
    Need("injured", "I'm injured", "けがをした", "我受伤了", "다쳤어요"),
    Need("when_to_return", "When can I go back?", "いつ戻っていい？", "什么时候可以回去？", "언제 돌아가도 돼요?"),
)

@Composable
fun App(vm: AppViewModel = viewModel()) {
    val s by vm.state.collectAsState()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.onPermissionsGranted() }
    LaunchedEffect(Unit) {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        launcher.launch(perms.toTypedArray())
    }
    KeepScreenOn(s.active)
    val guiding = s.active && s.target != null
    CompositionLocalProvider(LocalLang provides s.lang) {
    Box(Modifier.fillMaxSize()) {
    Scaffold(
        topBar = { AppBar(s, vm) },
        bottomBar = { if (guiding && !s.arrived) TalkBar(s, vm) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(PaddingValues(16.dp)),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (!s.online && s.active) OfflineStrip()
                s.error?.let { Banner(it, null, Brand.Danger) }
                s.busy?.let { Busy(it) }
                when {
                    !s.active -> Home(s, vm)
                    s.arrived -> ArrivedScreen(s, vm)
                    else -> Guide(s, vm)
                }
            }
        }
    }
    if (s.takeover) AlertTakeover(s, vm)
    }
    }
}

/** 上の帯。警報がある時は赤になり、案内中は戻るボタンを出す。 */
@Composable
private fun AppBar(s: UiState, vm: AppViewModel) {
    val alert = s.alertKind != null
    val bg by animateColorAsState(if (alert) Brand.Danger else MaterialTheme.colorScheme.surface, label = "bar")
    val fg = if (alert) Color.White else MaterialTheme.colorScheme.onSurface
    TopAppBar(
        title = {
            Column {
                Text("Tsunami Navi", fontWeight = FontWeight.Black, fontSize = 20.sp)
                Text(
                    when {
                        alert -> alertName(s.alertKind!!) + (if (s.isTraining) tr(" · training", " · 訓練", " · 演练", " · 훈련") else tr(" · JMA", " · 気象庁", " · 日本气象厅", " · 일본 기상청")) +
                            (if (s.alertFromCache || !s.online) s.alertReceivedAt?.let { tr(" · received ${clock(it)}", " · ${clock(it)} 受信", " · ${clock(it)} 收到", " · ${clock(it)} 수신") } ?: "" else "")
                        else -> tr("No warning received", "警報は出ていません", "没有发布警报", "발령된 경보 없음")
                    },
                    fontSize = 12.sp,
                    color = fg.copy(alpha = .85f),
                )
            }
        },
        navigationIcon = {
            if (s.active) IconButton(onClick = { vm.reset() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back to start", "最初の画面に戻る", "返回首页", "처음 화면으로")) }
            else Box(Modifier.padding(start = 12.dp, end = 4.dp)) { Icon(Icons.Filled.Warning, null, tint = if (alert) Color.White else Brand.Danger) }
        },
        actions = {
            LangMenu(s.lang) { vm.setLang(it) }
            Pill(if (s.online) tr("Online", "オンライン", "在线", "온라인") else tr("Offline", "圏外", "无信号", "오프라인"), if (s.online) Brand.Safe else Color(0xFF6B7280))
            if (s.useDemoPoint) Pill(tr("Demo", "デモ", "演示", "데모"), Brand.Demo)
            Spacer(Modifier.width(8.dp))
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = bg,
            titleContentColor = fg,
            navigationIconContentColor = fg,
            actionIconContentColor = fg,
        ),
    )
}

// ───────────── 最初の画面 ─────────────

@Composable
private fun Home(s: UiState, vm: AppViewModel) {
    Spacer(Modifier.height(8.dp))
    ShakeButton { vm.onShake() }
    Text(
        tr("Felt strong shaking near the sea, or saw a warning? Tap now. You get where to go and whether you will make it.", "海の近くで強い揺れ・警報を見たら、すぐ押してください。逃げる先と、間に合うかを出します。", "在海边感到强烈摇晃或看到警报？请立即点击。会显示去哪里避难，以及能否及时到达。", "바닷가에서 강한 흔들림을 느꼈거나 경보를 봤다면 지금 누르세요. 어디로 가야 하는지, 제때 도착할 수 있는지 알려 드립니다."),
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    s.jmaLine?.let { Banner(it, null, Brand.Teal) }
    s.nearby?.let { NearbyCard(it, stay = false) }

    Group("Before your trip", "旅行の前に（Wi-Fi のある所で）", "旅行之前（在有 Wi-Fi 的地方）", "여행 전에 (Wi-Fi가 있는 곳에서)") {
        ListItem(
            headlineContent = { Text(tr("Osaka Bay offline data", "大阪湾のオフラインデータ", "大阪湾离线数据", "오사카만 오프라인 데이터"), fontWeight = FontWeight.SemiBold) },
            supportingContent = { Text(s.packInfo ?: tr("Not saved yet", "まだ保存していません", "尚未保存", "아직 저장하지 않음")) },
            trailingContent = { FilledTonalButton(onClick = { vm.downloadPack() }) { Text(if (s.packInfo == null) tr("Save", "保存", "保存", "저장") else tr("Update", "更新", "更新", "업데이트")) } },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        ListItem(
            headlineContent = { Text(tr("Ready for no signal", "圏外への備え", "无信号时的准备", "신호가 없을 때를 위한 준비"), fontWeight = FontWeight.SemiBold) },
            supportingContent = {
                Text(
                    when {
                        s.preparing -> tr("Getting the latest warning and routes in the background…", "最新の警報と避難先を裏で用意しています…", "正在后台获取最新警报和避难路线…", "최신 경보와 대피 경로를 백그라운드에서 준비하는 중…")
                        s.preparedAt != null -> tr("Latest warning and routes from here prepared at ${clock(s.preparedAt)}. Kept up to date while online.", "最新の警報と、ここからの避難先を ${clock(s.preparedAt)} に用意しました。通信がある間は自動で更新します", "已于 ${clock(s.preparedAt)} 准备好最新警报和从这里出发的避难地点。在线时会自动更新。", "${clock(s.preparedAt)}에 최신 경보와 이곳에서의 대피 장소를 준비했습니다. 온라인일 때 자동으로 업데이트합니다.")
                        !s.online -> tr("Will prepare as soon as there is signal", "通信が戻ったら用意します", "有信号后就会准备", "신호가 잡히면 바로 준비합니다")
                        else -> tr("Preparing soon (in the background)", "まもなく裏で用意します", "即将在后台准备", "곧 백그라운드에서 준비합니다")
                    },
                )
            },
            trailingContent = { if (s.preparing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        ListItem(
            headlineContent = { Text(tr("Offline speech recognition", "圏外用の音声データ", "无信号时的语音识别数据", "오프라인 음성 인식 데이터") + " (" + (LANGS.firstOrNull { it.first == s.lang }?.second ?: "English") + ")", fontWeight = FontWeight.SemiBold) },
            supportingContent = { Text(s.offlineSpeech ?: tr("checking…", "確認中…", "检查中…", "확인 중…")) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        ListItem(
            headlineContent = { Text(tr("Where you're staying", "泊まる所", "住宿地点", "숙소"), fontWeight = FontWeight.SemiBold) },
            supportingContent = {
                Text(
                    when {
                        s.stay == null -> tr("At your hotel, tap \"Use this spot\" to plan your way out in advance", "宿で「ここにする」を押すと、そこからの避難先を前もって決めておけます", "在酒店点击“设为这里”，即可提前确定从那里出发的避难地点", "숙소에서 \"여기로 설정\"을 누르면 그곳에서의 대피 장소를 미리 정해 둡니다")
                        s.packInfo == null -> tr("Saved. Save the offline data to see where to go.", "保存しました。避難先はデータを保存すると出ます", "已保存。保存离线数据后会显示避难地点。", "저장했습니다. 오프라인 데이터를 저장하면 대피 장소가 표시됩니다.")
                        else -> tr("Saved. Your way out is shown below.", "保存しました。避難先は下に出ています", "已保存。避难地点显示在下方。", "저장했습니다. 대피 장소는 아래에 표시됩니다.")
                    },
                )
            },
            trailingContent = {
                if (s.stay == null) FilledTonalButton(onClick = { vm.setStayHere() }, enabled = s.location != null) { Text(tr("Use this spot", "ここにする", "设为这里", "여기로 설정")) }
                else OutlinedButton(onClick = { vm.clearStay() }) { Text(tr("Clear", "消す", "清除", "지우기")) }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
    s.stayPlace?.let { NearbyCard(it, stay = true) }

    var demoOpen by rememberSaveable { mutableStateOf(false) }
    Group("Demo & test", "デモ・試験用", "演示・测试", "데모・테스트") {
        ListItem(
            headlineContent = { Text(tr("Demo tools", "デモ用の設定", "演示设置", "데모 설정"), fontWeight = FontWeight.SemiBold) },
            supportingContent = { Text(tr("Demo location · training warning · Gemini Live status", "デモ地点・訓練の警報・Gemini Live の状態", "演示地点 · 演练警报 · Gemini Live 状态", "데모 위치 · 훈련 경보 · Gemini Live 상태")) },
            leadingContent = { Icon(Icons.Filled.Settings, null) },
            trailingContent = { Text(if (demoOpen) "▲" else "▼") },
            modifier = Modifier.clickable { demoOpen = !demoOpen },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        if (demoOpen) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            ListItem(
                headlineContent = { Text(tr("Use demo location", "デモ地点を使う", "使用演示地点", "데모 위치 사용")) },
                supportingContent = { Text(tr("Indoor test at Nankai Sakai Sta.", "南海堺駅として屋内で試す", "在室内模拟南海堺站", "실내에서 난카이 사카이역으로 테스트")) },
                trailingContent = { Switch(checked = s.useDemoPoint, onCheckedChange = vm::setDemoPoint) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            ListItem(
                headlineContent = { Text(tr("Replay a training warning", "訓練の警報を流し直す", "重新播放演练警报", "훈련 경보 다시 보내기")) },
                supportingContent = { Text(tr("Guidance starts automatically", "自動で案内が始まります", "会自动开始引导", "안내가 자동으로 시작됩니다")) },
                leadingContent = { Icon(Icons.Filled.Refresh, null) },
                modifier = Modifier.clickable { vm.replayAlert() },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            ListItem(
                headlineContent = { Text("Gemini Live") },
                supportingContent = {
                    Text(
                        if (vm.liveAvailable) "Ready (App Check debug ${vm.debugSecret?.take(8) ?: "not set"}…)"
                        else "This build has no Firebase config",
                    )
                },
                leadingContent = { Icon(Icons.Filled.Info, null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            ListItem(
                headlineContent = { Text(tr("On-device intent model (FunctionGemma)", "端末の中の聞き分けモデル（FunctionGemma）", "设备端意图模型（FunctionGemma）", "기기 내 의도 모델 (FunctionGemma)")) },
                supportingContent = { Text(s.intentModel ?: "…") },
                leadingContent = { Icon(Icons.Filled.Info, null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
    Ja(tr("Warnings are also checked every 15 seconds when online.", "通信がある時は 15 秒ごとに警報を確かめ、出ていれば自動で案内を始めます", "在线时每 15 秒检查一次警报，如有警报会自动开始引导", "온라인일 때 15초마다 경보를 확인하고, 경보가 있으면 자동으로 안내를 시작합니다"))
    Sources(s)
}

/** 最初の画面の主役。押す場所が一目で分かるよう、丸いボタンの周りに波紋を出す。 */
@Composable
private fun ShakeButton(onClick: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val p by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart), label = "p")
    Box(Modifier.fillMaxWidth().height(280.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(280.dp)) {
            val base = size.minDimension / 2 * 0.78f
            for (k in 0..1) {
                val t = (p + k * 0.5f) % 1f
                drawCircle(Brand.Danger.copy(alpha = (1f - t) * 0.35f), radius = base + t * size.minDimension * 0.22f, style = Stroke(width = 6f))
            }
        }
        Box(
            Modifier.size(218.dp).clip(CircleShape).background(Brand.Danger).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val lang = LocalLang.current
                if (lang != "en") {
                    Text(tr("", "揺れた", "感到摇晃", "흔들렸어요"), color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Black, lineHeight = 44.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(tr("", "警報を見た時も", "看到警报时也按", "경보를 봤을 때도"), color = Color.White.copy(alpha = .9f), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                } else {
                    Text("I felt", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("shaking", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black, lineHeight = 36.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("or saw a warning", color = Color.White.copy(alpha = .9f), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** 出典と更新日。「公式情報に基づく参考の案内」であり、現地の指示を優先することを常に出す。 */
@Composable
private fun Sources(s: UiState) {
    Ja(tr("Reference guidance based on official data. Always follow local instructions and announcements.", "公式データに基づく参考の案内です。現地の指示・放送を優先してください", "这是基于官方数据的参考引导。请始终优先听从当地的指示和广播。", "공식 데이터에 기반한 참고 안내입니다. 항상 현지 지시와 방송을 우선하세요."))
    if (LocalLang.current == "ja") Ja("出典: 国土地理院 指定緊急避難場所・国土数値情報 津波浸水想定（大阪府 2023）・道: © OpenStreetMap contributors (ODbL)・気象庁 防災情報 XML（デモは訓練電文）。データ: ${s.packInfo ?: "未保存"}")
    else Ja("Shelters: GSI designated emergency evacuation sites (tsunami) · Flood depth: MLIT tsunami inundation (Osaka, 2023) · Walking roads: © OpenStreetMap contributors (ODbL) · Warning: JMA (demo uses a training telegram)")
}

// ───────────── 案内の画面 ─────────────

@Composable
private fun Guide(s: UiState, vm: AppViewModel) {
    val t = s.target
    if (t == null) {
        Busy("Finding a shelter… / 避難先を計算中")
        return
    }
    val walk = s.remainingWalkMin
    val left = s.minutesLeft
    val late = walk != null && left != null && walk > left

    // 今いる所がもう浸水想定区域の外なら、遠くの避難先へ歩かせず「内陸へ」とだけ出す
    if (s.decision == "already_outside_zone") {
        Surface(color = Brand.Safe, contentColor = Color.White, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(tr("You are outside the flood zone", "浸水想定区域の外にいます", "您在浸水预想区域之外", "침수 예상 구역 밖에 있습니다"), fontSize = 26.sp, fontWeight = FontWeight.Black, lineHeight = 30.sp)
                Text(tr("Stay away from the sea and rivers. Move further inland if you can.", "海と川から離れてください。できれば、さらに内陸へ", "请远离大海和河流。如果可以，请继续往内陆走。", "바다와 강에서 멀어지세요. 가능하면 더 내륙으로 이동하세요."), fontSize = 16.sp)
            }
        }
        s.notice?.let { Banner(it, null, Brand.Teal) }
        s.answer?.let { Banner(it, null, Brand.Info) }
        Ja(tr("Nearest designated tsunami site: ${t.name}", "近くの津波の指定避難場所: ${t.name}", "最近的海啸指定避难场所: ${t.name}", "가장 가까운 쓰나미 지정 대피 장소: ${t.name}"))
        Sources(s)
        return
    }

    // 1. どっちへ・どこへ（ナビの案内の帯と同じ形）
    Surface(color = if (late) Brand.Danger else Brand.Safe, contentColor = Color.White, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val step = s.step
            if (step != null) Arrow(step.relativeDeg ?: step.bearingDeg, Color.White, Modifier.size(84.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        step == null -> tr("Go to", "避難先", "前往", "대피 장소")
                        step.arrived -> tr("You have arrived", "着きました", "已到达", "도착했습니다")
                        else -> tr("${step.compass.en.replaceFirstChar { it.uppercase() }} · ${step.distanceM.roundToInt()} m", "${step.compass.ja}へ ${step.distanceM.roundToInt()} m", "向${step.compass.zh} ${step.distanceM.roundToInt()} 米", "${step.compass.ko}쪽으로 ${step.distanceM.roundToInt()} m")
                    },
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Black,
                )
                Text(t.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp)
                Text(
                    if (s.targetIsUpstairs) tr("Go up to floor ${t.floorMin ?: 3}+", "${t.floorMin ?: 3}階以上へ", "上到 ${t.floorMin ?: 3} 楼以上", "${t.floorMin ?: 3}층 이상으로") else tr("Outside the flood zone", "浸水想定区域の外へ", "离开浸水预想区域", "침수 예상 구역 밖으로"),
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = .9f),
                )
                if (step?.relativeDeg == null && step != null) Text(pick("North-up (device direction unknown) / 北が上"), fontSize = 11.sp, color = Color.White.copy(alpha = .8f))
            }
        }
    }

    // 2. 間に合うか
    if (walk != null || left != null) TimeLine(s, walk, left, late)

    s.previous?.let { ReselectDiff(it, s, vm) }
    s.notice?.let { Banner(it, null, Brand.Teal) }
    s.answer?.takeIf { s.previous == null }?.let { Banner(it, null, Brand.Info) }

    // 3. 地図（通信があって本物の GPS の時は Navigation SDK、それ以外は保存した道と浸水域から描く）
    if (s.online && !s.useDemoPoint) NavPanel(t, silent = s.liveActive, flood = s.map)
    else if (s.online) s.map?.let { GoogleMapPanel(it, s) }
    else s.map?.let { MiniMap(it, s) }
    if (!s.online) {
        val at = s.alertReceivedAt?.let { " (${clock(it)})" } ?: ""
        Ja(
            if (s.alertArrivalMillis != null) tr("Time basis: last JMA warning received$at", "時間の基準: 最後に受け取った気象庁の警報$at", "时间依据: 最后收到的日本气象厅警报$at", "시간 기준: 마지막으로 받은 일본 기상청 경보$at")
            else tr("Time basis: shaking + 26 min (estimate)", "時間の基準: 揺れてから 26 分（目安）", "时间依据: 摇晃后 26 分钟（估计）", "시간 기준: 흔들림 후 26분 (추정)"),
        )
    }

    // 4. やること
    Group("What to do", "やること", "要做的事", "할 일") {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Todo(1, "Leave now", "今すぐ出発", "立即出发", "지금 출발")
            walk?.let { Todo(2, "Walk about ${it.roundToInt()} min", "歩いて約${it.roundToInt()}分", "步行约 ${it.roundToInt()} 分钟", "걸어서 약 ${it.roundToInt()}분") }
            Todo(
                if (walk != null) 3 else 2,
                if (s.targetIsUpstairs) "Go up to floor ${t.floorMin ?: 3} or higher" else "Get outside the flood zone",
                if (s.targetIsUpstairs) "建物の ${t.floorMin ?: 3} 階以上へ" else "浸水想定区域の外へ",
                if (s.targetIsUpstairs) "上到建筑的 ${t.floorMin ?: 3} 楼以上" else "离开浸水预想区域",
                if (s.targetIsUpstairs) "건물 ${t.floorMin ?: 3}층 이상으로" else "침수 예상 구역 밖으로",
            )
            s.explanation?.let { Text(it, fontSize = 15.sp) }
            Ja(tr("Residents: follow your local evacuation plan.", "地元の方は、市や自治会が決めた避難先に従ってください", "当地居民：请遵循市政府或自治会规定的避难地点。", "주민: 시나 자치회가 정한 대피 장소를 따르세요."))
        }
    }
    OutlinedButton(onClick = { vm.markArrived() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Text(tr("I've arrived", "着いた", "我已到达", "도착했어요"), fontWeight = FontWeight.Bold)
    }

    Ja(tr("Calculated by: ${s.planSource} · walking ${if (s.slow) "0.5" else "0.62"} m/s", "計算: ${s.planSource}・歩く速さ ${if (s.slow) "0.5" else "0.62"} m/秒", "计算: ${s.planSource} · 步行速度 ${if (s.slow) "0.5" else "0.62"} 米/秒", "계산: ${s.planSource} · 걷는 속도 ${if (s.slow) "0.5" else "0.62"} m/초"))
    if (s.planSource?.contains("× 1.6") == true) Ja(tr("Offline estimate: real walking can take longer (rivers, canals).", "圏外の目安。川や運河で実際はもっとかかることがあります", "无信号时的估计：因河流、运河，实际步行可能需要更久。", "오프라인 추정치: 강이나 운하 때문에 실제로는 더 걸릴 수 있습니다."))
    Sources(s)
}

/** 今・着く・津波の 3 点を 1 本の線に並べる。 */
@Composable
private fun TimeLine(s: UiState, walk: Double?, left: Double?, late: Boolean) {
    val max = maxOf(walk ?: 0.0, left ?: 0.0, 1.0)
    val w by animateFloatAsState(((walk ?: 0.0) / max).toFloat(), label = "w")
    val tt = ((left ?: max) / max).toFloat()
    val ink = MaterialTheme.colorScheme.onSurface
    val track = MaterialTheme.colorScheme.surfaceVariant
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                walk?.let { Big(tr("Walk", "歩いて", "步行", "걸어서"), it, ink, Alignment.Start) }
                left?.let { Big(tr("Tsunami in", "津波まで", "距海啸到达", "쓰나미까지"), it, Brand.Danger, Alignment.End) }
            }
            Canvas(Modifier.fillMaxWidth().height(28.dp).padding(vertical = 8.dp)) {
                val h = size.height
                drawRoundRect(track, size = size, cornerRadius = CornerRadius(h / 2))
                drawRoundRect(if (late) Brand.Danger else ink, size = Size(size.width * w, h), cornerRadius = CornerRadius(h / 2))
                val x = size.width * tt
                drawLine(Brand.Danger, Offset(x - 2f, -10f), Offset(x - 2f, h + 10f), strokeWidth = 6f)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(tr("Now", "今", "现在", "지금"), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(tr("Tsunami", "津波", "海啸", "쓰나미"), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Brand.Danger)
            }
            if (walk != null && left != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    if (late) tr("May not make it — go up the nearest tall building now.", "間に合わない恐れ。近くのできるだけ高い建物へ", "可能来不及 — 请立即上到最近的高楼。", "늦을 수 있습니다 — 지금 가장 가까운 높은 건물로 올라가세요.")
                    else tr("About ${(left - walk).roundToInt()} min to spare — keep going.", "約${(left - walk).roundToInt()}分の余裕。このまま進む", "还有约 ${(left - walk).roundToInt()} 分钟余裕 — 继续前进。", "약 ${(left - walk).roundToInt()}분 여유 — 계속 가세요."),
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = if (late) Brand.Danger else ink,
                )
            }
            Ja(
                tr("Basis: ${s.timeBasis}. It may come earlier. Walking time uses evacuation pace (${if (s.slow) "0.5" else "0.62"} m/s).", "基準: ${if (s.timeBasis.startsWith("JMA")) "気象庁の到達予想時刻" else "揺れから26分（目安）"}。想定より早く来ることがあります。歩く時間は避難の速さ（${if (s.slow) "0.5" else "0.62"} m/秒）で計算", "依据: ${if (s.timeBasis.startsWith("JMA")) "日本气象厅的预计到达时间" else "摇晃后26分钟（估计）"}。可能会更早到达。步行时间按避难速度（${if (s.slow) "0.5" else "0.62"} 米/秒）计算。", "기준: ${if (s.timeBasis.startsWith("JMA")) "일본 기상청 도달 예상 시각" else "흔들림 후 26분(추정)"}. 더 일찍 올 수 있습니다. 걷는 시간은 대피 속도(${if (s.slow) "0.5" else "0.62"} m/초)로 계산했습니다."),
            )
        }
    }
}

@Composable
private fun Big(label: String, minutes: Double, color: Color, align: Alignment.Horizontal) {
    Column(horizontalAlignment = align) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${minutes.roundToInt()}", fontSize = 44.sp, fontWeight = FontWeight.Black, color = color, lineHeight = 46.sp)
            Text(tr(" min", " 分", " 分钟", " 분"), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = color, modifier = Modifier.padding(bottom = 7.dp))
        }
    }
}

/** 下に固定する「話しかける」の帯。歩きながらでも親指で届く位置に置く。 */
@Composable
private fun TalkBar(s: UiState, vm: AppViewModel) {
    var helpOpen by rememberSaveable { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 12.dp, tonalElevation = 2.dp) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            s.pendingIntent?.let { id -> ConfirmIntent(id, vm) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = { helpOpen = true }) { Text(tr("Need help?", "困ったこと", "需要帮助？", "도움이 필요해요?")) }
            }
            if (vm.liveAvailable && s.online) {
                val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) vm.toggleLive() }
                Button(
                    onClick = { if (s.liveActive) vm.toggleLive() else micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    colors = ButtonDefaults.buttonColors(containerColor = Brand.Info, contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text(if (s.liveActive) tr("Stop talking (Gemini Live)", "会話を終える（Gemini Live）", "结束对话（Gemini Live）", "대화 끝내기 (Gemini Live)") else tr("Talk to Gemini (Gemini Live)", "Gemini と話す（Gemini Live）", "与 Gemini 对话（Gemini Live）", "Gemini와 대화 (Gemini Live)"), fontSize = 17.sp) }
            }
            if (s.liveActive) {
                Surface(color = Brand.Info.copy(alpha = .10f), shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(tr("You: ", "あなた: ", "你: ", "나: ") + (s.liveHeard ?: "…"), fontSize = 15.sp)
                        s.liveSaid?.takeIf { it.isNotBlank() }?.let { Text("Gemini: $it", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Brand.Info) }
                    }
                }
            }
            if (!s.liveActive) HoldToTalk(s, vm)
            if (!s.online) Ja(tr("Offline: speech is handled on this device; if not understood, use “Need help?”.", "圏外: 端末の中で聞き分けます", "无信号：语音在本机上识别；如未听懂，请使用“需要帮助？”。", "오프라인: 음성은 이 기기에서 인식합니다. 인식되지 않으면 \"도움이 필요해요?\"를 누르세요."))
        }
    }
    if (helpOpen) HelpSheet(vm) { helpOpen = false }
}

/** 困ったことを選ぶ下からのシート。 */
@Composable
private fun HelpSheet(vm: AppViewModel, close: () -> Unit) {
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("What happened?", "何に困っていますか？", "遇到了什么困难？", "무슨 일이 있나요?"), fontSize = 22.sp, fontWeight = FontWeight.Black)
            Ja(tr("Pick one: the app chooses a new shelter or reads the official answer.", "選ぶと、避難先を選び直したり、公式の答えを読み上げます", "请选择一项：应用会重新选择避难地点，或朗读官方回答。", "하나를 고르세요: 앱이 대피 장소를 다시 고르거나 공식 답변을 읽어 줍니다."))
            Spacer(Modifier.height(4.dp))
            INTENTS.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (id, en, ja, zh, ko) ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.weight(1f).heightIn(min = 72.dp).clip(MaterialTheme.shapes.medium).clickable {
                                vm.onIntent(id)
                                close()
                            },
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text(tr(en, ja, zh, ko), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

private const val TAP_MAX_MS = 400L

/** 押している間だけ聞くボタン（短いタップなら、もう一度タップするまで聞く）。初めての時はマイクの許可を先にもらう。 */
@Composable
private fun HoldToTalk(s: UiState, vm: AppViewModel) {
    var pressed by remember { mutableStateOf(false) }
    var tapMode by remember { mutableStateOf(false) }
    val latestTap by rememberUpdatedState(tapMode)
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    if (!granted) {
        OutlinedButton(onClick = { launcher.launch(Manifest.permission.RECORD_AUDIO) }, modifier = Modifier.fillMaxWidth()) {
            Text(tr("Allow microphone to speak", "マイクを許可して話しかける", "允许麦克风后即可说话", "말하려면 마이크를 허용하세요"))
        }
        return
    }
    val on = pressed || tapMode
    val bg by animateColorAsState(if (on) Brand.Safe else MaterialTheme.colorScheme.surfaceVariant, label = "talk")
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(bg)
            .pointerInput(Unit) {
                // 押している間の指の動きを全部ここで受け取り、画面のスクロールに渡さない（少し動いても離すまで聞き続ける）
                // 短いタップ（0.4 秒未満）は「切り替え」: タップで聞き始め、もう一度タップで終わる
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    if (latestTap) {
                        tapMode = false
                        vm.stopListening()
                    } else {
                        pressed = true
                        vm.startListening()
                    }
                    var released = false
                    while (!released) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                        released = event.changes.none { it.pressed }
                    }
                    if (pressed) {
                        pressed = false
                        val heldMs = (currentEvent.changes.firstOrNull()?.uptimeMillis ?: down.uptimeMillis) - down.uptimeMillis
                        if (heldMs >= TAP_MAX_MS) vm.stopListening() else tapMode = true
                    }
                }
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Mic(if (on) Color.White else MaterialTheme.colorScheme.onSurface)
            Column {
                Text(
                    if (pressed) tr("Listening… release when done", "聞いています。話し終えたら離す", "正在听… 说完后松开", "듣고 있어요… 다 말하면 손을 떼세요")
                    else if (tapMode) tr("Listening… tap again when done", "聞いています。もう一度タップで終わる", "正在听… 说完后再点一次", "듣고 있어요… 다 말하면 다시 누르세요")
                    else tr("Hold to speak", "押している間に話す", "按住说话", "누르고 말하기"),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (on) Color.White else MaterialTheme.colorScheme.onSurface,
                )
                if (!on) Text(tr("Tap also works · works offline", "タップでも可・圏外でも使える", "也可以点击 · 无信号也能用", "탭해도 됩니다 · 오프라인에서도 작동"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    s.heard?.let { Ja(tr("Heard: ", "聞き取った言葉: ", "听到的内容: ", "들은 말: ") + "“$it”" + (s.heardBy?.let { by -> tr(" · understood by $by", " · 聞き分け: $by", " · 理解方式: $by", " · 인식: $by") } ?: "")) }
    s.speechError?.let { Ja(it) }
    // 音声認識が直らないエラーで止まった時は、タップの状態も戻す
    LaunchedEffect(s.listening) { if (!s.listening && tapMode && s.speechError != null) tapMode = false }
}

/** 聞き取った頼みごとを確かめる（はい／いいえ）。 */
@Composable
private fun ConfirmIntent(id: String, vm: AppViewModel) {
    val need = INTENTS.firstOrNull { it.id == id } ?: when (id) {
        "reselect" -> Need(id, "Choose another place", "別の避難先にする", "换一个避难地点", "다른 대피 장소로")
        "repeat" -> Need(id, "Repeat the guidance", "もう一度言って", "再说一遍", "다시 말해 주세요")
        else -> Need(id, id, id, id, id)
    }
    val en = need.en
    val ja = need.ja
    val zh = need.zh
    val ko = need.ko
    Surface(color = Color(0xFFFEF3C7), contentColor = Color(0xFF1F1300), shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(tr("Did you mean: $en?", "「$ja」でよいですか？", "您是指“$zh”吗？", "\"$ko\" 맞나요?"), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Button(onClick = { vm.confirmIntent(true) }) { Text(tr("Yes", "はい", "是", "예")) }
            OutlinedButton(onClick = { vm.confirmIntent(false) }) { Text(tr("No", "いいえ", "否", "아니요")) }
        }
    }
}

/** 案内中は画面を消さない（歩きながら見るため）。 */
@Composable
private fun KeepScreenOn(on: Boolean) {
    val activity = LocalContext.current as Activity
    DisposableEffect(on) {
        if (on) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

// ───────────── 小さな部品 ─────────────

@Composable
private fun Group(title: String, ja: String, zh: String, ko: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 4.dp)) {
            Text(tr(title.uppercase(), ja, zh, ko), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
            Column { content() }
        }
    }
}

@Composable
private fun Todo(n: Int, en: String, ja: String, zh: String, ko: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.onSurface), contentAlignment = Alignment.Center) {
            Text("$n", color = MaterialTheme.colorScheme.surface, fontSize = 14.sp, fontWeight = FontWeight.Black)
        }
        Text(tr(en, ja, zh, ko), fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Busy(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(pick(text))
    }
}

@Composable
private fun Arrow(deg: Double, color: Color, modifier: Modifier) {
    val animated by animateFloatAsState(deg.toFloat(), label = "arrow")
    Canvas(modifier) {
        rotate(animated) {
            val w = size.width
            val h = size.height
            val path = Path().apply {
                moveTo(w / 2, 0f)
                lineTo(w * 0.88f, h * 0.82f)
                lineTo(w / 2, h * 0.64f)
                lineTo(w * 0.12f, h * 0.82f)
                close()
            }
            drawPath(path, color)
        }
    }
}

@Composable
private fun Mic(color: Color) {
    Canvas(Modifier.size(26.dp)) {
        val w = size.width
        val h = size.height
        drawRoundRect(color, topLeft = Offset(w * 0.32f, 0f), size = Size(w * 0.36f, h * 0.62f), cornerRadius = CornerRadius(w * 0.18f))
        drawLine(color, Offset(w / 2, h * 0.78f), Offset(w / 2, h), strokeWidth = w * 0.08f)
        drawArc(color, 0f, 180f, false, topLeft = Offset(w * 0.18f, h * 0.28f), size = Size(w * 0.64f, h * 0.5f), style = Stroke(width = w * 0.08f))
    }
}

/** 画面の言語（en / ja / zh / ko）。設定で選んだ言語で、画面・声・聞き取り・説明をそろえる。 */
internal val LocalLang = compositionLocalOf { "en" }

@Composable
internal fun tr(en: String, ja: String, zh: String = en, ko: String = en) = when (LocalLang.current) {
    "ja" -> ja
    "zh" -> zh
    "ko" -> ko
    else -> en
}

/** 古い形の「英語 / 日本語」の文から、選んだ言語の側を取り出す（中国語・韓国語は英語の側）。 */
@Composable
internal fun pick(text: String): String {
    val i = text.indexOf(" / ")
    if (i < 0) return text
    return if (LocalLang.current == "ja") text.substring(i + 3) else text.substring(0, i)
}

/** 警報の種類の名前。 */
@Composable
internal fun alertName(kind: String): String = tr(
    alertEn(kind),
    kind,
    when { kind.contains("大津波警報") -> "大海啸警报"; kind.contains("津波警報") -> "海啸警报"; kind.contains("津波注意報") -> "海啸注意报"; else -> kind },
    when { kind.contains("大津波警報") -> "대형 쓰나미 경보"; kind.contains("津波警報") -> "쓰나미 경보"; kind.contains("津波注意報") -> "쓰나미 주의보"; else -> kind },
)

/** 時刻を「14:46」の形にする。 */
internal fun clock(millis: Long): String =
    java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalTime().withSecond(0).withNano(0).toString()

internal fun alertEn(kind: String) = when {
    kind.contains("大津波警報") -> "Major Tsunami Warning"
    kind.contains("津波警報") -> "Tsunami Warning"
    kind.contains("津波注意報") -> "Tsunami Advisory"
    else -> kind
}

/** 右上の言語の選択。選ぶと、画面・読み上げ・聞き取り・圏外の答えと音声・Gemini の説明がその言語になる。 */
private val LANGS = listOf("en" to "English", "ja" to "日本語", "zh" to "简体中文", "ko" to "한국어")

@Composable
private fun LangMenu(lang: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Text(
            (LANGS.firstOrNull { it.first == lang }?.second ?: "English") + " ▾",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.clip(CircleShape).background(Brand.Info).clickable { open = true }.padding(horizontal = 10.dp, vertical = 5.dp),
        )
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            LANGS.forEach { (code, label) ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(label, fontWeight = if (code == lang) FontWeight.Bold else FontWeight.Normal) },
                    onClick = {
                        open = false
                        onChange(code)
                    },
                )
            }
        }
    }
}

@Composable
private fun Ja(text: String) {
    Text(pick(text), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun Banner(text: String, ja: String?, color: Color) {
    Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(color).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(if (ja != null) tr(text, ja) else pick(text), color = Color.White, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Pill(text: String, color: Color) {
    Text(
        text,
        color = Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 6.dp).clip(CircleShape).background(color).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
