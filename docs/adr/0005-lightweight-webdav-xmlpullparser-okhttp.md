# ADR-0005: Lightweight WebDAV Stack (`OkHttp` + Android `XmlPullParser`)

- **Status:** Accepted
- **Date:** 2026-10-20

## Context

Nextcloud's primary file manipulation API is **WebDAV** (`/remote.php/dav/files/<user>/`), supplemented by small JSON/OCS endpoints (`/index.php/login/v2`, `/status.php`, `/ocs/v2.php/cloud/user`).

Many third-party WebDAV libraries (`sardine`, `nextcloud-android-library`) pull in outdated Apache HttpClient forks, SimpleXML, or heavy transitive dependencies that duplicate functionality already present in the Android framework.

## Decision

We will build a lean, coroutine-native WebDAV client directly on top of **`OkHttp`** and Android's built-in **`android.util.Xml` / `org.xmlpull.v1.XmlPullParser`**, paired with **`kotlinx.serialization.json`** for Login v2 and OCS JSON responses.

### Supported WebDAV Operations:
1. **`PROPFIND` (`Depth: 0` and `Depth: 1`):**
   - Requests namespaces `DAV:` (`d`) and `http://owncloud.org/ns` (`oc`) / `http://nextcloud.org/ns` (`nc`).
   - Properties requested: `d:resourcetypes`, `d:getcontentlength`, `d:getcontenttype`, `d:getlastmodified`, `d:getetag`, `oc:fileid`, `oc:permissions`, `oc:size`, `nc:has-preview`, `oc:favorite`.
   - Parsed via streaming `XmlPullParser` directly into domain `DavResource` models without DOM memory overhead.
2. **`GET` / `PUT` / `DELETE` / `MKCOL` / `MOVE` / `COPY`:**
   - Standard HTTP verbs via `OkHttpClient` with custom `Request.Builder().method("PROPFIND", body)` / `.method("MKCOL", null)` / `.method("MOVE", null)` (with `Destination` and `Overwrite` headers).
3. **Nextcloud Chunked Upload v2 (for large files > 10 MB):**
   - `MKCOL /remote.php/dav/uploads/<user>/<transfer-uuid>`
   - `PUT /remote.php/dav/uploads/<user>/<transfer-uuid>/<chunk-index>` (with `OC-Total-Length` header)
   - `MOVE /remote.php/dav/uploads/<user>/<transfer-uuid>/.file` -> `Destination: /remote.php/dav/files/<user>/<target-path>`
4. **Thumbnails / Previews:**
   - `GET /index.php/core/preview?fileId=<oc:fileid>&x=256&y=256&a=1` for `DocumentsProvider.openDocumentThumbnail()`.

## Rationale

- **Leverages Built-in Android Capabilities:** `org.xmlpull.v1.XmlPullParser` is compiled into the Android OS runtime (`bootclasspath`), fast, zero-dependency, and streaming.
- **Full Control Over Streaming & Progress:** Direct `OkHttp` `RequestBody` and `ResponseBody` wrappers allow accurate byte-level progress callbacks for notification bars and `ParcelFileDescriptor` streaming.
- **Zero Legacy Transitive Bloat:** Avoids JAXB, DOM4J, or legacy Apache HTTP clients.

## Consequences

- **Positive:** Tiny APK size, first-class Kotlin Coroutines cancellation support, easy unit testing with `MockWebServer`.
- **Negative / Mitigation:** Requires writing a well-tested `WebDavXmlParser` class. Because `XmlPullParser` is part of the Android SDK stub in unit tests, we can either use `XmlPullParserFactory.newInstance()` with `kxml2` in pure JVM unit tests or run Robolectric tests against `MockWebServer`.

## Implementation notes

- **Redirects.** All requests share one client (`newAetherHttpClient`) with `followSslRedirects(false)`, so a server cannot move a request between `http` and `https`: such a redirect is returned as a failed response (`AetherError.Http(3xx)`) and the request is not repeated. Redirects within the same scheme are still followed, and OkHttp drops the `Authorization` header when the target is a different origin, so the app password is not sent to another host. Both behaviours are covered by `HttpClientTest`.
