package com.aether.nextcloud.dav

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * A search over one account's files, as the system file manager asks for it. Sent to the server as a WebDAV `SEARCH`
 * ([toRequestBody]) and re-applied to the answer by [matches], so the cached rows shown while the request is in flight
 * obey the same rules as the server's.
 */
data class SearchQuery(
    /** Text the file name must contain, ignoring case. Blank matches every name (browsing by type). */
    val name: String = "",
    /** MIME types, or a family written with a trailing star (image, slash, star); empty means any. */
    val mimeTypes: List<String> = emptyList(),
    val modifiedAfter: Long? = null,
    val sizeOver: Long? = null,
    val excludeMedia: Boolean = false,
    val limit: Int = DEFAULT_LIMIT,
) {
    private val typeFilters: List<String> get() = mimeTypes.filter { it.isNotBlank() && it != "*/*" }

    /** Key for remembering the last server answer to this query. */
    val key: String get() = listOf(name.lowercase(), typeFilters.sorted(), modifiedAfter, sizeOver, excludeMedia).joinToString("|")

    fun matches(displayName: String, mimeType: String, isDirectory: Boolean, size: Long, lastModified: Long): Boolean {
        if (name.isNotBlank() && !displayName.contains(name.trim(), ignoreCase = true)) return false
        if (typeFilters.isNotEmpty() && typeFilters.none { mimeMatches(it, mimeType) }) return false
        if (excludeMedia && MEDIA_TYPES.any { mimeMatches(it, mimeType) }) return false
        if (modifiedAfter != null && lastModified <= modifiedAfter) return false
        if (sizeOver != null && (isDirectory || size <= sizeOver)) return false
        return true
    }

    /** `SEARCH` body scoped to `/files/<loginName>`; see Nextcloud's "WebDAV search" (DAV:basicsearch) documentation. */
    fun toRequestBody(loginName: String): String {
        val clauses = buildList {
            add(like("d:displayname", "%${name.trim()}%"))
            typeFilters.map { like("d:getcontenttype", it.replace("*", "%")) }.let { types ->
                if (types.size == 1) add(types.single()) else if (types.size > 1) add(group("or", types))
            }
            if (excludeMedia) add(group("not", listOf(group("or", MEDIA_TYPES.map { like("d:getcontenttype", it.replace("*", "%")) }))))
            modifiedAfter?.let { add(greaterThan("d:getlastmodified", HTTP_DATE.format(Instant.ofEpochMilli(it)))) }
            sizeOver?.let { add(greaterThan("d:getcontentlength", it.toString())) }
        }
        return """
            <?xml version="1.0" encoding="utf-8" ?>
            <d:searchrequest xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
              <d:basicsearch>
                <d:select>$SELECTED_PROPS</d:select>
                <d:from><d:scope><d:href>/files/${escapeXml(loginName)}</d:href><d:depth>infinity</d:depth></d:scope></d:from>
                <d:where>${if (clauses.size == 1) clauses.single() else group("and", clauses)}</d:where>
                <d:orderby><d:order><d:prop><d:getlastmodified/></d:prop><d:descending/></d:order></d:orderby>
                <d:limit><d:nresults>$limit</d:nresults></d:limit>
              </d:basicsearch>
            </d:searchrequest>
        """.trimIndent()
    }

    private fun like(prop: String, literal: String) = comparison("like", prop, literal)

    private fun greaterThan(prop: String, literal: String) = comparison("gt", prop, literal)

    private fun comparison(op: String, prop: String, literal: String) =
        "<d:$op><d:prop><$prop/></d:prop><d:literal>${escapeXml(literal)}</d:literal></d:$op>"

    private fun group(op: String, children: List<String>) = "<d:$op>${children.joinToString("")}</d:$op>"

    companion object {
        const val DEFAULT_LIMIT = 100

        private val MEDIA_TYPES = listOf("image/*", "video/*", "audio/*")
        private val HTTP_DATE = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC)

        /** The properties a PROPFIND listing asks for, so a search result becomes the same cache row. */
        private const val SELECTED_PROPS = """<d:prop><d:resourcetype/><d:getcontentlength/><d:getcontenttype/><d:getlastmodified/><d:getetag/><oc:fileid/><oc:permissions/><oc:size/><nc:has-preview/><oc:favorite/></d:prop>"""

        internal fun mimeMatches(pattern: String, mimeType: String): Boolean =
            if (pattern.endsWith("/*")) mimeType.startsWith(pattern.dropLast(1), ignoreCase = true) else pattern.equals(mimeType, ignoreCase = true)

        private fun escapeXml(value: String) = buildString {
            value.forEach {
                when (it) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&apos;")
                    else -> append(it)
                }
            }
        }
    }
}
