package jp.oif.tsunaminavi

import android.Manifest
import android.content.pm.PackageManager
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.core.content.ContextCompat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { App() } } }
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusBar(s)
        if (s.switchedToOfflineAt != null) Banner("Switched to offline mode — guidance continues with saved data", "オフラインに切り替えました（保存したデータで案内を続けます）", Color(0xFFB45309))
        s.error?.let { Banner(it, null, Color(0xFFB91C1C)) }
        s.busy?.let { Text(it) }
        if (!s.active) Home(s, vm) else Guide(s, vm)
    }
}

private const val TAP_MAX_MS = 400L

/** 押している間だけ聞くボタン（短いタップなら、もう一度タップするまで聞く）。初めての時はマイクの許可を先にもらう。 */
@Composable
private fun HoldToTalk(s: UiState, vm: AppViewModel) {
    val latest by rememberUpdatedState(s.listening)
    val listeningNow = { latest }
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
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(if (s.listening) Color(0xFF15803D) else Color(0xFFE2E8F0))
            .pointerInput(Unit) {
                // 押している間の指の動きを全部ここで受け取り、画面のスクロールに渡さない（少し動いても離すまで聞き続ける）
                // 短いタップ（0.4 秒未満）は「切り替え」: タップで聞き始め、もう一度タップで終わる
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val wasListening = listeningNow()
                    if (wasListening) vm.stopListening() else vm.startListening()
                    var released = false
                    while (!released) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                        released = event.changes.none { it.pressed }
                    }
                    val heldMs = (currentEvent.changes.firstOrNull()?.uptimeMillis ?: down.uptimeMillis) - down.uptimeMillis
                    if (!wasListening && heldMs >= TAP_MAX_MS) vm.stopListening()
                }
            },
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (s.listening) "Listening… release (or tap) when done" else "Hold (or tap) to speak — works offline",
                fontSize = 17.sp,
                color = if (s.listening) Color.White else Color(0xFF0F172A),
            )
            Text(
                if (s.listening) "聞いています。話し終えたら離す（タップした時はもう一度タップ）" else "押している間、またはタップしてからもう一度タップするまで聞きます（圏外でも使える）",
                fontSize = 12.sp,
                color = if (s.listening) Color.White else Color(0xFF64748B),
            )
        }
    }
    s.heard?.let { Ja("Heard: \"$it\"") }
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
        Chip(if (s.online) "Online" else "Offline", if (s.online) Color(0xFF15803D) else Color(0xFF6B7280))
        if (s.isTraining) Chip("Training / 訓練", Color(0xFF1D4ED8))
        if (s.useDemoPoint) Chip("Demo location / デモ地点", Color(0xFF7C3AED))
        Text(s.location?.let { "%.5f, %.5f".format(it.first, it.second) } ?: "Getting GPS…", fontSize = 12.sp)
    }
}

@Composable
private fun Home(s: UiState, vm: AppViewModel) {
    Text("Tsunami Evacuation Navi", fontSize = 24.sp, fontWeight = FontWeight.Bold)
    Ja("津波避難ナビ（旅行者向け）")
    Spacer(Modifier.height(8.dp))
    Text("Before your trip (on Wi-Fi)", fontWeight = FontWeight.Bold)
    Ja("旅行の前に（Wi-Fi のある所で）")
    Text(s.packInfo ?: "Not saved yet / まだ保存していません")
    OutlinedButton(onClick = { vm.downloadPack() }) { Text("Save Osaka Bay offline data / 大阪湾岸のデータを保存") }
    Text(
        if (vm.liveAvailable) "Gemini Live: ready (App Check debug ${vm.debugSecret?.take(8) ?: "not set"}…)"
        else "Gemini Live: this build has no Firebase config",
        fontSize = 12.sp,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = s.useDemoPoint, onCheckedChange = vm::setDemoPoint)
        Text("  Use demo location (indoor test) / デモ地点を使う", fontSize = 13.sp)
    }
    Spacer(Modifier.height(20.dp))
    Button(
        onClick = { vm.onShake() },
        modifier = Modifier.fillMaxWidth().height(130.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB91C1C)),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("I felt shaking / I saw a warning", fontSize = 24.sp)
            Text("揺れた／警報を見た", fontSize = 16.sp)
        }
    }
    s.jmaLine?.let { Text(it, fontSize = 13.sp, color = Color(0xFF0F766E)) }
    Ja("Warnings are also checked every 15 seconds when online. / 通信がある時は 15 秒ごとに警報を確かめ、出ていれば自動で案内を始めます")
    OutlinedButton(onClick = { vm.replayAlert() }) { Text("Demo: replay a training warning / 訓練電文を流し直す") }
    Sources(s)
}

/** 出典と更新日。「公式情報に基づく参考の案内」であり、現地の指示を優先することを常に出す。 */
@Composable
private fun Sources(s: UiState) {
    Spacer(Modifier.height(12.dp))
    Ja("Reference guidance based on official data. Always follow local instructions and announcements. / 公式データに基づく参考の案内です。現地の指示・放送を優先してください")
    Ja("Shelters: GSI designated emergency evacuation sites (tsunami) · Flood depth: MLIT tsunami inundation (Osaka, 2023) · Walking roads: © OpenStreetMap contributors (ODbL) · Warning: JMA (demo uses a training telegram)")
    Ja("出典: 国土地理院 指定緊急避難場所・国土数値情報 津波浸水想定（大阪府 2023）・気象庁 防災情報 XML。データ: ${s.packInfo ?: "未保存"}")
}

@Composable
private fun Guide(s: UiState, vm: AppViewModel) {
    val t = s.target
    if (t == null) {
        Text("Finding a shelter… / 避難先を計算中")
        return
    }
    s.alertKind?.let { Text(it + if (s.isTraining) " (training telegram)" else " (JMA)", color = Color(0xFFB91C1C), fontWeight = FontWeight.Bold) }
    s.jmaLine?.let { Ja(it) }
    Text("Go to", fontSize = 14.sp)
    Text(t.name, fontSize = 26.sp, fontWeight = FontWeight.Bold)
    Text(
        if (s.targetIsUpstairs) "Building — go up to floor ${t.floorMin ?: 3} or higher" else "Outside the flood zone",
        fontSize = 20.sp,
    )
    Ja(if (s.targetIsUpstairs) "建物の ${t.floorMin ?: 3} 階以上へ" else "浸水想定区域の外へ")
    s.notice?.let { Banner(it, null, Color(0xFF0E7490)) }
    if (s.online && !s.useDemoPoint) NavPanel(t, silent = s.liveActive)
    else if (s.online) Ja("（Navigation SDK is off at the demo location. Try with real GPS. / デモ地点ではナビを使いません）")
    s.step?.let { step ->
        Arrow(step.relativeDeg ?: step.bearingDeg)
        Text(
            if (step.arrived) "You have arrived. Go up to floor ${t.floorMin ?: 3} or higher."
            else "${step.compass.en.replaceFirstChar { it.uppercase() }}, about ${step.distanceM.roundToInt()} m (≈ ${step.remainingMin.roundToInt()} min on foot)",
            fontSize = 20.sp,
        )
        if (step.relativeDeg == null) Ja("(Arrow points north-up: device direction unknown / 端末の向きが分からないため北が上)")
    }
    s.minutesLeft?.let {
        Text("About ${it.roundToInt()} min until the tsunami may arrive", fontSize = 16.sp)
        Ja("基準: ${s.timeBasis}。想定より早く来ることがあります")
    }
    Ja("Calculated by: ${s.planSource} · walking ${if (s.slow) "0.5" else "0.62"} m/s")
    if (s.planSource?.contains("× 1.6") == true) Ja("Offline estimate: real walking can take longer (rivers, canals). / 圏外の目安。川や運河で実際はもっとかかることがあります")
    s.explanation?.let { Text(it, fontSize = 14.sp) }
    s.answer?.let { Banner(it, null, Color(0xFF1D4ED8)) }
    if (vm.liveAvailable && s.online) {
        val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) vm.toggleLive() }
        Button(
            onClick = { if (s.liveActive) vm.toggleLive() else micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1D4ED8)),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (s.liveActive) "Stop talking (Gemini Live)" else "Talk to Gemini (Gemini Live)", fontSize = 18.sp) }
    }
    if (!s.liveActive) HoldToTalk(s, vm)
    if (!s.online) Ja("Offline: speech is handled on this device; if not understood, use the buttons. / 圏外: 端末の中で聞き分け、分からなければボタン")
    Text("Need help? / 困ったこと", fontWeight = FontWeight.Bold)
    INTENTS.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { (id, en, ja) ->
                OutlinedButton(onClick = { vm.onIntent(id) }) {
                    Column {
                        Text(en, fontSize = 13.sp)
                        Text(ja, fontSize = 10.sp, color = Color(0xFF64748B))
                    }
                }
            }
        }
    }
    OutlinedButton(onClick = { vm.reset() }) { Text("Back to start / 最初の画面に戻る") }
    Sources(s)
}

@Composable
private fun Arrow(deg: Double) {
    Canvas(Modifier.size(160.dp).padding(8.dp)) {
        rotate(deg.toFloat()) {
            val w = size.width
            val h = size.height
            val path = Path().apply {
                moveTo(w / 2, 0f)
                lineTo(w * 0.85f, h * 0.75f)
                lineTo(w / 2, h * 0.6f)
                lineTo(w * 0.15f, h * 0.75f)
                close()
            }
            drawPath(path, Color(0xFF15803D))
        }
    }
}

@Composable
private fun Ja(text: String) {
    Text(text, fontSize = 12.sp, color = Color(0xFF64748B))
}

@Composable
private fun Banner(text: String, ja: String?, color: Color) {
    Column(Modifier.fillMaxWidth().background(color).padding(10.dp)) {
        Text(text, color = Color.White)
        ja?.let { Text(it, color = Color.White, fontSize = 12.sp) }
    }
}

@Composable
private fun Chip(text: String, color: Color) {
    Text(text, color = Color.White, fontSize = 12.sp, modifier = Modifier.background(color).padding(horizontal = 8.dp, vertical = 2.dp))
}
