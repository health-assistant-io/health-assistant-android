package io.healthassistant.android.ui.navigation

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/** The persistent bottom navigation bar with the four top-level destinations. */
@Composable
fun HABottomBar(
    currentRoute: String?,
    onTabSelected: (HATab) -> Unit,
) {
    NavigationBar {
        HATab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab.matches(currentRoute),
                onClick = { onTabSelected(tab) },
                icon = { Icon(tab.icon, contentDescription = stringResource(tab.labelRes)) },
                label = { Text(stringResource(tab.labelRes)) },
            )
        }
    }
}
