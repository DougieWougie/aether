# ADR-0011: File Search in the System File Manager via WebDAV `SEARCH`

- **Status:** Accepted
- **Date:** 2026-10-27

## Context

The system Files app shows a search box for any root that sets `Root.FLAG_SUPPORTS_SEARCH`. Aether did not, so a user could only browse folder by folder, and only folders already listed were quick. Local search over the Room cache would miss every folder the user had not opened. Nextcloud answers WebDAV `SEARCH` (`DAV:basicsearch`) over a whole account.

## Decision

1. **Roots set `FLAG_SUPPORTS_SEARCH`** and the provider implements `querySearchDocuments`: the `(rootId, query, projection)` form for Android 10, and the `Bundle` form on Android 11+, where DocumentsUI sends the typed text in `QUERY_ARG_DISPLAY_NAME` together with its file-type, date and size filters.
2. **The server does the searching.** `WebDavClient.search` sends `SEARCH /remote.php/dav/` scoped to `/files/<loginName>`, with a name `like`, optional MIME `like` clauses, and `gt` for modified date and size, newest first, limited to 100 hits. The same `SearchQuery` re-applies the filters to the answer, so the server and the cache obey one set of rules.
3. **Same non-blocking shape as listings** (ADR-0001). A query is answered at once from Room (cached rows whose name matches) with `EXTRA_LOADING` set, a background coroutine runs the `SEARCH`, stores the hits in Room, and calls `notifyChange` on the search URI. Failures surface as `EXTRA_ERROR` with the same 10 s back-off as listings.
4. **The server's answer replaces the cached guess.** The ids of the last answer to each query are kept in memory (16 queries, 60 s, the listing TTL), so a repeat query is not re-sent and a file the server no longer has is not shown from a stale cache. Rows deleted through the provider drop out of that answer because it is resolved against Room each time.
5. **Hits are stored like listing rows** (pin flag and local copy are kept), but no folder is stamped as listed, so a later listing still diffs against the server.

## Consequences

- **Positive:** Search covers the whole account without listing it first, and adds no UI to the companion app.
- **Positive:** No dependency; one more verb on the existing OkHttp client.
- **Negative:** Search needs the network; offline it returns only cached rows whose name matches. It matches file names, not file contents.
- **Negative:** Search relies on the server supporting WebDAV `SEARCH`. A server that does not answers an error, which shows as the search error.
- **Negative:** A query made with the URI alone, without DocumentsUI's arguments, is not supported on Android 11+; only the system file manager can call the provider.
