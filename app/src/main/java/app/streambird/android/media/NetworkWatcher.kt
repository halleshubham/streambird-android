package app.streambird.android.media

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Default-network callbacks turned into [Trigger]s (via the pure [networkTrigger]). Reacts to a new default network, to the
 * transport type changing on the same network, and to losing the network. Signal-strength-only capability updates are ignored.
 */
class NetworkWatcher(context: Context, private val onTrigger: (Trigger) -> Unit) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var state = current()
    private var cb: ConnectivityManager.NetworkCallback? = null

    val transport: String get() = state.transport

    private fun transportOf(caps: NetworkCapabilities?) = when {
        caps == null -> "none"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
        else -> "other"
    }

    private fun current(): NetState {
        val n = cm.activeNetwork
        return NetState(n?.toString(), transportOf(n?.let { cm.getNetworkCapabilities(it) }))
    }

    @Synchronized private fun update(cur: NetState) {
        val t = networkTrigger(state, cur)
        state = cur
        if (t != null) onTrigger(t)
    }

    fun start() {
        val c = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = update(NetState(network.toString(), transportOf(cm.getNetworkCapabilities(network)).let { if (it == "none") state.transport else it }))
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = update(NetState(network.toString(), transportOf(caps)))
            override fun onLost(network: Network) = update(current().let { if (it.id == network.toString()) NetState(null, "none") else it })
        }
        cb = c
        runCatching { cm.registerDefaultNetworkCallback(c) }
    }

    fun stop() { cb?.let { runCatching { cm.unregisterNetworkCallback(it) } }; cb = null }
}
