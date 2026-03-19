package com.localshare.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.localshare.client.RoomScanner;
import com.localshare.utils.NetworkUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_PERMS = 100;
    private TextView scanStatusTv;
    private Button   scanBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildLayout());
        checkPermissions();
    }

    private View buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xFF0D1117);
        root.setPadding(48, 64, 48, 48);

        // ── ASCII logo ───────────────────────────────────────────────────
        TextView logo = new TextView(this);
        logo.setText(
            "  _    ___   ___ _   _    _   _      \n" +
            " | |  / _ \\ / __| | | |  | | | |     \n" +
            " | |_| (_) | (__| |_| |  | |_| |     \n" +
            " |____\\___/ \\___|\\__,_|   \\___/      \n" +
            "  ___ _  _   _   ___ ___             \n" +
            " / __| || | /_\\ | _ \\ __|            \n" +
            " \\__ \\ __ |/ _ \\|   / _|             \n" +
            " |___/_||_/_/ \\_\\_|_\\___|            ");
        logo.setTextColor(0xFF00FF41);
        logo.setTextSize(8);
        logo.setTypeface(android.graphics.Typeface.MONOSPACE);
        logo.setGravity(Gravity.CENTER);
        root.addView(logo);

        TextView tagline = new TextView(this);
        tagline.setText("local · offline · fast");
        tagline.setTextColor(0xFF444444);
        tagline.setTextSize(11);
        tagline.setTypeface(android.graphics.Typeface.MONOSPACE);
        tagline.setGravity(Gravity.CENTER);
        tagline.setPadding(0, 4, 0, 56);
        root.addView(tagline);

        // ── Host button ──────────────────────────────────────────────────
        Button hostBtn = makeBtn("[ HOST A ROOM ]", 0xFF00FF41, 0xFF0D1117);
        hostBtn.setOnClickListener(v -> hostRoom());
        root.addView(hostBtn);

        TextView orTxt = new TextView(this);
        orTxt.setText("──────── or ────────");
        orTxt.setTextColor(0xFF333333);
        orTxt.setTypeface(android.graphics.Typeface.MONOSPACE);
        orTxt.setGravity(Gravity.CENTER);
        orTxt.setPadding(0, 20, 0, 20);
        root.addView(orTxt);

        // ── Join button ──────────────────────────────────────────────────
        Button joinBtn = makeBtn("[ JOIN BY IP ]", 0xFF161B22, 0xFF00FF41);
        joinBtn.setOnClickListener(v -> joinByIp());
        root.addView(joinBtn);

        View divider = new View(this);
        divider.setBackgroundColor(0xFF1A1A1A);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
        LinearLayout.LayoutParams divLp = (LinearLayout.LayoutParams) divider.getLayoutParams();
        divLp.setMargins(0, 24, 0, 24);
        root.addView(divider, divLp);

        // ── Auto-scan button ─────────────────────────────────────────────
        scanBtn = makeBtn("[ AUTO-SCAN NETWORK ]", 0xFF161B22, 0xFF888888);
        scanBtn.setOnClickListener(v -> autoScan());
        root.addView(scanBtn);

        scanStatusTv = new TextView(this);
        scanStatusTv.setTextColor(0xFF444444);
        scanStatusTv.setTextSize(11);
        scanStatusTv.setTypeface(android.graphics.Typeface.MONOSPACE);
        scanStatusTv.setGravity(Gravity.CENTER);
        scanStatusTv.setPadding(0, 8, 0, 0);
        scanStatusTv.setVisibility(View.GONE);
        root.addView(scanStatusTv);

        // ── Hint ─────────────────────────────────────────────────────────
        TextView hint = new TextView(this);
        hint.setText("\n① Host: enable your phone's hotspot, then HOST\n" +
                     "② Others: join that hotspot in Wi-Fi settings\n" +
                     "③ Others: tap JOIN or AUTO-SCAN");
        hint.setTextColor(0xFF333333);
        hint.setTextSize(10);
        hint.setTypeface(android.graphics.Typeface.MONOSPACE);
        hint.setGravity(Gravity.CENTER);
        root.addView(hint);

        return root;
    }

    private Button makeBtn(String text, int bg, int fg) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(fg);
        btn.setTypeface(android.graphics.Typeface.MONOSPACE);
        btn.setTextSize(14);
        btn.setBackgroundColor(bg);
        btn.setPadding(24, 18, 24, 18);
        btn.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, 10);
        btn.setLayoutParams(lp);
        return btn;
    }

    // ── actions ──────────────────────────────────────────────────────────────

    private void hostRoom() {
        String code = generateCode();
        Intent i = new Intent(this, RoomActivity.class);
        i.putExtra(RoomActivity.EXTRA_IS_HOST, true);
        i.putExtra(RoomActivity.EXTRA_ROOM_CODE, code);
        startActivity(i);
    }

    private void joinByIp() {
        LinearLayout layout = new LinearLayout(this);
        layout.setPadding(40, 20, 40, 10);

        EditText ipInput = new EditText(this);
        ipInput.setHint("e.g. 192.168.43.1");
        ipInput.setText(NetworkUtils.getHotspotGateway());
        ipInput.setInputType(InputType.TYPE_CLASS_PHONE);
        layout.addView(ipInput);

        new AlertDialog.Builder(this)
            .setTitle("Enter host IP")
            .setMessage("The host IP is shown on the host's room screen.")
            .setView(layout)
            .setPositiveButton("Connect", (d, w) -> {
                String ip = ipInput.getText().toString().trim();
                if (!NetworkUtils.isValidIp(ip)) {
                    Toast.makeText(this, "Invalid IP address", Toast.LENGTH_SHORT).show();
                    return;
                }
                launchClientRoom(ip);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void autoScan() {
        String myIp = NetworkUtils.getLocalIpAddress(this);
        String subnet = myIp.substring(0, myIp.lastIndexOf('.') + 1);

        scanBtn.setEnabled(false);
        scanBtn.setTextColor(0xFF444444);
        scanStatusTv.setText("Scanning " + subnet + "0/24 …");
        scanStatusTv.setTextColor(0xFFFFAA00);
        scanStatusTv.setVisibility(View.VISIBLE);

        RoomScanner.scan(myIp, new RoomScanner.ScanCallback() {
            @Override public void onHostFound(String ip, String code) {
                runOnUiThread(() -> {
                    scanStatusTv.setText("Found room " + code + " at " + ip);
                    scanStatusTv.setTextColor(0xFF00FF41);
                    new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Room found!")
                        .setMessage("Room " + code + " at " + ip + "\nConnect?")
                        .setPositiveButton("Yes", (d,w) -> launchClientRoom(ip))
                        .setNegativeButton("Keep scanning", null)
                        .show();
                });
            }
            @Override public void onScanComplete(List<String> found) {
                runOnUiThread(() -> {
                    scanBtn.setEnabled(true);
                    scanBtn.setTextColor(0xFF888888);
                    if (found.isEmpty()) {
                        scanStatusTv.setText("No rooms found. Are you on the host's hotspot?");
                        scanStatusTv.setTextColor(0xFFFF4444);
                    }
                });
            }
        });
    }

    private void launchClientRoom(String ip) {
        Intent i = new Intent(this, RoomActivity.class);
        i.putExtra(RoomActivity.EXTRA_IS_HOST, false);
        i.putExtra(RoomActivity.EXTRA_HOST_IP, ip);
        startActivity(i);
    }

    private String generateCode() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        Random rnd = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) sb.append(chars.charAt(rnd.nextInt(chars.length())));
        return sb.toString();
    }

    // ── permissions ──────────────────────────────────────────────────────────

    private void checkPermissions() {
        List<String> needed = new ArrayList<>();
        String[] perms;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms = new String[]{
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.ACCESS_FINE_LOCATION
            };
        } else {
            perms = new String[]{
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.ACCESS_FINE_LOCATION
            };
        }
        for (String p : perms)
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED)
                needed.add(p);
        if (!needed.isEmpty())
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
    }
}
