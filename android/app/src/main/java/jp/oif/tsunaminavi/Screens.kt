package jp.oif.tsunaminavi

import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jp.oif.tsunaminavi.offline.DEPTH_ORDER
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private val Ink = Color(0xFF111111)
private val Muted = Color(0xFF6B6B6B)

/** 警報が届いた瞬間の全画面。裏では案内がもう始まっていて、押すか 5 秒で案内の画面になる。 */
@Composable
fun AlertTakeover(s: UiState, vm: AppViewModel) {
    var left by remember { mutableIntStateOf(5) }
    LaunchedEffect(Unit) {
        while (left > 0) {
            delay(1_000)
            left -= 1
        }
        vm.closeTakeover()
    }
    val kind = s.alertKind ?: "大津波警報"
    Column(
        Modifier.fillMaxSize().background(Brand.Danger).statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.Warning, null, tint = Color(0xFFFFD100), modifier = Modifier.size(44.dp))
            Column {
                Text(
                    "JMA · OSAKA" + if (s.isTraining) tr(" · TRAINING", " · 訓練", " · 演练", " · 훈련") else "",
                    color = Color.White.copy(alpha = .9f), fontSize = 13.sp, fontWeight = FontWeight.Bold,
                )
                Text(kind, color = Color.White.copy(alpha = .9f), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(alertName(kind), color = Color.White, fontSize = 44.sp, lineHeight = 46.sp, fontWeight = FontWeight.Black)
        HorizontalDivider(color = Color.White.copy(alpha = .4f), thickness = 2.dp)
        Column {
            Text(tr("First wave expected in", "津波の到達まで", "距第一波海啸到达", "첫 파도 도달까지"), color = Color.White.copy(alpha = .9f), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${(s.minutesLeft ?: 0.0).roundToInt()}", color = Color.White, fontSize = 112.sp, lineHeight = 112.sp, fontWeight = FontWeight.Black)
                Text(tr(" min", " 分", " 分钟", " 분"), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(bottom = 16.dp))
            }
        }
        Text(
            tr("Move away from the sea and go high — now. Don't take a car.", "今すぐ海から離れ、高い所へ。車は使わないでください。", "立即远离大海，前往高处。不要开车。", "지금 바로 바다에서 멀어져 높은 곳으로 가세요. 차는 타지 마세요."),
            color = Color.White, fontSize = 20.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.weight(1f))
        Button(
            onClick = { vm.closeTakeover() },
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Brand.Danger),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().height(64.dp),
        ) { Text(tr("Show me where to go", "逃げる先を見る", "查看避难地点", "대피 장소 보기"), fontSize = 20.sp, fontWeight = FontWeight.Black) }
        Text(
            tr("Guidance starts by itself in $left s", "$left 秒後に自動で案内を始めます", "$left 秒后自动开始引导", "${left}초 후 자동으로 안내를 시작합니다"),
            color = Color.White.copy(alpha = .9f), fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Text(
            tr("I'm already high in a strong building", "もう丈夫な建物の高い階にいる", "我已在坚固建筑的高层", "이미 튼튼한 건물의 높은 층에 있어요"),
            color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline,
            modifier = Modifier.align(Alignment.CenterHorizontally).clickable { vm.markArrived() }.padding(8.dp),
        )
    }
}

/** 通信が切れても案内を続けていることを、案内の一番上で伝える。 */
@Composable
fun OfflineStrip() {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(Ink).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Filled.Warning, null, tint = Color(0xFFFFD100), modifier = Modifier.size(24.dp))
        Column {
            Text(tr("No signal — still guiding you", "圏外です。案内は続けます", "无信号 — 仍在为您引导", "신호 없음 — 안내는 계속됩니다"), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
            Text(tr("Using the map and roads saved on this phone", "この端末に保存した地図と道を使っています", "正在使用保存在本机的地图和道路", "이 휴대폰에 저장한 지도와 도로를 사용 중"), color = Color.White.copy(alpha = .8f), fontSize = 12.sp)
        }
    }
}

/** 深さの区分ごとの塗り（浅いほど薄い青）。 */
private fun floodColor(depth: String): Color {
    val i = DEPTH_ORDER.indexOf(depth).coerceAtLeast(0)
    val t = i / (DEPTH_ORDER.size - 1f)
    return Color(red = 0.80f - 0.45f * t, green = 0.88f - 0.35f * t, blue = 0.97f - 0.12f * t, alpha = 0.75f)
}

/**
 * 端末に保存した道のグラフと浸水想定から描く地図。通信が無くても、デモ地点でも出せる。
 * 地図の画像は使わず、道の線・浸水域・避難先までの道を自分で描く。
 */
@Composable
fun MiniMap(m: MiniMapData, s: UiState) {
    val here = s.location
    val target = s.target
    val label = remember {
        Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
    }
    Box(Modifier.fillMaxWidth().height(320.dp).clip(MaterialTheme.shapes.large)) {
        Canvas(Modifier.fillMaxSize()) {
            val k = cos(Math.toRadians((m.minLat + m.maxLat) / 2))
            val spanX = (m.maxLon - m.minLon) * k
            val spanY = m.maxLat - m.minLat
            val scale = min(size.width / spanX, size.height / spanY)
            val ox = (size.width - spanX * scale) / 2
            val oy = (size.height - spanY * scale) / 2
            fun pt(lat: Double, lon: Double) = Offset(
                (ox + (lon - m.minLon) * k * scale).toFloat(),
                (oy + (m.maxLat - lat) * scale).toFloat(),
            )
            drawRect(Color(0xFFF1F1EC))
            for ((depth, ring) in m.flood) {
                val path = Path()
                for (i in ring.indices step 2) {
                    val p = pt(ring[i + 1], ring[i])
                    if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                path.close()
                drawPath(path, floodColor(depth))
            }
            val r = m.roads
            for (i in r.indices step 4) {
                drawLine(Color(0xFFFFFFFF), pt(r[i].toDouble(), r[i + 1].toDouble()), pt(r[i + 2].toDouble(), r[i + 3].toDouble()), strokeWidth = 5f, cap = StrokeCap.Round)
            }
            for (i in r.indices step 4) {
                drawLine(Color(0xFFD6D6D0), pt(r[i].toDouble(), r[i + 1].toDouble()), pt(r[i + 2].toDouble(), r[i + 3].toDouble()), strokeWidth = 1.5f, cap = StrokeCap.Round)
            }
            val route = Path()
            m.route.forEachIndexed { i, (la, lo) ->
                val p = pt(la, lo)
                if (i == 0) route.moveTo(p.x, p.y) else route.lineTo(p.x, p.y)
            }
            drawPath(route, Color.White, style = Stroke(width = 22f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(route, Brand.Safe, style = Stroke(width = 12f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            if (target != null) {
                val p = pt(target.lat, target.lon)
                val half = 34f
                drawRoundRect(Color.White, Offset(p.x - half - 5, p.y - half - 5), Size(2 * half + 10, 2 * half + 10), androidx.compose.ui.geometry.CornerRadius(16f))
                drawRoundRect(Brand.Safe, Offset(p.x - half, p.y - half), Size(2 * half, 2 * half), androidx.compose.ui.geometry.CornerRadius(12f))
                label.textSize = 38f
                drawContext.canvas.nativeCanvas.drawText("避", p.x, p.y + 13f, label)
            }
            if (here != null) {
                val p = pt(here.first, here.second)
                drawCircle(Brand.Info.copy(alpha = .18f), 46f, p)
                drawCircle(Color.White, 22f, p)
                drawCircle(Brand.Info, 15f, p)
            }
        }
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip {
                Box(Modifier.size(12.dp).background(floodColor("2.0～3.0m")))
                Text(tr("Flood zone (MLIT)", "浸水想定区域（国交省）", "浸水预想区域（国土交通省）", "침수 예상 구역 (국토교통성)"), fontSize = 11.sp, color = Ink)
            }
            if (!m.onRoads) Chip { Text(tr("Straight line (no road data here)", "直線（この辺りの道のデータなし）", "直线（此处没有道路数据）", "직선 (이 근처 도로 데이터 없음)"), fontSize = 11.sp, color = Ink) }
        }
        Row(Modifier.align(Alignment.TopEnd).padding(10.dp)) {
            Chip {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (s.online) Brand.Safe else Color(0xFF9A9A9A)))
                Text(if (s.online) tr("Online", "オンライン", "在线", "온라인") else tr("Offline", "圏外", "无信号", "오프라인"), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Ink)
            }
        }
        Row(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
            Chip { Text(tr("Saved map · roads © OpenStreetMap", "保存した地図・道 © OpenStreetMap", "已保存的地图 · 道路 © OpenStreetMap", "저장한 지도 · 도로 © OpenStreetMap"), fontSize = 11.sp, color = Color(0xFF4A4A4A)) }
        }
    }
}

@Composable
private fun Chip(content: @Composable () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).background(Color.White).padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

/** 選び直した時、前の避難先と新しい避難先を並べて、何が変わったかを見せる。 */
@Composable
fun ReselectDiff(prev: Previous, s: UiState, vm: AppViewModel) {
    val t = s.target ?: return
    val left = s.minutesLeft
    val newWalk = s.targetWalkMin
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (prev.reason == "slow") tr("RECALCULATED AT A SLOWER PACE (0.5 m/s)", "ゆっくり歩く速さ（0.5 m/秒）で選び直しました", "已按较慢的步行速度（0.5 米/秒）重新选择", "느린 걸음(0.5 m/초)으로 다시 골랐습니다")
                    else tr("THAT WAY IS BLOCKED — NEW PLACE", "その道は通れない — 別の避難先にしました", "那条路走不通 — 已换到新的避难地点", "그 길은 막혔습니다 — 새 대피 장소"),
                    fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Muted, modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { vm.clearPrevious() }, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Close, tr("Close", "閉じる", "关闭", "닫기")) }
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFFF3F3F1)).padding(12.dp)) {
                Text(prev.name, fontSize = 13.sp, color = Muted, textDecoration = TextDecoration.LineThrough)
                val spare = left?.let { it - prev.walkMin }
                Text(
                    when {
                        prev.reason == "blocked" -> tr("Not used — you said the way is blocked", "使わない（道がふさがっている）", "不使用 — 您说道路被堵", "사용 안 함 — 길이 막혔다고 하셨어요")
                        spare == null -> tr("${prev.walkMin.roundToInt()} min", "${prev.walkMin.roundToInt()} 分", "${prev.walkMin.roundToInt()} 分钟", "${prev.walkMin.roundToInt()}분")
                        spare < 3 -> tr("${prev.walkMin.roundToInt()} min — only ${max(0, spare.roundToInt())} min to spare", "${prev.walkMin.roundToInt()} 分 — 余裕は ${max(0, spare.roundToInt())} 分だけ", "${prev.walkMin.roundToInt()} 分钟 — 只剩 ${max(0, spare.roundToInt())} 分钟余裕", "${prev.walkMin.roundToInt()}분 — 여유 ${max(0, spare.roundToInt())}분뿐")
                        else -> tr("${prev.walkMin.roundToInt()} min at this pace", "この速さで ${prev.walkMin.roundToInt()} 分", "按此速度 ${prev.walkMin.roundToInt()} 分钟", "이 속도로 ${prev.walkMin.roundToInt()}분")
                    },
                    fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB30010),
                )
            }
            Row(
                Modifier.fillMaxWidth().border(BorderStroke(2.dp, Brand.Safe), RoundedCornerShape(12.dp)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(Brand.Safe), contentAlignment = Alignment.Center) {
                    Text("避", color = Color.White, fontWeight = FontWeight.Black, fontSize = 18.sp)
                }
                Column(Modifier.weight(1f)) {
                    Text(t.name, fontSize = 17.sp, fontWeight = FontWeight.Black, lineHeight = 21.sp)
                    val parts = buildList {
                        newWalk?.let { add(tr("${it.roundToInt()} min", "${it.roundToInt()} 分", "${it.roundToInt()} 分钟", "${it.roundToInt()}분")) }
                        add(if (s.targetIsUpstairs) tr("floor ${t.floorMin ?: 3}+", "${t.floorMin ?: 3} 階以上", "${t.floorMin ?: 3} 楼以上", "${t.floorMin ?: 3}층 이상") else tr("outside the flood zone", "浸水想定の外", "浸水预想区域外", "침수 예상 구역 밖"))
                        if (newWalk != null && left != null) add(tr("${max(0, (left - newWalk).roundToInt())} min to spare", "余裕 ${max(0, (left - newWalk).roundToInt())} 分", "余裕 ${max(0, (left - newWalk).roundToInt())} 分钟", "여유 ${max(0, (left - newWalk).roundToInt())}분"))
                    }
                    Text(parts.joinToString(" · "), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00602B))
                }
            }
        }
    }
}

/** 着いた後の画面。上がる階を一番大きく出し、その後にすること（家族への連絡・119・いつ戻れるか）を並べる。 */
@Composable
fun ArrivedScreen(s: UiState, vm: AppViewModel) {
    val t = s.target
    val context = LocalContext.current
    Surface(color = Brand.Safe, contentColor = Color.White, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Filled.CheckCircle, null, modifier = Modifier.size(28.dp))
                Text(tr("You've arrived", "着きました", "已到达", "도착했습니다"), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                s.minutesLeft?.let { Text(tr("Tsunami in ${it.roundToInt()} min", "津波まで ${it.roundToInt()} 分", "距海啸 ${it.roundToInt()} 分钟", "쓰나미까지 ${it.roundToInt()}분"), fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            }
            t?.let { Text(it.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
            val upstairs = t == null || s.targetIsUpstairs
            Text(
                if (upstairs) tr("Go up to floor ${t?.floorMin ?: 3}+", "${t?.floorMin ?: 3} 階以上へ上がる", "上到 ${t?.floorMin ?: 3} 楼以上", "${t?.floorMin ?: 3}층 이상으로 올라가세요") else tr("Stay here, away from the sea", "ここで待つ（海に近づかない）", "留在这里，远离大海", "여기 머무르세요, 바다에서 떨어져서"),
                fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.Black,
            )
            Text(
                tr("Stay high until the warning is lifted. It can take hours. Later waves can be bigger.", "警報が解除されるまで、高い所にいてください。何時間もかかることがあり、後の波の方が大きいこともあります。", "在警报解除前请留在高处。可能需要几个小时。后面的海浪可能更大。", "경보가 해제될 때까지 높은 곳에 있으세요. 몇 시간이 걸릴 수 있습니다. 나중 파도가 더 클 수 있습니다."),
                fontSize = 15.sp, lineHeight = 21.sp,
            )
        }
    }
    s.answer?.let { Banner(it, null, Brand.Info) }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column {
            AfterItem(Icons.Filled.Share, tr("Tell your family you're safe", "家族に無事を知らせる", "告诉家人您平安", "가족에게 무사하다고 알리기"), tr("Share by SMS or any app. SMS can get through when data does not.", "SMS などで送ります。データ通信が無くても SMS は届くことがあります", "可用短信或其他应用发送。没有数据网络时短信也可能送达。", "문자나 다른 앱으로 보냅니다. 데이터가 안 될 때도 문자는 갈 수 있습니다.")) {
                val where = t?.let { "${it.name} (https://maps.google.com/?q=${it.lat},${it.lon})" } ?: "a safe place"
                val text = "I'm safe. I evacuated from the tsunami warning to $where. I will stay until the warning is lifted."
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                runCatching { context.startActivity(Intent.createChooser(send, null)) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            AfterItem(Icons.Filled.Info, tr("When can I go back?", "いつ戻れる？", "什么时候可以回去？", "언제 돌아갈 수 있나요?"), tr("Only after JMA lifts the warning", "気象庁が警報を解除してから", "要等日本气象厅解除警报后", "일본 기상청이 경보를 해제한 뒤에만")) { vm.onIntent("when_to_return") }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            AfterItem(Icons.Filled.Phone, tr("Injured? Call 119", "けがをしたら 119", "受伤了？拨打 119", "다쳤나요? 119에 전화"), tr("Ambulance and fire. Say \"English, please.\"", "救急・消防", "救护车和消防。可以说 \"English, please.\"", "구급차와 소방. \"English, please.\"라고 말하세요")) {
                runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:119"))) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            AfterItem(Icons.Filled.Person, tr("Lost someone?", "はぐれた人がいる", "和同伴走散了？", "일행을 잃어버렸나요?"), tr("Stay here. Shelter staff keep a list of names.", "ここを動かずに。避難所の係の人が名前の一覧を持っています", "请留在这里。避难所工作人员有人员名单。", "여기 계세요. 대피소 직원이 이름 명단을 갖고 있습니다.")) { vm.onIntent("lost_family") }
        }
    }
    if (s.arrivedManually && s.target != null) {
        OutlinedButton(onClick = { vm.backToGuide() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(tr("Not there yet — back to guidance", "まだ着いていない（案内に戻る）", "还没到 — 返回引导", "아직 도착 안 함 — 안내로 돌아가기"), fontWeight = FontWeight.Bold)
        }
    }
    OutlinedButton(onClick = { vm.reset() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Text(tr("Back to start", "最初の画面に戻る", "返回首页", "처음 화면으로"), fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AfterItem(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp) },
        supportingContent = { Text(sub, fontSize = 13.sp) },
        leadingContent = { Icon(icon, null) },
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** 最初の画面: 今いる所から逃げるならどこか（揺れる前に一度見ておける）。 */
@Composable
fun NearbyCard(place: NearbyPlace, stay: Boolean) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surface).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(9.dp)).background(Brand.Safe), contentAlignment = Alignment.Center) {
            Text("避", color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp)
        }
        Column(Modifier.weight(1f)) {
            Text(
                if (stay) tr("FROM WHERE YOU'RE STAYING", "泊まる所から逃げるなら", "从住宿地点出发时", "숙소에서 대피한다면") else tr("IF YOU HAD TO GO NOW", "今ここから逃げるなら", "如果现在就要避难", "지금 여기서 대피한다면"),
                fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Brand.Safe,
            )
            Text(place.name, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 20.sp)
            Text(
                tr("${place.walkMin.roundToInt()} min on foot", "歩いて ${place.walkMin.roundToInt()} 分", "步行 ${place.walkMin.roundToInt()} 分钟", "걸어서 ${place.walkMin.roundToInt()}분") + " · " +
                    if (place.upstairs) tr("floor ${place.floorMin ?: 3}+", "${place.floorMin ?: 3} 階以上", "${place.floorMin ?: 3} 楼以上", "${place.floorMin ?: 3}층 이상") else tr("outside the flood zone", "浸水想定の外", "浸水预想区域外", "침수 예상 구역 밖"),
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
