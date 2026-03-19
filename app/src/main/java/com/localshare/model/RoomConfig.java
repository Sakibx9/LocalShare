package com.localshare.model;

public class RoomConfig {
    private int maxMembers;       // 0 = unlimited
    private boolean allowUpload;  // can clients upload?
    private boolean allowDelete;  // can clients delete?
    private long maxFileSizeMb;   // 0 = unlimited
    private String roomName;

    public RoomConfig() {
        this.maxMembers    = 0;
        this.allowUpload   = true;
        this.allowDelete   = false;
        this.maxFileSizeMb = 0;
        this.roomName      = "";
    }

    public int     getMaxMembers()    { return maxMembers; }
    public boolean isAllowUpload()    { return allowUpload; }
    public boolean isAllowDelete()    { return allowDelete; }
    public long    getMaxFileSizeMb() { return maxFileSizeMb; }
    public String  getRoomName()      { return roomName; }

    public void setMaxMembers(int v)       { this.maxMembers    = v; }
    public void setAllowUpload(boolean v)  { this.allowUpload   = v; }
    public void setAllowDelete(boolean v)  { this.allowDelete   = v; }
    public void setMaxFileSizeMb(long v)   { this.maxFileSizeMb = v; }
    public void setRoomName(String v)      { this.roomName      = v; }

    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(maxMembers > 0 ? "Max " + maxMembers + " members" : "Unlimited members");
        sb.append(" · ");
        sb.append(allowUpload ? "Upload ON" : "Upload OFF");
        if (maxFileSizeMb > 0) sb.append(" · Max ").append(maxFileSizeMb).append(" MB/file");
        return sb.toString();
    }
}
