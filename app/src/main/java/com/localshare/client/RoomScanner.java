package com.localshare.client;

import android.util.Log;

import com.localshare.utils.NetworkUtils;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Full subnet scanner — finds all LocalShare rooms on the LAN.
 * Works on hotspot networks AND regular WiFi/router networks.
 */
public class RoomScanner {

    private static final String TAG = "RoomScanner";

    public interface ScanCallback {
        void onHostFound(String ip, String roomCode);
        void onScanComplete(List<String> found);
    }

    public static void scan(String localIp, ScanCallback cb) {
        new Thread(() -> {
            String subnet = NetworkUtils.getSubnet(localIp);
            Log.i(TAG, "Scanning subnet: " + subnet + "0/24");

            ExecutorService pool = Executors.newFixedThreadPool(32);
            List<Future<?>> futures = new ArrayList<>();
            List<String> found = new CopyOnWriteArrayList<>();

            for (int i = 1; i <= 254; i++) {
                final String ip = subnet + i;
                if (ip.equals(localIp)) continue;
                futures.add(pool.submit(() -> {
                    try {
                        ApiClient client = new ApiClient(ip, NetworkUtils.SERVER_PORT);
                        JSONObject info = client.pingInfo();
                        if (info != null && !info.optBoolean("closed", false)) {
                            String code = info.optString("room", "????");
                            Log.i(TAG, "Found room " + code + " @ " + ip);
                            found.add(ip);
                            cb.onHostFound(ip, code);
                        }
                    } catch (Exception ignored) {}
                }));
            }

            pool.shutdown();
            try { pool.awaitTermination(8, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) {}

            cb.onScanComplete(new ArrayList<>(found));
        }, "RoomScanner").start();
    }
}
