package app.streambird.android.media

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

/** Battery %, battery temperature, thermal status and network type, read on demand (cheap; called every 2 s). */
class DeviceTelemetry(private val context: Context) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    fun read(): DeviceReading {
        val i: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else null
        val temp = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.let { it / 10.0 }
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return DeviceReading(pct, temp, charging, thermalName(), networkName())
    }

    private fun thermalName(): String? {
        if (Build.VERSION.SDK_INT < 29) return null
        return when (pm.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "NONE"
            PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
            PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
            PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
            PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
            else -> "UNKNOWN"
        }
    }

    fun networkName(): String = describe(cm.getNetworkCapabilities(cm.activeNetwork))

    private fun describe(caps: NetworkCapabilities?): String = when {
        caps == null -> "none"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
        else -> "other"
    }

    /** Reports every change of the default network ("wifi -> cellular"). Returns an unregister function. */
    fun watchNetwork(onChange: (String) -> Unit): () -> Unit {
        var last = networkName()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = update(describe(caps))
            override fun onLost(network: Network) = update(networkName())
            private fun update(now: String) {
                if (now != last) { onChange("network $last -> $now"); last = now }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }
        return { runCatching { cm.unregisterNetworkCallback(cb) } }
    }
}
