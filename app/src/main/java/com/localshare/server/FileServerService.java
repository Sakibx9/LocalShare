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

import com.localshare.model.MediaItem;
import com.localshare.ui.RoomActivity;
import com.localshare.utils.NetworkUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public class FileServerService extends Service {

    private static final String TAG = "FileServerService";
    private static final String CHANNEL_ID = "localshare_server";
    private static final int NOTIF_ID = 1;

    public static final String EXTRA_ROOM_CODE = "room_code";
    public static final String EXTRA_DEVICE_NAME = "device_name";
    public static final String ACTION_FILE_UPLOADED = "com.localshare.FILE_UPLOADED";
    public static final String ACTION_CLIENT_CONNECTED = "com.localshare.CLIENT_CONNECTED";

    private LocalHttpServer server;
    private String roomCode;
    private String deviceName;

    public class LocalBinder extends Binder {
        public FileServerService getService() { return FileServerService.this; }
    }

    private final IBinder binder = new LocalBinder();

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            roomCode = intent.getStringExtra(EXTRA_ROOM_CODE);
            deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME);
        }
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification());
        startServer();
        return START_STICKY;
    }

    private void startServer() {
        if (server != null) return;
        server = new LocalHttpServer(this, NetworkUtils.SERVER_PORT, roomCode, deviceName);
        server.setListener(new LocalHttpServer.OnEventListener() {
            @Override
            public void onFileUploaded(MediaItem item) {
                Intent i = new Intent(ACTION_FILE_UPLOADED);
                sendBroadcast(i);
                updateNotification("New file: " + item.getName());
            }
            @Override
            public void onClientConnected(String ip) {
                Intent i = new Intent(ACTION_CLIENT_CONNECTED);
                i.putExtra("ip", ip);
                sendBroadcast(i);
            }
        });
        try {
            server.start();
            Log.i(TAG, "Server running at " + NetworkUtils.getServerUrl(this));
        } catch (IOException e) {
            Log.e(TAG, "Failed to start server", e);
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        if (server != null) server.stop();
        super.onDestroy();
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    public MediaItem addFile(InputStream in, String name, long size, String mime, String uploader) {
        if (server == null) return null;
        return server.addLocalFile(in, name, size, mime, uploader);
    }

    public List<MediaItem> getFiles() {
        if (server == null) return java.util.Collections.emptyList();
        return server.getFileList();
    }

    public String getRoomCode() { return roomCode; }
    public String getServerUrl() { return NetworkUtils.getServerUrl(this); }

    // ─── Notification ─────────────────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "LocalShare Server", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("File sharing server");
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        return buildNotification("Room " + roomCode + " is active");
    }

    private Notification buildNotification(String text) {
        Intent tap = new Intent(this, RoomActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, tap,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("LocalShare")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, buildNotification(text));
    }
}
