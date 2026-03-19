package com.localshare.utils;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.text.format.Formatter;
import android.util.Log;

import com.localshare.client.ApiClient;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scans the local subnet for LocalShare rooms.
 * Works on BOTH hotspot networks and regular WiFi/router networks.
 * Uses 32 threads to scan all 254 hosts in ~2-3 seconds.
 */
public class AutoJoinDetector {

    private static final String TAG = "AutoJoinDetector";

    public interface DetectCallback {
        void onFound(String hostIp, String roomCode, boolean isProtected);
        void onNotFound();
    }

    public static void detect(Context ctx, DetectCallback cb) {
        new Thread(() -> {
            String myIp = NetworkUtils.getLocalIpAddress(ctx);
            Log.i(TAG, "Scanning from IP: " + myIp);

            // Build priority candidate list
            List<String> priority = buildPriorityCandidates(ctx, myIp);

            // Check priority candidates first (fast, ~200ms)
            for (String ip : priority) {
                if (ip.equals(myIp)) continue;
                JSONObject info = quickPing(ip);
                if (info != null && !info.optBoolean("closed", false)) {
                    cb.onFound(ip, info.optString("room","????"), info.optBoolean("protected",false));
                    return;
                }
            }

            // Full subnet scan
            String subnet = NetworkUtils.getSubnet(myIp);
            ExecutorService pool = Executors.newFixedThreadPool(32);
            List<Future<?>> futures = new ArrayList<>();
            AtomicBoolean found = new AtomicBoolean(false);

            for (int i = 1; i <= 254; i++) {
                final String ip = subnet + i;
                if (ip.equals(myIp) || priority.contains(ip)) continue;
                futures.add(pool.submit(() -> {
                    if (found.get()) return;
                    JSONObject info = quickPing(ip);
                    if (info != null && !info.optBoolean("closed", false) && !found.getAndSet(true)) {
                        cb.onFound(ip, info.optString("room","????"), info.optBoolean("protected",false));
                    }
                }));
            }

            pool.shutdown();
            try { pool.awaitTermination(6, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) {}

            if (!found.get()) cb.onNotFound();
        }, "AutoJoinDetector").start();
    }

    private static JSONObject quickPing(String ip) {
        try {
            ApiClient client = new ApiClient(ip, NetworkUtils.SERVER_PORT);
            return client.pingInfo();
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Build a list of likely host IPs to check first before full scan.
     * Covers hotspot gateways, DHCP gateway, and first few hosts on subnet.
     */
    private static List<String> buildPriorityCandidates(Context ctx, String myIp) {
        List<String> list = new ArrayList<>();

        // DHCP gateway — most likely host on any network (router, hotspot, etc.)
        String dhcpGw = NetworkUtils.getDhcpGateway(ctx);
        if (dhcpGw != null && !list.contains(dhcpGw)) list.add(dhcpGw);

        // Common hotspot gateways
        for (String gw : new String[]{
                "192.168.43.1",   // Android hotspot
                "172.20.10.1",    // iPhone hotspot
                "192.168.0.1",    // Router common
                "192.168.1.1",    // Router common
                "10.0.0.1",       // Some carriers
                "192.168.137.1",  // Windows hotspot
        }) {
            if (!list.contains(gw)) list.add(gw);
        }

        // First few hosts on the same subnet as this device
        if (myIp != null && myIp.contains(".")) {
            String subnet = NetworkUtils.getSubnet(myIp);
            for (int i = 1; i <= 10; i++) {
                String ip = subnet + i;
                if (!list.contains(ip)) list.add(ip);
            }
        }

        return list;
    }
}
