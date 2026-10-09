package io.healthassistant.android.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.healthassistant.android.ui.theme.LocalHASpacing
import io.healthassistant.android.ui.theme.LocalReduceMotion
import io.healthassistant.android.ui.theme.spacing

/**
 * M9 polish: shimmer skeletons — placeholders that mimic the layout they
 * stand in for (the chart block, the stats row, the latest card, list rows)
 * instead of a bare spinner. The sweep is a tokened gradient
 * (`surfaceVariant` → `surface` → `surfaceVariant`) and freezes into a plain
 * tinted block under reduce-motion. Components render no text, so they stay
 * silent for TalkBack.
 */
@Composable
fun rememberShimmerBrush(): Brush {
    val base = MaterialTheme.colorScheme.surfaceVariant
    val highlight = MaterialTheme.colorScheme.surface
    if (LocalReduceMotion.current) return Brush.linearGradient(listOf(base, base, base))
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(SHIMMER_SWEEP_MS, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
            label = "shimmer_progress",
        )
    val start = progress * 2f * SHIMMER_BAND_PX - SHIMMER_BAND_PX
    return Brush.linearGradient(
        colors = listOf(base, highlight, base),
        start = Offset(start, 0f),
        end = Offset(start + SHIMMER_BAND_PX, 0f),
    )
}

/** One placeholder block with the shimmer background. */
@Composable
fun SkeletonBox(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.small,
) {
    Box(modifier.background(rememberShimmerBrush(), shape))
}

/** The chart-area placeholder: the plot block at the chart's height plus a
 *  thin axis strip beneath it. */
@Composable
fun ChartSkeleton(modifier: Modifier = Modifier) {
    Column(modifier.testTag("chart_skeleton")) {
        SkeletonBox(
            modifier = Modifier.fillMaxWidth().height(CHART_SKELETON_HEIGHT),
            shape = MaterialTheme.shapes.medium,
        )
        Spacer(Modifier.height(LocalHASpacing.current.xs))
        SkeletonBox(Modifier.fillMaxWidth().height(AXIS_SKELETON_HEIGHT))
    }
}

/** The biomarker detail's loading body: the chart skeleton plus the two
 *  blocks that follow it on the loaded screen (stats row, latest card). */
@Composable
fun DetailSkeleton(modifier: Modifier = Modifier) {
    Column(modifier.testTag("detail_skeleton")) {
        ChartSkeleton(Modifier.fillMaxWidth())
        Spacer(Modifier.height(MaterialTheme.spacing.md))
        Row(Modifier.fillMaxWidth()) {
            repeat(STAT_CELLS) { index ->
                if (index > 0) Spacer(Modifier.width(MaterialTheme.spacing.sm))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    SkeletonBox(Modifier.size(STAT_LABEL_SIZE))
                    Spacer(Modifier.height(MaterialTheme.spacing.xs))
                    SkeletonBox(Modifier.size(STAT_VALUE_SIZE))
                }
            }
        }
        Spacer(Modifier.height(MaterialTheme.spacing.md))
        SkeletonBox(
            modifier = Modifier.fillMaxWidth().height(LATEST_CARD_HEIGHT),
            shape = MaterialTheme.shapes.medium,
        )
    }
}

/** A list-of-cards placeholder (e.g. the biomarkers list while the catalog
 *  loads): [rows] card-shaped blocks. */
@Composable
fun ListSkeleton(
    rows: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.testTag("list_skeleton"),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
    ) {
        repeat(rows) {
            SkeletonBox(
                modifier = Modifier.fillMaxWidth().height(LIST_ROW_HEIGHT),
                shape = MaterialTheme.shapes.medium,
            )
        }
    }
}

private const val SHIMMER_SWEEP_MS = 1_200
private const val SHIMMER_BAND_PX = 400f
private const val STAT_CELLS = 4
private val CHART_SKELETON_HEIGHT = 260.dp
private val AXIS_SKELETON_HEIGHT = 12.dp
private val LATEST_CARD_HEIGHT = 88.dp
private val LIST_ROW_HEIGHT = 56.dp
private val STAT_LABEL_SIZE = DpSize(40.dp, 12.dp)
private val STAT_VALUE_SIZE = DpSize(48.dp, 20.dp)
