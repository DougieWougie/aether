package com.aether.nextcloud.dav.model

/** One `<d:response>` of a WebDAV multistatus, as requested by [com.aether.nextcloud.dav.WebDavClient.propfind]. */
data class DavResource(
    /** Percent-encoded path exactly as the server sent it. */
    val href: String,
    val isDirectory: Boolean,
    val fileId: String? = null,
    /** `d:getcontentlength`; absent for collections. */
    val contentLength: Long? = null,
    val contentType: String? = null,
    /** Epoch millis from `d:getlastmodified`. */
    val lastModified: Long? = null,
    /** ETag without quotes or weak prefix; for collections it changes whenever any descendant does. */
    val etag: String? = null,
    val permissions: String? = null,
    /** `oc:size`: total bytes, including descendants for collections. */
    val size: Long? = null,
    val hasPreview: Boolean = false,
    val isFavorite: Boolean = false,
    /** `oc:checksums`, each as `ALGORITHM:hex` (for example `SHA1:da39...`); empty when the server holds none. */
    val checksums: List<String> = emptyList(),
) {
    val canWrite: Boolean get() = permissions?.any { it == 'W' || it == 'C' || it == 'K' } ?: false
    val canDelete: Boolean get() = permissions?.contains('D') ?: false
    val canRename: Boolean get() = permissions?.contains('N') ?: false
    val canMove: Boolean get() = permissions?.contains('V') ?: false
}
