package com.localshare.client;

import android.util.Log;

import com.localshare.model.MediaItem;
import com.localshare.utils.FileUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Client for connecting to a LocalShare room (hosted by another device).
 * All calls are synchronous — run on background threads.
 */
public class ApiClient {

    private static final String TAG = "ApiClient";
    private static final int CONNECT_TIMEOUT = 5000;
    private static final int READ_TIMEOUT = 60000;

    private final String baseUrl;

    public ApiClient(String hostIp, int port) {
        this.baseUrl = "http://" + hostIp + ":" + port;
    }

    /** Returns room code if reachable, null otherwise */
    public String ping() {
        try {
            HttpURLConnection conn = open("/api/ping");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            if (conn.getResponseCode() == 200) {
                String body = readString(conn.getInputStream());
                JSONObject o = new JSONObject(body);
                return o.optString("room", null);
            }
        } catch (Exception e) {
            Log.w(TAG, "Ping failed: " + e.getMessage());
        }
        return null;
    }

    public List<MediaItem> listFiles() throws Exception {
        HttpURLConnection conn = open("/api/list");
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        String body = readString(conn.getInputStream());
        JSONArray arr = new JSONArray(body);
        List<MediaItem> items = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            MediaItem item = new MediaItem(
                    o.getString("id"),
                    o.getString("name"),
                    o.getLong("size"),
                    o.optString("mime", "application/octet-stream"),
                    o.optString("uploader", "unknown")
            );
            item.setUploadTime(o.optLong("time", 0));
            items.add(item);
        }
        return items;
    }

    /**
     * Download file and save to destFile.
     * @param progressCallback called with bytes received so far, total
     */
    public void downloadFile(String id, File destFile, ProgressCallback progressCallback) throws Exception {
        HttpURLConnection conn = open("/api/download?id=" + id);
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setRequestProperty("Connection", "keep-alive");

        // Increase buffer for maximum throughput
        conn.setReadTimeout(READ_TIMEOUT);
        long total = conn.getContentLengthLong();

        try (InputStream in = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(destFile)) {
            byte[] buf = new byte[256 * 1024];
            long received = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                received += n;
                if (progressCallback != null) progressCallback.onProgress(received, total);
            }
        }
    }

    /**
     * Upload file bytes as multipart/form-data.
     */
    public String uploadFile(InputStream fileStream, String filename, String mimeType,
                             long fileSize, String deviceName, ProgressCallback cb) throws Exception {
        String boundary = "----LocalShare" + System.currentTimeMillis();
        URL url = new URL(baseUrl + "/api/upload");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setRequestProperty("X-Device-Name", deviceName);
        conn.setChunkedStreamingMode(256 * 1024);

        String partHeader = "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n" +
                "Content-Type: " + mimeType + "\r\n\r\n";
        String partFooter = "\r\n--" + boundary + "--\r\n";

        try (OutputStream out = conn.getOutputStream()) {
            out.write(partHeader.getBytes());
            byte[] buf = new byte[256 * 1024];
            long sent = 0;
            int n;
            while ((n = fileStream.read(buf)) != -1) {
                out.write(buf, 0, n);
                sent += n;
                if (cb != null) cb.onProgress(sent, fileSize);
            }
            out.write(partFooter.getBytes());
            out.flush();
        }

        int code = conn.getResponseCode();
        if (code != 200) throw new Exception("Upload failed: HTTP " + code);
        return readString(conn.getInputStream());
    }

    private HttpURLConnection open(String path) throws Exception {
        URL url = new URL(baseUrl + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("Accept", "*/*");
        return conn;
    }

    private String readString(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        int n;
        while ((n = in.read(tmp)) != -1) buf.write(tmp, 0, n);
        return buf.toString("UTF-8");
    }

    public interface ProgressCallback {
        void onProgress(long done, long total);
    }
}
