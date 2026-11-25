package com.aether.nextcloud.support

/** Polls [condition] until it holds or [timeoutMs] passes; the provider and workers finish on background threads. */
fun awaitTrue(message: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    var lastError: Throwable? = null
    while (System.currentTimeMillis() < deadline) {
        val result = runCatching(condition)
        if (result.getOrDefault(false)) return
        lastError = result.exceptionOrNull()
        Thread.sleep(10)
    }
    val detail = lastError?.let { " (last exception: $it)" }.orEmpty()
    throw AssertionError("Timed out after ${timeoutMs}ms waiting for: $message$detail", lastError)
}
