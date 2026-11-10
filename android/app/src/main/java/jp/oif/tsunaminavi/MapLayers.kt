package jp.oif.tsunaminavi

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolygonOptions
import com.google.android.gms.maps.model.PolylineOptions
import jp.oif.tsunaminavi.offline.DEPTH_ORDER

/** Google の地図に重ねる浸水域の色（深いほど濃い青・下の地図が透ける）。 */
private fun floodArgb(depth: String): Int {
    val i = DEPTH_ORDER.indexOf(depth).coerceAtLeast(0)
    val t = i / (DEPTH_ORDER.size - 1f)
    return androidx.compose.ui.graphics.Color(red = 0.55f - 0.40f * t, green = 0.72f - 0.40f * t, blue = 0.98f - 0.15f * t, alpha = 0.45f + 0.2f * t).toArgb()
}

/** 浸水想定区域（国交省）の多角形を地図に足す。消す時のために、足した物を返す。 */
fun addFlood(map: GoogleMap, m: MiniMapData): List<() -> Unit> = m.flood.mapNotNull { (depth, ring) ->
    val pts = (ring.indices step 2).map { LatLng(ring[it + 1], ring[it]) }
    if (pts.size < 3) null
    else map.addPolygon(PolygonOptions().addAll(pts).fillColor(floodArgb(depth)).strokeWidth(0f).zIndex(1f)).let { p -> { p.remove() } }
}

/**
 * 通信がある時の地図（Google Maps）。指で拡大・移動できる。
 * 浸水想定区域・保存した道に沿った避難先までの道・避難先のピン・今いる所を重ねる。
 */
@Composable
fun GoogleMapPanel(m: MiniMapData, s: UiState) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var gmap by remember { mutableStateOf<GoogleMap?>(null) }
    var fittedFor by remember { mutableStateOf<String?>(null) }
    val shelterTitle = tr("Shelter", "避難先", "避难所", "대피소") + ": " + (s.target?.name ?: "")
    val shelterSub = s.target?.let { t -> if (s.targetIsUpstairs) tr("Go up to floor ${t.floorMin ?: 3}+", "${t.floorMin ?: 3}階以上へ", "请上到${t.floorMin ?: 3}楼以上", "${t.floorMin ?: 3}층 이상으로 올라가세요") else tr("Outside the flood zone", "浸水想定区域の外", "浸水预想区域外", "침수 예상 구역 밖") }
    val youTitle = tr("You are here", "現在地", "您的位置", "현재 위치")

    AndroidView(
        factory = { ctx ->
            MapView(ctx).also { v ->
                v.onCreate(null)
                val observer = LifecycleEventObserver { _, event ->
                    runCatching {
                        when (event) {
                            Lifecycle.Event.ON_START -> v.onStart()
                            Lifecycle.Event.ON_RESUME -> v.onResume()
                            Lifecycle.Event.ON_PAUSE -> v.onPause()
                            Lifecycle.Event.ON_STOP -> v.onStop()
                            else -> Unit
                        }
                    }
                }
                lifecycle.addObserver(observer)
                v.tag = observer
                v.getMapAsync { map ->
                    map.uiSettings.isZoomControlsEnabled = true
                    map.uiSettings.isCompassEnabled = true
                    map.uiSettings.isMapToolbarEnabled = false
                    gmap = map
                }
            }
        },
        modifier = Modifier.fillMaxWidth().height(400.dp).clip(MaterialTheme.shapes.large),
        onRelease = { v ->
            (v.tag as? LifecycleEventObserver)?.let { lifecycle.removeObserver(it) }
            (v as? MapView)?.let { runCatching { it.onPause() }; runCatching { it.onStop() }; runCatching { it.onDestroy() } }
        },
    )

    LaunchedEffect(gmap, m, s.location, s.target?.id, shelterTitle) {
        val map = gmap ?: return@LaunchedEffect
        runCatching {
            map.clear()
            addFlood(map, m)
            if (m.route.size >= 2) {
                val line = m.route.map { LatLng(it.first, it.second) }
                map.addPolyline(PolylineOptions().addAll(line).color(android.graphics.Color.WHITE).width(24f).zIndex(2f))
                map.addPolyline(PolylineOptions().addAll(line).color(Brand.Safe.toArgb()).width(13f).zIndex(3f))
            }
            val bounds = LatLngBounds.builder()
            s.target?.let { t ->
                val p = LatLng(t.lat, t.lon)
                bounds.include(p)
                map.addMarker(
                    MarkerOptions().position(p).title(shelterTitle).snippet(shelterSub)
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)).zIndex(5f),
                )?.showInfoWindow()
            }
            s.location?.let { (la, lo) ->
                val p = LatLng(la, lo)
                bounds.include(p)
                map.addMarker(MarkerOptions().position(p).title(youTitle).icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)).zIndex(6f))
            }
            m.route.forEach { bounds.include(LatLng(it.first, it.second)) }
            val key = s.target?.id
            if (key != null && fittedFor != key) {
                fittedFor = key
                val b = bounds.build()
                map.setOnMapLoadedCallback { runCatching { map.animateCamera(CameraUpdateFactory.newLatLngBounds(b, 140)) } }
                runCatching { map.moveCamera(CameraUpdateFactory.newLatLngBounds(b, 140)) }
            }
        }
    }
}
