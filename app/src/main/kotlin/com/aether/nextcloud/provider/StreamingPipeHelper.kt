package com.aether.nextcloud.provider

import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.IOException

/**
 * Hands out [ParcelFileDescriptor]s for SAF clients. Reads point straight at the local copy; writes go to a staging
 * file and [onClosed] fires when the client closes its end, which is the moment to enqueue the upload.
 */
class StreamingPipeHelper {
    private val handler: Handler by lazy {
        Handler(HandlerThread("aether-pfd").apply { start() }.looper)
    }

    fun openForRead(file: File): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)

    /**
     * Opens [staging] in the SAF [mode] (`w`, `wt`, `wa`, `rw`, `rwt`). [onClosed] receives null for a clean close,
     * or the error if the client aborted (e.g. `closeWithError`); it runs on a background handler thread.
     */
    fun openForWrite(staging: File, mode: String, onClosed: (IOException?) -> Unit): ParcelFileDescriptor =
        ParcelFileDescriptor.open(staging, ParcelFileDescriptor.parseMode(mode), handler) { error -> onClosed(error) }

    companion object {
        fun isWriteMode(mode: String): Boolean = mode.contains('w')

        /** `rw` and `wa` keep existing bytes, so the staging file must start as a copy of the current content. */
        fun needsExistingContent(mode: String): Boolean = mode.contains('a') || (mode.contains('r') && !mode.contains('t'))

        fun truncates(mode: String): Boolean = mode.contains('t') || mode == "w"
    }
}
