package com.gravity.ide.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.gravity.ide.data.EventCategory
import com.gravity.ide.data.EventLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NetworkState(val online: Boolean, val validated: Boolean, val metered: Boolean, val transport: String)

/** Real connectivity from ConnectivityManager (validated = the system confirmed internet access). */
class NetworkMonitor(context: Context, private val events: EventLog) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val _state = MutableStateFlow(read(cm.activeNetwork))
    val state: StateFlow<NetworkState> = _state.asStateFlow()

    val isOnline: Boolean get() = _state.value.online

    init {
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = update(network)
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = update(network)
            override fun onLost(network: Network) = update(null)
        })
    }

    private fun update(network: Network?) {
        val previous = _state.value
        val next = read(network)
        _state.value = next
        if (previous.online != next.online) {
            events.log(EventCategory.NETWORK, if (next.online) "Back online (${next.transport})" else "Offline", if (next.online) "Pending cloud tasks will be retried." else "Cloud requests paused; local operations continue.")
        }
    }

    private fun read(network: Network?): NetworkState {
        val caps = network?.let { cm.getNetworkCapabilities(it) } ?: return NetworkState(false, false, false, "none")
        val internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "Other"
        }
        return NetworkState(internet && validated, validated, !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED), transport)
    }
}
