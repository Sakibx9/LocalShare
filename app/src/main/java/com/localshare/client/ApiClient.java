package com.localshare.client;

import com.localshare.model.FolderItem;
import com.localshare.model.MediaItem;
import com.localshare.model.RoomMember;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class ApiClient {
    private static final int CONNECT_TIMEOUT = 5000;
    private static final int READ_TIMEOUT    = 180000; // 3min for large folders

    private final String baseUrl;
    private String password;

    public ApiClient(String hostIp, int port) { this.baseUrl = "http://" + hostIp + ":" + port; }
    public void setPassword(String pw) { this.password = pw; }

    public JSONObject pingInfo() {
        try {
            HttpURLConnection c = open("/api/ping"); c.setConnectTimeout(3000); c.setReadTimeout(3000);
            if (c.getResponseCode() == 200) return new JSONObject(readString(c.getInputStream()));
        } catch (Exception ignored) {} return null;
    }

    public String ping()            { JSONObject o=pingInfo(); return o!=null?o.optString("room",null):null; }
    public boolean isRoomProtected(){ JSONObject o=pingInfo(); return o!=null&&o.optBoolean("protected",false); }
    public boolean isRoomClosed()   {
        try {
            HttpURLConnection c=open("/api/room-status"); c.setConnectTimeout(3000); c.setReadTimeout(3000);
            if(c.getResponseCode()==200) return new JSONObject(readString(c.getInputStream())).optBoolean("closed",false);
        } catch (Exception ignored) {} return false;
    }

    public void joinRoom(String deviceName) {
        try {
            URL url=new URL(baseUrl+"/api/join");
            HttpURLConnection c=(HttpURLConnection)url.openConnection();
            c.setRequestMethod("POST"); c.setDoOutput(true);
            c.setConnectTimeout(CONNECT_TIMEOUT); c.setReadTimeout(5000);
            c.setRequestProperty("Content-Type","application/json");
            c.getOutputStream().write(("{\"deviceName\":\""+deviceName+"\"}").getBytes());
            c.getResponseCode();
        } catch (Exception ignored) {}
    }

    public void leaveRoom() {
        try {
            URL url=new URL(baseUrl+"/api/leave");
            HttpURLConnection c=(HttpURLConnection)url.openConnection();
            c.setRequestMethod("POST"); c.setConnectTimeout(3000); c.setReadTimeout(3000);
            c.getResponseCode();
        } catch (Exception ignored) {}
    }

    public List<RoomMember> getMembers() throws Exception {
        HttpURLConnection c=open("/api/members"); c.setConnectTimeout(CONNECT_TIMEOUT); c.setReadTimeout(10000);
        JSONArray arr=new JSONArray(readString(c.getInputStream()));
        List<RoomMember> list=new ArrayList<>();
        for (int i=0;i<arr.length();i++) {
            JSONObject o=arr.getJSONObject(i);
            list.add(new RoomMember(o.optString("ip"),o.optString("name"),o.optBoolean("isHost")));
        }
        return list;
    }

    public List<MediaItem> listFiles() throws Exception {
        HttpURLConnection c=open("/api/list"); c.setConnectTimeout(CONNECT_TIMEOUT); c.setReadTimeout(READ_TIMEOUT);
        int code=c.getResponseCode(); if(code==401) throw new Exception("Wrong password");
        JSONArray arr=new JSONArray(readString(c.getInputStream()));
        List<MediaItem> items=new ArrayList<>();
        for (int i=0;i<arr.length();i++) {
            JSONObject o=arr.getJSONObject(i);
            MediaItem item=new MediaItem(o.getString("id"),o.getString("name"),
                    o.getLong("size"),o.optString("mime","application/octet-stream"),o.optString("uploader","?"));
            item.setUploadTime(o.optLong("time",0)); items.add(item);
        }
        return items;
    }

    public List<FolderItem> listFolders() throws Exception {
        HttpURLConnection c=open("/api/folders"); c.setConnectTimeout(CONNECT_TIMEOUT); c.setReadTimeout(READ_TIMEOUT);
        int code=c.getResponseCode(); if(code==401) throw new Exception("Wrong password");
        JSONArray arr=new JSONArray(readString(c.getInputStream()));
        List<FolderItem> folders=new ArrayList<>();
        for (int i=0;i<arr.length();i++) {
            JSONObject o=arr.getJSONObject(i);
            FolderItem f=new FolderItem(o.getString("id"),o.getString("name"),o.optString("uploader","?"));
            f.setTotalSize(o.optLong("size",0)); f.setFileCount((int)o.optLong("fileCount",0));
            f.setUploadTime(o.optLong("time",0)); folders.add(f);
        }
        return folders;
    }

    public List<MediaItem> listFolderFiles(String folderId) throws Exception {
        HttpURLConnection c=open("/api/folder?id="+folderId); c.setConnectTimeout(CONNECT_TIMEOUT); c.setReadTimeout(READ_TIMEOUT);
        JSONArray arr=new JSONArray(readString(c.getInputStream()));
        List<MediaItem> items=new ArrayList<>();
        for (int i=0;i<arr.length();i++) {
            JSONObject o=arr.getJSONObject(i);
            MediaItem item=new MediaItem(o.getString("id"),o.getString("name"),
                    o.getLong("size"),o.optString("mime","application/octet-stream"),"");
            items.add(item);
        }
        return items;
    }

    public void downloadFile(String id, File destFile, ProgressCallback cb) throws Exception {
        HttpURLConnection c=open("/api/download?id="+id); c.setConnectTimeout(CONNECT_TIMEOUT); c.setReadTimeout(READ_TIMEOUT);
        long total=c.getContentLengthLong();
        try (InputStream in=c.getInputStream(); FileOutputStream out=new FileOutputStream(destFile)) {
            byte[] buf=new byte[512*1024]; long received=0; int n;
            while((n=in.read(buf))!=-1){out.write(buf,0,n);received+=n;if(cb!=null)cb.onProgress(received,total);}
        }
    }

    public void downloadFolderZip(String folderId, File destFile, ProgressCallback cb) throws Exception {
        HttpURLConnection c=open("/api/folder-zip?id="+folderId); c.setConnectTimeout(CONNECT_TIMEOUT); c.setReadTimeout(READ_TIMEOUT);
        long total=c.getContentLengthLong();
        try (InputStream in=c.getInputStream(); FileOutputStream out=new FileOutputStream(destFile)) {
            byte[] buf=new byte[512*1024]; long received=0; int n;
            while((n=in.read(buf))!=-1){out.write(buf,0,n);received+=n;if(cb!=null)cb.onProgress(received,total);}
        }
    }

    public byte[] downloadThumb(String id) throws Exception {
        HttpURLConnection c=open("/api/thumb?id="+id); c.setConnectTimeout(3000); c.setReadTimeout(10000);
        if(c.getResponseCode()!=200) return null;
        ByteArrayOutputStream buf=new ByteArrayOutputStream();
        byte[] tmp=new byte[8192]; int n;
        try(InputStream in=c.getInputStream()){while((n=in.read(tmp))!=-1)buf.write(tmp,0,n);}
        return buf.toByteArray();
    }

    public String uploadFile(InputStream stream, String filename, String mimeType,
                             long fileSize, String deviceName, ProgressCallback cb) throws Exception {
        return doMultipartUpload("/api/upload", stream, filename, mimeType, fileSize, deviceName, null, cb);
    }

    /** Upload a ZIP file as a folder. folderName = display name */
    public String uploadFolderZip(InputStream zipStream, String folderName, long zipSize,
                                  String deviceName, ProgressCallback cb) throws Exception {
        return doMultipartUpload("/api/upload-folder", zipStream, folderName+".zip",
                "application/zip", zipSize, deviceName, folderName, cb);
    }

    private String doMultipartUpload(String endpoint, InputStream stream, String filename,
                                     String mimeType, long fileSize, String deviceName,
                                     String folderName, ProgressCallback cb) throws Exception {
        String boundary = "----LocalShare" + System.currentTimeMillis();
        URL url = new URL(baseUrl + endpoint + pwParam("?"));
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setConnectTimeout(CONNECT_TIMEOUT); c.setReadTimeout(READ_TIMEOUT);
        c.setRequestProperty("Content-Type","multipart/form-data; boundary="+boundary);
        c.setRequestProperty("X-Device-Name", deviceName);
        if (password != null) c.setRequestProperty("X-Room-Password", password);
        if (folderName != null) c.setRequestProperty("X-Folder-Name", folderName);
        c.setRequestProperty("X-Host-Request","true");
        c.setChunkedStreamingMode(512*1024);
        String hdr="--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+filename+"\"\r\nContent-Type: "+mimeType+"\r\n\r\n";
        try (OutputStream out=c.getOutputStream()) {
            out.write(hdr.getBytes());
            byte[] buf=new byte[512*1024]; long sent=0; int n;
            while((n=stream.read(buf))!=-1){out.write(buf,0,n);sent+=n;if(cb!=null)cb.onProgress(sent,fileSize);}
            out.write(("\r\n--"+boundary+"--\r\n").getBytes()); out.flush();
        }
        int code=c.getResponseCode();
        if(code==401) throw new Exception("Wrong password");
        if(code==413) throw new Exception(readString(c.getErrorStream()));
        if(code!=200) throw new Exception("HTTP "+code);
        return readString(c.getInputStream());
    }

    public boolean deleteFile(String id) throws Exception {
        URL url=new URL(baseUrl+"/api/delete?id="+id+pwParam("&"));
        HttpURLConnection c=(HttpURLConnection)url.openConnection();
        c.setRequestMethod("DELETE"); c.setRequestProperty("X-Host-Request","true");
        if(password!=null) c.setRequestProperty("X-Room-Password",password);
        c.setConnectTimeout(CONNECT_TIMEOUT); c.setReadTimeout(10000);
        return c.getResponseCode()==200;
    }

    public boolean deleteFolder(String id) throws Exception {
        URL url=new URL(baseUrl+"/api/delete-folder?id="+id+pwParam("&"));
        HttpURLConnection c=(HttpURLConnection)url.openConnection();
        c.setRequestMethod("DELETE"); c.setRequestProperty("X-Host-Request","true");
        if(password!=null) c.setRequestProperty("X-Room-Password",password);
        c.setConnectTimeout(CONNECT_TIMEOUT); c.setReadTimeout(10000);
        return c.getResponseCode()==200;
    }

    public boolean endRoom() throws Exception {
        URL url=new URL(baseUrl+"/api/end-room");
        HttpURLConnection c=(HttpURLConnection)url.openConnection();
        c.setRequestMethod("POST"); c.setRequestProperty("X-Host-Request","true");
        if(password!=null) c.setRequestProperty("X-Room-Password",password);
        c.setConnectTimeout(3000); c.setReadTimeout(5000);
        return c.getResponseCode()==200;
    }

    private HttpURLConnection open(String path) throws Exception {
        URL url=new URL(baseUrl+path+pwParam(path.contains("?")?"&":"?"));
        HttpURLConnection c=(HttpURLConnection)url.openConnection();
        if(password!=null) c.setRequestProperty("X-Room-Password",password);
        return c;
    }
    private String pwParam(String sep) { return(password!=null&&!password.isEmpty())?sep+"pw="+password:""; }
    private String readString(InputStream in) throws Exception {
        if(in==null) return "";
        ByteArrayOutputStream buf=new ByteArrayOutputStream();
        byte[] tmp=new byte[8192]; int n;
        while((n=in.read(tmp))!=-1)buf.write(tmp,0,n);
        return buf.toString("UTF-8");
    }

    public interface ProgressCallback { void onProgress(long done, long total); }
}
