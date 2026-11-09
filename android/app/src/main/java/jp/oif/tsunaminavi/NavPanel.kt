package jp.oif.tsunaminavi

import android.app.Activity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.NavigationView
import com.google.android.libraries.navigation.Navigator
import com.google.android.libraries.navigation.RoutingOptions
import com.google.android.libraries.navigation.Waypoint
import jp.oif.tsunaminavi.offline.Shelter

/**
 * 通信がある時の徒歩の案内（Navigation SDK）。アプリの中に地図と曲がり角の案内を出す。
 * 案内を始めた時点で 15〜20 分ぶんの経路が保存されるので、途中で圏外になっても続く見込み（O3）。
 * @param silent Gemini Live と話している間は、ナビの声を止める（声がぶつからないように）
 */
@Composable
fun NavPanel(target: Shelter, silent: Boolean) {
    val activity = LocalContext.current as Activity
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var navigator by remember { mutableStateOf<Navigator?>(null) }
    var status by remember { mutableStateOf("ナビを準備中…") }

    LaunchedEffect(Unit) {
        val start = {
            NavigationApi.getNavigator(activity, object : NavigationApi.NavigatorListener {
                override fun onNavigatorReady(nav: Navigator) {
                    navigator = nav
                }

                override fun onError(errorCode: Int) {
                    status = "ナビを使えません（エラー $errorCode）。矢印の案内を使ってください"
                }
            })
        }
        runCatching {
            if (NavigationApi.areTermsAccepted(activity.application)) start()
            else NavigationApi.showTermsAndConditionsDialog(activity, "OIF") { accepted ->
                if (accepted) start() else status = "Navigation terms not accepted. Use the arrow below. / 利用規約に同意していないため、ナビは使えません"
            }
        }.onFailure { status = "Navigation is not available (${it.message}). Use the arrow below. / ナビを使えません。矢印の案内を使ってください" }
    }

    LaunchedEffect(navigator, target.id) {
        val nav = navigator ?: return@LaunchedEffect
        runCatching {
        nav.stopGuidance()
        val waypoint = Waypoint.builder().setLatLng(target.lat, target.lon).setTitle(target.name).build()
        val options = RoutingOptions().travelMode(RoutingOptions.TravelMode.WALKING)
        nav.setDestinations(listOf(waypoint), options).setOnResultListener { result ->
            if (result == Navigator.RouteStatus.OK) {
                nav.startGuidance()
                status = "徒歩の案内中（Navigation SDK）"
            } else {
                status = "道を出せませんでした（$result）。矢印の案内を使ってください"
            }
        }
        }.onFailure { status = "Navigation failed (${it.message}). Use the arrow below. / ナビに失敗。矢印の案内を使ってください" }
    }

    LaunchedEffect(navigator, silent) {
        navigator?.setAudioGuidance(
            if (silent) Navigator.AudioGuidance.SILENT else Navigator.AudioGuidance.VOICE_ALERTS_AND_GUIDANCE,
        )
    }

    Column {
        Text(status, fontSize = 12.sp)
        AndroidView(
            factory = { ctx ->
                runCatching { NavigationView(ctx) }.getOrElse {
                    status = "Map could not be shown (${it.message}). Use the arrow below. / 地図を出せません。矢印の案内を使ってください"
                    return@AndroidView android.view.View(ctx)
                }.also { view -> if (view is NavigationView) {
                    view.onCreate(null)
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_START -> view.onStart()
                            Lifecycle.Event.ON_RESUME -> view.onResume()
                            Lifecycle.Event.ON_PAUSE -> view.onPause()
                            Lifecycle.Event.ON_STOP -> view.onStop()
                            else -> Unit
                        }
                    }
                    lifecycle.addObserver(observer)
                    view.tag = observer
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) view.onStart()
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.onResume()
                } }
            },
            modifier = Modifier.fillMaxWidth().height(420.dp),
            onRelease = { v ->
                (v.tag as? LifecycleEventObserver)?.let { lifecycle.removeObserver(it) }
                (v as? NavigationView)?.let { view ->
                    view.onPause()
                    view.onStop()
                    view.onDestroy()
                }
            },
        )
    }

    DisposableEffect(Unit) {
        onDispose { navigator?.stopGuidance() }
    }
}
