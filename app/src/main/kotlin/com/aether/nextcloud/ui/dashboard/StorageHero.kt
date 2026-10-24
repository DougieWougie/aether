package com.aether.nextcloud.ui.dashboard

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.res.pluralStringResource
import com.aether.nextcloud.R
import androidx.compose.ui.res.stringResource
import android.text.format.Formatter
import kotlin.math.roundToInt
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aether.nextcloud.ui.components.HeroShape
import com.aether.nextcloud.ui.components.MorphingShapeBadge
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.shape.CircleShape

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun StorageHero(state: DashboardUiState, onRetry: () -> Unit) {
    val context = LocalContext.current
    val quota = state.quota
    val total = quota?.totalBytes
    val syncing = state.activeTransfers.isNotEmpty()
    val failed = state.quotaError != null
    val colors = MaterialTheme.colorScheme
    val fraction = if (quota != null && total != null && total > 0) (quota.usedBytes.toFloat() / total).coerceIn(0f, 1f) else null

    Surface(shape = HeroShape, color = colors.primaryContainer, contentColor = colors.onPrimaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.storage_title), style = MaterialTheme.typography.labelLargeEmphasized)
                    when {
                        quota != null -> {
                            val (value, unit) = splitSize(Formatter.formatShortFileSize(context, quota.usedBytes))
                            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(value, style = MaterialTheme.typography.displayLargeEmphasized, maxLines = 1)
                                Text(unit, style = MaterialTheme.typography.headlineMediumEmphasized, modifier = Modifier.padding(bottom = 10.dp))
                            }
                            Text(
                                if (total != null) stringResource(R.string.storage_used_of, Formatter.formatShortFileSize(context, total)) else stringResource(R.string.storage_used_unlimited),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        failed -> {
                            Text(stringResource(R.string.storage_load_failed), style = MaterialTheme.typography.headlineMediumEmphasized)
                            Text(state.quotaError ?: "", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
                        }
                        else -> Text(stringResource(R.string.storage_loading), style = MaterialTheme.typography.headlineMediumEmphasized)
                    }
                }
                // The text beside the gauge already says how much is used, so the gauge only adds the percentage or the transfer count.
                val gaugeDescription = when {
                    failed -> null
                    syncing -> pluralStringResource(R.plurals.transfers_active, state.activeTransfers.size, state.activeTransfers.size)
                    fraction != null -> stringResource(R.string.storage_percent_used, (fraction * 100).roundToInt())
                    else -> null
                }
                Box(Modifier.size(116.dp).clearAndSetSemantics { gaugeDescription?.let { contentDescription = it } }, contentAlignment = Alignment.Center) {
                    QuotaArcGauge(fraction = fraction, modifier = Modifier.size(116.dp))
                    when {
                        failed -> MorphingShapeBadge(shape = MaterialShapes.Pentagon, size = 76.dp, container = colors.errorContainer) {
                            Text("!", style = MaterialTheme.typography.headlineMediumEmphasized, color = colors.onErrorContainer)
                        }
                        syncing -> MorphingShapeBadge(shape = MaterialShapes.Sunny, size = 76.dp, container = colors.primary, spinning = true) {
                            Text("${state.activeTransfers.size}", style = MaterialTheme.typography.titleLargeEmphasized, color = colors.onPrimary)
                        }
                        quota != null -> Text(
                            if (fraction != null) "${(fraction * 100).roundToInt()}%" else "∞",
                            style = MaterialTheme.typography.headlineMediumEmphasized,
                        )
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.storage.pinnedBytes > 0) StatChip(stringResource(R.string.storage_pinned, Formatter.formatShortFileSize(context, state.storage.pinnedBytes)))
                if (state.storage.cacheBytes > 0) StatChip(stringResource(R.string.storage_cache, Formatter.formatShortFileSize(context, state.storage.cacheBytes)))
            }
        }
    }
}

/** Open 270° gauge: primary normally, tertiary from 80% and error from 95% so a nearly full account stands out. No fraction (unlimited quota) draws the track only. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun QuotaArcGauge(fraction: Float?, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val sweepFraction by animateFloatAsState(fraction ?: 0f, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "quotaSweep")
    val track = colors.onPrimaryContainer.copy(alpha = 0.16f)
    val progress = when {
        fraction == null -> colors.primary
        fraction >= 0.95f -> colors.error
        fraction >= 0.8f -> colors.tertiary
        else -> colors.primary
    }
    Canvas(modifier) {
        val stroke = 12.dp.toPx()
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        val style = Stroke(width = stroke, cap = StrokeCap.Round)
        drawArc(track, startAngle = 135f, sweepAngle = 270f, useCenter = false, topLeft = Offset(inset, inset), size = arcSize, style = style)
        if (fraction != null && sweepFraction > 0f) {
            drawArc(progress, startAngle = 135f, sweepAngle = 270f * sweepFraction, useCenter = false, topLeft = Offset(inset, inset), size = arcSize, style = style)
        }
    }
}

@Composable
internal fun StatChip(text: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.10f)) {
        Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
    }
}
