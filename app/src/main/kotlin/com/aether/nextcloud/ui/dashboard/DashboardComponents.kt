package com.aether.nextcloud.ui.dashboard

import android.text.format.DateUtils
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aether.nextcloud.ui.components.MorphingShapeBadge

/** "5.4 GB" -> ("5.4", "GB"); locales use a regular or non-breaking space between number and unit. */
internal fun splitSize(formatted: String): Pair<String, String> {
    val clean = formatted.replace('\u00A0', ' ').replace('\u202F', ' ')
    val at = clean.lastIndexOf(' ')
    return if (at < 0) clean to "" else clean.substring(0, at) to clean.substring(at + 1)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLargeEmphasized, modifier = Modifier.padding(start = 8.dp, top = 8.dp))
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun EmptyState(shape: androidx.graphics.shapes.RoundedPolygon, icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MorphingShapeBadge(shape, size = 112.dp, container = MaterialTheme.colorScheme.secondaryContainer) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.headlineSmallEmphasized)
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

internal fun relative(millis: Long): String =
    DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
