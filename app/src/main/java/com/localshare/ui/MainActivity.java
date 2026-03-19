package com.localshare.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.localshare.client.ApiClient;
import com.localshare.client.RoomScanner;
import com.localshare.model.RoomConfig;
import com.localshare.utils.AutoJoinDetector;
import com.localshare.utils.HotspotManager;
import com.localshare.utils.NetworkUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_PERMS = 100;

    private TextView   statusTv, scanStatusTv, networkBadgeTv;
    private Button     scanBtn;
    private ProgressBar detectBar;
    private HotspotManager hotspotManager;
    private boolean permissionsReady = false;

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        hotspotManager = new HotspotManager(this);
        setContentView(buildLayout());
        checkPermissions();
    }

    @Override protected void onResume() {
        super.onResume();
        updateNetworkBadge();
        if (permissionsReady) runAutoDetect();
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    private View buildLayout() {
        LinearLayout root = vBox(0xFF0D1117, 0);
        root.setGravity(Gravity.CENTER);
        root.setPadding(40, 40, 40, 40);

        // Title
        TextView logo = tv("LOCAL SHARE", 0xFF00FF41, 28, true);
        logo.setGravity(Gravity.CENTER);
        root.addView(logo);
        TextView ver = tv("v5 · offline · any network", 0xFF333333, 10, true);
        ver.setGravity(Gravity.CENTER); ver.setPadding(0, 4, 0, 20);
        root.addView(ver);

        // Network badge — shows current network status
        networkBadgeTv = tv("", 0xFF888888, 11, true);
        networkBadgeTv.setGravity(Gravity.CENTER);
        networkBadgeTv.setBackgroundColor(0xFF161B22);
        networkBadgeTv.setPadding(16, 10, 16, 10);
        root.addView(networkBadgeTv, matchWrap());

        root.addView(spacer(12));

        // Auto-detect banner
        LinearLayout detectBox = vBox(0xFF161B22, 14);
        statusTv = tv("Scanning for nearby rooms…", 0xFFFFAA00, 12, true);
        detectBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        detectBar.setIndeterminate(true);
        detectBox.addView(statusTv); detectBox.addView(detectBar);
        root.addView(detectBox, matchWrap());

        root.addView(spacer(20));

        // HOST
        Button hostBtn = btn("[ HOST A ROOM ]", 0xFF00FF41, 0xFF0D1117);
        hostBtn.setOnClickListener(v -> showHostDialog());
        root.addView(hostBtn, matchWrap());

        // Separator
        TextView orTv = tv("──────── or ────────", 0xFF333333, 11, true);
        orTv.setGravity(Gravity.CENTER); orTv.setPadding(0, 14, 0, 14);
        root.addView(orTv);

        // JOIN
        Button joinBtn = btn("[ JOIN BY IP ]", 0xFF161B22, 0xFF00FF41);
        joinBtn.setOnClickListener(v -> showJoinDialog());
        root.addView(joinBtn, matchWrap());

        root.addView(spacer(10));

        // SCAN
        scanBtn = btn("[ SCAN NETWORK ]", 0xFF161B22, 0xFF888888);
        scanBtn.setOnClickListener(v -> autoScan());
        root.addView(scanBtn, matchWrap());

        scanStatusTv = tv("", 0xFF555555, 11, true);
        scanStatusTv.setGravity(Gravity.CENTER); scanStatusTv.setPadding(0, 6, 0, 0);
        root.addView(scanStatusTv);

        root.addView(spacer(16));

        // Instructions for both modes
        LinearLayout instructBox = vBox(0xFF161B22, 14);
        instructBox.addView(tv("HOW TO CONNECT", 0xFF00FF41, 9, true));
        instructBox.addView(spacer(6));
        instructBox.addView(tv("📶 Same WiFi  →  all join same router/WiFi\n    then Host + others Join", 0xFF555555, 10, true));
        instructBox.addView(spacer(6));
        instructBox.addView(tv("📡 Hotspot  →  one device enables hotspot\n    others connect to it, then Join", 0xFF555555, 10, true));
        root.addView(instructBox, matchWrap());

        return root;
    }

    private void updateNetworkBadge() {
        NetworkUtils.NetworkMode mode = NetworkUtils.detectMode(this);
        String myIp = NetworkUtils.getLocalIpAddress(this);
        String ssid = NetworkUtils.getWifiSsid(this);

        switch (mode) {
            case HOTSPOT:
                networkBadgeTv.setText("📡 HOTSPOT HOST  ·  " + myIp);
                networkBadgeTv.setTextColor(0xFF00FF41);
                break;
            case WIFI:
                String net = ssid != null ? "\"" + ssid + "\"" : "WiFi";
                networkBadgeTv.setText("📶 " + net + "  ·  " + myIp);
                networkBadgeTv.setTextColor(0xFF00AAFF);
                break;
            default:
                networkBadgeTv.setText("⚠ No network  ·  " + myIp);
                networkBadgeTv.setTextColor(0xFFFF4444);
                break;
        }
    }

    // ── Auto-detect ───────────────────────────────────────────────────────────

    private void runAutoDetect() {
        updateNetworkBadge();

        NetworkUtils.NetworkMode mode = NetworkUtils.detectMode(this);
        if (mode == NetworkUtils.NetworkMode.UNKNOWN) {
            statusTv.setText("No network detected. Connect to WiFi or enable hotspot.");
            statusTv.setTextColor(0xFFFF4444);
            detectBar.setVisibility(View.GONE);
            return;
        }

        statusTv.setText("Scanning for rooms on your network…");
        statusTv.setTextColor(0xFFFFAA00);
        detectBar.setVisibility(View.VISIBLE);

        AutoJoinDetector.detect(this, new AutoJoinDetector.DetectCallback() {
            @Override public void onFound(String hostIp, String roomCode, boolean isProtected) {
                runOnUiThread(() -> {
                    detectBar.setVisibility(View.GONE);
                    statusTv.setText("✓ Room " + roomCode + " @ " + hostIp);
                    statusTv.setTextColor(0xFF00FF41);
                    showAutoFoundDialog(hostIp, roomCode, isProtected);
                });
            }
            @Override public void onNotFound() {
                runOnUiThread(() -> {
                    detectBar.setVisibility(View.GONE);
                    NetworkUtils.NetworkMode m = NetworkUtils.detectMode(MainActivity.this);
                    if (m == NetworkUtils.NetworkMode.WIFI) {
                        statusTv.setText("No rooms found on " + (NetworkUtils.getWifiSsid(MainActivity.this) != null ? "\""+NetworkUtils.getWifiSsid(MainActivity.this)+"\"" : "this WiFi") + ". Host one!");
                    } else if (m == NetworkUtils.NetworkMode.HOTSPOT) {
                        statusTv.setText("Hotspot active. Waiting for others to join…");
                    } else {
                        statusTv.setText("No rooms found. Connect to a network first.");
                    }
                    statusTv.setTextColor(0xFF444444);
                });
            }
        });
    }

    private void showAutoFoundDialog(String ip, String roomCode, boolean isProtected) {
        NetworkUtils.NetworkMode mode = NetworkUtils.detectMode(this);
        String networkInfo = mode == NetworkUtils.NetworkMode.WIFI
                ? "📶 " + (NetworkUtils.getWifiSsid(this) != null ? NetworkUtils.getWifiSsid(this) : "WiFi")
                : "📡 Hotspot";

        LinearLayout layout = vBox(0xFF0D1117, 32);
        layout.addView(tv("Room detected on your network!", 0xFF00FF41, 13, true));
        layout.addView(spacer(8));
        layout.addView(tv("Room:     " + roomCode, 0xFFE6EDF3, 12, true));
        layout.addView(tv("Host IP:  " + ip, 0xFF888888, 11, true));
        layout.addView(tv("Network:  " + networkInfo, 0xFF888888, 11, true));
        layout.addView(tv(isProtected ? "🔒 Password required" : "🌐 Public room", 0xFFFFAA00, 11, true));

        EditText pwEt = null;
        if (isProtected) {
            layout.addView(spacer(8));
            pwEt = editText("", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            pwEt.setHint("Enter password"); layout.addView(pwEt);
        }
        final EditText finalPwEt = pwEt;
        new AlertDialog.Builder(this).setTitle("🎯 Room detected!")
            .setView(layout)
            .setPositiveButton("Join Now", (d, w) -> {
                String pw = finalPwEt != null ? finalPwEt.getText().toString().trim() : null;
                launchClientRoom(ip, pw != null && pw.isEmpty() ? null : pw);
            })
            .setNegativeButton("Dismiss", null).show();
    }

    // ── Host dialog ───────────────────────────────────────────────────────────

    private void showHostDialog() {
        NetworkUtils.NetworkMode mode = NetworkUtils.detectMode(this);
        String myIp = NetworkUtils.getLocalIpAddress(this);

        ScrollView sv = new ScrollView(this);
        LinearLayout layout = vBox(0xFF0D1117, 32); sv.addView(layout);

        // Network status at top
        LinearLayout netBox = vBox(0xFF161B22, 12);
        if (mode == NetworkUtils.NetworkMode.WIFI) {
            String ssid = NetworkUtils.getWifiSsid(this);
            netBox.addView(tv("📶 WiFi mode", 0xFF00AAFF, 13, true));
            netBox.addView(tv("Network: " + (ssid != null ? "\""+ssid+"\"" : "WiFi"), 0xFF888888, 11, true));
            netBox.addView(tv("Your IP: " + myIp, 0xFF00FF41, 11, true));
            netBox.addView(spacer(6));
            netBox.addView(tv("Others on the SAME WiFi can join with your IP.", 0xFF555555, 10, true));
        } else if (mode == NetworkUtils.NetworkMode.HOTSPOT) {
            netBox.addView(tv("📡 Hotspot mode", 0xFF00FF41, 13, true));
            netBox.addView(tv("Your IP: " + myIp, 0xFF00FF41, 11, true));
            netBox.addView(spacer(6));
            netBox.addView(tv("Others must connect to YOUR hotspot first.", 0xFF555555, 10, true));
        } else {
            netBox.addView(tv("⚠ No network detected", 0xFFFF4444, 13, true));
            netBox.addView(tv("Connect to WiFi or enable your hotspot.", 0xFFFF6666, 11, true));
        }
        layout.addView(netBox, matchWrap());
        layout.addView(spacer(12));

        layout.addView(sectionHeader("ROOM SETTINGS"));
        layout.addView(label("Room code:"));
        EditText codeEt = editText(generateCode(), InputType.TYPE_CLASS_TEXT);
        layout.addView(codeEt);
        layout.addView(label("Display name (optional):"));
        EditText nameEt = editText("", InputType.TYPE_CLASS_TEXT); nameEt.setHint("e.g. Office Share");
        layout.addView(nameEt);
        layout.addView(spacer(10));

        layout.addView(sectionHeader("ACCESS"));
        CheckBox pwCheck = checkBoxWidget("Password protect 🔒", false);
        layout.addView(pwCheck);
        EditText pwEt = editText("", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pwEt.setHint("Set password"); pwEt.setVisibility(View.GONE);
        layout.addView(pwEt);
        pwCheck.setOnCheckedChangeListener((b, on) -> pwEt.setVisibility(on ? View.VISIBLE : View.GONE));
        layout.addView(spacer(10));

        layout.addView(sectionHeader("LIMITS"));
        layout.addView(label("Max members (0=unlimited):"));
        EditText maxMEt = editText("0", InputType.TYPE_CLASS_NUMBER);
        layout.addView(maxMEt);
        layout.addView(label("Max file size MB (0=unlimited):"));
        EditText maxFEt = editText("0", InputType.TYPE_CLASS_NUMBER);
        layout.addView(maxFEt);
        layout.addView(spacer(10));

        layout.addView(sectionHeader("PERMISSIONS"));
        CheckBox upCb  = checkBoxWidget("Allow member uploads", true);
        CheckBox delCb = checkBoxWidget("Allow member deletes", false);
        layout.addView(upCb); layout.addView(delCb);

        // Hotspot helper (only show if not on WiFi)
        if (mode != NetworkUtils.NetworkMode.WIFI) {
            layout.addView(spacer(10));
            layout.addView(sectionHeader("HOTSPOT"));
            boolean hsOn = hotspotManager.isHotspotOn();
            layout.addView(tv(hsOn ? "✓ Hotspot ON" : "⚠ Hotspot OFF", hsOn ? 0xFF00FF41 : 0xFFFFAA00, 11, true));
            Button hsBtn = btn("Open Hotspot Settings →", 0xFF161B22, 0xFF888888);
            hsBtn.setOnClickListener(v -> hotspotManager.openHotspotSettings());
            layout.addView(hsBtn);
        }

        new AlertDialog.Builder(this).setTitle("Host a Room").setView(sv)
            .setPositiveButton("Start Room", (d, w) -> {
                String code = codeEt.getText().toString().trim();
                if (code.isEmpty()) code = generateCode();
                String pw = pwCheck.isChecked() ? pwEt.getText().toString().trim() : null;
                if (pw != null && pw.isEmpty()) pw = null;
                RoomConfig cfg = new RoomConfig();
                cfg.setRoomName(nameEt.getText().toString().trim());
                try { cfg.setMaxMembers(Integer.parseInt(maxMEt.getText().toString())); } catch (Exception ignored) {}
                try { cfg.setMaxFileSizeMb(Long.parseLong(maxFEt.getText().toString())); } catch (Exception ignored) {}
                cfg.setAllowUpload(upCb.isChecked()); cfg.setAllowDelete(delCb.isChecked());
                launchHostRoom(code, pw, cfg);
            })
            .setNegativeButton("Cancel", null).show();
    }

    // ── Join dialog ───────────────────────────────────────────────────────────

    private void showJoinDialog() {
        NetworkUtils.NetworkMode mode = NetworkUtils.detectMode(this);
        String myIp = NetworkUtils.getLocalIpAddress(this);
        String subnet = NetworkUtils.getSubnet(myIp);

        LinearLayout layout = vBox(0xFF0D1117, 32);

        // Hint based on mode
        LinearLayout hintBox = vBox(0xFF161B22, 12);
        if (mode == NetworkUtils.NetworkMode.WIFI) {
            hintBox.addView(tv("📶 WiFi mode — enter the host's IP address.\nBoth you and the host must be on the same WiFi.", 0xFF00AAFF, 11, true));
            hintBox.addView(tv("Host subnet: " + subnet + "x", 0xFF555555, 10, true));
        } else {
            hintBox.addView(tv("📡 Hotspot mode — connect to the host's hotspot\nfirst, then enter their IP (usually 192.168.43.1).", 0xFFFFAA00, 11, true));
        }
        layout.addView(hintBox, matchWrap());
        layout.addView(spacer(12));

        layout.addView(label("Host IP address:"));
        // Pre-fill based on mode
        String defaultIp = mode == NetworkUtils.NetworkMode.WIFI
                ? (NetworkUtils.getDhcpGateway(this) != null ? NetworkUtils.getDhcpGateway(this) : subnet + "1")
                : NetworkUtils.getHotspotGateway();
        EditText ipEt = editText(defaultIp, InputType.TYPE_CLASS_PHONE);
        layout.addView(ipEt);
        layout.addView(spacer(10));
        layout.addView(label("Password (blank if public):"));
        EditText pwEt = editText("", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pwEt.setHint("optional"); layout.addView(pwEt);

        new AlertDialog.Builder(this).setTitle("Join a Room").setView(layout)
            .setPositiveButton("Connect", (d, w) -> {
                String ip = ipEt.getText().toString().trim();
                String pw = pwEt.getText().toString().trim();
                if (!NetworkUtils.isValidIp(ip)) { toast("Invalid IP address"); return; }
                launchClientRoom(ip, pw.isEmpty() ? null : pw);
            })
            .setNegativeButton("Cancel", null).show();
    }

    // ── Scan ──────────────────────────────────────────────────────────────────

    private void autoScan() {
        NetworkUtils.NetworkMode mode = NetworkUtils.detectMode(this);
        if (mode == NetworkUtils.NetworkMode.UNKNOWN) {
            toast("Connect to a network first");
            return;
        }

        String myIp = NetworkUtils.getLocalIpAddress(this);
        String subnet = NetworkUtils.getSubnet(myIp);

        scanBtn.setEnabled(false);
        String networkName = mode == NetworkUtils.NetworkMode.WIFI
                ? (NetworkUtils.getWifiSsid(this) != null ? "\""+NetworkUtils.getWifiSsid(this)+"\"" : "WiFi")
                : "Hotspot";
        scanStatusTv.setText("Scanning " + networkName + " (" + subnet + "0/24)…");
        scanStatusTv.setTextColor(0xFFFFAA00);

        RoomScanner.scan(myIp, new RoomScanner.ScanCallback() {
            @Override public void onHostFound(String ip, String code) {
                runOnUiThread(() -> {
                    scanStatusTv.setText("Found: Room " + code + " @ " + ip);
                    scanStatusTv.setTextColor(0xFF00FF41);
                    // Check if password protected before showing dialog
                    new Thread(() -> {
                        ApiClient client = new ApiClient(ip, NetworkUtils.SERVER_PORT);
                        boolean prot = client.isRoomProtected();
                        runOnUiThread(() -> showScanFoundDialog(ip, code, prot));
                    }).start();
                });
            }
            @Override public void onScanComplete(List<String> found) {
                runOnUiThread(() -> {
                    scanBtn.setEnabled(true);
                    if (found.isEmpty()) {
                        scanStatusTv.setText("No rooms found on " + subnet + "0/24");
                        scanStatusTv.setTextColor(0xFFFF4444);
                    } else {
                        scanStatusTv.setText("Scan complete — " + found.size() + " room(s) found");
                        scanStatusTv.setTextColor(0xFF00FF41);
                    }
                });
            }
        });
    }

    private void showScanFoundDialog(String ip, String code, boolean isProtected) {
        LinearLayout layout = vBox(0xFF0D1117, 32);
        layout.addView(tv("Room " + code + " at " + ip, 0xFF00FF41, 13, true));
        layout.addView(tv(isProtected ? "🔒 Password required" : "🌐 Public room", 0xFFFFAA00, 11, true));
        EditText pwEt = null;
        if (isProtected) {
            layout.addView(spacer(8));
            pwEt = editText("", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            pwEt.setHint("Enter password"); layout.addView(pwEt);
        }
        final EditText finalPwEt = pwEt;
        new AlertDialog.Builder(this).setTitle("Room found!")
            .setView(layout)
            .setPositiveButton("Join", (d, w) -> {
                String pw = finalPwEt != null ? finalPwEt.getText().toString().trim() : null;
                launchClientRoom(ip, pw != null && pw.isEmpty() ? null : pw);
            })
            .setNegativeButton("Cancel", null).show();
    }

    // ── Launch ────────────────────────────────────────────────────────────────

    private void launchHostRoom(String code, String password, RoomConfig cfg) {
        Intent i = new Intent(this, RoomActivity.class);
        i.putExtra(RoomActivity.EXTRA_IS_HOST, true);
        i.putExtra(RoomActivity.EXTRA_ROOM_CODE, code);
        if (password != null) i.putExtra(RoomActivity.EXTRA_PASSWORD, password);
        i.putExtra(RoomActivity.EXTRA_MAX_MEMBERS, cfg.getMaxMembers());
        i.putExtra(RoomActivity.EXTRA_ALLOW_UPLOAD, cfg.isAllowUpload());
        i.putExtra(RoomActivity.EXTRA_ALLOW_DELETE, cfg.isAllowDelete());
        i.putExtra(RoomActivity.EXTRA_MAX_FILE_MB, cfg.getMaxFileSizeMb());
        i.putExtra(RoomActivity.EXTRA_ROOM_NAME, cfg.getRoomName());
        startActivity(i);
    }

    private void launchClientRoom(String ip, String password) {
        Intent i = new Intent(this, RoomActivity.class);
        i.putExtra(RoomActivity.EXTRA_IS_HOST, false);
        i.putExtra(RoomActivity.EXTRA_HOST_IP, ip);
        if (password != null) i.putExtra(RoomActivity.EXTRA_PASSWORD, password);
        startActivity(i);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String generateCode() {
        String c = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; Random r = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) sb.append(c.charAt(r.nextInt(c.length())));
        return sb.toString();
    }

    private LinearLayout vBox(int bg, int pad) {
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL);
        l.setBackgroundColor(bg); l.setPadding(pad,pad,pad,pad); return l;
    }
    private TextView tv(String t, int c, float sp, boolean mono) {
        TextView v = new TextView(this); v.setText(t); v.setTextColor(c); v.setTextSize(sp);
        if (mono) v.setTypeface(android.graphics.Typeface.MONOSPACE); return v;
    }
    private Button btn(String t, int bg, int fg) {
        Button b = new Button(this); b.setText(t); b.setTextColor(fg); b.setBackgroundColor(bg);
        b.setTypeface(android.graphics.Typeface.MONOSPACE); b.setAllCaps(false); b.setPadding(24,18,24,18); return b;
    }
    private EditText editText(String def, int type) {
        EditText et = new EditText(this); et.setText(def); et.setInputType(type);
        et.setTextColor(0xFFE6EDF3); et.setHintTextColor(0xFF555555);
        et.setBackgroundColor(0xFF21262D); et.setPadding(16,12,16,12); return et;
    }
    private CheckBox checkBoxWidget(String label, boolean checked) {
        CheckBox cb = new CheckBox(this); cb.setText("  " + label); cb.setChecked(checked);
        cb.setTextColor(0xFFE6EDF3);
        cb.setButtonTintList(android.content.res.ColorStateList.valueOf(0xFF00FF41)); return cb;
    }
    private View spacer(int dp) {
        View v = new View(this); int px = Math.round(dp * getResources().getDisplayMetrics().density);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, px)); return v;
    }
    private TextView sectionHeader(String t) { TextView v = tv(t,0xFF00FF41,10,true); v.setPadding(0,8,0,4); return v; }
    private TextView label(String t) { return tv(t, 0xFF888888, 11, true); }
    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }
    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); }

    private void checkPermissions() {
        List<String> needed = new ArrayList<>();
        String[] perms = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            ? new String[]{Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
                           Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS,
                           Manifest.permission.ACCESS_FINE_LOCATION}
            : new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                           Manifest.permission.WRITE_EXTERNAL_STORAGE,
                           Manifest.permission.ACCESS_FINE_LOCATION};
        for (String p : perms)
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED)
                needed.add(p);
        if (!needed.isEmpty())
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), REQ_PERMS);
        else { permissionsReady = true; runAutoDetect(); }
    }

    @Override public void onRequestPermissionsResult(int r, @NonNull String[] p, @NonNull int[] g) {
        super.onRequestPermissionsResult(r, p, g); permissionsReady = true; runAutoDetect();
    }
}
