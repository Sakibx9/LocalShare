package com.localshare.utils;

import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.Settings;

import java.lang.reflect.Method;

/**
 * Handles hotspot enable/disable.
 * Android 8+: opens Settings panel (no programmatic control without root).
 * Android 7 and below: uses reflection to toggle.
 */
public class HotspotManager {

    public interface HotspotCallback {
        void onStarted(String ip);
        void onFailed(String reason);
    }

    private final Context ctx;
    private Object reservationObj; // Android 8+ hotspot reservation

    public HotspotManager(Context ctx) {
        this.ctx = ctx;
    }

    public boolean isHotspotOn() {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            Method method = wm.getClass().getDeclaredMethod("isWifiApEnabled");
            method.setAccessible(true);
            return (Boolean) method.invoke(wm);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * On Android 8+: opens the hotspot settings screen for user to enable manually.
     * Returns false so caller can show instructions.
     */
    public boolean tryEnableHotspot() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Can't enable programmatically — open settings
            return false;
        }
        // Android 7 and below — reflection approach
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            Method setWifiApEnabled = wm.getClass().getDeclaredMethod(
                    "setWifiApEnabled", android.net.wifi.WifiConfiguration.class, boolean.class);
            setWifiApEnabled.setAccessible(true);
            wm.setWifiEnabled(false);
            setWifiApEnabled.invoke(wm, null, true);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Opens the system hotspot settings page */
    public void openHotspotSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_WIRELESS_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
        } catch (Exception e) {
            Intent intent = new Intent(Settings.ACTION_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
        }
    }

    /** Gets the SSID of the hotspot if available */
    public String getHotspotSsid() {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            Method getWifiApConfig = wm.getClass().getDeclaredMethod("getWifiApConfiguration");
            getWifiApConfig.setAccessible(true);
            android.net.wifi.WifiConfiguration config =
                    (android.net.wifi.WifiConfiguration) getWifiApConfig.invoke(wm);
            if (config != null && config.SSID != null) return config.SSID;
        } catch (Exception ignored) {}
        return "LocalShare Hotspot";
    }
}
