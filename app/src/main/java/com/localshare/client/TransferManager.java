package com.localshare.client;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.localshare.model.MediaItem;
import com.localshare.utils.FileUtils;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class TransferManager {

    private static final String TAG = "TransferManager";

    public enum TransferType { UPLOAD, DOWNLOAD, FOLDER_UPLOAD }

    public static class Transfer {
        public final String id;
        public final TransferType type;
        public final String fileName;
        public volatile long bytesDone;
        public volatile long bytesTotal;
        public volatile boolean done;
        public volatile boolean failed;
        public volatile String error;

        Transfer(String id, TransferType type, String fileName, long total) {
            this.id = id; this.type = type; this.fileName = fileName; this.bytesTotal = total;
        }

        public int progressPercent() {
            if (bytesTotal <= 0) return 0;
            return (int) Math.min(100, bytesDone * 100 / bytesTotal);
        }
    }

    public interface TransferListener {
        void onProgress(Transfer t);
        void onComplete(Transfer t);
        void onError(Transfer t);
    }

    private final ThreadPoolExecutor pool;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private TransferListener listener;

    public TransferManager() {
        pool = new ThreadPoolExecutor(4,4,60,TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                r -> { Thread t=new Thread(r,"Transfer"); t.setDaemon(true); return t; });
    }

    public void setListener(TransferListener l) { this.listener = l; }

    public Transfer enqueueUpload(Context ctx, ApiClient client, Uri uri, String deviceName) {
        String name  = FileUtils.getFileName(ctx, uri);
        String mime  = FileUtils.getMimeType(ctx, uri);
        long   size  = FileUtils.getFileSize(ctx, uri);
        Transfer t   = new Transfer(FileUtils.generateId(), TransferType.UPLOAD, name, size);

        pool.execute(() -> {
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                client.uploadFile(in, name, mime, size, deviceName, (done, total) -> {
                    t.bytesDone = done; t.bytesTotal = total; notifyProgress(t);
                });
                t.done = true; notifyComplete(t);
            } catch (Exception e) {
                t.failed = true; t.error = e.getMessage(); Log.e(TAG,"Upload failed",e); notifyError(t);
            }
        });
        return t;
    }

    public Transfer enqueueFolderUpload(ApiClient client, InputStream zipStream,
                                        String folderName, long zipSize, String deviceName) {
        Transfer t = new Transfer(FileUtils.generateId(), TransferType.FOLDER_UPLOAD, "📁 "+folderName, zipSize);
        // Read zip into bytes first (stream may not be reopenable)
        pool.execute(() -> {
            try {
                client.uploadFolderZip(zipStream, folderName, zipSize, deviceName, (done, total) -> {
                    t.bytesDone = done; t.bytesTotal = total; notifyProgress(t);
                });
                t.done = true; notifyComplete(t);
            } catch (Exception e) {
                t.failed = true; t.error = e.getMessage(); Log.e(TAG,"Folder upload failed",e); notifyError(t);
            }
        });
        return t;
    }

    public Transfer enqueueDownload(ApiClient client, MediaItem item, File destDir) {
        Transfer t = new Transfer(item.getId(), TransferType.DOWNLOAD, item.getName(), item.getSize());
        File dest  = new File(destDir, item.getName());

        pool.execute(() -> {
            try {
                client.downloadFile(item.getId(), dest, (done, total) -> {
                    t.bytesDone = done; t.bytesTotal = total; notifyProgress(t);
                });
                t.done = true; notifyComplete(t);
            } catch (Exception e) {
                t.failed = true; t.error = e.getMessage();
                if (dest.exists()) dest.delete(); notifyError(t);
            }
        });
        return t;
    }

    public void shutdown() { pool.shutdownNow(); }

    private void notifyProgress(Transfer t) { if(listener!=null) mainHandler.post(()->listener.onProgress(t)); }
    private void notifyComplete(Transfer t) { if(listener!=null) mainHandler.post(()->listener.onComplete(t)); }
    private void notifyError(Transfer t)    { if(listener!=null) mainHandler.post(()->listener.onError(t)); }
}
