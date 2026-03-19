package com.localshare.server;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import com.localshare.model.FolderItem;
import com.localshare.model.MediaItem;
import com.localshare.model.RoomConfig;
import com.localshare.model.RoomMember;
import com.localshare.utils.FileUtils;
import com.localshare.utils.ThumbnailUtils;
import com.localshare.utils.ZipUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * v5 — adds folder sharing:
 *
 *   GET  /api/folders              → list of shared folders
 *   GET  /api/folder?id=X         → list files inside folder
 *   GET  /api/folder-zip?id=X     → download entire folder as ZIP
 *   POST /api/upload-folder        → multipart ZIP upload (auto-extracted into folder)
 */
public class LocalHttpServer {

    private static final String TAG = "LocalHttpServer";
    private static final int THREAD_POOL = 12;

    private final Context ctx;
    private final int port;
    private final String roomCode;
    private final String deviceName;
    private final File storageDir;
    private String roomPassword;
    private volatile boolean roomClosed = false;

    // Files (individual)
    private final ConcurrentHashMap<String, MediaItem>  fileIndex  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, File>       fileStore  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, byte[]>     thumbCache = new ConcurrentHashMap<>();

    // Folders
    private final ConcurrentHashMap<String, FolderItem> folderIndex = new ConcurrentHashMap<>();
    // folderId → physical folder on disk
    private final ConcurrentHashMap<String, File>       folderStore = new ConcurrentHashMap<>();

    // Members
    private final ConcurrentHashMap<String, RoomMember> members = new ConcurrentHashMap<>();
    private final Set<String> blockedIps = ConcurrentHashMap.newKeySet();
    private final Set<String> kickedIps  = ConcurrentHashMap.newKeySet();

    private RoomConfig config = new RoomConfig();

    private ServerSocket serverSocket;
    private ExecutorService pool;
    private volatile boolean running;
    private OnEventListener listener;

    public interface OnEventListener {
        void onFileUploaded(MediaItem item);
        void onFileDeleted(String id);
        void onFolderUploaded(FolderItem folder);
        void onFolderDeleted(String id);
        void onMemberJoined(RoomMember member);
        void onMemberLeft(String ip);
        void onMemberKicked(String ip, String name, boolean blocked);
        void onConfigChanged(RoomConfig cfg);
        void onRoomEnded();
    }

    public LocalHttpServer(Context ctx, int port, String roomCode,
                           String deviceName, String password, RoomConfig config) {
        this.ctx        = ctx;
        this.port       = port;
        this.roomCode   = roomCode;
        this.deviceName = deviceName;
        this.roomPassword = password;
        this.config     = config != null ? config : new RoomConfig();
        this.storageDir = FileUtils.getShareDir(ctx);
        members.put("host", new RoomMember("host", deviceName, true));
    }

    public void setListener(OnEventListener l) { this.listener = l; }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        serverSocket.setReuseAddress(true);
        pool = Executors.newFixedThreadPool(THREAD_POOL);
        running = true;
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    Socket client = serverSocket.accept();
                    String ip = client.getInetAddress().getHostAddress();
                    if (blockedIps.contains(ip)) { try{client.close();}catch(Exception ignored){} continue; }
                    client.setSoTimeout(60000);
                    client.setTcpNoDelay(true);
                    client.setReceiveBufferSize(512 * 1024);
                    client.setSendBufferSize(512 * 1024);
                    pool.execute(() -> handleClient(client));
                } catch (IOException e) { if (running) Log.e(TAG, "Accept", e); }
            }
        }, "ServerAccept");
        t.setDaemon(true);
        t.start();
        Log.i(TAG, "Server v5 started port=" + port);
    }

    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        if (pool != null) pool.shutdownNow();
    }

    // ── Public management ──────────────────────────────────────────────────────

    public void endRoom() { roomClosed = true; if (listener!=null) listener.onRoomEnded(); }

    public boolean kickMember(String ip, boolean block) {
        RoomMember m = members.remove(ip); kickedIps.add(ip);
        if (block) blockedIps.add(ip);
        if (m!=null && listener!=null) listener.onMemberKicked(ip, m.getDeviceName(), block);
        return m != null;
    }

    public boolean unblockIp(String ip) { blockedIps.remove(ip); kickedIps.remove(ip); return true; }
    public void updateConfig(RoomConfig c) { this.config=c; if(listener!=null) listener.onConfigChanged(c); }
    public RoomConfig getConfig()          { return config; }
    public List<String> getBlockedIps()   { return new ArrayList<>(blockedIps); }
    public List<RoomMember> getMemberList() { return new ArrayList<>(members.values()); }

    // ── File operations ────────────────────────────────────────────────────────

    public MediaItem addLocalFile(InputStream in, String name, long size, String mime, String uploader) {
        String id = FileUtils.generateId();
        File dest = new File(storageDir, id + "_" + sanitize(name));
        try (FileOutputStream fos = new FileOutputStream(dest)) { FileUtils.copy(in, fos); }
        catch (IOException e) { Log.e(TAG,"Store failed",e); return null; }
        MediaItem item = new MediaItem(id, name, dest.length(), mime, uploader);
        item.setLocalPath(dest.getAbsolutePath());
        fileIndex.put(id,item); fileStore.put(id,dest);
        trackUpload(uploader);
        if (listener!=null) listener.onFileUploaded(item);
        return item;
    }

    public boolean deleteFile(String id) {
        File f=fileStore.remove(id); fileIndex.remove(id); thumbCache.remove(id);
        if (f!=null&&f.exists()) f.delete();
        if (listener!=null) listener.onFileDeleted(id);
        return f != null;
    }

    /** Add an already-extracted folder to the index */
    public FolderItem addLocalFolder(String folderName, File physicalDir, String uploader) {
        String id = FileUtils.generateId();
        FolderItem folder = new FolderItem(id, folderName, uploader);
        // Walk physicalDir, register all files
        List<File> files = new ArrayList<>();
        ZipUtils.collectFiles(physicalDir, files);
        for (File f : files) {
            String relPath = f.getAbsolutePath().substring(physicalDir.getAbsolutePath().length());
            if (relPath.startsWith("/")) relPath = relPath.substring(1);
            String fileId = FileUtils.generateId();
            String mime   = guessMime(f.getName());
            MediaItem item = new MediaItem(fileId, relPath, f.length(), mime, uploader);
            item.setLocalPath(f.getAbsolutePath());
            fileIndex.put(fileId, item);
            fileStore.put(fileId, f);
            folder.addFile(fileId, f.length());
        }
        folderIndex.put(id, folder);
        folderStore.put(id, physicalDir);
        trackUpload(uploader);
        if (listener!=null) listener.onFolderUploaded(folder);
        return folder;
    }

    public boolean deleteFolder(String id) {
        FolderItem folder = folderIndex.remove(id);
        File dir = folderStore.remove(id);
        if (folder != null) {
            for (String fid : folder.getFileIds()) {
                fileIndex.remove(fid); fileStore.remove(fid); thumbCache.remove(fid);
            }
        }
        if (dir != null) deleteDir(dir);
        if (listener!=null) listener.onFolderDeleted(id);
        return folder != null;
    }

    public List<MediaItem>  getFileList()   { List<MediaItem> l=new ArrayList<>(fileIndex.values()); Collections.sort(l,(a,b)->Long.compare(b.getUploadTime(),a.getUploadTime())); return l; }
    public List<FolderItem> getFolderList() { List<FolderItem> l=new ArrayList<>(folderIndex.values()); Collections.sort(l,(a,b)->Long.compare(b.getUploadTime(),a.getUploadTime())); return l; }

    public boolean isProtected() { return roomPassword!=null&&!roomPassword.isEmpty(); }
    public boolean isClosed()    { return roomClosed; }

    // ── HTTP routing ───────────────────────────────────────────────────────────

    private void handleClient(Socket socket) {
        try {
            String clientIp = socket.getInetAddress().getHostAddress();
            HttpRequest req = HttpRequest.parse(socket.getInputStream());
            if (req==null) { socket.close(); return; }
            HttpResponse res = route(req, clientIp);
            res.write(socket.getOutputStream());
        } catch (Exception e) { Log.w(TAG,"Client: "+e.getMessage()); }
        finally { try{socket.close();}catch(IOException ignored){} }
    }

    private HttpResponse route(HttpRequest req, String clientIp) {
        String path = req.path.split("\\?")[0];

        // Always-public
        if ("GET".equals(req.method)  && "/api/ping".equals(path))        return handlePing(clientIp);
        if ("GET".equals(req.method)  && "/api/room-status".equals(path)) return handleRoomStatus();
        if ("POST".equals(req.method) && "/api/join".equals(path))        return handleJoin(req, clientIp);
        if ("POST".equals(req.method) && "/api/leave".equals(path))       return handleLeave(clientIp);

        if (kickedIps.contains(clientIp)) return HttpResponse.text(403,"Removed from room.");

        // Auth
        if (isProtected()) {
            String pw=req.header("X-Room-Password"); if(pw==null) pw=req.queryParam("pw");
            if (!roomPassword.equals(pw)) return HttpResponse.text(401,"Wrong password");
        }

        boolean isHostReq = "127.0.0.1".equals(clientIp)||"::1".equals(clientIp)||"true".equals(req.header("X-Host-Request"));

        switch (req.method + " " + path) {
            case "GET /api/list":           return handleList();
            case "GET /api/download":       return handleDownload(req.queryParam("id"));
            case "GET /api/thumb":          return handleThumb(req.queryParam("id"));
            case "POST /api/upload":        return handleUpload(req, clientIp);
            case "DELETE /api/delete":      return handleDelete(req.queryParam("id"));
            case "GET /api/members":        return handleMembers();
            case "GET /api/folders":        return handleFolderList();
            case "GET /api/folder":         return handleFolderFiles(req.queryParam("id"));
            case "GET /api/folder-zip":     return handleFolderZip(req.queryParam("id"));
            case "POST /api/upload-folder": return handleUploadFolder(req, clientIp);
            case "DELETE /api/delete-folder": return isHostReq ? handleDeleteFolder(req.queryParam("id")) : HttpResponse.text(403,"Host only");
            case "GET /api/config":         return handleGetConfig();
            case "POST /api/config":        return isHostReq ? handleSetConfig(req) : HttpResponse.text(403,"Host only");
            case "POST /api/kick":          return isHostReq ? handleKick(req.queryParam("ip"),false) : HttpResponse.text(403,"Host only");
            case "POST /api/block":         return isHostReq ? handleKick(req.queryParam("ip"),true) : HttpResponse.text(403,"Host only");
            case "GET /api/blocked":        return isHostReq ? handleGetBlocked() : HttpResponse.text(403,"Host only");
            case "POST /api/unblock":       return isHostReq ? handleUnblock(req.queryParam("ip")) : HttpResponse.text(403,"Host only");
            case "POST /api/end-room":      return isHostReq ? handleEndRoom() : HttpResponse.text(403,"Host only");
            case "GET /":                   return handleWebUi();
            default: return HttpResponse.text(404,"Not found");
        }
    }

    // ── Handlers ──────────────────────────────────────────────────────────────

    private HttpResponse handlePing(String clientIp) {
        try {
            JSONObject o=new JSONObject();
            o.put("room",roomCode); o.put("host",deviceName); o.put("protected",isProtected());
            o.put("closed",roomClosed); o.put("memberCount",members.size());
            o.put("maxMembers",config.getMaxMembers()); o.put("kicked",kickedIps.contains(clientIp));
            o.put("version",5);
            return jsonResp(o.toString());
        } catch (Exception e) { return HttpResponse.text(500,"Error"); }
    }

    private HttpResponse handleRoomStatus() {
        try { JSONObject o=new JSONObject(); o.put("closed",roomClosed); o.put("room",roomCode); return jsonResp(o.toString()); }
        catch (Exception e) { return HttpResponse.text(500,"Error"); }
    }

    private HttpResponse handleJoin(HttpRequest req, String clientIp) {
        if (blockedIps.contains(clientIp)) return HttpResponse.text(403,"Banned.");
        if (kickedIps.contains(clientIp))  return HttpResponse.text(403,"Removed.");
        if (config.getMaxMembers()>0 && members.size()>=config.getMaxMembers()+1)
            return HttpResponse.text(403,"Room full ("+config.getMaxMembers()+" max).");
        try {
            String name="Unknown";
            if (req.body!=null&&req.body.length>0) {
                JSONObject body=new JSONObject(new String(req.body));
                name=body.optString("deviceName",clientIp);
            }
            if (!members.containsKey(clientIp)) {
                RoomMember m=new RoomMember(clientIp,name,false); members.put(clientIp,m);
                if (listener!=null) listener.onMemberJoined(m);
            }
            JSONObject o=new JSONObject(); o.put("ok",true); o.put("memberCount",members.size());
            return jsonResp(o.toString());
        } catch (Exception e) { return HttpResponse.text(500,"Error"); }
    }

    private HttpResponse handleLeave(String clientIp) {
        members.remove(clientIp);
        if (listener!=null) listener.onMemberLeft(clientIp);
        return jsonResp("{\"ok\":true}");
    }

    private HttpResponse handleList() {
        try {
            JSONArray arr=new JSONArray();
            for (MediaItem item : getFileList()) {
                // Only return files NOT inside folders
                boolean inFolder = false;
                for (FolderItem f : folderIndex.values()) {
                    if (f.getFileIds().contains(item.getId())) { inFolder=true; break; }
                }
                if (inFolder) continue;
                JSONObject o=new JSONObject();
                o.put("id",item.getId()); o.put("name",item.getName());
                o.put("size",item.getSize()); o.put("mime",item.getMimeType());
                o.put("type",item.getType()); o.put("uploader",item.getUploader());
                o.put("time",item.getUploadTime());
                o.put("hasThumb",item.getType()==MediaItem.TYPE_IMAGE||item.getType()==MediaItem.TYPE_VIDEO);
                arr.put(o);
            }
            return jsonResp(arr.toString());
        } catch (Exception e) { return HttpResponse.text(500,"Error"); }
    }

    private HttpResponse handleFolderList() {
        try {
            JSONArray arr=new JSONArray();
            for (FolderItem f : getFolderList()) {
                JSONObject o=new JSONObject();
                o.put("id",f.getId()); o.put("name",f.getName());
                o.put("uploader",f.getUploader()); o.put("time",f.getUploadTime());
                o.put("size",f.getTotalSize()); o.put("fileCount",f.getFileCount());
                arr.put(o);
            }
            return jsonResp(arr.toString());
        } catch (Exception e) { return HttpResponse.text(500,"Error"); }
    }

    private HttpResponse handleFolderFiles(String folderId) {
        if (folderId==null||!folderIndex.containsKey(folderId)) return HttpResponse.text(404,"Not found");
        try {
            FolderItem folder=folderIndex.get(folderId);
            JSONArray arr=new JSONArray();
            for (String fid : folder.getFileIds()) {
                MediaItem item=fileIndex.get(fid); if(item==null) continue;
                JSONObject o=new JSONObject();
                o.put("id",item.getId()); o.put("name",item.getName());
                o.put("size",item.getSize()); o.put("mime",item.getMimeType());
                o.put("type",item.getType());
                o.put("hasThumb",item.getType()==MediaItem.TYPE_IMAGE||item.getType()==MediaItem.TYPE_VIDEO);
                arr.put(o);
            }
            return jsonResp(arr.toString());
        } catch (Exception e) { return HttpResponse.text(500,"Error"); }
    }

    private HttpResponse handleFolderZip(String folderId) {
        if (folderId==null||!folderStore.containsKey(folderId)) return HttpResponse.text(404,"Not found");
        try {
            FolderItem folder=folderIndex.get(folderId);
            File physDir=folderStore.get(folderId);
            File zipFile=new File(ctx.getCacheDir(), folder.getId()+".zip");
            // Build zip from files in this folder
            List<File> files=new ArrayList<>();
            for (String fid : folder.getFileIds()) {
                File f=fileStore.get(fid); if(f!=null&&f.exists()) files.add(f);
            }
            String basePath=physDir.getAbsolutePath();
            ZipUtils.zipFiles(files, basePath, zipFile);
            return HttpResponse.file(zipFile, "application/zip", folder.getName()+".zip");
        } catch (Exception e) { return HttpResponse.text(500,"ZIP failed: "+e.getMessage()); }
    }

    private HttpResponse handleDownload(String id) {
        if (id==null||!fileStore.containsKey(id)) return HttpResponse.text(404,"Not found");
        return HttpResponse.file(fileStore.get(id), fileIndex.get(id).getMimeType(), fileIndex.get(id).getName());
    }

    private HttpResponse handleThumb(String id) {
        if (id==null||!fileStore.containsKey(id)) return HttpResponse.text(404,"No thumb");
        if (thumbCache.containsKey(id)) return new HttpResponse(200,"image/jpeg",thumbCache.get(id));
        File f=fileStore.get(id); MediaItem item=fileIndex.get(id);
        Bitmap bmp=ThumbnailUtils.getThumbnail(f,item.getMimeType());
        if (bmp==null) return HttpResponse.text(404,"No thumb");
        ByteArrayOutputStream baos=new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG,75,baos);
        byte[] bytes=baos.toByteArray(); thumbCache.put(id,bytes);
        return new HttpResponse(200,"image/jpeg",bytes);
    }

    private HttpResponse handleUpload(HttpRequest req, String clientIp) {
        if (!config.isAllowUpload()&&!"127.0.0.1".equals(clientIp))
            return HttpResponse.text(403,"Uploads disabled.");
        try {
            String uploader=req.header("X-Device-Name"); if(uploader==null) uploader="unknown";
            String ct=req.header("Content-Type");
            if (ct==null||!ct.contains("multipart")) return HttpResponse.text(400,"Expected multipart");
            String boundary=ct.substring(ct.indexOf("boundary=")+9).trim();
            MultipartParser.Part part=new MultipartParser(req.body,boundary).nextPart();
            if (part==null) return HttpResponse.text(400,"No file");
            if (config.getMaxFileSizeMb()>0&&part.data.length>config.getMaxFileSizeMb()*1024*1024)
                return HttpResponse.text(413,"File too large. Max: "+config.getMaxFileSizeMb()+" MB");
            String id=FileUtils.generateId();
            File dest=new File(storageDir,id+"_"+sanitize(part.filename));
            try(FileOutputStream fos=new FileOutputStream(dest)){fos.write(part.data);}
            MediaItem item=new MediaItem(id,part.filename,dest.length(),part.contentType,uploader);
            item.setLocalPath(dest.getAbsolutePath());
            fileIndex.put(id,item); fileStore.put(id,dest);
            trackUpload(uploader);
            if(listener!=null) listener.onFileUploaded(item);
            JSONObject resp=new JSONObject(); resp.put("id",id); resp.put("name",part.filename); resp.put("size",dest.length());
            return jsonResp(resp.toString());
        } catch (Exception e) { return HttpResponse.text(500,"Upload failed: "+e.getMessage()); }
    }

    /**
     * Receives a ZIP file, extracts it into a dedicated folder directory.
     * Header X-Folder-Name: the folder display name.
     */
    private HttpResponse handleUploadFolder(HttpRequest req, String clientIp) {
        if (!config.isAllowUpload()&&!"127.0.0.1".equals(clientIp))
            return HttpResponse.text(403,"Uploads disabled.");
        try {
            String uploader=req.header("X-Device-Name"); if(uploader==null) uploader="unknown";
            String folderName=req.header("X-Folder-Name"); if(folderName==null) folderName="folder";
            String ct=req.header("Content-Type");
            if (ct==null||!ct.contains("multipart")) return HttpResponse.text(400,"Expected multipart");
            String boundary=ct.substring(ct.indexOf("boundary=")+9).trim();
            MultipartParser.Part part=new MultipartParser(req.body,boundary).nextPart();
            if (part==null) return HttpResponse.text(400,"No file");

            // Extract ZIP into dedicated directory
            String folderId=FileUtils.generateId();
            File destDir=new File(storageDir, folderId+"_"+sanitize(folderName));
            destDir.mkdirs();
            java.io.ByteArrayInputStream zipStream=new java.io.ByteArrayInputStream(part.data);
            ZipUtils.unzip(zipStream, destDir);

            FolderItem folder=addLocalFolder(folderName, destDir, uploader);
            JSONObject resp=new JSONObject();
            resp.put("id",folder.getId()); resp.put("name",folder.getName());
            resp.put("fileCount",folder.getFileCount()); resp.put("size",folder.getTotalSize());
            return jsonResp(resp.toString());
        } catch (Exception e) { return HttpResponse.text(500,"Folder upload failed: "+e.getMessage()); }
    }

    private HttpResponse handleDelete(String id) {
        if(id==null) return HttpResponse.text(400,"No id");
        return deleteFile(id)?jsonResp("{\"ok\":true}"):HttpResponse.text(404,"Not found");
    }

    private HttpResponse handleDeleteFolder(String id) {
        if(id==null) return HttpResponse.text(400,"No id");
        return deleteFolder(id)?jsonResp("{\"ok\":true}"):HttpResponse.text(404,"Not found");
    }

    private HttpResponse handleMembers() {
        try {
            JSONArray arr=new JSONArray();
            for (RoomMember m : getMemberList()) {
                JSONObject o=new JSONObject();
                o.put("ip",m.getIp()); o.put("name",m.getDeviceName());
                o.put("isHost",m.isHost()); o.put("joinedAgo",m.getJoinedAgo());
                o.put("filesShared",m.getFilesShared()); arr.put(o);
            }
            return jsonResp(arr.toString());
        } catch (Exception e) { return HttpResponse.text(500,"Error"); }
    }

    private HttpResponse handleGetConfig() {
        try {
            JSONObject o=new JSONObject();
            o.put("maxMembers",config.getMaxMembers()); o.put("allowUpload",config.isAllowUpload());
            o.put("allowDelete",config.isAllowDelete()); o.put("maxFileSizeMb",config.getMaxFileSizeMb());
            o.put("roomName",config.getRoomName()); return jsonResp(o.toString());
        } catch (Exception e) { return HttpResponse.text(500,"Error"); }
    }

    private HttpResponse handleSetConfig(HttpRequest req) {
        try {
            if(req.body==null) return HttpResponse.text(400,"No body");
            JSONObject o=new JSONObject(new String(req.body));
            config.setMaxMembers((int)o.optLong("maxMembers",config.getMaxMembers()));
            config.setAllowUpload(o.optBoolean("allowUpload",config.isAllowUpload()));
            config.setAllowDelete(o.optBoolean("allowDelete",config.isAllowDelete()));
            config.setMaxFileSizeMb(o.optLong("maxFileSizeMb",config.getMaxFileSizeMb()));
            config.setRoomName(o.optString("roomName",config.getRoomName()));
            if(listener!=null) listener.onConfigChanged(config);
            return jsonResp("{\"ok\":true}");
        } catch (Exception e) { return HttpResponse.text(500,"Error"); }
    }

    private HttpResponse handleKick(String ip, boolean block) {
        if(ip==null) return HttpResponse.text(400,"No ip");
        boolean ok=kickMember(ip,block);
        return jsonResp("{\"ok\":"+ok+",\"blocked\":"+block+"}");
    }
    private HttpResponse handleGetBlocked() {
        try { JSONArray arr=new JSONArray(); for(String ip:blockedIps) arr.put(ip); return jsonResp(arr.toString()); }
        catch (Exception e) { return HttpResponse.text(500,"Error"); }
    }
    private HttpResponse handleUnblock(String ip) {
        if(ip==null) return HttpResponse.text(400,"No ip");
        unblockIp(ip); return jsonResp("{\"ok\":true}");
    }
    private HttpResponse handleEndRoom() { endRoom(); return jsonResp("{\"ok\":true}"); }

    private HttpResponse handleWebUi() {
        String html = buildWebUiHtml();
        return HttpResponse.html(html);
    }

    private String buildWebUiHtml() {
        return "<!DOCTYPE html><html><head><meta charset='utf-8'>" +
            "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
            "<title>LocalShare · "+roomCode+"</title>" +
            "<style>*{box-sizing:border-box;margin:0;padding:0}" +
            "body{font:12px monospace;background:#0d1117;color:#e6edf3;padding:10px}" +
            "h1{color:#00ff41}p{color:#555;font-size:10px;margin-bottom:8px}" +
            ".tabs{display:flex;gap:4px;margin:8px 0}" +
            ".tab{padding:6px 14px;cursor:pointer;background:#161b22;color:#888;border-radius:2px;font-size:11px;border:none}" +
            ".tab.active{background:#00ff41;color:#0d1117}" +
            ".grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(120px,1fr));gap:6px;margin:8px 0}" +
            ".card{background:#161b22;border-radius:4px;padding:6px;cursor:pointer;position:relative}" +
            ".card:hover{background:#21262d}" +
            ".card img{width:100%;height:85px;object-fit:cover;border-radius:2px}" +
            ".card .ic{height:85px;display:flex;align-items:center;justify-content:center;font-size:36px;background:#0d1117;border-radius:2px}" +
            ".card .nm{font-size:9px;margin-top:3px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}" +
            ".card .sz{font-size:8px;color:#555}" +
            ".folder-card{background:#161b22;border:1px solid #21262d;border-radius:4px;padding:10px;cursor:pointer;margin-bottom:6px;display:flex;align-items:center;gap:10px}" +
            ".folder-card:hover{background:#21262d}" +
            ".folder-info{flex:1}" +
            ".folder-name{color:#e6edf3;font-size:13px}" +
            ".folder-meta{color:#555;font-size:10px;margin-top:2px}" +
            "button{background:#00ff41;color:#0d1117;border:0;padding:6px 12px;cursor:pointer;font:11px monospace;margin:3px 3px 3px 0}" +
            "button.sec{background:#161b22;color:#888}" +
            "button.danger{background:#3d0000;color:#ff4444}" +
            "input[type=file]{color:#eee;display:block;margin:6px 0}" +
            ".bar{width:100%;background:#21262d;height:3px;margin:5px 0}" +
            ".bar div{height:3px;background:#00ff41;transition:width .3s}" +
            ".breadcrumb{color:#555;font-size:10px;margin-bottom:8px;cursor:pointer}" +
            ".breadcrumb span{color:#00ff41}" +
            "</style></head><body>" +
            "<h1>📡 "+roomCode+"</h1>" +
            "<p>Host: "+deviceName+(isProtected()?" 🔒":" 🌐")+" · "+config.summary()+"</p>" +
            "<div class='tabs'>" +
            "<button class='tab active' onclick='showTab(\"files\",this)'>Files</button>" +
            "<button class='tab' onclick='showTab(\"folders\",this)'>Folders</button>" +
            "<button class='tab' onclick='showTab(\"upload\",this)'>Upload</button>" +
            "</div>" +
            // Files tab
            "<div id='tab-files'><div class='grid' id='grid'></div></div>" +
            // Folders tab
            "<div id='tab-folders' style='display:none'>" +
            "<div id='breadcrumb' class='breadcrumb'></div>" +
            "<div id='folder-list'></div>" +
            "<div class='grid' id='folder-files' style='display:none'></div>" +
            "</div>" +
            // Upload tab
            "<div id='tab-upload' style='display:none'>" +
            "<p style='color:#888;margin-bottom:8px'>Upload individual files:</p>" +
            "<input type='file' multiple id='f'>" +
            "<button onclick='upload()'>Upload Files</button>" +
            "<p style='color:#888;margin:12px 0 6px'>Upload a folder (select ZIP):</p>" +
            "<input type='file' id='fz' accept='.zip'>" +
            "<input id='fnm' placeholder='Folder name' style='background:#21262d;color:#e6edf3;border:1px solid #30363d;padding:5px;font:11px monospace;width:100%;margin:4px 0'>" +
            "<button onclick='uploadFolder()'>Upload Folder (ZIP)</button>" +
            "<div class='bar'><div id='prog' style='width:0'></div></div>" +
            "</div>" +
            "<script>" +
            "const pw=new URLSearchParams(location.search).get('pw')||'';" +
            "const ph=pw?{'X-Room-Password':pw,'X-Host-Request':'true'}:{'X-Host-Request':'true'};" +
            "function showTab(t,el){" +
            "  ['files','folders','upload'].forEach(id=>document.getElementById('tab-'+id).style.display='none');" +
            "  document.getElementById('tab-'+t).style.display='';" +
            "  document.querySelectorAll('.tab').forEach(e=>e.classList.remove('active'));" +
            "  el.classList.add('active');" +
            "  if(t=='files')load();else if(t=='folders')loadFolders();}" +
            "function fmt(b){if(b<1024)return b+'B';if(b<1048576)return(b/1024).toFixed(1)+'KB';return(b/1048576).toFixed(1)+'MB';}" +
            "async function load(){" +
            "  const r=await fetch('/api/list'+(pw?'?pw='+pw:''));const d=await r.json();" +
            "  const g=document.getElementById('grid');g.innerHTML='';" +
            "  if(d.length==0){g.innerHTML='<p style=\"color:#555;padding:20px\">No files yet.</p>';return;}" +
            "  d.forEach(f=>{const c=document.createElement('div');c.className='card';" +
            "    if(f.hasThumb)c.innerHTML=`<img src='/api/thumb?id=${f.id}${pw?'&pw='+pw:''}' loading='lazy'>`;" +
            "    else c.innerHTML=`<div class='ic'>${f.type==1?'🎬':f.type==2?'🎵':'📄'}</div>`;" +
            "    c.innerHTML+=`<div class='nm'>${f.name}</div><div class='sz'>${fmt(f.size)}</div>`;" +
            "    c.onclick=()=>location.href='/api/download?id='+f.id+(pw?'&pw='+pw:'');" +
            "    g.appendChild(c);});}" +
            "async function loadFolders(){" +
            "  document.getElementById('folder-files').style.display='none';" +
            "  document.getElementById('breadcrumb').innerHTML='';" +
            "  const r=await fetch('/api/folders'+(pw?'?pw='+pw:''));const d=await r.json();" +
            "  const fl=document.getElementById('folder-list');fl.innerHTML='';" +
            "  if(d.length==0){fl.innerHTML='<p style=\"color:#555;padding:20px\">No folders yet.</p>';return;}" +
            "  d.forEach(f=>{" +
            "    const c=document.createElement('div');c.className='folder-card';" +
            "    c.innerHTML=`<div style='font-size:32px'>📁</div><div class='folder-info'>" +
            "    <div class='folder-name'>${f.name}</div>" +
            "    <div class='folder-meta'>${f.fileCount} files · ${fmt(f.size)} · ${f.uploader}</div>" +
            "    </div>" +
            "    <button class='sec' onclick='event.stopPropagation();downloadFolderZip(\"${f.id}\",\"${f.name}\")'>⬇ ZIP</button>`;" +
            "    c.onclick=()=>openFolder(f.id,f.name);" +
            "    fl.appendChild(c);});}" +
            "async function openFolder(id,name){" +
            "  document.getElementById('folder-list').style.display='none';" +
            "  document.getElementById('folder-files').style.display='';" +
            "  const bc=document.getElementById('breadcrumb');" +
            "  bc.innerHTML=`<span onclick='loadFolders()'>📁 Folders</span> / <span>${name}</span>`;" +
            "  const r=await fetch('/api/folder?id='+id+(pw?'&pw='+pw:''));const d=await r.json();" +
            "  const g=document.getElementById('folder-files');g.innerHTML='';" +
            "  d.forEach(f=>{const c=document.createElement('div');c.className='card';" +
            "    if(f.hasThumb)c.innerHTML=`<img src='/api/thumb?id=${f.id}${pw?'&pw='+pw:''}' loading='lazy'>`;" +
            "    else c.innerHTML=`<div class='ic'>${f.type==1?'🎬':f.type==2?'🎵':'📄'}</div>`;" +
            "    c.innerHTML+=`<div class='nm'>${f.name}</div><div class='sz'>${fmt(f.size)}</div>`;" +
            "    c.onclick=()=>location.href='/api/download?id='+f.id+(pw?'&pw='+pw:'');" +
            "    g.appendChild(c);});}" +
            "function downloadFolderZip(id,name){" +
            "  location.href='/api/folder-zip?id='+id+(pw?'&pw='+pw:'');}" +
            "async function upload(){const inp=document.getElementById('f');" +
            "  const prog=document.getElementById('prog');" +
            "  for(const file of inp.files){const fd=new FormData();fd.append('file',file);" +
            "    prog.style.width='50%';" +
            "    const r=await fetch('/api/upload'+(pw?'?pw='+pw:''),{method:'POST',headers:ph,body:fd});" +
            "    if(!r.ok)alert(await r.text());prog.style.width='100%';}" +
            "  setTimeout(()=>{prog.style.width='0';},500);}" +
            "async function uploadFolder(){" +
            "  const inp=document.getElementById('fz');const name=document.getElementById('fnm').value||'folder';" +
            "  if(!inp.files[0]){alert('Select a ZIP file');return;}" +
            "  const prog=document.getElementById('prog');prog.style.width='30%';" +
            "  const fd=new FormData();fd.append('file',inp.files[0]);" +
            "  const h={...ph,'X-Folder-Name':name};" +
            "  const r=await fetch('/api/upload-folder'+(pw?'?pw='+pw:''),{method:'POST',headers:h,body:fd});" +
            "  prog.style.width='100%';" +
            "  if(r.ok){const d=await r.json();alert('Folder uploaded: '+d.name+' ('+d.fileCount+' files)');}" +
            "  else{alert('Failed: '+await r.text());}" +
            "  setTimeout(()=>{prog.style.width='0';},500);}" +
            "load();" +
            "setInterval(async()=>{const r=await fetch('/api/room-status');const d=await r.json();" +
            "  if(d.closed)document.body.innerHTML='<p style=\"color:#ff4444;font-family:monospace;padding:32px\">Room ended.</p>';},5000);" +
            "</script></body></html>";
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void trackUpload(String uploaderName) {
        for (RoomMember m : members.values()) {
            if (m.getDeviceName().equals(uploaderName)) { m.incrementFiles(); break; }
        }
    }

    private static void deleteDir(File dir) {
        if (dir==null||!dir.exists()) return;
        File[] files=dir.listFiles();
        if (files!=null) for (File f:files) { if(f.isDirectory()) deleteDir(f); else f.delete(); }
        dir.delete();
    }

    private static String guessMime(String name) {
        int dot=name.lastIndexOf('.');
        if (dot>=0) {
            String ext=name.substring(dot+1).toLowerCase();
            String m=android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (m!=null) return m;
        }
        return "application/octet-stream";
    }

    private static String sanitize(String n) { return n==null?"file":n.replaceAll("[^a-zA-Z0-9._\\-]","_"); }
    static HttpResponse jsonResp(String json) { return new HttpResponse(200,"application/json",json.getBytes()); }
}
