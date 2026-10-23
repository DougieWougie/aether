package com.aether.nextcloud.ui.backup

import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aether.nextcloud.data.BackupStats
import com.aether.nextcloud.data.local.entity.BackedUpMediaEntity

/** Loads a thumbnail off the main thread; null means neither the phone nor the server could provide one. */
typealias ThumbnailLoader = suspend (BackedUpMediaEntity) -> ImageBitmap?

@Composable
fun UploadThumbnail(item: BackedUpMediaEntity, load: ThumbnailLoader, size: Dp, modifier: Modifier = Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, item.accountName, item.mediaId, item.isVideo) { value = load(item) }
    val colors = MaterialTheme.colorScheme
    Box(modifier.size(size).clip(RoundedCornerShape(18.dp)).background(colors.onTertiaryContainer.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            ?: Icon(if (item.isVideo) Icons.Outlined.Videocam else Icons.Outlined.PhotoCamera, contentDescription = null, tint = colors.onTertiaryContainer.copy(alpha = 0.5f))
        if (item.isVideo && bitmap != null) {
            Icon(
                Icons.Outlined.Videocam,
                contentDescription = "Video",
                tint = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).size(18.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecentUploads(items: List<BackedUpMediaEntity>, load: ThumbnailLoader, thumbSize: Dp, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { UploadThumbnail(it, load, thumbSize) }
    }
}

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
        Legend(photoColor, "${stats.photoCount} ${if (stats.photoCount == 1) "photo" else "photos"} · ${Formatter.formatShortFileSize(context, stats.photoBytes)}")
        if (stats.videoCount > 0) {
            Legend(videoColor, "${stats.videoCount} ${if (stats.videoCount == 1) "video" else "videos"} · ${Formatter.formatShortFileSize(context, stats.videoBytes)}")
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
