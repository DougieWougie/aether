package com.aether.nextcloud.support

/** Polls [condition] until it holds or [timeoutMs] passes; the provider and workers finish on background threads. */
fun awaitTrue(message: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (runCatching(condition).getOrDefault(false)) return
        Thread.sleep(50)
    }
    throw AssertionError("Timed out after ${timeoutMs}ms waiting for: $message")
}
