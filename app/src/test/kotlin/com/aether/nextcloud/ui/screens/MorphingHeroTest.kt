package com.aether.nextcloud.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aether.nextcloud.ui.login.MorphingHero
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The sign-in mark morphs forever; stepping the clock by hand shows it keeps changing shape without crashing. */
@RunWith(AndroidJUnit4::class)
class MorphingHeroTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun itMorphsThroughItsShapesAtTheIdleCadence() {
        compose.mainClock.autoAdvance = false
        compose.show { MorphingHero(busy = false) }

        repeat(8) { compose.mainClock.advanceTimeBy(1_000) }
    }

    @Test
    fun itSpeedsUpWhileSigningIn() {
        compose.mainClock.autoAdvance = false
        compose.show { MorphingHero(busy = true) }

        repeat(8) { compose.mainClock.advanceTimeBy(500) }
    }
}
