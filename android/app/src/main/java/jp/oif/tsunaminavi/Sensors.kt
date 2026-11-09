package jp.oif.tsunaminavi

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 通信があるか。機内モードや圏外になると false になる（切り替えの検知・N8）。 */
class Connectivity(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _online = MutableStateFlow(isOnlineNow())
    val online: StateFlow<Boolean> = _online

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            _online.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        override fun onLost(network: Network) {
            _online.value = isOnlineNow()
        }
    }

    init {
        cm.registerDefaultNetworkCallback(callback)
    }

    private fun isOnlineNow(): Boolean {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}

/** 現在地（GPS）。通信が無くても GPS は動く。 */
class LocationSource(context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context)
    private val _location = MutableStateFlow<Pair<Double, Double>?>(null)
    val location: StateFlow<Pair<Double, Double>?> = _location

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { _location.value = it.latitude to it.longitude }
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        // まず端末が最後に知っていた位置をすぐ使い、そのあと GPS で正確にする
        client.lastLocation.addOnSuccessListener { loc -> if (loc != null && _location.value == null) _location.value = loc.latitude to loc.longitude }
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2_000L).build()
        client.requestLocationUpdates(req, callback, Looper.getMainLooper())
    }
}

/** 端末が向いている方角（北が 0 度）。矢印を、端末から見た向きで出すために使う。 */
class HeadingSource(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(SensorManager::class.java)
    private val _heading = MutableStateFlow<Double?>(null)
    val heading: StateFlow<Double?> = _heading
    private val rotation = FloatArray(9)
    private val orientation = FloatArray(3)

    fun start() {
        sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        SensorManager.getOrientation(rotation, orientation)
        _heading.value = (Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
