package com.localshare.server;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import com.localshare.model.FolderItem;
import com.localshare.model.MediaItem;
import com.localshare.model.RoomConfig;
import com.localshare.model.RoomMember;
import com.localshare.ui.RoomActivity;
import com.localshare.utils.NetworkUtils;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public class FileServerService extends Service {

    private static final String CHANNEL_ID = "localshare_server";
    private static final int    NOTIF_ID   = 1;

    public static final String EXTRA_ROOM_CODE    = "room_code";
    public static final String EXTRA_DEVICE_NAME  = "device_name";
    public static final String EXTRA_PASSWORD     = "password";
    public static final String EXTRA_MAX_MEMBERS  = "max_members";
    public static final String EXTRA_ALLOW_UPLOAD = "allow_upload";
    public static final String EXTRA_ALLOW_DELETE = "allow_delete";
    public static final String EXTRA_MAX_FILE_MB  = "max_file_mb";
    public static final String EXTRA_ROOM_NAME    = "room_name";

    public static final String ACTION_FILE_UPLOADED    = "com.localshare.FILE_UPLOADED";
    public static final String ACTION_FILE_DELETED     = "com.localshare.FILE_DELETED";
    public static final String ACTION_FOLDER_UPLOADED  = "com.localshare.FOLDER_UPLOADED";
    public static final String ACTION_FOLDER_DELETED   = "com.localshare.FOLDER_DELETED";
    public static final String ACTION_MEMBER_JOINED    = "com.localshare.MEMBER_JOINED";
    public static final String ACTION_MEMBER_LEFT      = "com.localshare.MEMBER_LEFT";
    public static final String ACTION_MEMBER_KICKED    = "com.localshare.MEMBER_KICKED";
    public static final String ACTION_CONFIG_CHANGED   = "com.localshare.CONFIG_CHANGED";
    public static final String ACTION_ROOM_ENDED       = "com.localshare.ROOM_ENDED";

    private LocalHttpServer server;
    private String roomCode, deviceName, password;
    private RoomConfig config;

    public class LocalBinder extends Binder {
        public FileServerService getService() { return FileServerService.this; }
    }
    private final IBinder binder = new LocalBinder();
    @Override public IBinder onBind(Intent i) { return binder; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            roomCode   = intent.getStringExtra(EXTRA_ROOM_CODE);
            deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME);
            password   = intent.getStringExtra(EXTRA_PASSWORD);
            config = new RoomConfig();
            config.setMaxMembers(intent.getIntExtra(EXTRA_MAX_MEMBERS, 0));
            config.setAllowUpload(intent.getBooleanExtra(EXTRA_ALLOW_UPLOAD, true));
            config.setAllowDelete(intent.getBooleanExtra(EXTRA_ALLOW_DELETE, false));
            config.setMaxFileSizeMb(intent.getLongExtra(EXTRA_MAX_FILE_MB, 0));
            String rn = intent.getStringExtra(EXTRA_ROOM_NAME);
            config.setRoomName(rn != null ? rn : "");
        }
        createChannel();
        startForeground(NOTIF_ID, buildNotif("Room " + roomCode + (password!=null?" 🔒":" 🌐")));
        startServer();
        return START_STICKY;
    }

    private void startServer() {
        if (server != null) return;
        server = new LocalHttpServer(this, NetworkUtils.SERVER_PORT, roomCode, deviceName, password, config);
        server.setListener(new LocalHttpServer.OnEventListener() {
            @Override public void onFileUploaded(MediaItem item) {
                sendBroadcast(new Intent(ACTION_FILE_UPLOADED)); updateNotif("New file: " + item.getName());
            }
            @Override public void onFileDeleted(String id) { sendBroadcast(new Intent(ACTION_FILE_DELETED)); }
            @Override public void onFolderUploaded(FolderItem folder) {
                Intent i = new Intent(ACTION_FOLDER_UPLOADED);
                i.putExtra("name", folder.getName()); i.putExtra("count", folder.getFileCount());
                sendBroadcast(i); updateNotif("Folder: " + folder.getName() + " (" + folder.getFileCount() + " files)");
            }
            @Override public void onFolderDeleted(String id) { sendBroadcast(new Intent(ACTION_FOLDER_DELETED)); }
            @Override public void onMemberJoined(RoomMember m) {
                Intent i = new Intent(ACTION_MEMBER_JOINED); i.putExtra("name",m.getDeviceName()); i.putExtra("ip",m.getIp());
                sendBroadcast(i); updateNotif(m.getDeviceName() + " joined");
            }
            @Override public void onMemberLeft(String ip) {
                Intent i = new Intent(ACTION_MEMBER_LEFT); i.putExtra("ip",ip); sendBroadcast(i);
            }
            @Override public void onMemberKicked(String ip, String name, boolean blocked) {
                Intent i = new Intent(ACTION_MEMBER_KICKED);
                i.putExtra("ip",ip); i.putExtra("name",name); i.putExtra("blocked",blocked);
                sendBroadcast(i); updateNotif(name + (blocked?" banned":" kicked"));
            }
            @Override public void onConfigChanged(RoomConfig cfg) { config=cfg; sendBroadcast(new Intent(ACTION_CONFIG_CHANGED)); }
            @Override public void onRoomEnded() { sendBroadcast(new Intent(ACTION_ROOM_ENDED)); stopSelf(); }
        });
        try { server.start(); } catch (IOException e) { Log.e("FSS","Start failed",e); stopSelf(); }
    }

    @Override public void onDestroy() { if (server!=null) server.stop(); super.onDestroy(); }

    // ── Public API ────────────────────────────────────────────────────────────

    public MediaItem addFile(InputStream in, String name, long size, String mime, String uploader) {
        return server==null ? null : server.addLocalFile(in, name, size, mime, uploader);
    }
    public FolderItem addFolder(String folderName, File physicalDir, String uploader) {
        return server==null ? null : server.addLocalFolder(folderName, physicalDir, uploader);
    }
    public boolean deleteFile(String id)    { return server!=null && server.deleteFile(id); }
    public boolean deleteFolder(String id)  { return server!=null && server.deleteFolder(id); }
    public void endRoom()                   { if(server!=null) server.endRoom(); stopSelf(); }
    public boolean kickMember(String ip, boolean block) { return server!=null && server.kickMember(ip,block); }
    public boolean unblockIp(String ip)     { return server!=null && server.unblockIp(ip); }
    public void updateConfig(RoomConfig c)  { if(server!=null) server.updateConfig(c); this.config=c; }

    public List<MediaItem>  getFiles()      { return server==null?java.util.Collections.emptyList():server.getFileList(); }
    public List<FolderItem> getFolders()    { return server==null?java.util.Collections.emptyList():server.getFolderList(); }
    public List<RoomMember> getMembers()    { return server==null?java.util.Collections.emptyList():server.getMemberList(); }
    public List<String>     getBlockedIps() { return server==null?java.util.Collections.emptyList():server.getBlockedIps(); }
    public RoomConfig       getConfig()     { return config; }
    public String           getRoomCode()   { return roomCode; }
    public String           getPassword()   { return password; }
    public boolean          isProtected()   { return password!=null&&!password.isEmpty(); }
    public String           getServerUrl()  { return NetworkUtils.getServerUrl(this); }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,"LocalShare",NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }
    private Notification buildNotif(String text) {
        Intent tap = new Intent(this, RoomActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this,0,tap,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this,CHANNEL_ID)
                .setContentTitle("LocalShare").setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_share).setContentIntent(pi).setOngoing(true).build();
    }
    private void updateNotif(String text) {
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_ID,buildNotif(text));
    }
}
