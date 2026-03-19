package com.localshare.server;

import android.content.Context;
import android.util.Log;

import com.localshare.model.MediaItem;
import com.localshare.utils.FileUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lightweight multi-threaded HTTP server.
 * No external dependencies - pure Java sockets.
 *
 * API:
 *   GET  /api/list          → JSON list of files
 *   GET  /api/download?id=X → stream file
 *   POST /api/upload        → multipart upload
 *   GET  /api/ping          → {"status":"ok","room":"XXXX"}
 *   GET  /                  → web UI for browsers (bonus)
 */
public class LocalHttpServer {

    private static final String TAG = "LocalHttpServer";
    private static final int THREAD_POOL = 8; // handle 8 concurrent transfers

    private final Context ctx;
    private final int port;
    private final String roomCode;
    private final String deviceName;
    private final File storageDir;

    // In-memory index: id → MediaItem metadata
    private final ConcurrentHashMap<String, MediaItem> fileIndex = new ConcurrentHashMap<>();
    // Physical files: id → File
    private final ConcurrentHashMap<String, File> fileStore = new ConcurrentHashMap<>();

    private ServerSocket serverSocket;
    private ExecutorService pool;
    private volatile boolean running;
    private OnEventListener listener;

    public interface OnEventListener {
        void onFileUploaded(MediaItem item);
        void onClientConnected(String ip);
    }

    public LocalHttpServer(Context ctx, int port, String roomCode, String deviceName) {
        this.ctx = ctx;
        this.port = port;
        this.roomCode = roomCode;
        this.deviceName = deviceName;
        this.storageDir = FileUtils.getShareDir(ctx);
    }

    public void setListener(OnEventListener l) { this.listener = l; }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        serverSocket.setReuseAddress(true);
        pool = Executors.newFixedThreadPool(THREAD_POOL);
        running = true;

        Thread acceptThread = new Thread(() -> {
            while (running) {
                try {
                    Socket client = serverSocket.accept();
                    client.setSoTimeout(30000);
                    client.setTcpNoDelay(true); // disable Nagle for speed
                    client.setReceiveBufferSize(FileUtils.BUFFER_SIZE);
                    client.setSendBufferSize(FileUtils.BUFFER_SIZE);
                    pool.execute(() -> handleClient(client));
                } catch (IOException e) {
                    if (running) Log.e(TAG, "Accept error", e);
                }
            }
        }, "ServerAccept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        Log.i(TAG, "Server started on port " + port);
    }

    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        if (pool != null) pool.shutdownNow();
    }

    /** Add a file that was uploaded via content URI (from host device itself) */
    public MediaItem addLocalFile(InputStream in, String name, long size, String mime, String uploader) {
        String id = FileUtils.generateId();
        File dest = new File(storageDir, id + "_" + sanitize(name));
        try (FileOutputStream fos = new FileOutputStream(dest)) {
            FileUtils.copy(in, fos);
        } catch (IOException e) {
            Log.e(TAG, "Failed to store local file", e);
            return null;
        }
        MediaItem item = new MediaItem(id, name, dest.length(), mime, uploader);
        fileIndex.put(id, item);
        fileStore.put(id, dest);
        if (listener != null) listener.onFileUploaded(item);
        return item;
    }

    public List<MediaItem> getFileList() {
        List<MediaItem> list = new ArrayList<>(fileIndex.values());
        Collections.sort(list, (a, b) -> Long.compare(b.getUploadTime(), a.getUploadTime()));
        return list;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HTTP handling
    // ─────────────────────────────────────────────────────────────────────────

    private void handleClient(Socket socket) {
        try {
            HttpRequest req = HttpRequest.parse(socket.getInputStream());
            if (req == null) { socket.close(); return; }

            String clientIp = socket.getInetAddress().getHostAddress();
            if (listener != null && req.path.startsWith("/api/")) {
                listener.onClientConnected(clientIp);
            }

            HttpResponse res = route(req, clientIp);
            res.write(socket.getOutputStream());
        } catch (Exception e) {
            Log.w(TAG, "Client error: " + e.getMessage());
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private HttpResponse route(HttpRequest req, String clientIp) {
        switch (req.method + " " + req.path.split("\\?")[0]) {
            case "GET /api/ping":
                return jsonResponse("{\"status\":\"ok\",\"room\":\"" + roomCode
                        + "\",\"host\":\"" + deviceName + "\"}");

            case "GET /api/list":
                return listFiles();

            case "GET /api/download":
                return downloadFile(req.queryParam("id"));

            case "POST /api/upload":
                return receiveUpload(req, clientIp);

            case "GET /":
                return webUi();

            default:
                return HttpResponse.text(404, "Not Found");
        }
    }

    private HttpResponse listFiles() {
        try {
            JSONArray arr = new JSONArray();
            for (MediaItem item : getFileList()) {
                JSONObject o = new JSONObject();
                o.put("id", item.getId());
                o.put("name", item.getName());
                o.put("size", item.getSize());
                o.put("mime", item.getMimeType());
                o.put("type", item.getType());
                o.put("uploader", item.getUploader());
                o.put("time", item.getUploadTime());
                arr.put(o);
            }
            return jsonResponse(arr.toString());
        } catch (Exception e) {
            return HttpResponse.text(500, "Error");
        }
    }

    private HttpResponse downloadFile(String id) {
        if (id == null || !fileStore.containsKey(id))
            return HttpResponse.text(404, "File not found");
        File f = fileStore.get(id);
        MediaItem item = fileIndex.get(id);
        return HttpResponse.file(f, item.getMimeType(), item.getName());
    }

    private HttpResponse receiveUpload(HttpRequest req, String clientIp) {
        // Parse multipart form data
        try {
            String uploader = req.header("X-Device-Name");
            if (uploader == null) uploader = clientIp;

            // Content-Type: multipart/form-data; boundary=----...
            String ct = req.header("Content-Type");
            if (ct == null || !ct.contains("multipart/form-data"))
                return HttpResponse.text(400, "Expected multipart");

            String boundary = ct.substring(ct.indexOf("boundary=") + 9).trim();
            MultipartParser mp = new MultipartParser(req.body, boundary);
            MultipartParser.Part part = mp.nextPart();

            if (part == null) return HttpResponse.text(400, "No file in request");

            String id = FileUtils.generateId();
            File dest = new File(storageDir, id + "_" + sanitize(part.filename));

            try (FileOutputStream fos = new FileOutputStream(dest)) {
                fos.write(part.data);
            }

            MediaItem item = new MediaItem(id, part.filename, dest.length(), part.contentType, uploader);
            fileIndex.put(id, item);
            fileStore.put(id, dest);

            if (listener != null) listener.onFileUploaded(item);

            JSONObject resp = new JSONObject();
            resp.put("id", id);
            resp.put("name", part.filename);
            resp.put("size", dest.length());
            return jsonResponse(resp.toString());

        } catch (Exception e) {
            Log.e(TAG, "Upload error", e);
            return HttpResponse.text(500, "Upload failed: " + e.getMessage());
        }
    }

    /** Minimal web UI served to browser users */
    private HttpResponse webUi() {
        String html = "<!DOCTYPE html><html><head><meta charset='utf-8'>" +
                "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
                "<title>LocalShare - Room " + roomCode + "</title>" +
                "<style>*{box-sizing:border-box;margin:0;padding:0}" +
                "body{font-family:monospace;background:#111;color:#eee;padding:16px}" +
                "h1{color:#0f0;margin-bottom:12px}table{width:100%;border-collapse:collapse}" +
                "th,td{padding:8px;border-bottom:1px solid #333;text-align:left}" +
                "a{color:#0af}button{background:#0a0;color:#fff;border:0;padding:8px 16px;" +
                "cursor:pointer;margin-top:12px;font-family:monospace}" +
                "input[type=file]{color:#eee}</style></head><body>" +
                "<h1>&#128225; LocalShare / Room: " + roomCode + "</h1>" +
                "<p>Host: " + deviceName + "</p><br>" +
                "<input type='file' multiple id='f'>" +
                "<button onclick='upload()'>Upload</button><br><br>" +
                "<table><thead><tr><th>Name</th><th>Size</th><th>By</th><th></th></tr></thead>" +
                "<tbody id='list'></tbody></table>" +
                "<script>" +
                "async function load(){const r=await fetch('/api/list');const d=await r.json();" +
                "const t=document.getElementById('list');t.innerHTML='';" +
                "d.forEach(f=>{const tr=document.createElement('tr');" +
                "tr.innerHTML=`<td>${f.name}</td><td>${fmt(f.size)}</td><td>${f.uploader}</td>" +
                "<td><a href='/api/download?id=${f.id}'>&#11015;</a></td>`;" +
                "t.appendChild(tr);});}" +
                "function fmt(b){if(b<1024)return b+'B';if(b<1048576)return(b/1024).toFixed(1)+'KB';" +
                "return(b/1048576).toFixed(1)+'MB';}" +
                "async function upload(){const inp=document.getElementById('f');" +
                "for(const file of inp.files){const fd=new FormData();" +
                "fd.append('file',file);await fetch('/api/upload',{method:'POST',body:fd});}" +
                "load();}" +
                "load();setInterval(load,3000);" +
                "</script></body></html>";
        return HttpResponse.html(html);
    }

    private static String sanitize(String name) {
        if (name == null) return "file";
        return name.replaceAll("[^a-zA-Z0-9._\\-]", "_");
    }

    private static HttpResponse jsonResponse(String json) {
        return new HttpResponse(200, "application/json", json.getBytes());
    }
}
