package com.aether.nextcloud.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon

/** Writes [Morph] at [progress] into a Compose path scaled to [size]. */
internal fun Morph.toComposePath(progress: Float, size: Size, path: Path): Path {
    path.rewind()
    var first = true
    forEachCubic(progress) { c ->
        if (first) {
            path.moveTo(c.anchor0X * size.width, c.anchor0Y * size.height)
            first = false
        }
        path.cubicTo(
            c.control0X * size.width, c.control0Y * size.height,
            c.control1X * size.width, c.control1Y * size.height,
            c.anchor1X * size.width, c.anchor1Y * size.height,
        )
    }
    path.close()
    return path
}

/**
 * A [MaterialShapes][androidx.compose.material3.MaterialShapes] badge that morphs into [shape] with the theme's spatial
 * spring whenever it changes, optionally rotating slowly while [spinning] (an activity cue). [content] sits upright in the middle.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MorphingShapeBadge(
    shape: RoundedPolygon,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    container: Color = MaterialTheme.colorScheme.primary,
    spinning: Boolean = false,
    content: @Composable BoxScope.() -> Unit = {},
) {
    var current by remember { mutableStateOf(shape) }
    var morph by remember { mutableStateOf(Morph(shape, shape)) }
    val progress = remember { Animatable(1f) }
    val spec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    LaunchedEffect(shape) {
        if (shape !== current) {
            morph = Morph(current, shape)
            current = shape
            progress.snapTo(0f)
            progress.animateTo(1f, spec)
        }
    }

    val rotation = remember { Animatable(0f) }
    val settle = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    LaunchedEffect(spinning) {
        if (spinning) {
            while (true) rotation.animateTo(rotation.value + 360f, tween(14_000, easing = LinearEasing))
        } else {
            val rest = rotation.value % 360f
            rotation.snapTo(rest)
            rotation.animateTo(if (rest > 180f) 360f else 0f, settle)
            rotation.snapTo(0f)
        }
    }

    val path = remember { Path() }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().rotate(rotation.value)) {
            drawPath(morph.toComposePath(progress.value, this.size, path), container)
        }
        content()
    }
}

private val OuterCorner = 28.dp
private val InnerCorner = 6.dp

/** Corner shape for item [index] of a [count]-long connected list: large outer corners, tight inner ones. */
fun groupedShape(index: Int, count: Int): Shape {
    val top = if (index == 0) OuterCorner else InnerCorner
    val bottom = if (index == count - 1) OuterCorner else InnerCorner
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/** The asymmetric "hero" card shape: three big corners and one tight one. */
val HeroShape: Shape = RoundedCornerShape(topStart = 48.dp, topEnd = 48.dp, bottomEnd = 48.dp, bottomStart = 12.dp)

/** Mirror of [HeroShape], used to alternate rhythm between stacked hero cards. */
val HeroShapeAlt: Shape = RoundedCornerShape(topStart = 48.dp, topEnd = 12.dp, bottomEnd = 48.dp, bottomStart = 48.dp)
