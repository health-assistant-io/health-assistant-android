package io.healthassistant.android.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import io.healthassistant.shared.data.repository.ConnectivityProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide connectivity source (offline-first M0). Promotes the old
 * Compose-only `rememberConnectivity()` into a Koin singleton so every
 * repository (not just Home) can react to online/offline transitions.
 *
 * Backed by the same `ConnectivityManager.NetworkCallback` mechanism: a hot
 * [online] `StateFlow` plus the [ConnectivityProvider] interface for repository
 * injection. Requires `ACCESS_NETWORK_STATE` (already declared for the old
 * helper). Constructed once via Koin; the callback lives for the process, so no
 * unregister is needed.
 */
class ConnectivityRepository(
    context: Context,
) : ConnectivityProvider {
    private val cm =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _online = MutableStateFlow(currentlyOnline())
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _online.value = true
            }

            override fun onLost(network: Network) {
                _online.value = currentlyOnline()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                caps: NetworkCapabilities,
            ) {
                _online.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        }

    init {
        // registerNetworkCallback can throw on some OEM ROMs when the platform
        // connectivity service is mid-restart; guard so app launch never crashes.
        runCatching {
            cm.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                callback,
            )
        }
    }

    override fun isOnline(): Boolean = _online.value

    private fun currentlyOnline(): Boolean =
        runCatching {
            val active = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(active) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.getOrDefault(false)
}
