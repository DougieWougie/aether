package com.aether.nextcloud.support

import android.os.Looper
import org.robolectric.Shadows.shadowOf

/**
 * [AetherTestRule] for Robolectric. The test body runs on a worker thread (see the base class), so the main thread, which
 * Robolectric drives by hand, keeps running whatever the code under test posts to the main looper (view models, `Dispatchers.Main`).
 */
class RobolectricAetherRule : AetherTestRule() {
    override fun waitFor(worker: Thread) {
        val main = shadowOf(Looper.getMainLooper())
        while (worker.isAlive) {
            main.idle()
            worker.join(5)
        }
    }
}
