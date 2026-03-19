package com.localshare.utils;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.webkit.MimeTypeMap;

import java.util.ArrayList;
import java.util.List;

/**
 * Scans a document-tree URI (from ACTION_OPEN_DOCUMENT_TREE) and
 * returns a flat list of all file URIs with their relative paths.
 */
public class FolderScanner {

    public static class FileEntry {
        public Uri    uri;
        public String relativePath; // e.g. "photos/beach/img001.jpg"
        public String mimeType;
        public String name;
        public long   size;

        public FileEntry(Uri uri, String relativePath, String name, String mimeType, long size) {
            this.uri          = uri;
            this.relativePath = relativePath;
            this.name         = name;
            this.mimeType     = mimeType;
            this.size         = size;
        }
    }

    /**
     * Recursively walk a tree URI and collect all file entries.
     * @param treeUri   URI returned from ACTION_OPEN_DOCUMENT_TREE
     * @param rootName  display name of the root folder
     */
    public static List<FileEntry> scanTree(Context ctx, Uri treeUri, String rootName) {
        List<FileEntry> result = new ArrayList<>();
        Uri docUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri));
        walkDir(ctx, treeUri, docUri, rootName, result);
        return result;
    }

    private static void walkDir(Context ctx, Uri treeUri, Uri dirUri,
                                String currentPath, List<FileEntry> out) {
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri, DocumentsContract.getDocumentId(dirUri));

        ContentResolver cr = ctx.getContentResolver();
        try (Cursor c = cr.query(childrenUri,
                new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE
                }, null, null, null)) {

            if (c == null) return;

            while (c.moveToNext()) {
                String docId   = c.getString(0);
                String name    = c.getString(1);
                String mime    = c.getString(2);
                long   size    = c.isNull(3) ? 0 : c.getLong(3);
                String relPath = currentPath + "/" + name;

                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    // Recurse into subfolder
                    Uri subDir = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
                    walkDir(ctx, treeUri, subDir, relPath, out);
                } else {
                    Uri fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
                    // Resolve mime from extension if needed
                    if (mime == null || mime.isEmpty() || "*/*".equals(mime)) {
                        int dot = name.lastIndexOf('.');
                        if (dot >= 0) {
                            String ext = name.substring(dot + 1).toLowerCase();
                            String resolved = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                            if (resolved != null) mime = resolved;
                        }
                    }
                    if (mime == null) mime = "application/octet-stream";
                    out.add(new FileEntry(fileUri, relPath, name, mime, size));
                }
            }
        } catch (Exception e) {
            android.util.Log.e("FolderScanner", "Walk error", e);
        }
    }
}
