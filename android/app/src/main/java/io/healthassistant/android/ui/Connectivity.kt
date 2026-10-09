package io.healthassistant.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.healthassistant.android.data.ConnectivityRepository
import org.koin.compose.koinInject

enum class Connectivity { Online, Offline }

/**
 * Online/offline for the dashboard's offline-aware surfaces. A thin reader of
 * the [ConnectivityRepository] singleton (offline-first M0) so there is one
 * connectivity source shared with every repository, instead of each composable
 * registering its own `ConnectivityManager.NetworkCallback`.
 */
@Composable
fun rememberConnectivity(): Connectivity {
    val repo: ConnectivityRepository = koinInject()
    val online by repo.online.collectAsState()
    return if (online) Connectivity.Online else Connectivity.Offline
}
