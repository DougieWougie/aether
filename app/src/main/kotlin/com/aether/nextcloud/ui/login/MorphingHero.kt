package com.aether.nextcloud.ui.login

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import com.aether.nextcloud.R
import androidx.graphics.shapes.Morph
import com.aether.nextcloud.ui.components.toComposePath
import androidx.graphics.shapes.RoundedPolygon
import kotlinx.coroutines.delay

/**
 * Hero blob that continuously morphs between Material Expressive shapes with spring motion, slowly rotating.
 * With [busy] the cadence speeds up, doubling as an activity cue while sign-in is in flight.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MorphingHero(
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
    busy: Boolean = false,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    val shapes: List<RoundedPolygon> = remember {
        listOf(MaterialShapes.Cookie9Sided, MaterialShapes.Sunny, MaterialShapes.Clover4Leaf, MaterialShapes.Pentagon, MaterialShapes.Pill)
    }
    val morphs = remember(shapes) { shapes.indices.map { Morph(shapes[it], shapes[(it + 1) % shapes.size]) } }
    var index by remember { mutableIntStateOf(0) }
    val progress = remember { Animatable(0f) }
    val spec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()

    LaunchedEffect(busy) {
        while (true) {
            delay(if (busy) 250 else 1200)
            progress.animateTo(1f, spec)
            progress.snapTo(0f)
            index = (index + 1) % morphs.size
        }
    }

    val rotation by rememberInfiniteTransition(label = "hero-rotation").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(if (busy) 12_000 else 40_000, easing = LinearEasing), RepeatMode.Restart),
        label = "rotation",
    )

    val container = containerColor
    val onContainer = contentColor
    val path = remember { Path() }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().rotate(rotation)) {
            drawPath(morphs[index].toComposePath(progress.value, this.size, path), container)
        }
        // The launcher mark's themed layer, so the sign-in screen shows the same logo as the home screen.
        Icon(painterResource(R.drawable.ic_launcher_monochrome), contentDescription = null, tint = onContainer, modifier = Modifier.size(size * 0.75f))
    }
}
