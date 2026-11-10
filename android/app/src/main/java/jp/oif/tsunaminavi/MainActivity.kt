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

/** 困ったことのボタン。id は Gemini Live・FunctionGemma と同じ頼みごとの一覧（docs/decisions.md）。 */
private val INTENTS = listOf(
    Triple("reselect_slow", "Can't walk fast", "走れない（子ども・高齢者）"),
    Triple("reselect_wheelchair", "Wheelchair / stroller", "車いす・ベビーカー"),
    Triple("road_blocked", "Road is blocked", "道がふさがっている"),
    Triple("ask_floor", "How high should I go?", "何階まで上がる？"),
    Triple("lost_family", "Lost my family", "家族とはぐれた"),
    Triple("injured", "I'm injured", "けがをした"),
    Triple("when_to_return", "When can I go back?", "いつ戻っていい？"),
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
    CompositionLocalProvider(LocalJa provides (s.speechLang == "ja-JP")) {
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
                        alert -> tr(alertEn(s.alertKind!!), s.alertKind!!) + (if (s.isTraining) tr(" · training", " · 訓練") else tr(" · JMA", " · 気象庁")) +
                            (if (s.alertFromCache || !s.online) s.alertReceivedAt?.let { tr(" · received ${clock(it)}", " · ${clock(it)} 受信") } ?: "" else "")
                        else -> tr("No warning received", "警報は出ていません")
                    },
                    fontSize = 12.sp,
                    color = fg.copy(alpha = .85f),
                )
            }
        },
        navigationIcon = {
            if (s.active) IconButton(onClick = { vm.reset() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back to start", "最初の画面に戻る")) }
            else Box(Modifier.padding(start = 12.dp, end = 4.dp)) { Icon(Icons.Filled.Warning, null, tint = if (alert) Color.White else Brand.Danger) }
        },
        actions = {
            LangSwitch(s.speechLang == "ja-JP") { ja -> vm.setSpeechLang(if (ja) "ja-JP" else "en-US") }
            Pill(if (s.online) tr("Online", "オンライン") else tr("Offline", "圏外"), if (s.online) Brand.Safe else Color(0xFF6B7280))
            if (s.useDemoPoint) Pill(tr("Demo", "デモ"), Brand.Demo)
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
        tr(
            "Felt strong shaking near the sea, or saw a warning? Tap now. You get where to go and whether you will make it.",
            "海の近くで強い揺れ・警報を見たら、すぐ押してください。逃げる先と、間に合うかを出します。",
        ),
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    s.jmaLine?.let { Banner(it, null, Brand.Teal) }
    s.nearby?.let { NearbyCard(it, stay = false) }

    Group("Before your trip", "旅行の前に（Wi-Fi のある所で）") {
        ListItem(
            headlineContent = { Text(tr("Osaka Bay offline data", "大阪湾のオフラインデータ"), fontWeight = FontWeight.SemiBold) },
            supportingContent = { Text(s.packInfo ?: tr("Not saved yet", "まだ保存していません")) },
            trailingContent = { FilledTonalButton(onClick = { vm.downloadPack() }) { Text(if (s.packInfo == null) tr("Save", "保存") else tr("Update", "更新")) } },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        ListItem(
            headlineContent = { Text(tr("Ready for no signal", "圏外への備え"), fontWeight = FontWeight.SemiBold) },
            supportingContent = {
                Text(
                    when {
                        s.preparing -> tr("Getting the latest warning and routes in the background…", "最新の警報と避難先を裏で用意しています…")
                        s.preparedAt != null -> tr(
                            "Latest warning and routes from here prepared at ${clock(s.preparedAt)}. Kept up to date while online.",
                            "最新の警報と、ここからの避難先を ${clock(s.preparedAt)} に用意しました。通信がある間は自動で更新します",
                        )
                        !s.online -> tr("Will prepare as soon as there is signal", "通信が戻ったら用意します")
                        else -> tr("Preparing soon (in the background)", "まもなく裏で用意します")
                    },
                )
            },
            trailingContent = { if (s.preparing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        ListItem(
            headlineContent = { Text(tr("Offline speech (English)", "圏外用の音声データ（英語）"), fontWeight = FontWeight.SemiBold) },
            supportingContent = { Text(s.offlineSpeech ?: tr("checking…", "確認中…")) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        ListItem(
            headlineContent = { Text(tr("Where you're staying", "泊まる所"), fontWeight = FontWeight.SemiBold) },
            supportingContent = {
                Text(
                    when {
                        s.stay == null -> tr("At your hotel, tap \"Use this spot\" to plan your way out in advance", "宿で「ここにする」を押すと、そこからの避難先を前もって決めておけます")
                        s.packInfo == null -> tr("Saved. Save the offline data to see where to go.", "保存しました。避難先はデータを保存すると出ます")
                        else -> tr("Saved. Your way out is shown below.", "保存しました。避難先は下に出ています")
                    },
                )
            },
            trailingContent = {
                if (s.stay == null) FilledTonalButton(onClick = { vm.setStayHere() }, enabled = s.location != null) { Text(tr("Use this spot", "ここにする")) }
                else OutlinedButton(onClick = { vm.clearStay() }) { Text(tr("Clear", "消す")) }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
    s.stayPlace?.let { NearbyCard(it, stay = true) }

    var demoOpen by rememberSaveable { mutableStateOf(false) }
    Group("Demo & test", "デモ・試験用") {
        ListItem(
            headlineContent = { Text(tr("Demo tools", "デモ用の設定"), fontWeight = FontWeight.SemiBold) },
            supportingContent = { Text(tr("Demo location · training warning · Gemini Live status", "デモ地点・訓練の警報・Gemini Live の状態")) },
            leadingContent = { Icon(Icons.Filled.Settings, null) },
            trailingContent = { Text(if (demoOpen) "▲" else "▼") },
            modifier = Modifier.clickable { demoOpen = !demoOpen },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        if (demoOpen) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            ListItem(
                headlineContent = { Text(tr("Use demo location", "デモ地点を使う")) },
                supportingContent = { Text(tr("Indoor test at Nankai Sakai Sta.", "南海堺駅として屋内で試す")) },
                trailingContent = { Switch(checked = s.useDemoPoint, onCheckedChange = vm::setDemoPoint) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            ListItem(
                headlineContent = { Text(tr("Replay a training warning", "訓練の警報を流し直す")) },
                supportingContent = { Text(tr("Guidance starts automatically", "自動で案内が始まります")) },
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
                headlineContent = { Text(tr("On-device intent model (FunctionGemma)", "端末の中の聞き分けモデル（FunctionGemma）")) },
                supportingContent = { Text(s.intentModel ?: "…") },
                leadingContent = { Icon(Icons.Filled.Info, null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
    Ja("Warnings are also checked every 15 seconds when online. / 通信がある時は 15 秒ごとに警報を確かめ、出ていれば自動で案内を始めます")
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
                if (LocalJa.current) {
                    Text("揺れた", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Black, lineHeight = 48.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("警報を見た時も", color = Color.White.copy(alpha = .9f), fontSize = 15.sp, fontWeight = FontWeight.Bold)
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
    Ja("Reference guidance based on official data. Always follow local instructions and announcements. / 公式データに基づく参考の案内です。現地の指示・放送を優先してください")
    if (LocalJa.current) Ja("出典: 国土地理院 指定緊急避難場所・国土数値情報 津波浸水想定（大阪府 2023）・道: © OpenStreetMap contributors (ODbL)・気象庁 防災情報 XML（デモは訓練電文）。データ: ${s.packInfo ?: "未保存"}")
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
                Text(tr("You are outside the flood zone", "浸水想定区域の外にいます"), fontSize = 26.sp, fontWeight = FontWeight.Black, lineHeight = 30.sp)
                Text(tr("Stay away from the sea and rivers. Move further inland if you can.", "海と川から離れてください。できれば、さらに内陸へ"), fontSize = 16.sp)
            }
        }
        s.notice?.let { Banner(it, null, Brand.Teal) }
        s.answer?.let { Banner(it, null, Brand.Info) }
        Ja(tr("Nearest designated tsunami site: ${t.name}", "近くの津波の指定避難場所: ${t.name}"))
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
                        step == null -> tr("Go to", "避難先")
                        step.arrived -> tr("You have arrived", "着きました")
                        else -> tr("${step.compass.en.replaceFirstChar { it.uppercase() }} · ${step.distanceM.roundToInt()} m", "${step.compass.ja}へ ${step.distanceM.roundToInt()} m")
                    },
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Black,
                )
                Text(t.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp)
                Text(
                    if (s.targetIsUpstairs) tr("Go up to floor ${t.floorMin ?: 3}+", "${t.floorMin ?: 3}階以上へ") else tr("Outside the flood zone", "浸水想定区域の外へ"),
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
    if (s.online && !s.useDemoPoint) NavPanel(t, silent = s.liveActive)
    else s.map?.let { MiniMap(it, s) }
    if (!s.online) Ja(if (s.alertArrivalMillis != null) "Time basis: last JMA warning received${s.alertReceivedAt?.let { " (${clock(it)})" } ?: ""} / 時間の基準: 最後に受け取った気象庁の警報${s.alertReceivedAt?.let { "（${clock(it)}）" } ?: ""}" else "Time basis: shaking + 26 min (estimate) / 時間の基準: 揺れてから 26 分（目安）")

    // 4. やること
    Group("What to do", "やること") {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Todo(1, "Leave now", "今すぐ出発")
            walk?.let { Todo(2, "Walk about ${it.roundToInt()} min", "歩いて約${it.roundToInt()}分") }
            Todo(
                if (walk != null) 3 else 2,
                if (s.targetIsUpstairs) "Go up to floor ${t.floorMin ?: 3} or higher" else "Get outside the flood zone",
                if (s.targetIsUpstairs) "建物の ${t.floorMin ?: 3} 階以上へ" else "浸水想定区域の外へ",
            )
            s.explanation?.let { Text(it, fontSize = 15.sp) }
            Ja("Residents: follow your local evacuation plan. / 地元の方は、市や自治会が決めた避難先に従ってください")
        }
    }
    OutlinedButton(onClick = { vm.markArrived() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Text(tr("I've arrived", "着いた"), fontWeight = FontWeight.Bold)
    }

    Ja(tr("Calculated by: ${s.planSource} · walking ${if (s.slow) "0.5" else "0.62"} m/s", "計算: ${s.planSource}・歩く速さ ${if (s.slow) "0.5" else "0.62"} m/秒"))
    if (s.planSource?.contains("× 1.6") == true) Ja("Offline estimate: real walking can take longer (rivers, canals). / 圏外の目安。川や運河で実際はもっとかかることがあります")
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
                walk?.let { Big(tr("Walk", "歩いて"), it, ink, Alignment.Start) }
                left?.let { Big(tr("Tsunami in", "津波まで"), it, Brand.Danger, Alignment.End) }
            }
            Canvas(Modifier.fillMaxWidth().height(28.dp).padding(vertical = 8.dp)) {
                val h = size.height
                drawRoundRect(track, size = size, cornerRadius = CornerRadius(h / 2))
                drawRoundRect(if (late) Brand.Danger else ink, size = Size(size.width * w, h), cornerRadius = CornerRadius(h / 2))
                val x = size.width * tt
                drawLine(Brand.Danger, Offset(x - 2f, -10f), Offset(x - 2f, h + 10f), strokeWidth = 6f)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(tr("Now", "今"), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(tr("Tsunami", "津波"), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Brand.Danger)
            }
            if (walk != null && left != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    if (late) tr("May not make it — go up the nearest tall building now.", "間に合わない恐れ。近くのできるだけ高い建物へ")
                    else tr("About ${(left - walk).roundToInt()} min to spare — keep going.", "約${(left - walk).roundToInt()}分の余裕。このまま進む"),
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = if (late) Brand.Danger else ink,
                )
            }
            Ja(
                tr(
                    "Basis: ${s.timeBasis}. It may come earlier. Walking time uses evacuation pace (${if (s.slow) "0.5" else "0.62"} m/s).",
                    "基準: ${if (s.timeBasis.startsWith("JMA")) "気象庁の到達予想時刻" else "揺れから26分（目安）"}。想定より早く来ることがあります。歩く時間は避難の速さ（${if (s.slow) "0.5" else "0.62"} m/秒）で計算",
                ),
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
            Text(tr(" min", " 分"), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = color, modifier = Modifier.padding(bottom = 7.dp))
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
                OutlinedButton(onClick = { helpOpen = true }) { Text(tr("Need help?", "困ったこと")) }
            }
            if (vm.liveAvailable && s.online) {
                val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) vm.toggleLive() }
                Button(
                    onClick = { if (s.liveActive) vm.toggleLive() else micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    colors = ButtonDefaults.buttonColors(containerColor = Brand.Info, contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text(if (s.liveActive) tr("Stop talking (Gemini Live)", "会話を終える（Gemini Live）") else tr("Talk to Gemini (Gemini Live)", "Gemini と話す（Gemini Live）"), fontSize = 17.sp) }
            }
            if (s.liveActive) {
                Surface(color = Brand.Info.copy(alpha = .10f), shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(tr("You: ", "あなた: ") + (s.liveHeard ?: "…"), fontSize = 15.sp)
                        s.liveSaid?.takeIf { it.isNotBlank() }?.let { Text("Gemini: $it", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Brand.Info) }
                    }
                }
            }
            if (!s.liveActive) HoldToTalk(s, vm)
            if (!s.online) Ja("Offline: speech is handled on this device; if not understood, use “Need help?”. / 圏外: 端末の中で聞き分けます")
        }
    }
    if (helpOpen) HelpSheet(vm) { helpOpen = false }
}

/** 困ったことを選ぶ下からのシート。 */
@Composable
private fun HelpSheet(vm: AppViewModel, close: () -> Unit) {
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("What happened?", "何に困っていますか？"), fontSize = 22.sp, fontWeight = FontWeight.Black)
            Ja(tr("Pick one: the app chooses a new shelter or reads the official answer.", "選ぶと、避難先を選び直したり、公式の答えを読み上げます"))
            Spacer(Modifier.height(4.dp))
            INTENTS.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (id, en, ja) ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.weight(1f).heightIn(min = 72.dp).clip(MaterialTheme.shapes.medium).clickable {
                                vm.onIntent(id)
                                close()
                            },
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text(tr(en, ja), fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
            Text(tr("Allow microphone to speak", "マイクを許可して話しかける"))
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
                    if (pressed) tr("Listening… release when done", "聞いています。話し終えたら離す")
                    else if (tapMode) tr("Listening… tap again when done", "聞いています。もう一度タップで終わる")
                    else tr("Hold to speak", "押している間に話す"),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (on) Color.White else MaterialTheme.colorScheme.onSurface,
                )
                if (!on) Text(tr("Tap also works · works offline", "タップでも可・圏外でも使える"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    s.heard?.let { Ja(tr("Heard: ", "聞き取った言葉: ") + "“$it”" + (s.heardBy?.let { by -> tr(" · understood by $by", " · 聞き分け: $by") } ?: "")) }
    s.speechError?.let { Ja(it) }
    // 音声認識が直らないエラーで止まった時は、タップの状態も戻す
    LaunchedEffect(s.listening) { if (!s.listening && tapMode && s.speechError != null) tapMode = false }
}

/** 聞き取った頼みごとを確かめる（はい／いいえ）。 */
@Composable
private fun ConfirmIntent(id: String, vm: AppViewModel) {
    val (en, ja) = INTENTS.firstOrNull { it.first == id }?.let { it.second to it.third }
        ?: when (id) {
            "reselect" -> "Choose another place" to "別の避難先にする"
            "repeat" -> "Repeat the guidance" to "もう一度言って"
            else -> id to id
        }
    Surface(color = Color(0xFFFEF3C7), contentColor = Color(0xFF1F1300), shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(tr("Did you mean: $en?", "「$ja」でよいですか？"), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Button(onClick = { vm.confirmIntent(true) }) { Text(tr("Yes", "はい")) }
            OutlinedButton(onClick = { vm.confirmIntent(false) }) { Text(tr("No", "いいえ")) }
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
private fun Group(title: String, ja: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 4.dp)) {
            Text(tr(title.uppercase(), ja), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
            Column { content() }
        }
    }
}

@Composable
private fun Todo(n: Int, en: String, ja: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.onSurface), contentAlignment = Alignment.Center) {
            Text("$n", color = MaterialTheme.colorScheme.surface, fontSize = 14.sp, fontWeight = FontWeight.Black)
        }
        Text(tr(en, ja), fontSize = 18.sp, fontWeight = FontWeight.Bold)
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

/** 画面の言語。true なら日本語だけ、false なら英語だけを出す（聞き取る言語と同じ）。 */
internal val LocalJa = compositionLocalOf { false }

@Composable
internal fun tr(en: String, ja: String) = if (LocalJa.current) ja else en

/** アプリが作る「英語 / 日本語」の文から、選んだ言語の側だけを取り出す。 */
@Composable
internal fun pick(text: String): String {
    val i = text.indexOf(" / ")
    if (i < 0) return text
    return if (LocalJa.current) text.substring(i + 3) else text.substring(0, i)
}

/** 時刻を「14:46」の形にする。 */
internal fun clock(millis: Long): String =
    java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalTime().withSecond(0).withNano(0).toString()

internal fun alertEn(kind: String) = when {
    kind.contains("大津波警報") -> "Major Tsunami Warning"
    kind.contains("津波警報") -> "Tsunami Warning"
    kind.contains("津波注意報") -> "Tsunami Advisory"
    else -> kind
}

/** 右上の言語の切り替え（EN ⇄ 日本語）。 */
@Composable
private fun LangSwitch(ja: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.clip(CircleShape).background(Color.Black.copy(alpha = .08f)).padding(2.dp)) {
        listOf(false to "EN", true to "日本語").forEach { (value, label) ->
            Text(
                label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (ja == value) Color.White else LocalContentColor.current,
                modifier = Modifier.clip(CircleShape).background(if (ja == value) Brand.Info else Color.Transparent)
                    .clickable { onChange(value) }.padding(horizontal = 10.dp, vertical = 4.dp),
            )
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
