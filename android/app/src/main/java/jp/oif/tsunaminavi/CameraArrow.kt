package jp.oif.tsunaminavi

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/** 案内の画面の「地図」と「カメラ」の切り替え。 */
@Composable
fun ViewSwitch(camera: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = .06f)).padding(3.dp)) {
        listOf(
            false to tr("Map", "地図", "地图", "지도"),
            true to tr("Camera arrow", "カメラで矢印", "相机箭头", "카메라 화살표"),
        ).forEach { (value, label) ->
            Text(
                label,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = if (camera == value) Color.White else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (camera == value) Brand.Info else Color.Transparent)
                    .clickable { onChange(value) }.padding(horizontal = 18.dp, vertical = 9.dp),
            )
        }
    }
}

/**
 * カメラの映像の上に、避難先の方角を指す矢印を重ねる（簡易の AR）。
 * 方角は、端末の方位センサーと避難先の位置から計算する。建物には固定されないので、方位センサーの誤差がそのまま出る。
 */
@Composable
fun CameraArrow(s: UiState) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    if (!granted) {
        OutlinedButton(onClick = { launcher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text(tr("Allow camera to show the arrow", "カメラを許可して矢印を出す", "允许使用相机以显示箭头", "카메라를 허용해 화살표 표시"))
        }
        return
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val step = s.step
    val t = s.target
    val rel = step?.relativeDeg
    val angle by animateFloatAsState(((rel ?: step?.bearingDeg ?: 0.0)).toFloat(), label = "cam-arrow")

    Box(Modifier.fillMaxWidth().height(460.dp).clip(MaterialTheme.shapes.large).background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).also { view ->
                    val future = ProcessCameraProvider.getInstance(ctx)
                    future.addListener({
                        runCatching {
                            val provider = future.get()
                            val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                            provider.unbindAll()
                            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                }
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = { v -> runCatching { ProcessCameraProvider.getInstance(v.context).get().unbindAll() } },
        )

        // 地面に寝かせた矢印（奥へ傾けて、進む向きが分かるようにする）
        Canvas(
            Modifier.align(Alignment.Center).size(260.dp).graphicsLayer {
                rotationX = 50f
                rotationZ = angle
                cameraDistance = 12f * density
            },
        ) {
            val w = size.width
            val h = size.height
            val path = Path().apply {
                moveTo(w / 2, 0f)
                lineTo(w * 0.92f, h * 0.55f)
                lineTo(w * 0.64f, h * 0.55f)
                lineTo(w * 0.64f, h)
                lineTo(w * 0.36f, h)
                lineTo(w * 0.36f, h * 0.55f)
                lineTo(w * 0.08f, h * 0.55f)
                close()
            }
            drawPath(path, Brand.Safe.copy(alpha = .88f))
            drawPath(path, Color.White, style = Stroke(width = 10f))
        }

        Column(
            Modifier.align(Alignment.TopStart).fillMaxWidth().background(Color.Black.copy(alpha = .55f)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (t != null) Text(t.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
            if (step != null) {
                Text(
                    if (step.arrived) tr("You have arrived", "着きました", "已到达", "도착했습니다")
                    else tr("${step.distanceM.roundToInt()} m in a straight line", "直線で ${step.distanceM.roundToInt()} m", "直线距离 ${step.distanceM.roundToInt()} 米", "직선거리 ${step.distanceM.roundToInt()} m"),
                    color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                )
            }
            if (t != null && s.targetIsUpstairs) Text(tr("Then go up to floor ${t.floorMin ?: 3}+", "着いたら ${t.floorMin ?: 3} 階以上へ", "到达后上到${t.floorMin ?: 3}楼以上", "도착하면 ${t.floorMin ?: 3}층 이상으로"), color = Color.White, fontSize = 14.sp)
        }

        Row(
            Modifier.align(Alignment.BottomCenter).padding(12.dp).clip(CircleShape).background(Color.Black.copy(alpha = .55f)).padding(horizontal = 14.dp, vertical = 7.dp),
        ) {
            Text(
                if (rel == null) tr("Compass not ready — arrow points north-up", "方位が分からないため、北を上にしています", "无法获取方向，箭头以北为上", "방위를 알 수 없어 북쪽 기준으로 표시")
                else tr("Hold the tablet upright and walk where the arrow points. Compass may be off near steel.", "縦に持ち、矢印の向きへ歩く。鉄の近くでは方位がずれることがあります", "竖着拿，朝箭头方向走。靠近钢铁时方向可能偏差", "세로로 들고 화살표 방향으로 걸으세요. 철 근처에서는 방위가 틀릴 수 있습니다"),
                color = Color.White, fontSize = 12.sp,
            )
        }
    }
}
