package com.localshare.model;

public class RoomMember {
    private String ip;
    private String deviceName;
    private long joinTime;
    private int filesShared;
    private boolean isHost;

    public RoomMember(String ip, String deviceName, boolean isHost) {
        this.ip = ip;
        this.deviceName = deviceName;
        this.isHost = isHost;
        this.joinTime = System.currentTimeMillis();
        this.filesShared = 0;
    }

    public String getIp() { return ip; }
    public String getDeviceName() { return deviceName; }
    public long getJoinTime() { return joinTime; }
    public int getFilesShared() { return filesShared; }
    public boolean isHost() { return isHost; }
    public void incrementFiles() { filesShared++; }

    public String getJoinedAgo() {
        long secs = (System.currentTimeMillis() - joinTime) / 1000;
        if (secs < 60) return secs + "s ago";
        if (secs < 3600) return (secs / 60) + "m ago";
        return (secs / 3600) + "h ago";
    }
}
