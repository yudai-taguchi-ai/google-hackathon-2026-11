package jp.oif.tsunaminavi

import android.Manifest
import android.content.pm.PackageManager
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.core.content.ContextCompat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            TsunamiTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { App() }
            }
        }
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
    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 680.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusBar(s)
            if (s.switchedToOfflineAt != null) Banner("Switched to offline mode — guidance continues with saved data", "オフラインに切り替えました（保存したデータで案内を続けます）", Brand.Warn)
            s.error?.let { Banner(it, null, Brand.Danger) }
            s.busy?.let { Busy(it) }
            if (!s.active) Home(s, vm) else Guide(s, vm)
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
            Text("Allow microphone to speak / マイクを許可して話しかける")
        }
        return
    }
    val on = pressed || tapMode
    val bg by animateColorAsState(if (on) Brand.Safe else MaterialTheme.colorScheme.surfaceVariant, label = "talk")
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(84.dp)
            .clip(RoundedCornerShape(20.dp))
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
            Mic(if (on) Color.White else MaterialTheme.colorScheme.onSurface)
            Column {
                Text(
                    if (pressed) "Listening… release when done" else if (tapMode) "Listening… tap again when done" else "Hold (or tap) to speak",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (on) Color.White else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    if (pressed) "聞いています。話し終えたら離す" else if (tapMode) "聞いています。話し終えたらもう一度タップ" else "押している間、またはタップしてからもう一度タップするまで聞きます（圏外でも使える）",
                    fontSize = 12.sp,
                    color = if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    s.heard?.let { Ja("Heard: \"$it\"") }
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
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF3C7), contentColor = Color(0xFF1F1300)), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Did you mean: $en?", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("「$ja」でよいですか？", fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { vm.confirmIntent(true) }) { Text("Yes / はい") }
                OutlinedButton(onClick = { vm.confirmIntent(false) }) { Text("No / いいえ") }
            }
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

@Composable
private fun StatusBar(s: UiState) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Chip(if (s.online) "● Online" else "● Offline", if (s.online) Brand.Safe else Color(0xFF6B7280))
        if (s.isTraining) Chip("Training / 訓練", Brand.Training)
        if (s.useDemoPoint) Chip("Demo location / デモ地点", Brand.Demo)
        Text(
            s.location?.let { "%.5f, %.5f".format(it.first, it.second) } ?: "Getting GPS…",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Home(s: UiState, vm: AppViewModel) {
    Column {
        Text("Tsunami Evacuation Navi", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
        Ja("津波避難ナビ（旅行者向け）")
    }
    s.jmaLine?.let { Banner(it, null, Brand.Teal) }
    Button(
        onClick = { vm.onShake() },
        modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp),
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Brand.Danger, contentColor = Color.White),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("I felt shaking / I saw a warning", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            Text("揺れた／警報を見た", fontSize = 17.sp)
            Text("Tap to find where to go now", fontSize = 13.sp, color = Color.White.copy(alpha = .85f))
        }
    }
    Ja("Warnings are also checked every 15 seconds when online. / 通信がある時は 15 秒ごとに警報を確かめ、出ていれば自動で案内を始めます")

    Section("Before your trip (on Wi-Fi)", "旅行の前に（Wi-Fi のある所で）") {
        Text(s.packInfo ?: "Not saved yet / まだ保存していません", fontSize = 14.sp)
        Button(onClick = { vm.downloadPack() }, modifier = Modifier.fillMaxWidth()) { Text("Save Osaka Bay offline data / 大阪湾岸のデータを保存") }
        Ja("Offline speech (en-US): ${s.offlineSpeech ?: "checking…"} / 圏外用の英語の音声データ")
    }

    var demoOpen by rememberSaveable { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().clickable { demoOpen = !demoOpen }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Demo & test tools", fontWeight = FontWeight.Bold)
                    Ja("デモ・試験用の操作")
                }
                Text(if (demoOpen) "▲" else "▼", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AnimatedVisibility(demoOpen) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = s.useDemoPoint, onCheckedChange = vm::setDemoPoint)
                        Text("  Use demo location (indoor test) / デモ地点を使う", fontSize = 13.sp)
                    }
                    OutlinedButton(onClick = { vm.replayAlert() }, modifier = Modifier.fillMaxWidth()) { Text("Replay a training warning / 訓練電文を流し直す") }
                    Ja(
                        if (vm.liveAvailable) "Gemini Live: ready (App Check debug ${vm.debugSecret?.take(8) ?: "not set"}…)"
                        else "Gemini Live: this build has no Firebase config",
                    )
                }
            }
        }
    }
    Sources(s)
}

/** 出典と更新日。「公式情報に基づく参考の案内」であり、現地の指示を優先することを常に出す。 */
@Composable
private fun Sources(s: UiState) {
    Spacer(Modifier.height(4.dp))
    Ja("Reference guidance based on official data. Always follow local instructions and announcements. / 公式データに基づく参考の案内です。現地の指示・放送を優先してください")
    Ja("Shelters: GSI designated emergency evacuation sites (tsunami) · Flood depth: MLIT tsunami inundation (Osaka, 2023) · Walking roads: © OpenStreetMap contributors (ODbL) · Warning: JMA (demo uses a training telegram)")
    Ja("出典: 国土地理院 指定緊急避難場所・国土数値情報 津波浸水想定（大阪府 2023）・気象庁 防災情報 XML。データ: ${s.packInfo ?: "未保存"}")
}

@Composable
private fun Guide(s: UiState, vm: AppViewModel) {
    val t = s.target
    if (t == null) {
        Busy("Finding a shelter… / 避難先を計算中")
        return
    }
    s.alertKind?.let { Banner(it + if (s.isTraining) " (training telegram)" else " (JMA)", s.jmaLine, Brand.Danger) }

    // 1. どこへ行くか
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(2.dp, Brand.Safe),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("GO TO / 避難先", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Brand.Safe, letterSpacing = 1.sp)
            Text(t.name, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 32.sp)
            s.step?.let { step ->
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Arrow(step.relativeDeg ?: step.bearingDeg)
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (step.arrived) "You have arrived."
                            else "${step.compass.en.replaceFirstChar { it.uppercase() }}, ${step.distanceM.roundToInt()} m",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Ja(if (step.arrived) "着きました" else "この向きに直線で ${step.distanceM.roundToInt()} m")
                        if (step.relativeDeg == null) Ja("(Arrow points north-up: device direction unknown / 端末の向きが分からないため北が上)")
                    }
                }
            }
            val walk = s.remainingWalkMin?.roundToInt()
            Todo(1, "Leave now", "今すぐ出発")
            if (walk != null) Todo(2, "Walk about $walk min", "歩いて約${walk}分")
            Todo(
                if (walk != null) 3 else 2,
                if (s.targetIsUpstairs) "Go up to floor ${t.floorMin ?: 3} or higher" else "Get outside the flood zone",
                if (s.targetIsUpstairs) "建物の ${t.floorMin ?: 3} 階以上へ" else "浸水想定区域の外へ",
            )
            Ja("Residents: follow your local evacuation plan. / 地元の方は、市や自治会が決めた避難先に従ってください")
        }
    }

    // 2. 間に合うか
    TimeCard(s)

    s.notice?.let { Banner(it, null, Brand.Teal) }

    // 3. どちらへ歩くか
    if (s.online && !s.useDemoPoint) NavPanel(t, silent = s.liveActive)
    else if (s.online) Ja("（Navigation SDK is off at the demo location. Try with real GPS. / デモ地点ではナビを使いません）")

    s.explanation?.let { Text(it, fontSize = 15.sp) }
    s.answer?.let { Banner(it, null, Brand.Info) }

    // 4. 話しかける
    Section("Ask for help", "話しかける・困ったこと") {
        if (vm.liveAvailable && s.online) {
            val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) vm.toggleLive() }
            Button(
                onClick = { if (s.liveActive) vm.toggleLive() else micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                colors = ButtonDefaults.buttonColors(containerColor = Brand.Info, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text(if (s.liveActive) "Stop talking (Gemini Live)" else "Talk to Gemini (Gemini Live)", fontSize = 18.sp) }
        }
        if (!s.liveActive) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Speak in:", fontSize = 13.sp)
                listOf("en-US" to "English", "ja-JP" to "日本語").forEach { (code, label) ->
                    FilterChip(selected = s.speechLang == code, onClick = { vm.setSpeechLang(code) }, label = { Text(label) })
                }
            }
            HoldToTalk(s, vm)
            s.pendingIntent?.let { id -> ConfirmIntent(id, vm) }
        }
        if (!s.online) Ja("Offline: speech is handled on this device; if not understood, use the buttons. / 圏外: 端末の中で聞き分け、分からなければボタン")
        INTENTS.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (id, en, ja) ->
                    OutlinedButton(
                        onClick = { vm.onIntent(id) },
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(en, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(ja, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }

    Ja("Calculated by: ${s.planSource} · walking ${if (s.slow) "0.5" else "0.62"} m/s")
    if (s.planSource?.contains("× 1.6") == true) Ja("Offline estimate: real walking can take longer (rivers, canals). / 圏外の目安。川や運河で実際はもっとかかることがあります")
    OutlinedButton(onClick = { vm.reset() }, modifier = Modifier.fillMaxWidth()) { Text("Back to start / 最初の画面に戻る") }
    Sources(s)
}

/** 残り時間と歩く時間を並べ、間に合うかを一目で出す。 */
@Composable
private fun TimeCard(s: UiState) {
    val left = s.minutesLeft
    val walk = s.remainingWalkMin
    if (left == null && walk == null) return
    val late = left != null && walk != null && walk > left
    val ratio by animateFloatAsState(
        if (left != null && walk != null && left > 0) (walk / left).toFloat().coerceIn(0.03f, 1f) else 1f,
        label = "ratio",
    )
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                left?.let { Num("Time left / 到達まで", it, Brand.Danger, Modifier.weight(1f)) }
                walk?.let { Num("Walk / 歩いて (${if (s.slow) "0.5" else "0.62"} m/s)", it, MaterialTheme.colorScheme.onSurface, Modifier.weight(1f)) }
            }
            if (left != null && walk != null) {
                LinearProgressIndicator(
                    progress = { ratio },
                    modifier = Modifier.fillMaxWidth().height(12.dp).clip(CircleShape),
                    color = if (late) Brand.Danger else Brand.Safe,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    drawStopIndicator = {},
                )
                Text(
                    if (late) "Walking takes longer than the time left — go up the nearest tall building now."
                    else "About ${(left - walk).roundToInt()} min to spare — keep going.",
                    fontWeight = FontWeight.Bold,
                    color = if (late) Brand.Danger else MaterialTheme.colorScheme.onSurface,
                )
                Ja(if (late) "間に合わない恐れ。近くの高い建物へ" else "約${(left - walk).roundToInt()}分の余裕。このまま進む")
            }
            Ja("基準: ${s.timeBasis}。想定より早く来ることがあります。歩く時間は避難の速さで計算（地図の時間は普通の速さ約 1.2 m/秒なので短く出ます）")
        }
    }
}

@Composable
private fun Todo(n: Int, en: String, ja: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(Brand.Safe), contentAlignment = Alignment.Center) {
            Text("$n", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
        }
        Column {
            Text(en, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Ja(ja)
        }
    }
}

@Composable
private fun Num(label: String, minutes: Double, color: Color, modifier: Modifier) {
    Column(modifier.clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp)) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${minutes.roundToInt()}", fontSize = 38.sp, fontWeight = FontWeight.ExtraBold, color = color, lineHeight = 40.sp)
            Text(" min", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = color, modifier = Modifier.padding(bottom = 6.dp))
        }
    }
}

@Composable
private fun Section(title: String, ja: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Ja(ja)
            }
            content()
        }
    }
}

@Composable
private fun Busy(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(text)
    }
}

@Composable
private fun Arrow(deg: Double) {
    val animated by animateFloatAsState(deg.toFloat(), label = "arrow")
    Canvas(Modifier.size(120.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).padding(14.dp)) {
        rotate(animated) {
            val w = size.width
            val h = size.height
            val path = Path().apply {
                moveTo(w / 2, 0f)
                lineTo(w * 0.85f, h * 0.75f)
                lineTo(w / 2, h * 0.6f)
                lineTo(w * 0.15f, h * 0.75f)
                close()
            }
            drawPath(path, Brand.Safe)
        }
    }
}

@Composable
private fun Mic(color: Color) {
    Canvas(Modifier.size(28.dp)) {
        val w = size.width
        val h = size.height
        drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(w * 0.32f, 0f), size = androidx.compose.ui.geometry.Size(w * 0.36f, h * 0.62f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.18f))
        drawLine(color, androidx.compose.ui.geometry.Offset(w / 2, h * 0.78f), androidx.compose.ui.geometry.Offset(w / 2, h), strokeWidth = w * 0.08f)
        drawArc(color, 0f, 180f, false, topLeft = androidx.compose.ui.geometry.Offset(w * 0.18f, h * 0.28f), size = androidx.compose.ui.geometry.Size(w * 0.64f, h * 0.5f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.08f))
    }
}

@Composable
private fun Ja(text: String) {
    Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Banner(text: String, ja: String?, color: Color) {
    Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(color).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(text, color = Color.White, fontWeight = FontWeight.SemiBold)
        ja?.let { Text(it, color = Color.White, fontSize = 12.sp) }
    }
}

@Composable
private fun Chip(text: String, color: Color) {
    Text(
        text,
        color = Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(CircleShape).background(color).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
