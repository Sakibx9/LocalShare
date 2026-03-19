package com.localshare.utils;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

public class FileUtils {

    public static final int BUFFER_SIZE = 256 * 1024; // 256KB buffer for speed

    public static String getFileName(Context ctx, Uri uri) {
        String name = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor c = ctx.getContentResolver().query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) name = c.getString(idx);
                }
            }
        }
        if (name == null) {
            name = uri.getLastPathSegment();
            if (name == null) name = "file_" + System.currentTimeMillis();
        }
        return name;
    }

    public static long getFileSize(Context ctx, Uri uri) {
        if ("content".equals(uri.getScheme())) {
            try (Cursor c = ctx.getContentResolver().query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int idx = c.getColumnIndex(OpenableColumns.SIZE);
                    if (idx >= 0) return c.getLong(idx);
                }
            }
        }
        try {
            return new File(uri.getPath()).length();
        } catch (Exception e) { return 0; }
    }

    public static String getMimeType(Context ctx, Uri uri) {
        ContentResolver cr = ctx.getContentResolver();
        String mime = cr.getType(uri);
        if (mime == null) {
            String ext = MimeTypeMap.getFileExtensionFromUrl(uri.toString());
            if (ext != null) mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase());
        }
        return mime != null ? mime : "application/octet-stream";
    }

    public static String generateId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /** Fast stream copy with large buffer */
    public static long copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[BUFFER_SIZE];
        long total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            total += n;
        }
        return total;
    }

    public static File getShareDir(Context ctx) {
        File dir = new File(ctx.getExternalFilesDir(null), "LocalShare");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static String extensionForMime(String mime) {
        if (mime == null) return "";
        return MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) != null
                ? "." + MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
                : "";
    }
}
