package com.aether.nextcloud.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle

/**
 * Material 3 Expressive type scale. The emphasized tokens (`*Emphasized`) carry the extra weight used for key
 * metrics such as storage quota, sync status and section titles; display sizes go heavier still so the hero numbers
 * read as graphic elements rather than captions.
 */
val AetherTypography: Typography = Typography().let { base ->
    base.copy(
        displayLargeEmphasized = base.displayLargeEmphasized.weight(FontWeight.ExtraBold),
        displayMediumEmphasized = base.displayMediumEmphasized.weight(FontWeight.ExtraBold),
        displaySmallEmphasized = base.displaySmallEmphasized.weight(FontWeight.ExtraBold),
        headlineLargeEmphasized = base.headlineLargeEmphasized.weight(FontWeight.Bold),
        headlineMediumEmphasized = base.headlineMediumEmphasized.weight(FontWeight.Bold),
        headlineSmallEmphasized = base.headlineSmallEmphasized.weight(FontWeight.Bold),
        titleLargeEmphasized = base.titleLargeEmphasized.weight(FontWeight.Bold),
        titleMediumEmphasized = base.titleMediumEmphasized.weight(FontWeight.Bold),
        labelLargeEmphasized = base.labelLargeEmphasized.weight(FontWeight.Bold),
    )
}

private fun TextStyle.weight(weight: FontWeight): TextStyle = copy(fontWeight = weight)
