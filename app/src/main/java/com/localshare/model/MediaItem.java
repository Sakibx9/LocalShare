package com.localshare.model;

public class MediaItem {
    public static final int TYPE_IMAGE = 0;
    public static final int TYPE_VIDEO = 1;
    public static final int TYPE_AUDIO = 2;
    public static final int TYPE_OTHER = 3;

    private String id;
    private String name;
    private long size;
    private int type;
    private String uploader;
    private long uploadTime;
    private String mimeType;
    private String localPath; // path on server disk

    public MediaItem() {}

    public MediaItem(String id, String name, long size, String mimeType, String uploader) {
        this.id = id;
        this.name = name;
        this.size = size;
        this.mimeType = mimeType;
        this.uploader = uploader;
        this.uploadTime = System.currentTimeMillis();
        this.type = typeFromMime(mimeType);
    }

    public static int typeFromMime(String mime) {
        if (mime == null) return TYPE_OTHER;
        if (mime.startsWith("image/")) return TYPE_IMAGE;
        if (mime.startsWith("video/")) return TYPE_VIDEO;
        if (mime.startsWith("audio/")) return TYPE_AUDIO;
        return TYPE_OTHER;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }
    public int getType() { return type; }
    public void setType(int type) { this.type = type; }
    public String getUploader() { return uploader; }
    public void setUploader(String uploader) { this.uploader = uploader; }
    public long getUploadTime() { return uploadTime; }
    public void setUploadTime(long t) { this.uploadTime = t; }
    public String getMimeType() { return mimeType; }
    public void setMimeType(String m) { this.mimeType = m; }
    public String getLocalPath() { return localPath; }
    public void setLocalPath(String p) { this.localPath = p; }

    public String getFormattedSize() {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        if (size < 1024L * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024));
        return String.format("%.1f GB", size / (1024.0 * 1024 * 1024));
    }
}
