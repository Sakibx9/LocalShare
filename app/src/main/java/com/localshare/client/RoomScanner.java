package com.localshare.client;

import android.util.Log;
import com.localshare.utils.NetworkUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Scans the local subnet for active LocalShare servers.
 * Uses a 32-thread pool to ping all 254 hosts in ~2 seconds.
 */
public class RoomScanner {

    private static final String TAG = "RoomScanner";

    public interface ScanCallback {
        void onHostFound(String ip, String roomCode);
        void onScanComplete(List<String> found);
    }

    public static void scan(String localIp, ScanCallback cb) {
        new Thread(() -> {
            String subnet = getSubnet(localIp);
            if (subnet == null) {
                cb.onScanComplete(new ArrayList<>());
                return;
            }

            ExecutorService pool = Executors.newFixedThreadPool(32);
            List<Future<?>> futures = new ArrayList<>();
            List<String> found = new ArrayList<>();

            for (int i = 1; i <= 254; i++) {
                final String ip = subnet + i;
                if (ip.equals(localIp)) continue;

                futures.add(pool.submit(() -> {
                    ApiClient client = new ApiClient(ip, NetworkUtils.SERVER_PORT);
                    String code = client.ping();
                    if (code != null) {
                        Log.i(TAG, "Found host at " + ip + " room=" + code);
                        synchronized (found) { found.add(ip); }
                        cb.onHostFound(ip, code);
                    }
                }));
            }

            pool.shutdown();
            try { pool.awaitTermination(5, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) {}

            cb.onScanComplete(found);
        }, "RoomScanner").start();
    }

    private static String getSubnet(String ip) {
        int lastDot = ip.lastIndexOf('.');
        if (lastDot < 0) return null;
        return ip.substring(0, lastDot + 1);
    }
}
