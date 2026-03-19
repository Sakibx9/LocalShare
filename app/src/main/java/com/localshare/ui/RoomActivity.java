package com.localshare.ui;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.localshare.client.ApiClient;
import com.localshare.client.RoomScanner;
import com.localshare.client.TransferManager;
import com.localshare.model.MediaItem;
import com.localshare.server.FileServerService;
import com.localshare.utils.FileUtils;
import com.localshare.utils.NetworkUtils;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RoomActivity extends AppCompatActivity {

    public static final String EXTRA_IS_HOST   = "is_host";
    public static final String EXTRA_ROOM_CODE = "room_code";
    public static final String EXTRA_HOST_IP   = "host_ip";

    private boolean isHost;
    private String  roomCode;
    private String  hostIp;
    private String  deviceName;

    // Host
    private FileServerService serverService;
    private boolean           serviceBound;

    // Client
    private ApiClient       apiClient;
    private TransferManager transferManager;

    private final ExecutorService bgPool     = Executors.newFixedThreadPool(4);
    private final Handler         mainHandler = new Handler(Looper.getMainLooper());

    // UI refs
    private MediaAdapter adapter;
    private TextView     statusTv, ipTv, codeTv, emptyTv;
    private ProgressBar  uploadBar;
    private TextView     uploadBarLabel;
    private LinearLayout transferPanel;

    // ── file picker ──────────────────────────────────────────────────────────
    private final ActivityResultLauncher<String[]> filePicker =
        registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(),
            uris -> { if (uris != null) for (Uri u : uris) uploadFile(u); });

    // ── lifecycle ────────────────────────────────────────────────────────────

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        isHost     = getIntent().getBooleanExtra(EXTRA_IS_HOST, false);
        roomCode   = getIntent().getStringExtra(EXTRA_ROOM_CODE);
        hostIp     = getIntent().getStringExtra(EXTRA_HOST_IP);
        deviceName = Build.MODEL;

        transferManager = new TransferManager();
        setContentView(buildLayout());

        if (isHost) {
            startServerService();
            registerFileReceiver();
        } else {
            connectToHost();
            startPolling();
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (serviceBound) { unbindService(serviceConn); serviceBound = false; }
        bgPool.shutdownNow();
        transferManager.shutdown();
        mainHandler.removeCallbacksAndMessages(null);
        try { unregisterReceiver(fileReceiver); } catch (Exception ignored) {}
    }

    // ── layout (pure code – no XML needed) ──────────────────────────────────

    private View buildLayout() {
        LinearLayout root = vBox(0xFF0D1117, 0);

        // ── top info card ────────────────────────────────────────────────
        LinearLayout card = vBox(0xFF161B22, 20);

        codeTv = tv("", 0xFF00FF41, 20, true);
        ipTv   = tv("", 0xFF888888, 11, true);
        statusTv = tv("Starting…", 0xFFFFAA00, 11, true);
        card.addView(codeTv);
        card.addView(ipTv);
        card.addView(statusTv);
        root.addView(card, matchWrap());

        root.addView(divider());

        // ── active transfers ─────────────────────────────────────────────
        transferPanel = vBox(0xFF0D1117, 8);
        transferPanel.setVisibility(View.GONE);
        root.addView(transferPanel, matchWrap());

        // ── file list ────────────────────────────────────────────────────
        emptyTv = tv("No files shared yet.", 0xFF444444, 13, true);
        emptyTv.setGravity(Gravity.CENTER);
        emptyTv.setPadding(0, 48, 0, 0);

        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new MediaAdapter();
        rv.setAdapter(adapter);

        LinearLayout.LayoutParams rvLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        rvLp.setMargins(0, 4, 0, 4);
        rv.setLayoutParams(rvLp);

        root.addView(emptyTv, matchWrap());
        root.addView(rv, rvLp);

        root.addView(divider());

        // ── bottom bar ───────────────────────────────────────────────────
        LinearLayout bottom = hBox(0xFF0D1117, 12);

        Button shareBtn = btn("＋ SHARE", 0xFF00FF41, 0xFF0D1117);
        shareBtn.setOnClickListener(v ->
            filePicker.launch(new String[]{"image/*","video/*","audio/*","*/*"}));

        Button scanBtn = btn("⟳ SCAN", 0xFF161B22, 0xFF00FF41);
        scanBtn.setOnClickListener(v -> scanForHosts());

        bottom.addView(shareBtn, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (!isHost) {
            bottom.addView(scanBtn, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 0.5f));
        }
        root.addView(bottom, matchWrap());

        // fill initial labels
        if (isHost) {
            String ip = NetworkUtils.getLocalIpAddress(this);
            codeTv.setText("ROOM  " + roomCode);
            ipTv.setText("Your IP → " + ip + " : " + NetworkUtils.SERVER_PORT);
            statusTv.setText("Tell others to join your hotspot and enter this IP");
            statusTv.setTextColor(0xFFFFAA00);
        } else {
            codeTv.setText("JOINING ROOM");
            ipTv.setText("Host IP → " + hostIp);
            statusTv.setText("Connecting…");
        }

        return root;
    }

    // ── host mode ────────────────────────────────────────────────────────────

    private void startServerService() {
        Intent intent = new Intent(this, FileServerService.class);
        intent.putExtra(FileServerService.EXTRA_ROOM_CODE, roomCode);
        intent.putExtra(FileServerService.EXTRA_DEVICE_NAME, deviceName);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(intent);
        else
            startService(intent);
        bindService(intent, serviceConn, Context.BIND_AUTO_CREATE);
    }

    private final ServiceConnection serviceConn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            serverService = ((FileServerService.LocalBinder) b).getService();
            serviceBound  = true;
            String url = serverService.getServerUrl();
            statusTv.setText("Active · " + url);
            statusTv.setTextColor(0xFF00FF41);
            refreshFiles();
        }
        @Override public void onServiceDisconnected(ComponentName n) { serviceBound = false; }
    };

    private final BroadcastReceiver fileReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { refreshFiles(); }
    };

    private void registerFileReceiver() {
        IntentFilter f = new IntentFilter();
        f.addAction(FileServerService.ACTION_FILE_UPLOADED);
        f.addAction(FileServerService.ACTION_CLIENT_CONNECTED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(fileReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else
            registerReceiver(fileReceiver, f);
    }

    // ── client mode ──────────────────────────────────────────────────────────

    private void connectToHost() {
        apiClient = new ApiClient(hostIp, NetworkUtils.SERVER_PORT);
        bgPool.execute(() -> {
            String code = apiClient.ping();
            mainHandler.post(() -> {
                if (code != null) {
                    roomCode = code;
                    codeTv.setText("ROOM  " + roomCode);
                    statusTv.setText("Connected · " + hostIp);
                    statusTv.setTextColor(0xFF00FF41);
                    refreshFiles();
                } else {
                    statusTv.setText("Cannot reach host – check Wi-Fi");
                    statusTv.setTextColor(0xFFFF4444);
                }
            });
        });
    }

    private void startPolling() {
        mainHandler.postDelayed(new Runnable() {
            @Override public void run() {
                if (!isFinishing()) { refreshFiles(); mainHandler.postDelayed(this, 3000); }
            }
        }, 3000);
    }

    private void scanForHosts() {
        String myIp = NetworkUtils.getLocalIpAddress(this);
        statusTv.setText("Scanning network…");
        statusTv.setTextColor(0xFFFFAA00);

        RoomScanner.scan(myIp, new RoomScanner.ScanCallback() {
            @Override public void onHostFound(String ip, String code) {
                mainHandler.post(() -> showHostFound(ip, code));
            }
            @Override public void onScanComplete(List<String> found) {
                mainHandler.post(() -> {
                    if (found.isEmpty()) {
                        statusTv.setText("No hosts found. Make sure you're on their hotspot.");
                        statusTv.setTextColor(0xFFFF4444);
                    }
                });
            }
        });
    }

    private void showHostFound(String ip, String code) {
        new AlertDialog.Builder(this)
            .setTitle("Found Room " + code)
            .setMessage("Host found at " + ip + "\nConnect to this room?")
            .setPositiveButton("Connect", (d, w) -> {
                hostIp    = ip;
                apiClient = new ApiClient(ip, NetworkUtils.SERVER_PORT);
                connectToHost();
            })
            .setNegativeButton("Ignore", null)
            .show();
    }

    // ── refresh file list ────────────────────────────────────────────────────

    private void refreshFiles() {
        if (isHost) {
            if (!serviceBound) return;
            List<MediaItem> items = serverService.getFiles();
            updateList(items);
        } else {
            if (apiClient == null) return;
            bgPool.execute(() -> {
                try {
                    List<MediaItem> items = apiClient.listFiles();
                    mainHandler.post(() -> updateList(items));
                } catch (Exception ignored) {}
            });
        }
    }

    private void updateList(List<MediaItem> items) {
        adapter.setItems(items);
        emptyTv.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ── upload ───────────────────────────────────────────────────────────────

    private void uploadFile(Uri uri) {
        if (isHost && serviceBound) {
            bgPool.execute(() -> {
                try {
                    String name = FileUtils.getFileName(this, uri);
                    String mime = FileUtils.getMimeType(this, uri);
                    long   size = FileUtils.getFileSize(this, uri);
                    try (InputStream in = getContentResolver().openInputStream(uri)) {
                        serverService.addFile(in, name, size, mime, deviceName);
                    }
                    mainHandler.post(() -> { toast("Shared: " + name); refreshFiles(); });
                } catch (Exception e) { mainHandler.post(() -> toast("Error: " + e.getMessage())); }
            });
            return;
        }
        if (apiClient == null) { toast("Not connected"); return; }

        TransferManager.Transfer t = transferManager.enqueueUpload(this, apiClient, uri, deviceName);
        addTransferRow(t);
        transferManager.setListener(new TransferManager.TransferListener() {
            @Override public void onProgress(TransferManager.Transfer tr) { updateTransferRow(tr); }
            @Override public void onComplete(TransferManager.Transfer tr) {
                updateTransferRow(tr);
                toast("Uploaded: " + tr.fileName);
                refreshFiles();
            }
            @Override public void onError(TransferManager.Transfer tr) {
                updateTransferRow(tr);
                toast("Failed: " + tr.error);
            }
        });
    }

    // ── download ─────────────────────────────────────────────────────────────

    private void downloadFile(MediaItem item) {
        if (isHost) {
            toast("You're the host – file already on device");
            return;
        }
        if (apiClient == null) { toast("Not connected"); return; }

        File dest = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), item.getName());

        TransferManager.Transfer t = transferManager.enqueueDownload(apiClient, item, dest.getParentFile());
        addTransferRow(t);
        transferManager.setListener(new TransferManager.TransferListener() {
            @Override public void onProgress(TransferManager.Transfer tr) { updateTransferRow(tr); }
            @Override public void onComplete(TransferManager.Transfer tr) {
                updateTransferRow(tr);
                toast("Saved to Downloads: " + tr.fileName);
                // Trigger media scanner
                sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                        Uri.fromFile(dest)));
            }
            @Override public void onError(TransferManager.Transfer tr) {
                updateTransferRow(tr);
                toast("Download failed: " + tr.error);
            }
        });
    }

    // ── transfer UI rows ─────────────────────────────────────────────────────

    private void addTransferRow(TransferManager.Transfer t) {
        transferPanel.setVisibility(View.VISIBLE);

        LinearLayout row = hBox(0xFF161B22, 10);
        row.setTag(t.id);

        TextView nameTv = tv(
            (t.type == TransferManager.TransferType.UPLOAD ? "↑ " : "↓ ") + t.fileName,
            0xFFCCCCCC, 11, true);
        nameTv.setMaxLines(1);
        nameTv.setEllipsize(android.text.TextUtils.TruncateAt.END);

        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        pb.setProgress(0);
        pb.setTag("pb");

        TextView pctTv = tv("0%", 0xFF00FF41, 11, true);
        pctTv.setMinWidth(dp(36));
        pctTv.setTag("pct");

        row.addView(nameTv, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout pbWrap = vBox(0xFF161B22, 0);
        pbWrap.addView(pb, new LinearLayout.LayoutParams(dp(120),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(pbWrap);
        row.addView(pctTv);

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.setMargins(0, 0, 0, 2);
        transferPanel.addView(row, rowLp);
    }

    private void updateTransferRow(TransferManager.Transfer t) {
        View row = transferPanel.findViewWithTag(t.id);
        if (row == null) return;
        View pbView  = row.findViewWithTag("pb");
        View pctView = row.findViewWithTag("pct");
        if (pbView instanceof ProgressBar)  ((ProgressBar) pbView).setProgress(t.progressPercent());
        if (pctView instanceof TextView) {
            ((TextView) pctView).setText(t.done ? "✓" : t.failed ? "✗" : t.progressPercent() + "%");
            ((TextView) pctView).setTextColor(t.done ? 0xFF00FF41 : t.failed ? 0xFFFF4444 : 0xFF00FF41);
        }
        if (t.done || t.failed) {
            mainHandler.postDelayed(() -> {
                transferPanel.removeView(row);
                if (transferPanel.getChildCount() == 0)
                    transferPanel.setVisibility(View.GONE);
            }, 2000);
        }
    }

    // ── adapter ──────────────────────────────────────────────────────────────

    private class MediaAdapter extends RecyclerView.Adapter<MediaAdapter.VH> {
        private final List<MediaItem> items = new ArrayList<>();

        void setItems(List<MediaItem> n) { items.clear(); items.addAll(n); notifyDataSetChanged(); }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = hBox(0xFF161B22, 12);
            row.setPadding(16, 14, 16, 14);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 0, 2);
            row.setLayoutParams(lp);
            return new VH(row);
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) { h.bind(items.get(pos)); }
        @Override public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView iconTv, nameTv, metaTv;
            Button   dlBtn;

            VH(LinearLayout row) {
                super(row);
                iconTv = tv("", 0xFFFFFFFF, 22, false); iconTv.setPadding(0,0,10,0);
                row.addView(iconTv);

                LinearLayout info = vBox(0xFF161B22, 0);
                nameTv = tv("", 0xFFE6EDF3, 13, true); nameTv.setMaxLines(1);
                nameTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
                metaTv = tv("", 0xFF555555, 11, true);
                info.addView(nameTv); info.addView(metaTv);
                row.addView(info, new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                dlBtn = btn("⬇", 0xFF1A2B1A, 0xFF00FF41);
                dlBtn.setPadding(16, 6, 16, 6);
                row.addView(dlBtn);
            }

            void bind(MediaItem item) {
                iconTv.setText(iconFor(item.getType()));
                nameTv.setText(item.getName());
                metaTv.setText(item.getFormattedSize() + "  ·  " + item.getUploader());
                dlBtn.setOnClickListener(v -> downloadFile(item));
            }

            String iconFor(int type) {
                switch (type) {
                    case MediaItem.TYPE_IMAGE: return "🖼";
                    case MediaItem.TYPE_VIDEO: return "🎬";
                    case MediaItem.TYPE_AUDIO: return "🎵";
                    default:                   return "📄";
                }
            }
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void toast(String msg) { mainHandler.post(()-> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()); }

    private LinearLayout vBox(int bg, int padding) {
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL);
        l.setBackgroundColor(bg); l.setPadding(padding,padding,padding,padding); return l;
    }
    private LinearLayout hBox(int bg, int padding) {
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setBackgroundColor(bg); l.setPadding(padding,padding,padding,padding); return l;
    }
    private TextView tv(String text, int color, float sp, boolean mono) {
        TextView t = new TextView(this); t.setText(text); t.setTextColor(color); t.setTextSize(sp);
        if (mono) t.setTypeface(android.graphics.Typeface.MONOSPACE); return t;
    }
    private Button btn(String text, int bg, int fg) {
        Button b = new Button(this); b.setText(text); b.setTextColor(fg);
        b.setBackgroundColor(bg); b.setTypeface(android.graphics.Typeface.MONOSPACE);
        b.setAllCaps(false); return b;
    }
    private View divider() {
        View v = new View(this); v.setBackgroundColor(0xFF21262D);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1)); return v;
    }
    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }
    private int dp(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
