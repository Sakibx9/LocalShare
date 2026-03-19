package com.localshare.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a shared folder on the server.
 * Contains a list of MediaItems (its files).
 */
public class FolderItem {
    private String id;
    private String name;        // folder display name
    private String uploader;
    private long   uploadTime;
    private long   totalSize;
    private int    fileCount;
    private List<String> fileIds; // IDs of MediaItems inside

    public FolderItem() {
        this.fileIds = new ArrayList<>();
    }

    public FolderItem(String id, String name, String uploader) {
        this.id         = id;
        this.name       = name;
        this.uploader   = uploader;
        this.uploadTime = System.currentTimeMillis();
        this.fileIds    = new ArrayList<>();
    }

    public void addFile(String fileId, long size) {
        fileIds.add(fileId);
        totalSize += size;
        fileCount++;
    }

    public String       getId()         { return id; }
    public void         setId(String v) { id = v; }
    public String       getName()       { return name; }
    public void         setName(String v) { name = v; }
    public String       getUploader()   { return uploader; }
    public void         setUploader(String v) { uploader = v; }
    public long         getUploadTime() { return uploadTime; }
    public void         setUploadTime(long v) { uploadTime = v; }
    public long         getTotalSize()  { return totalSize; }
    public void         setTotalSize(long v) { totalSize = v; }
    public int          getFileCount()  { return fileCount; }
    public void         setFileCount(int v) { fileCount = v; }
    public List<String> getFileIds()    { return fileIds; }
    public void         setFileIds(List<String> v) { fileIds = v; }

    public String getFormattedSize() {
        if (totalSize < 1024) return totalSize + " B";
        if (totalSize < 1024 * 1024) return String.format("%.1f KB", totalSize / 1024.0);
        if (totalSize < 1024L * 1024 * 1024) return String.format("%.1f MB", totalSize / (1024.0 * 1024));
        return String.format("%.1f GB", totalSize / (1024.0 * 1024 * 1024));
    }
}
