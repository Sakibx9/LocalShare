package com.localshare.utils;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.text.format.Formatter;
import android.util.Log;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

public class NetworkUtils {

    private static final String TAG = "NetworkUtils";
    public static final int SERVER_PORT = 8765;

    public enum NetworkMode {
        HOTSPOT,   // This device is sharing its own hotspot
        WIFI,      // Connected to an external WiFi / router
        UNKNOWN
    }

    /** Get the best local IP address for this device */
    public static String getLocalIpAddress(Context ctx) {
        // Try WiFi manager first (most reliable for WiFi client)
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm != null && wm.isWifiEnabled()) {
                WifiInfo info = wm.getConnectionInfo();
                int ipInt = info.getIpAddress();
                if (ipInt != 0) {
                    return Formatter.formatIpAddress(ipInt);
                }
            }
        } catch (Exception ignored) {}

        // Try hotspot / AP interfaces
        for (String iface : new String[]{"wlan0","ap0","swlan0","wlan1","eth0"}) {
            String ip = getIpForInterface(iface);
            if (ip != null && !ip.startsWith("127.")) return ip;
        }

        // Last resort: any non-loopback IPv4
        try {
            List<NetworkInterface> ifaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface ni : ifaces) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    String h = addr.getHostAddress();
                    if (h != null && !addr.isLoopbackAddress() && !h.contains(":")) {
                        return h;
                    }
                }
            }
        } catch (Exception ignored) {}

        return "127.0.0.1";
    }

    /** Detect whether this device is a hotspot host, WiFi client, or unknown */
    public static NetworkMode detectMode(Context ctx) {
        HotspotManager hm = new HotspotManager(ctx);
        if (hm.isHotspotOn()) return NetworkMode.HOTSPOT;

        try {
            ConnectivityManager cm = (ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network net = cm.getActiveNetwork();
                if (net != null) {
                    NetworkCapabilities nc = cm.getNetworkCapabilities(net);
                    if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        return NetworkMode.WIFI;
                    }
                }
            } else {
                WifiManager wm = (WifiManager) ctx.getApplicationContext()
                        .getSystemService(Context.WIFI_SERVICE);
                if (wm != null && wm.isWifiEnabled() && wm.getConnectionInfo().getIpAddress() != 0) {
                    return NetworkMode.WIFI;
                }
            }
        } catch (Exception ignored) {}

        return NetworkMode.UNKNOWN;
    }

    /** Get the subnet string e.g. "192.168.1." from an IP */
    public static String getSubnet(String ip) {
        if (ip == null) return "192.168.1.";
        int last = ip.lastIndexOf('.');
        return last >= 0 ? ip.substring(0, last + 1) : "192.168.1.";
    }

    /** Get gateway IP from DHCP info (useful for joining hotspots) */
    public static String getDhcpGateway(Context ctx) {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                int gw = wm.getDhcpInfo().gateway;
                if (gw != 0) return Formatter.formatIpAddress(gw);
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Returns the connected WiFi SSID or null */
    public static String getWifiSsid(Context ctx) {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                WifiInfo info = wm.getConnectionInfo();
                String ssid = info.getSSID();
                if (ssid != null && !ssid.equals("<unknown ssid>")) {
                    return ssid.replace("\"", "");
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    public static String getServerUrl(Context ctx) {
        return "http://" + getLocalIpAddress(ctx) + ":" + SERVER_PORT;
    }

    /** The default hotspot gateway — used as first guess when joining */
    public static String getHotspotGateway() { return "192.168.43.1"; }

    public static boolean isValidIp(String ip) {
        return ip != null && ip.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
    }

    private static String getIpForInterface(String ifaceName) {
        try {
            NetworkInterface ni = NetworkInterface.getByName(ifaceName);
            if (ni == null || !ni.isUp()) return null;
            for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                String h = addr.getHostAddress();
                if (h != null && !addr.isLoopbackAddress() && !h.contains(":")) return h;
            }
        } catch (Exception ignored) {}
        return null;
    }
}
