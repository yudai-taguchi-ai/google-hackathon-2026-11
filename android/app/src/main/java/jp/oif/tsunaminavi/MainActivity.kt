package jp.oif.tsunaminavi

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { App() } } }
    }
}

private val INTENTS = listOf(
    "reselect_slow" to "走れない（子ども・高齢者）",
    "reselect_wheelchair" to "車いす・ベビーカー",
    "road_blocked" to "道がふさがっている",
    "ask_floor" to "何階まで上がる？",
    "lost_family" to "家族とはぐれた",
    "injured" to "けがをした",
    "when_to_return" to "いつ戻っていい？",
)

@Composable
fun App(vm: AppViewModel = viewModel()) {
    val s by vm.state.collectAsState()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.onPermissionsGranted() }
    LaunchedEffect(Unit) {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (android.os.Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        launcher.launch(perms.toTypedArray())
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusBar(s)
        if (s.switchedToOfflineAt != null) Banner("オフラインに切り替えました（保存したデータで案内を続けます）", Color(0xFFB45309))
        s.error?.let { Banner(it, Color(0xFFB91C1C)) }
        s.busy?.let { Text(it) }
        if (!s.active) Home(s, vm) else Guide(s, vm)
    }
}

@Composable
private fun StatusBar(s: UiState) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Chip(if (s.online) "オンライン" else "オフライン", if (s.online) Color(0xFF15803D) else Color(0xFF6B7280))
        if (s.isTraining) Chip("訓練", Color(0xFF1D4ED8))
        if (s.useDemoPoint) Chip("デモ地点（固定）", Color(0xFF7C3AED))
        Text(s.location?.let { "%.5f, %.5f".format(it.first, it.second) } ?: "現在地を取得中…", fontSize = 12.sp)
    }
}

@Composable
private fun Home(s: UiState, vm: AppViewModel) {
    Text("旅行の前に（Wi-Fi のある所で）", fontWeight = FontWeight.Bold)
    Text(s.packInfo ?: "まだ保存していません")
    OutlinedButton(onClick = { vm.downloadPack() }) { Text("大阪湾岸のデータを保存") }
    if (vm.liveAvailable) Text("Gemini Live: 使える（App Check デバッグ用: ${vm.debugSecret?.take(8) ?: "未設定"}…）", fontSize = 12.sp)
    else Text("Gemini Live: このビルドには Firebase の設定が入っていません", fontSize = 12.sp)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = s.useDemoPoint, onCheckedChange = vm::setDemoPoint)
        Text("  デモ地点を使う（屋内の試験用）")
    }
    Spacer(Modifier.height(24.dp))
    Button(
        onClick = { vm.onShake() },
        modifier = Modifier.fillMaxWidth().height(120.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB91C1C)),
    ) { Text("揺れた／警報を見た", fontSize = 28.sp) }
    OutlinedButton(onClick = { vm.receiveAlert(replay = true) }) { Text("警報を受け取る（訓練電文を流し直す）") }
}

@Composable
private fun Guide(s: UiState, vm: AppViewModel) {
    val t = s.target
    if (t == null) {
        Text("避難先を計算しています…")
        return
    }
    s.alertKind?.let { Text(it, color = Color(0xFFB91C1C), fontWeight = FontWeight.Bold) }
    Text(t.name, fontSize = 26.sp, fontWeight = FontWeight.Bold)
    Text(if (s.targetIsUpstairs) "建物の ${t.floorMin ?: 3} 階以上へ" else "浸水想定区域の外へ", fontSize = 20.sp)
    if (s.online && !s.useDemoPoint) NavPanel(t, silent = s.liveActive)
    else if (s.online) Text("（デモ地点では Navigation SDK を使わない。実際の GPS で試す）", fontSize = 12.sp)
    s.step?.let { step ->
        Arrow(step.relativeDeg ?: step.bearingDeg)
        Text(
            if (step.arrived) "着きました。${t.floorMin ?: 3} 階以上へ上がってください"
            else "${step.compass.ja}へ 約 ${step.distanceM.roundToInt()}m（歩いて約 ${step.remainingMin.roundToInt()} 分）",
            fontSize = 20.sp,
        )
        if (step.relativeDeg == null) Text("（端末の向きが分からないため、北が上の矢印です）", fontSize = 12.sp)
    }
    s.minutesLeft?.let {
        val basis = if (s.alertArrivalMillis != null) "気象庁の到達予想時刻" else "揺れた時刻＋26分（目安）"
        Text("到達予想まで あと約 ${it.roundToInt()} 分（$basis。想定より早く来ることがあります）")
    }
    Text("計算: ${s.planSource}${if (s.slow) "・遅い歩き（0.5m/秒）" else "・0.62m/秒"}", fontSize = 12.sp)
    s.explanation?.let { Text(it, fontSize = 14.sp) }
    s.answer?.let { Banner(it, Color(0xFF1D4ED8)) }
    if (vm.liveAvailable && s.online) {
        val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) vm.toggleLive() }
        Button(
            onClick = { if (s.liveActive) vm.toggleLive() else micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1D4ED8)),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (s.liveActive) "会話を止める（Gemini Live）" else "Gemini と話す（Gemini Live）", fontSize = 18.sp) }
    }
    if (!s.online) Text("圏外: 会話は下のボタン（端末の中）で受けます", fontSize = 12.sp)
    Text("困ったこと（押すと選び直し・案内します）", fontWeight = FontWeight.Bold)
    INTENTS.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { (id, label) -> OutlinedButton(onClick = { vm.onIntent(id) }) { Text(label, fontSize = 13.sp) } }
        }
    }
    OutlinedButton(onClick = { vm.reset() }) { Text("最初の画面に戻る") }
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
private fun Banner(text: String, color: Color) {
    Text(text, color = Color.White, modifier = Modifier.fillMaxWidth().background(color).padding(10.dp))
}

@Composable
private fun Chip(text: String, color: Color) {
    Text(text, color = Color.White, fontSize = 12.sp, modifier = Modifier.background(color).padding(horizontal = 8.dp, vertical = 2.dp))
}
