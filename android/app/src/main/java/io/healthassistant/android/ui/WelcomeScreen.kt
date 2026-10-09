package io.healthassistant.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.healthassistant.android.R
import kotlinx.coroutines.launch

/** The three R7 welcome slides — what the app is, the privacy pitch, and what
 *  the user gets. */
enum class WelcomeSlide(
    val icon: ImageVector,
    val titleRes: Int,
    val bodyRes: Int,
) {
    Health(
        Icons.Outlined.Favorite,
        R.string.welcome_health_title,
        R.string.welcome_health_body,
    ),
    Privacy(
        Icons.Outlined.Lock,
        R.string.welcome_privacy_title,
        R.string.welcome_privacy_body,
    ),
    Value(
        Icons.Outlined.Insights,
        R.string.welcome_value_title,
        R.string.welcome_value_body,
    ),
}

/** R7: a skippable 3-slide welcome shown once before the connect screen
 *  (first run only; the owning route persists the flag). Pure state + lambdas —
 *  Compose-testable with `setContent`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WelcomeScreen(
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { WelcomeSlide.entries.size })
    val lastSlide = WelcomeSlide.entries.lastIndex

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {},
                actions = {
                    TextButton(onClick = onFinish) { Text(stringResource(R.string.welcome_skip)) }
                },
            )
        },
        modifier = modifier,
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) { page ->
                WelcomeSlideContent(WelcomeSlide.entries[page])
            }

            Column(Modifier.fillMaxWidth()) {
                DotsIndicator(current = pagerState.currentPage, count = WelcomeSlide.entries.size)
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = {
                        if (pagerState.currentPage < lastSlide) {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                        } else {
                            onFinish()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (pagerState.currentPage < lastSlide) {
                            stringResource(R.string.welcome_next)
                        } else {
                            stringResource(R.string.welcome_get_started)
                        },
                    )
                }
                Spacer(Modifier.height(8.dp))
                AnimatedVisibility(visible = pagerState.currentPage == lastSlide) {
                    Text(
                        stringResource(R.string.welcome_last_swap),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun WelcomeSlideContent(slide: WelcomeSlide) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().padding(bottom = 24.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(96.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.large),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                slide.icon,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.height(32.dp))
        Text(
            stringResource(slide.titleRes),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(slide.bodyRes),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DotsIndicator(
    current: Int,
    count: Int,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            val selected = index == current
            Box(
                Modifier
                    .size(if (selected) 10.dp else 8.dp)
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        MaterialTheme.shapes.extraLarge,
                    ),
            )
        }
    }
}
