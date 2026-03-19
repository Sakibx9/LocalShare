package com.localshare.utils;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.text.format.Formatter;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

public class NetworkUtils {

    public static final int SERVER_PORT = 8765;

    /** Get device IP on WiFi or hotspot interface */
    public static String getLocalIpAddress(Context ctx) {
        // Try hotspot interface first (wlan0, ap0, swlan0)
        String[] hotspotIfaces = {"wlan0", "ap0", "swlan0", "wlan1"};
        for (String iface : hotspotIfaces) {
            String ip = getIpForInterface(iface);
            if (ip != null && !ip.equals("0.0.0.0")) return ip;
        }
        // Fallback: WifiManager
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = wm.getConnectionInfo();
            int ipInt = info.getIpAddress();
            if (ipInt != 0) return Formatter.formatIpAddress(ipInt);
        } catch (Exception ignored) {}
        // Last resort: any non-loopback
        try {
            List<NetworkInterface> ifaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface ni : ifaces) {
                List<InetAddress> addrs = Collections.list(ni.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress() && addr.getHostAddress().indexOf(':') < 0) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }

    private static String getIpForInterface(String ifaceName) {
        try {
            NetworkInterface ni = NetworkInterface.getByName(ifaceName);
            if (ni == null || !ni.isUp()) return null;
            List<InetAddress> addrs = Collections.list(ni.getInetAddresses());
            for (InetAddress addr : addrs) {
                if (!addr.isLoopbackAddress() && addr.getHostAddress().indexOf(':') < 0) {
                    return addr.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    public static String getServerUrl(Context ctx) {
        return "http://" + getLocalIpAddress(ctx) + ":" + SERVER_PORT;
    }

    /** Typical hotspot gateway is 192.168.43.1 */
    public static String getHotspotGateway() {
        return "192.168.43.1";
    }

    public static boolean isValidIp(String ip) {
        return ip != null && ip.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
    }
}
