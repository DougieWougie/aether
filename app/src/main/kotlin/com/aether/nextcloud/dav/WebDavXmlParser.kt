package com.aether.nextcloud.dav

import com.aether.nextcloud.dav.model.DavResource
import java.io.InputStream
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory

/** Streaming parser for `207 Multi-Status` bodies; no DOM is ever built. */
class WebDavXmlParser {

    @Throws(XmlPullParserException::class, java.io.IOException::class)
    fun parse(input: InputStream): List<DavResource> {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(input, null)
        val resources = mutableListOf<DavResource>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.isNamed(DAV, "response")) {
                readResponse(parser)?.let(resources::add)
            }
        }
        return resources
    }

    private fun readResponse(p: XmlPullParser): DavResource? {
        var href: String? = null
        val props = Props()
        p.eachChild { name, ns ->
            when {
                ns == DAV && name == "href" -> href = p.readText().trim()
                ns == DAV && name == "propstat" -> readPropstat(p, props)
                else -> p.skip()
            }
        }
        val h = href ?: return null
        return DavResource(
            href = h,
            isDirectory = props.isCollection,
            fileId = props.fileId,
            contentLength = props.contentLength,
            contentType = props.contentType,
            lastModified = props.lastModified,
            etag = props.etag,
            permissions = props.permissions,
            size = props.size,
            hasPreview = props.hasPreview,
            isFavorite = props.isFavorite,
        )
    }

    /** Only properties reported with a 2xx status are merged; `<d:status>` follows `<d:prop>` in the stream. */
    private fun readPropstat(p: XmlPullParser, into: Props) {
        val found = Props()
        var ok = true
        p.eachChild { name, ns ->
            when {
                ns == DAV && name == "prop" -> readProp(p, found)
                ns == DAV && name == "status" -> ok = isSuccess(p.readText())
                else -> p.skip()
            }
        }
        if (ok) into.merge(found)
    }

    private fun readProp(p: XmlPullParser, into: Props) {
        p.eachChild { name, ns ->
            when (ns) {
                DAV -> when (name) {
                    "resourcetype" -> p.eachChild { child, childNs ->
                        if (childNs == DAV && child == "collection") into.isCollection = true
                        p.skip()
                    }
                    "getcontentlength" -> into.contentLength = p.readText().trim().toLongOrNull()
                    "getcontenttype" -> into.contentType = p.readText().trim().ifEmpty { null }
                    "getlastmodified" -> into.lastModified = parseHttpDate(p.readText().trim())
                    "getetag" -> into.etag = cleanEtag(p.readText())
                    else -> p.skip()
                }
                OC -> when (name) {
                    "fileid" -> into.fileId = p.readText().trim().ifEmpty { null }
                    "permissions" -> into.permissions = p.readText().trim().ifEmpty { null }
                    "size" -> into.size = p.readText().trim().toLongOrNull()
                    "favorite" -> into.isFavorite = p.readText().trim() == "1"
                    else -> p.skip()
                }
                NC -> when (name) {
                    "has-preview" -> into.hasPreview = p.readText().trim().equals("true", ignoreCase = true)
                    else -> p.skip()
                }
                else -> p.skip()
            }
        }
    }

    private class Props {
        var isCollection = false
        var fileId: String? = null
        var contentLength: Long? = null
        var contentType: String? = null
        var lastModified: Long? = null
        var etag: String? = null
        var permissions: String? = null
        var size: Long? = null
        var hasPreview = false
        var isFavorite = false

        fun merge(o: Props) {
            isCollection = isCollection || o.isCollection
            fileId = o.fileId ?: fileId
            contentLength = o.contentLength ?: contentLength
            contentType = o.contentType ?: contentType
            lastModified = o.lastModified ?: lastModified
            etag = o.etag ?: etag
            permissions = o.permissions ?: permissions
            size = o.size ?: size
            hasPreview = hasPreview || o.hasPreview
            isFavorite = isFavorite || o.isFavorite
        }
    }

    companion object {
        const val DAV = "DAV:"
        const val OC = "http://owncloud.org/ns"
        const val NC = "http://nextcloud.org/ns"

        internal fun cleanEtag(raw: String): String? =
            raw.trim().removePrefix("W/").trim('"').ifEmpty { null }

        internal fun parseHttpDate(value: String): Long? = try {
            OffsetDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }

        private fun isSuccess(status: String): Boolean =
            Regex("""\s(\d{3})\b""").find(status)?.groupValues?.get(1)?.startsWith("2") ?: false
    }
}

private fun XmlPullParser.isNamed(ns: String, local: String) = namespace == ns && name == local

/**
 * Calls [block] for each direct child element. [block] must consume that child entirely
 * (via [readText], [skip], or by iterating its own children) so the parser ends on its END_TAG.
 */
private inline fun XmlPullParser.eachChild(block: (name: String, namespace: String) -> Unit) {
    require(XmlPullParser.START_TAG, null, null)
    while (true) {
        when (next()) {
            XmlPullParser.END_TAG -> return
            XmlPullParser.START_TAG -> block(name, namespace ?: "")
            XmlPullParser.END_DOCUMENT -> throw XmlPullParserException("Unexpected end of document")
        }
    }
}

/** Returns the element's own text (nested elements are skipped) and leaves the parser on its END_TAG. */
private fun XmlPullParser.readText(): String {
    val text = StringBuilder()
    var depth = 1
    while (depth > 0) {
        when (next()) {
            XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (depth == 1) text.append(getText())
            XmlPullParser.START_TAG -> depth++
            XmlPullParser.END_TAG -> depth--
            XmlPullParser.END_DOCUMENT -> throw XmlPullParserException("Unexpected end of document")
        }
    }
    return text.toString()
}

/** Skips the current element including any nested content. */
private fun XmlPullParser.skip() {
    readText()
}
