package com.aether.nextcloud.ui.backup

import com.aether.nextcloud.R
import androidx.compose.ui.res.pluralStringResource
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.aether.nextcloud.data.BackupStats

/** Photos against videos as uploaded from this phone, with a legend so the split never relies on colour alone. */
@Composable
fun MediaSplit(stats: BackupStats, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val photoColor = colors.tertiary
    val videoColor = colors.onTertiaryContainer.copy(alpha = 0.35f)
    val total = stats.localBytes.coerceAtLeast(1L).toFloat()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(colors.onTertiaryContainer.copy(alpha = 0.12f))) {
            val photoShare = stats.photoBytes / total
            val videoShare = stats.videoBytes / total
            if (photoShare > 0f) Box(Modifier.weight(photoShare).fillMaxHeight().background(photoColor))
            if (videoShare > 0f) Box(Modifier.weight(videoShare).fillMaxHeight().background(videoColor))
            if (photoShare + videoShare < 1f) Box(Modifier.weight((1f - photoShare - videoShare).coerceAtLeast(0.0001f)))
        }
        Legend(photoColor, pluralStringResource(R.plurals.backup_photos_legend, stats.photoCount, stats.photoCount, Formatter.formatShortFileSize(context, stats.photoBytes)))
        if (stats.videoCount > 0) {
            Legend(videoColor, pluralStringResource(R.plurals.backup_videos_legend, stats.videoCount, stats.videoCount, Formatter.formatShortFileSize(context, stats.videoBytes)))
        }
    }
}

@Composable
private fun Legend(swatch: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(swatch))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Opens the phone's default gallery app, so the photos being backed up are one tap away. */
fun openPhotos(context: Context) {
    val intent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.camera_no_gallery, Toast.LENGTH_SHORT).show()
    }
}
