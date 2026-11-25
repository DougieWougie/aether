package com.aether.nextcloud.support;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A picked folder for the folder-upload tests, speaking document-tree URIs and columns. It lives in the test APK, which runs
 * in its own process, so the tests fill it through {@code ContentResolver.call} (see {@code TreeFixtures}) and the app reads it
 * as it would another app's folder. Plain Java because that process has no Kotlin runtime. Document ids are paths below "Holiday".
 */
public class TreeFixtureProvider extends ContentProvider {
    private static final String[] DEFAULT = {
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE,
    };

    private static final class Node {
        final byte[] bytes; final String mime; final String displayName;
        Node(byte[] bytes, String mime, String displayName) { this.bytes = bytes; this.mime = mime; this.displayName = displayName; }
    }

    private static final Map<String, Node> NODES = new TreeMap<>();

    @Override public boolean onCreate() { return true; }

    @Override
    public synchronized Bundle call(String method, String arg, Bundle extras) {
        switch (method) {
            case "reset":
                NODES.clear();
                NODES.put("Holiday", new Node(null, Document.MIME_TYPE_DIR, null));
                break;
            case "clear":
                NODES.clear();
                break;
            case "folder":
                NODES.put("Holiday/" + arg, new Node(null, Document.MIME_TYPE_DIR, null));
                break;
            case "file":
                NODES.put("Holiday/" + arg, new Node(extras.getByteArray("bytes"), extras.getString("mime"), extras.getString("name")));
                break;
            default:
                return null;
        }
        return Bundle.EMPTY;
    }

    private static String documentId(Uri uri) {
        List<String> segments = uri.getPathSegments();
        return segments.get(segments.indexOf("document") + 1);
    }

    private static void row(MatrixCursor cursor, String id, Node node) {
        String name = node.displayName != null ? node.displayName : id.substring(id.lastIndexOf('/') + 1);
        cursor.newRow().add(Document.COLUMN_DOCUMENT_ID, id).add(Document.COLUMN_DISPLAY_NAME, name)
            .add(Document.COLUMN_MIME_TYPE, node.mime).add(Document.COLUMN_FLAGS, 0)
            .add(Document.COLUMN_SIZE, node.bytes == null ? null : (long) node.bytes.length);
    }

    @Override
    public synchronized Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        String id = documentId(uri);
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DEFAULT);
        List<String> segments = uri.getPathSegments();
        if (segments.get(segments.size() - 1).equals("children")) {
            for (Map.Entry<String, Node> entry : NODES.entrySet()) {
                String key = entry.getKey();
                if (key.startsWith(id + "/") && key.indexOf('/', id.length() + 1) < 0) row(cursor, key, entry.getValue());
            }
        } else if (NODES.containsKey(id)) {
            row(cursor, id, NODES.get(id));
        }
        return cursor;
    }

    @Override
    public synchronized String getType(Uri uri) {
        Node node = NODES.get(documentId(uri));
        return node == null ? null : node.mime;
    }

    @Override
    public synchronized ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        Node node = NODES.get(documentId(uri));
        if (node == null || node.bytes == null) throw new FileNotFoundException(uri.toString());
        try {
            File file = File.createTempFile("tree", ".bin", getContext().getCacheDir());
            file.deleteOnExit();
            Files.write(file.toPath(), node.bytes);
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (IOException e) {
            throw new FileNotFoundException(e.toString());
        }
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
