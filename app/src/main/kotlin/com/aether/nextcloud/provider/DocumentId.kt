package com.aether.nextcloud.provider

/**
 * Structured SAF document id: `<accountName>:<normalizedRemotePath>`, e.g. `alice@cloud.example.com:/Documents/notes.md`.
 * The root of an account is `<accountName>:/`. Paths never end in `/` (except the root) and never contain `.`/`..`.
 */
data class DocumentId(val accountName: String, val path: String) {
    init {
        require(accountName.isNotEmpty()) { "Empty account name" }
        require(path == normalizePath(path)) { "Path is not normalized: $path" }
    }

    val isRoot: Boolean get() = path == "/"

    /** Display name of the last path segment; empty for the root. */
    val name: String get() = if (isRoot) "" else path.substringAfterLast('/')

    val parent: DocumentId?
        get() = if (isRoot) null else DocumentId(accountName, path.substringBeforeLast('/').ifEmpty { "/" })

    fun child(childName: String): DocumentId {
        require(childName.isNotEmpty() && '/' !in childName && childName != "." && childName != "..") {
            "Invalid file name: $childName"
        }
        return DocumentId(accountName, if (isRoot) "/$childName" else "$path/$childName")
    }

    /** True if [other] lives anywhere below this document (a document is not its own descendant). */
    fun isAncestorOf(other: DocumentId): Boolean =
        accountName == other.accountName && other.path != path &&
            (isRoot || other.path.startsWith("$path/"))

    override fun toString(): String = "$accountName:$path"

    companion object {
        fun root(accountName: String) = DocumentId(accountName, "/")

        /** @throws IllegalArgumentException for ids that were not produced by this provider. */
        fun parse(documentId: String): DocumentId {
            // Account names may carry a port ("alice@host:8443"), so split on the first ":/", not the first ":".
            val split = documentId.indexOf(":/")
            require(split > 0) { "Malformed document id: $documentId" }
            return DocumentId(documentId.substring(0, split), normalizePath(documentId.substring(split + 1)))
        }

        fun parseOrNull(documentId: String?): DocumentId? =
            documentId?.let { runCatching { parse(it) }.getOrNull() }

        fun normalizePath(path: String): String {
            val segments = path.split('/').filter { it.isNotEmpty() && it != "." }
            require(".." !in segments) { "Path traversal is not allowed: $path" }
            return "/" + segments.joinToString("/")
        }
    }
}
