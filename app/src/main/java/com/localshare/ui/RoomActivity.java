package com.localshare.ui;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.localshare.client.ApiClient;
import com.localshare.client.TransferManager;
import com.localshare.model.FolderItem;
import com.localshare.model.MediaItem;
import com.localshare.model.RoomConfig;
import com.localshare.model.RoomMember;
import com.localshare.server.FileServerService;
import com.localshare.utils.*;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RoomActivity extends AppCompatActivity {

    public static final String EXTRA_IS_HOST     = "is_host";
    public static final String EXTRA_ROOM_CODE   = "room_code";
    public static final String EXTRA_HOST_IP     = "host_ip";
    public static final String EXTRA_PASSWORD    = "password";
    public static final String EXTRA_MAX_MEMBERS = "max_members";
    public static final String EXTRA_ALLOW_UPLOAD= "allow_upload";
    public static final String EXTRA_ALLOW_DELETE= "allow_delete";
    public static final String EXTRA_MAX_FILE_MB = "max_file_mb";
    public static final String EXTRA_ROOM_NAME   = "room_name";

    private boolean isHost;
    private String  roomCode, hostIp, password, deviceName;

    private FileServerService serverService;
    private boolean           serviceBound;
    private ApiClient         apiClient;
    private TransferManager   transferManager;

    private final ExecutorService bgPool     = Executors.newFixedThreadPool(6);
    private final Handler         mainHandler = new Handler(Looper.getMainLooper());

    // UI
    private MediaGridAdapter  fileAdapter;
    private FolderListAdapter folderAdapter;
    private RecyclerView      fileRv, folderRv;
    private TextView   statusTv, ipTv, codeTv, lockTv, memberCountTv, emptyTv, emptyFolderTv;
    private LinearLayout transferPanel, memberListLayout;
    private Button filesTabBtn, foldersTabBtn;
    private boolean membersExpanded = false;
    private boolean showingFolders  = false;

    // Multi-select state
    private final java.util.Set<String> selectedIds = new java.util.LinkedHashSet<>();
    private boolean selectionMode = false;
    private LinearLayout selectionBar;
    private TextView selectionCountTv;

    // Folder browsing state
    private String currentFolderId   = null;
    private String currentFolderName = null;
    private LinearLayout breadcrumbBar;

    // Pickers
    private final ActivityResultLauncher<String[]> filePicker =
        registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(),
            uris -> { if (uris != null) for (Uri u : uris) uploadFile(u); });

    private final ActivityResultLauncher<Uri> folderPicker =
        registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(),
            uri -> { if (uri != null) uploadFolder(uri); });

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        isHost     = getIntent().getBooleanExtra(EXTRA_IS_HOST, false);
        roomCode   = getIntent().getStringExtra(EXTRA_ROOM_CODE);
        hostIp     = getIntent().getStringExtra(EXTRA_HOST_IP);
        password   = getIntent().getStringExtra(EXTRA_PASSWORD);
        deviceName = Build.MODEL;
        transferManager = new TransferManager();
        setContentView(buildLayout());
        if (isHost) { startServerService(); registerBroadcastReceiver(); }
        else        { connectToHost(); startPolling(); startRoomStatusPoller(); }
    }

    @Override public void onBackPressed() {
        if (selectionMode) { exitSelectionMode(); return; }
        if (currentFolderId != null) { exitFolderView(); return; }
        if (isHost) confirmEndRoom(); else confirmLeaveRoom();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (serviceBound) { unbindService(serviceConn); serviceBound = false; }
        bgPool.shutdownNow(); transferManager.shutdown();
        mainHandler.removeCallbacksAndMessages(null);
        try { unregisterReceiver(broadcastReceiver); } catch (Exception ignored) {}
        if (!isHost && apiClient != null) new Thread(() -> apiClient.leaveRoom()).start();
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    private View buildLayout() {
        LinearLayout root = vBox(0xFF0D1117, 0);

        // Info bar
        LinearLayout infoBar = vBox(0xFF161B22, 14);
        codeTv   = tv("ROOM …", 0xFF00FF41, 18, true);
        lockTv   = tv("", 0xFFFFAA00, 11, true);
        ipTv     = tv("", 0xFF888888, 11, true);
        statusTv = tv("Starting…", 0xFFFFAA00, 11, true);
        infoBar.addView(codeTv); infoBar.addView(lockTv); infoBar.addView(ipTv); infoBar.addView(statusTv);
        root.addView(infoBar, matchWrap());

        // Members row
        LinearLayout memberRow = hBox(0xFF0D1117, 12);
        memberCountTv = tv("👥 0 in room", 0xFF888888, 11, true);
        Button expandBtn = smallBtn("▼ Members", 0xFF0D1117, 0xFF555555);
        expandBtn.setOnClickListener(v -> {
            membersExpanded = !membersExpanded;
            memberListLayout.setVisibility(membersExpanded ? View.VISIBLE : View.GONE);
            expandBtn.setText(membersExpanded ? "▲ Members" : "▼ Members");
            if (membersExpanded) refreshMembers();
        });
        memberRow.addView(memberCountTv, new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f));
        memberRow.addView(expandBtn);
        root.addView(memberRow, matchWrap());
        memberListLayout = vBox(0xFF161B22, 10); memberListLayout.setVisibility(View.GONE);
        root.addView(memberListLayout, matchWrap());

        root.addView(divider());

        // Tab bar + action row
        LinearLayout tabRow = hBox(0xFF0D1117, 8);
        filesTabBtn   = tabBtn("FILES", true);
        foldersTabBtn = tabBtn("FOLDERS", false);
        filesTabBtn.setOnClickListener(v   -> switchTab(false));
        foldersTabBtn.setOnClickListener(v -> switchTab(true));
        tabRow.addView(filesTabBtn, new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f));
        tabRow.addView(foldersTabBtn, new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f));
        root.addView(tabRow, matchWrap());

        // Action row
        LinearLayout actionRow = hBox(0xFF0D1117, 8);
        Button shareBtn = btn("＋ FILES", 0xFF00FF41, 0xFF0D1117);
        shareBtn.setOnClickListener(v ->
            filePicker.launch(new String[]{"image/*","video/*","audio/*","*/*"}));
        Button folderBtn = btn("📁 FOLDER", 0xFF1A2B1A, 0xFF00FF41);
        folderBtn.setOnClickListener(v -> folderPicker.launch(null));
        Button qrBtn = smallBtn("QR", 0xFF161B22, 0xFF00FF41);
        qrBtn.setOnClickListener(v -> showQrDialog());

        if (isHost) {
            Button cfgBtn = smallBtn("⚙", 0xFF161B22, 0xFF888888);
            cfgBtn.setOnClickListener(v -> showConfigDialog());
            Button endBtn = smallBtn("END", 0xFF3D0000, 0xFFFF4444);
            endBtn.setOnClickListener(v -> confirmEndRoom());
            actionRow.addView(shareBtn, new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f));
            actionRow.addView(folderBtn, new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f));
            actionRow.addView(qrBtn); actionRow.addView(cfgBtn); actionRow.addView(endBtn);
        } else {
            Button dlAllBtn = smallBtn("⬇ ALL", 0xFF1A2B1A, 0xFF00FF41);
            dlAllBtn.setOnClickListener(v -> confirmDownloadAll());
            Button leaveBtn = smallBtn("LEAVE", 0xFF3D0000, 0xFFFF4444);
            leaveBtn.setOnClickListener(v -> confirmLeaveRoom());
            actionRow.addView(shareBtn, new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f));
            actionRow.addView(folderBtn, new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f));
            actionRow.addView(qrBtn); actionRow.addView(dlAllBtn); actionRow.addView(leaveBtn);
        }
        root.addView(actionRow, matchWrap());
        root.addView(divider());

        // Breadcrumb (folder nav)
        breadcrumbBar = hBox(0xFF161B22, 12); breadcrumbBar.setVisibility(View.GONE);
        root.addView(breadcrumbBar, matchWrap());

        // Transfer panel
        transferPanel = vBox(0xFF0D1117, 8); transferPanel.setVisibility(View.GONE);
        root.addView(transferPanel, matchWrap());

        // ── Selection action bar (shown during multi-select) ──────────────
        selectionBar = hBox(0xFF0A2A0A, 12);
        selectionBar.setVisibility(View.GONE);
        selectionCountTv = tv("0 selected", 0xFF00FF41, 12, true);
        selectionCountTv.setPadding(0,0,8,0);

        Button dlSelBtn   = smallBtn("⬇ Download", 0xFF1A2B1A, 0xFF00FF41);
        Button delSelBtn  = smallBtn("🗑 Delete",   0xFF3D0000, 0xFFFF4444);
        Button cancelSelBtn = smallBtn("✕ Cancel",  0xFF161B22, 0xFF888888);

        dlSelBtn.setOnClickListener(v   -> downloadSelected());
        delSelBtn.setOnClickListener(v  -> deleteSelected());
        cancelSelBtn.setOnClickListener(v -> exitSelectionMode());

        selectionBar.addView(selectionCountTv, new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f));
        selectionBar.addView(dlSelBtn);
        selectionBar.addView(delSelBtn);
        selectionBar.addView(cancelSelBtn);
        root.addView(selectionBar, matchWrap());

        // Empty labels
        emptyTv = tv("No files yet. Tap + FILES to share.", 0xFF333333, 12, true);
        emptyTv.setGravity(Gravity.CENTER); emptyTv.setPadding(0,48,0,0);
        root.addView(emptyTv, matchWrap());

        emptyFolderTv = tv("No folders yet. Tap 📁 FOLDER to share.", 0xFF333333, 12, true);
        emptyFolderTv.setGravity(Gravity.CENTER); emptyFolderTv.setPadding(0,48,0,0);
        emptyFolderTv.setVisibility(View.GONE);
        root.addView(emptyFolderTv, matchWrap());

        // File grid
        fileRv = new RecyclerView(this);
        fileRv.setLayoutManager(new GridLayoutManager(this, 3));
        fileAdapter = new MediaGridAdapter(); fileRv.setAdapter(fileAdapter);
        LinearLayout.LayoutParams rvLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        fileRv.setLayoutParams(rvLp); root.addView(fileRv, rvLp);

        // Folder list
        folderRv = new RecyclerView(this);
        folderRv.setLayoutManager(new LinearLayoutManager(this));
        folderAdapter = new FolderListAdapter(); folderRv.setAdapter(folderAdapter);
        folderRv.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        folderRv.setVisibility(View.GONE);
        root.addView(folderRv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // Initial labels
        if (isHost) {
            codeTv.setText("ROOM  " + (roomCode!=null?roomCode:"…"));
            lockTv.setText(password!=null?"🔒 "+password:"🌐 Public");
            String myIp = NetworkUtils.getLocalIpAddress(this);
            NetworkUtils.NetworkMode nMode = NetworkUtils.detectMode(this);
            String netLabel = nMode == NetworkUtils.NetworkMode.WIFI
                ? "📶 WiFi · " + myIp + " : " + NetworkUtils.SERVER_PORT
                : "📡 Hotspot · " + myIp + " : " + NetworkUtils.SERVER_PORT;
            ipTv.setText(netLabel);
            statusTv.setText("Starting…");
        } else {
            codeTv.setText("CONNECTING…"); ipTv.setText("Host → "+hostIp);
        }
        return root;
    }

    private void switchTab(boolean toFolders) {
        showingFolders = toFolders;
        filesTabBtn.setBackgroundColor(toFolders ? 0xFF161B22 : 0xFF00FF41);
        filesTabBtn.setTextColor(toFolders ? 0xFF888888 : 0xFF0D1117);
        foldersTabBtn.setBackgroundColor(toFolders ? 0xFF00FF41 : 0xFF161B22);
        foldersTabBtn.setTextColor(toFolders ? 0xFF0D1117 : 0xFF888888);

        fileRv.setVisibility(toFolders ? View.GONE : View.VISIBLE);
        emptyTv.setVisibility(View.GONE);
        folderRv.setVisibility(toFolders ? View.VISIBLE : View.GONE);
        emptyFolderTv.setVisibility(View.GONE);
        breadcrumbBar.setVisibility(View.GONE);
        currentFolderId = null; currentFolderName = null;

        if (toFolders) refreshFolders(); else refreshFiles();
    }

    // ── Folder browsing ───────────────────────────────────────────────────────

    private void openFolder(FolderItem folder) {
        currentFolderId   = folder.getId();
        currentFolderName = folder.getName();

        // Update breadcrumb
        breadcrumbBar.removeAllViews(); breadcrumbBar.setVisibility(View.VISIBLE);
        TextView backTv = tv("📁 All Folders", 0xFF888888, 11, true);
        backTv.setPadding(0,0,8,0);
        backTv.setOnClickListener(v -> exitFolderView());
        breadcrumbBar.addView(backTv);
        breadcrumbBar.addView(tv(" › ", 0xFF444444, 11, true));
        breadcrumbBar.addView(tv(folder.getName(), 0xFF00FF41, 11, true));

        // Switch to file grid view
        folderRv.setVisibility(View.GONE);
        fileRv.setVisibility(View.VISIBLE);

        bgPool.execute(() -> {
            try {
                List<MediaItem> files = isHost && serviceBound
                    ? getFolderFilesLocal(folder)
                    : apiClient.listFolderFiles(folder.getId());
                mainHandler.post(() -> {
                    fileAdapter.setItems(files);
                    emptyTv.setVisibility(files.isEmpty() ? View.VISIBLE : View.GONE);
                    emptyTv.setText("This folder is empty.");
                });
            } catch (Exception e) { mainHandler.post(() -> toast("Error: "+e.getMessage())); }
        });
    }

    private List<MediaItem> getFolderFilesLocal(FolderItem folder) {
        List<MediaItem> result = new ArrayList<>();
        for (String fid : folder.getFileIds()) {
            MediaItem item = serverService.getFiles().stream()
                    .filter(m -> m.getId().equals(fid)).findFirst().orElse(null);
            if (item != null) result.add(item);
        }
        return result;
    }

    private void exitFolderView() {
        currentFolderId = null; currentFolderName = null;
        breadcrumbBar.setVisibility(View.GONE);
        emptyTv.setText("No files yet. Tap + FILES to share.");
        fileRv.setVisibility(View.GONE);
        folderRv.setVisibility(View.VISIBLE);
        refreshFolders();
    }

    // ── Upload folder ─────────────────────────────────────────────────────────

    private void uploadFolder(Uri treeUri) {
        // Get folder name from URI
        String folderName = "folder";
        try {
            android.database.Cursor cursor = getContentResolver().query(treeUri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) folderName = cursor.getString(idx);
                cursor.close();
            }
        } catch (Exception ignored) {}
        if (folderName == null || folderName.isEmpty()) folderName = "folder";

        final String finalName = folderName;

        // Check if host (can add directly) or client (must ZIP + upload)
        if (isHost && serviceBound) {
            toast("Scanning folder…");
            final String fn = finalName;
            bgPool.execute(() -> {
                try {
                    // Extract files from tree URI to a local temp dir
                    File tempDir = new File(FileUtils.getShareDir(this), FileUtils.generateId()+"_"+fn);
                    tempDir.mkdirs();
                    List<FolderScanner.FileEntry> entries = FolderScanner.scanTree(this, treeUri, fn);
                    for (FolderScanner.FileEntry entry : entries) {
                        File dest = new File(tempDir, entry.relativePath);
                        dest.getParentFile().mkdirs();
                        try (InputStream in = getContentResolver().openInputStream(entry.uri);
                             FileOutputStream out = new FileOutputStream(dest)) {
                            if (in != null) FileUtils.copy(in, out);
                        }
                    }
                    FolderItem folder = serverService.addFolder(fn, tempDir, deviceName);
                    mainHandler.post(() -> {
                        toast("Folder shared: " + fn + " (" + folder.getFileCount() + " files)");
                        refreshFolders();
                    });
                } catch (Exception e) { mainHandler.post(() -> toast("Error: "+e.getMessage())); }
            });
        } else if (apiClient != null) {
            // Client: scan folder, zip it, upload
            toast("Preparing folder…");
            final String fn = finalName;
            bgPool.execute(() -> {
                try {
                    List<FolderScanner.FileEntry> entries = FolderScanner.scanTree(this, treeUri, fn);
                    if (entries.isEmpty()) { mainHandler.post(() -> toast("Folder is empty")); return; }

                    // Write entries to temp dir, then ZIP
                    File tempDir = new File(getCacheDir(), FileUtils.generateId());
                    tempDir.mkdirs();
                    for (FolderScanner.FileEntry entry : entries) {
                        File dest = new File(tempDir, entry.relativePath);
                        dest.getParentFile().mkdirs();
                        try (InputStream in = getContentResolver().openInputStream(entry.uri);
                             FileOutputStream out = new FileOutputStream(dest)) {
                            if (in != null) FileUtils.copy(in, out);
                        }
                    }

                    File zipFile = new File(getCacheDir(), FileUtils.generateId()+".zip");
                    ZipUtils.zipFolder(new File(tempDir, fn), zipFile);

                    mainHandler.post(() -> toast("Uploading folder " + fn + "…"));
                    try (FileInputStream fis = new FileInputStream(zipFile)) {
                        TransferManager.Transfer t = transferManager.enqueueFolderUpload(
                                apiClient, fis, fn, zipFile.length(), deviceName);
                        addTransferRow(t);
                        transferManager.setListener(new TransferManager.TransferListener() {
                            @Override public void onProgress(TransferManager.Transfer tr) { updateTransferRow(tr); }
                            @Override public void onComplete(TransferManager.Transfer tr) {
                                updateTransferRow(tr); toast("✓ Folder: "+fn); refreshFolders();
                                zipFile.delete();
                            }
                            @Override public void onError(TransferManager.Transfer tr) {
                                updateTransferRow(tr); toast("✗ Folder: "+tr.error); zipFile.delete();
                            }
                        });
                    }
                    // Clean up temp
                    ZipUtils.collectFiles(tempDir, new ArrayList<>());
                } catch (Exception e) { mainHandler.post(() -> toast("Error: "+e.getMessage())); }
            });
        } else {
            toast("Not connected");
        }
    }

    private void downloadFolderAsZip(FolderItem folder) {
        if (isHost) { toast("Folder is on your device already"); return; }
        if (apiClient == null) { toast("Not connected"); return; }
        File dest = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), folder.getName()+".zip");
        toast("Downloading " + folder.getName() + ".zip…");
        bgPool.execute(() -> {
            try {
                apiClient.downloadFolderZip(folder.getId(), dest, (done, total) -> {});
                mainHandler.post(() -> {
                    toast("Saved: " + folder.getName() + ".zip");
                    sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(dest)));
                });
            } catch (Exception e) { mainHandler.post(() -> toast("Failed: "+e.getMessage())); }
        });
    }

    private void confirmDeleteFolder(FolderItem folder) {
        new AlertDialog.Builder(this)
            .setTitle("Delete folder?")
            .setMessage("\"" + folder.getName() + "\"\n" + folder.getFileCount() + " files will be removed.")
            .setPositiveButton("Delete", (d,w) -> bgPool.execute(() -> {
                try {
                    boolean ok = isHost && serviceBound
                        ? serverService.deleteFolder(folder.getId())
                        : (apiClient != null && apiClient.deleteFolder(folder.getId()));
                    mainHandler.post(() -> { if(ok){toast("Deleted"); refreshFolders();}else toast("Failed"); });
                } catch (Exception e) { mainHandler.post(()->toast("Error: "+e.getMessage())); }
            }))
            .setNegativeButton("Cancel", null).show();
    }

    // ── QR dialog ─────────────────────────────────────────────────────────────

    private void showQrDialog() {
        String ip = isHost ? NetworkUtils.getLocalIpAddress(this) : hostIp;
        if (ip==null) { toast("IP not available"); return; }
        bgPool.execute(() -> {
            Bitmap qr = QrCodeGenerator.generateForRoom(ip, NetworkUtils.SERVER_PORT, roomCode!=null?roomCode:"????");
            mainHandler.post(() -> {
                if(qr==null){toast("QR failed");return;}
                LinearLayout layout=vBox(0xFF0D1117,32); layout.setGravity(Gravity.CENTER);
                ImageView iv=new ImageView(this); iv.setImageBitmap(qr);
                int sz=dp(260); iv.setLayoutParams(new LinearLayout.LayoutParams(sz,sz));
                iv.setBackgroundColor(0xFF0D1117); iv.setPadding(8,8,8,8); layout.addView(iv);
                String url="http://"+ip+":"+NetworkUtils.SERVER_PORT+(password!=null?"?pw="+password:"");
                layout.addView(spacer(8));
                layout.addView(tv(url,0xFF00FF41,10,true));
                layout.addView(tv("Scan to open in browser",0xFF555555,10,true));
                new AlertDialog.Builder(this).setTitle("Room "+roomCode)
                    .setView(layout).setPositiveButton("Close",null).show();
            });
        });
    }

    // ── Config dialog ─────────────────────────────────────────────────────────

    private void showConfigDialog() {
        if (!serviceBound) return;
        RoomConfig cfg = serverService.getConfig();
        ScrollView sv=new ScrollView(this);
        LinearLayout layout=vBox(0xFF0D1117,32); sv.addView(layout);
        layout.addView(sectionHeader("LIMITS"));
        layout.addView(label("Max members (0=unlimited):"));
        EditText maxMEt=editText(String.valueOf(cfg.getMaxMembers()), android.text.InputType.TYPE_CLASS_NUMBER);
        layout.addView(maxMEt);
        layout.addView(label("Max file size MB (0=unlimited):"));
        EditText maxFEt=editText(String.valueOf(cfg.getMaxFileSizeMb()), android.text.InputType.TYPE_CLASS_NUMBER);
        layout.addView(maxFEt);
        layout.addView(spacer(10));
        layout.addView(sectionHeader("PERMISSIONS"));
        CheckBox upCb=checkBox("Allow member uploads",cfg.isAllowUpload());
        CheckBox delCb=checkBox("Allow member deletes",cfg.isAllowDelete());
        layout.addView(upCb); layout.addView(delCb);
        layout.addView(spacer(10));
        layout.addView(sectionHeader("BANNED IPs"));
        LinearLayout banLayout=vBox(0xFF161B22,10); layout.addView(banLayout,matchWrap());
        List<String> blocked=serverService.getBlockedIps();
        if(blocked.isEmpty()) { banLayout.addView(tv("None",0xFF444444,11,true)); }
        else for(String ip:blocked){
            LinearLayout row=hBox(0xFF161B22,6);
            row.addView(tv("🚫 "+ip,0xFFFF6666,11,true),new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f));
            Button ub=smallBtn("Unban",0xFF161B22,0xFF00FF41);
            ub.setOnClickListener(v->{serverService.unblockIp(ip);toast("Unbanned: "+ip);showConfigDialog();});
            row.addView(ub); banLayout.addView(row);
        }
        new AlertDialog.Builder(this).setTitle("⚙ Room Config").setView(sv)
            .setPositiveButton("Apply",(d,w)->{
                RoomConfig nc=new RoomConfig(); nc.setRoomName(cfg.getRoomName());
                try{nc.setMaxMembers(Integer.parseInt(maxMEt.getText().toString()));}catch(Exception ignored){}
                try{nc.setMaxFileSizeMb(Long.parseLong(maxFEt.getText().toString()));}catch(Exception ignored){}
                nc.setAllowUpload(upCb.isChecked()); nc.setAllowDelete(delCb.isChecked());
                serverService.updateConfig(nc); toast("Config updated"); updateLockBadge();
            })
            .setNegativeButton("Cancel",null).show();
    }

    private void updateLockBadge() {
        if (!serviceBound) return;
        RoomConfig cfg=serverService.getConfig();
        String b=password!=null?"🔒 ":"🌐 ";
        if(cfg.getMaxMembers()>0) b+="Max "+cfg.getMaxMembers()+" · ";
        if(!cfg.isAllowUpload()) b+="No uploads · ";
        lockTv.setText(b.replaceAll(" · $",""));
    }

    // ── End / Leave ───────────────────────────────────────────────────────────

    private void confirmEndRoom() {
        new AlertDialog.Builder(this).setTitle("End Room?")
            .setMessage("All members disconnected, files removed.")
            .setPositiveButton("End Room",(d,w)->{
                if(serviceBound) serverService.endRoom();
                stopService(new Intent(this,FileServerService.class)); finish();
            }).setNegativeButton("Cancel",null).show();
    }

    private void confirmLeaveRoom() {
        new AlertDialog.Builder(this).setTitle("Leave Room?")
            .setMessage("You'll disconnect.")
            .setPositiveButton("Leave",(d,w)->{
                if(apiClient!=null) bgPool.execute(()->apiClient.leaveRoom()); finish();
            }).setNegativeButton("Stay",null).show();
    }

    // ── Service ───────────────────────────────────────────────────────────────

    private void startServerService() {
        Intent i=new Intent(this,FileServerService.class);
        i.putExtra(FileServerService.EXTRA_ROOM_CODE,roomCode);
        i.putExtra(FileServerService.EXTRA_DEVICE_NAME,deviceName);
        if(password!=null) i.putExtra(FileServerService.EXTRA_PASSWORD,password);
        i.putExtra(FileServerService.EXTRA_MAX_MEMBERS,getIntent().getIntExtra(EXTRA_MAX_MEMBERS,0));
        i.putExtra(FileServerService.EXTRA_ALLOW_UPLOAD,getIntent().getBooleanExtra(EXTRA_ALLOW_UPLOAD,true));
        i.putExtra(FileServerService.EXTRA_ALLOW_DELETE,getIntent().getBooleanExtra(EXTRA_ALLOW_DELETE,false));
        i.putExtra(FileServerService.EXTRA_MAX_FILE_MB,getIntent().getLongExtra(EXTRA_MAX_FILE_MB,0));
        i.putExtra(FileServerService.EXTRA_ROOM_NAME,getIntent().getStringExtra(EXTRA_ROOM_NAME));
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O) startForegroundService(i); else startService(i);
        bindService(i,serviceConn,Context.BIND_AUTO_CREATE);
    }

    private final ServiceConnection serviceConn=new ServiceConnection(){
        @Override public void onServiceConnected(ComponentName n,IBinder b){
            serverService=((FileServerService.LocalBinder)b).getService(); serviceBound=true;
            statusTv.setText("Active · "+serverService.getServerUrl()); statusTv.setTextColor(0xFF00FF41);
            updateLockBadge(); refreshFiles(); refreshFolders(); refreshMembers();
        }
        @Override public void onServiceDisconnected(ComponentName n){serviceBound=false;}
    };

    private final BroadcastReceiver broadcastReceiver=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent i){
            String a=i.getAction(); if(a==null) return;
            switch(a){
                case FileServerService.ACTION_FILE_UPLOADED:
                case FileServerService.ACTION_FILE_DELETED:
                    refreshFiles(); break;
                case FileServerService.ACTION_FOLDER_UPLOADED:
                    toast("📁 " + i.getStringExtra("name") + " ("+i.getIntExtra("count",0)+" files)");
                    refreshFolders(); break;
                case FileServerService.ACTION_FOLDER_DELETED: refreshFolders(); break;
                case FileServerService.ACTION_MEMBER_JOINED:
                    toast("👋 "+i.getStringExtra("name")+" joined"); refreshMembers(); break;
                case FileServerService.ACTION_MEMBER_LEFT: refreshMembers(); break;
                case FileServerService.ACTION_MEMBER_KICKED:
                    toast((i.getBooleanExtra("blocked",false)?"🚫 Banned: ":"👢 Kicked: ")+i.getStringExtra("name"));
                    refreshMembers(); break;
                case FileServerService.ACTION_CONFIG_CHANGED: updateLockBadge(); break;
                case FileServerService.ACTION_ROOM_ENDED: finish(); break;
            }
        }
    };

    private void registerBroadcastReceiver() {
        IntentFilter f=new IntentFilter();
        f.addAction(FileServerService.ACTION_FILE_UPLOADED); f.addAction(FileServerService.ACTION_FILE_DELETED);
        f.addAction(FileServerService.ACTION_FOLDER_UPLOADED); f.addAction(FileServerService.ACTION_FOLDER_DELETED);
        f.addAction(FileServerService.ACTION_MEMBER_JOINED); f.addAction(FileServerService.ACTION_MEMBER_LEFT);
        f.addAction(FileServerService.ACTION_MEMBER_KICKED); f.addAction(FileServerService.ACTION_CONFIG_CHANGED);
        f.addAction(FileServerService.ACTION_ROOM_ENDED);
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.TIRAMISU)
            registerReceiver(broadcastReceiver,f,Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(broadcastReceiver,f);
    }

    // ── Client ────────────────────────────────────────────────────────────────

    private void connectToHost() {
        apiClient=new ApiClient(hostIp,NetworkUtils.SERVER_PORT);
        if(password!=null) apiClient.setPassword(password);
        bgPool.execute(()->{
            String code=apiClient.ping();
            if(code!=null){
                apiClient.joinRoom(deviceName);
                mainHandler.post(()->{
                    roomCode=code; codeTv.setText("ROOM  "+roomCode);
                    statusTv.setText("Connected · "+hostIp); statusTv.setTextColor(0xFF00FF41);
                    refreshFiles(); refreshFolders(); refreshMembers();
                });
            } else {
                mainHandler.post(()->{ statusTv.setText("Cannot reach host"); statusTv.setTextColor(0xFFFF4444); });
            }
        });
    }

    private void startPolling() {
        mainHandler.postDelayed(new Runnable(){
            @Override public void run(){
                if(!isFinishing()){ refreshFiles(); if(showingFolders) refreshFolders(); refreshMembers(); mainHandler.postDelayed(this,3000); }
            }
        },3000);
    }

    private void startRoomStatusPoller() {
        mainHandler.postDelayed(new Runnable(){
            @Override public void run(){
                if(isFinishing()||apiClient==null) return;
                bgPool.execute(()->{
                    boolean closed=apiClient.isRoomClosed();
                    if(closed){
                        mainHandler.post(()->new AlertDialog.Builder(RoomActivity.this)
                            .setTitle("Room ended").setMessage("The host ended this room.")
                            .setPositiveButton("OK",(d,w)->finish()).setCancelable(false).show());
                    } else {
                        org.json.JSONObject info=apiClient.pingInfo();
                        if(info!=null&&info.optBoolean("kicked",false)){
                            mainHandler.post(()->new AlertDialog.Builder(RoomActivity.this)
                                .setTitle("Removed").setMessage("You were removed from this room.")
                                .setPositiveButton("OK",(d,w)->finish()).setCancelable(false).show());
                        } else { mainHandler.postDelayed(this,5000); }
                    }
                });
            }
        },5000);
    }

    // ── Refresh ───────────────────────────────────────────────────────────────

    private void refreshFiles() {
        if(currentFolderId!=null) return; // browsing folder, don't refresh
        if(showingFolders) return;
        if(isHost){ if(serviceBound) updateFileList(serverService.getFiles()); }
        else if(apiClient!=null){
            bgPool.execute(()->{try{List<MediaItem> items=apiClient.listFiles(); mainHandler.post(()->updateFileList(items));}catch(Exception ignored){}});
        }
    }

    private void refreshFolders() {
        if(isHost){ if(serviceBound) updateFolderList(serverService.getFolders()); }
        else if(apiClient!=null){
            bgPool.execute(()->{try{List<FolderItem> folders=apiClient.listFolders(); mainHandler.post(()->updateFolderList(folders));}catch(Exception ignored){}});
        }
    }

    private void refreshMembers() {
        if(isHost){ if(serviceBound) updateMembers(serverService.getMembers(),serverService.getBlockedIps()); }
        else if(apiClient!=null){
            bgPool.execute(()->{try{List<RoomMember> m=apiClient.getMembers(); mainHandler.post(()->updateMembers(m,new ArrayList<>()));}catch(Exception ignored){}});
        }
    }

    private void updateFileList(List<MediaItem> items) {
        fileAdapter.setItems(items);
        if(!showingFolders&&currentFolderId==null){
            emptyTv.setText("No files yet. Tap + FILES to share.");
            emptyTv.setVisibility(items.isEmpty()?View.VISIBLE:View.GONE);
        }
    }

    private void updateFolderList(List<FolderItem> folders) {
        folderAdapter.setItems(folders);
        if(showingFolders&&currentFolderId==null){
            emptyFolderTv.setVisibility(folders.isEmpty()?View.VISIBLE:View.GONE);
        }
    }

    private void updateMembers(List<RoomMember> members, List<String> bannedIps) {
        memberCountTv.setText("👥 "+members.size()+" in room");
        if(!membersExpanded) return;
        memberListLayout.removeAllViews();
        for(RoomMember m:members){
            LinearLayout row=hBox(0xFF161B22,10); row.setPadding(12,8,12,8);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0,0,0,1); row.setLayoutParams(lp);
            row.addView(tv(m.isHost()?"👑":"📱",0xFFFFFFFF,16,false));
            LinearLayout info=vBox(0xFF161B22,0);
            info.addView(tv(m.getDeviceName()+(m.isHost()?" (host)":""),0xFFE6EDF3,12,true));
            info.addView(tv(m.getFilesShared()+" files · "+m.getJoinedAgo()+" · "+m.getIp(),0xFF555555,10,true));
            row.addView(info,new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f));
            if(isHost&&!m.isHost()){
                LinearLayout btns=hBox(0xFF161B22,4);
                Button kb=smallBtn("Kick",0xFF2A2A00,0xFFFFAA00); kb.setOnClickListener(v->confirmKick(m,false));
                Button bb=smallBtn("Ban",0xFF3D0000,0xFFFF4444); bb.setOnClickListener(v->confirmKick(m,true));
                btns.addView(kb); btns.addView(bb); row.addView(btns);
            }
            memberListLayout.addView(row);
        }
        if(isHost&&!bannedIps.isEmpty()){
            memberListLayout.addView(tv("── Banned ──",0xFF555555,10,true));
            for(String ip:bannedIps){
                LinearLayout row=hBox(0xFF161B22,10); row.setPadding(12,6,12,6);
                row.addView(tv("🚫 "+ip,0xFFFF6666,11,true),new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f));
                Button ub=smallBtn("Unban",0xFF161B22,0xFF00FF41);
                ub.setOnClickListener(v->{if(serviceBound){serverService.unblockIp(ip);toast("Unbanned: "+ip);refreshMembers();}});
                row.addView(ub); memberListLayout.addView(row);
            }
        }
    }

    private void confirmKick(RoomMember m,boolean ban){
        new AlertDialog.Builder(this).setTitle(ban?"Ban "+m.getDeviceName()+"?":"Kick "+m.getDeviceName()+"?")
            .setMessage(ban?"Permanently ban this device.":"Remove (can rejoin).")
            .setPositiveButton(ban?"Ban":"Kick",(d,w)->{
                if(serviceBound){boolean ok=serverService.kickMember(m.getIp(),ban);
                    toast(ok?(ban?"Banned: ":"Kicked: ")+m.getDeviceName():"Failed"); refreshMembers();}
            }).setNegativeButton("Cancel",null).show();
    }

    // ── Upload file ───────────────────────────────────────────────────────────

    private void uploadFile(Uri uri) {
        if(isHost&&serviceBound){
            bgPool.execute(()->{
                try{
                    String name=FileUtils.getFileName(this,uri);
                    String mime=FileUtils.getMimeType(this,uri);
                    long size=FileUtils.getFileSize(this,uri);
                    try(InputStream in=getContentResolver().openInputStream(uri)){serverService.addFile(in,name,size,mime,deviceName);}
                    mainHandler.post(()->{toast("Shared: "+name); refreshFiles();});
                }catch(Exception e){mainHandler.post(()->toast("Error: "+e.getMessage()));}
            }); return;
        }
        if(apiClient==null){toast("Not connected");return;}
        TransferManager.Transfer t=transferManager.enqueueUpload(this,apiClient,uri,deviceName);
        addTransferRow(t);
        transferManager.setListener(new TransferManager.TransferListener(){
            @Override public void onProgress(TransferManager.Transfer tr){updateTransferRow(tr);}
            @Override public void onComplete(TransferManager.Transfer tr){updateTransferRow(tr);toast("✓ "+tr.fileName);refreshFiles();}
            @Override public void onError(TransferManager.Transfer tr){updateTransferRow(tr);toast("✗ "+tr.error);}
        });
    }

    // ── Download file ─────────────────────────────────────────────────────────

    private void downloadFile(MediaItem item){
        if(isHost){toast("Already on your device");return;}
        if(apiClient==null){toast("Not connected");return;}
        File dest=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),item.getName());
        TransferManager.Transfer t=transferManager.enqueueDownload(apiClient,item,dest.getParentFile());
        addTransferRow(t);
        transferManager.setListener(new TransferManager.TransferListener(){
            @Override public void onProgress(TransferManager.Transfer tr){updateTransferRow(tr);}
            @Override public void onComplete(TransferManager.Transfer tr){
                updateTransferRow(tr); toast("Saved: "+tr.fileName);
                sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,Uri.fromFile(dest)));
            }
            @Override public void onError(TransferManager.Transfer tr){updateTransferRow(tr);toast("Failed: "+tr.error);}
        });
    }

    private void confirmDelete(MediaItem item){
        new AlertDialog.Builder(this).setTitle("Delete?").setMessage(item.getName())
            .setPositiveButton("Delete",(d,w)->bgPool.execute(()->{
                try{boolean ok=isHost&&serviceBound?serverService.deleteFile(item.getId()):(apiClient!=null&&apiClient.deleteFile(item.getId()));
                    mainHandler.post(()->{if(ok){toast("Deleted");refreshFiles();}else toast("Failed");});
                }catch(Exception e){mainHandler.post(()->toast("Error: "+e.getMessage()));}
            })).setNegativeButton("Cancel",null).show();
    }

    // ── Transfer rows ─────────────────────────────────────────────────────────

    private void addTransferRow(TransferManager.Transfer t){
        transferPanel.setVisibility(View.VISIBLE);
        LinearLayout row=hBox(0xFF161B22,10); row.setTag(t.id);
        TextView name=tv((t.type==TransferManager.TransferType.UPLOAD?"↑ ":"↓ ")+t.fileName,0xFFCCCCCC,11,true);
        name.setMaxLines(1); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        ProgressBar pb=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100); pb.setTag("pb");
        TextView pct=tv("0%",0xFF00FF41,11,true); pct.setMinWidth(dp(36)); pct.setTag("pct");
        row.addView(name,new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f));
        LinearLayout pw=vBox(0xFF161B22,0);
        pw.addView(pb,new LinearLayout.LayoutParams(dp(90),LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(pw); row.addView(pct);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0,0,0,2); transferPanel.addView(row,lp);
    }

    private void updateTransferRow(TransferManager.Transfer t){
        View row=transferPanel.findViewWithTag(t.id); if(row==null) return;
        View pb=row.findViewWithTag("pb"),pct=row.findViewWithTag("pct");
        if(pb instanceof ProgressBar)((ProgressBar)pb).setProgress(t.progressPercent());
        if(pct instanceof TextView){
            ((TextView)pct).setText(t.done?"✓":t.failed?"✗":t.progressPercent()+"%");
            ((TextView)pct).setTextColor(t.done?0xFF00FF41:t.failed?0xFFFF4444:0xFF00FF41);
        }
        if(t.done||t.failed) mainHandler.postDelayed(()->{
            transferPanel.removeView(row);
            if(transferPanel.getChildCount()==0) transferPanel.setVisibility(View.GONE);
        },2000);
    }

    // ── Selection mode ────────────────────────────────────────────────────────

    private void enterSelectionMode(String firstId) {
        selectionMode = true;
        selectedIds.clear();
        selectedIds.add(firstId);
        selectionBar.setVisibility(View.VISIBLE);
        updateSelectionCount();
        fileAdapter.notifyDataSetChanged();
    }

    private void exitSelectionMode() {
        selectionMode = false;
        selectedIds.clear();
        selectionBar.setVisibility(View.GONE);
        fileAdapter.notifyDataSetChanged();
    }

    private void toggleSelection(String id) {
        if (selectedIds.contains(id)) selectedIds.remove(id);
        else selectedIds.add(id);
        if (selectedIds.isEmpty()) exitSelectionMode();
        else updateSelectionCount();
        fileAdapter.notifyDataSetChanged();
    }

    private void updateSelectionCount() {
        selectionCountTv.setText(selectedIds.size() + " selected");
    }

    private void downloadSelected() {
        if (isHost) { toast("Files already on your device"); return; }
        if (apiClient == null) { toast("Not connected"); return; }
        List<MediaItem> toDownload = new ArrayList<>();
        for (MediaItem item : fileAdapter.getItems()) {
            if (selectedIds.contains(item.getId())) toDownload.add(item);
        }
        if (toDownload.isEmpty()) { toast("Nothing selected"); return; }
        toast("Downloading " + toDownload.size() + " files…");
        exitSelectionMode();
        for (MediaItem item : toDownload) downloadFile(item);
    }

    private void deleteSelected() {
        if (selectedIds.isEmpty()) return;
        int count = selectedIds.size();
        new AlertDialog.Builder(this)
            .setTitle("Delete " + count + " file" + (count>1?"s":"") + "?")
            .setMessage("This will permanently remove " + count + " selected file" + (count>1?"s":"") + ".")
            .setPositiveButton("Delete All", (d, w) -> {
                List<String> ids = new ArrayList<>(selectedIds);
                exitSelectionMode();
                bgPool.execute(() -> {
                    int deleted = 0;
                    for (String id : ids) {
                        try {
                            boolean ok = isHost && serviceBound
                                ? serverService.deleteFile(id)
                                : (apiClient != null && apiClient.deleteFile(id));
                            if (ok) deleted++;
                        } catch (Exception ignored) {}
                    }
                    final int d2 = deleted;
                    mainHandler.post(() -> { toast("Deleted " + d2 + " file" + (d2>1?"s":"")); refreshFiles(); });
                });
            })
            .setNegativeButton("Cancel", null).show();
    }

    private void confirmDownloadAll() {
        if (apiClient == null) { toast("Not connected"); return; }
        List<MediaItem> all = fileAdapter.getItems();
        if (all.isEmpty()) { toast("No files to download"); return; }

        new AlertDialog.Builder(this)
            .setTitle("Download All?")
            .setMessage("Download all " + all.size() + " file" + (all.size()>1?"s":"") + " to Downloads folder?")
            .setPositiveButton("Download All", (d, w) -> {
                toast("Downloading " + all.size() + " files…");
                for (MediaItem item : all) downloadFile(item);
            })
            .setNegativeButton("Cancel", null).show();
    }

    // ── Adapters ──────────────────────────────────────────────────────────────

    private class MediaGridAdapter extends RecyclerView.Adapter<MediaGridAdapter.VH> {
        private final List<MediaItem> items = new ArrayList<>();

        void setItems(List<MediaItem> n) { items.clear(); items.addAll(n); notifyDataSetChanged(); }
        List<MediaItem> getItems() { return new ArrayList<>(items); }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            android.widget.FrameLayout frame = new android.widget.FrameLayout(RoomActivity.this);
            int cell = (getResources().getDisplayMetrics().widthPixels - dp(8)) / 3;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cell, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(2,2,2,2); frame.setLayoutParams(lp);

            LinearLayout card = vBox(0xFF161B22, 6);
            card.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));
            frame.addView(card);

            // Green overlay shown when selected
            int tSz = (getResources().getDisplayMetrics().widthPixels - dp(8)) / 3;
            android.widget.FrameLayout overlay = new android.widget.FrameLayout(RoomActivity.this);
            overlay.setLayoutParams(new android.widget.FrameLayout.LayoutParams(tSz, tSz));
            overlay.setBackgroundColor(0x8800FF41); // semi-transparent green
            overlay.setVisibility(View.GONE);

            TextView checkTv = new TextView(RoomActivity.this);
            checkTv.setText("✓");
            checkTv.setTextColor(0xFFFFFFFF);
            checkTv.setTextSize(30);
            checkTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            checkTv.setGravity(Gravity.CENTER);
            checkTv.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
            overlay.addView(checkTv);
            frame.addView(overlay);

            return new VH(frame, card, overlay);
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) { h.bind(items.get(pos)); }
        @Override public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            final LinearLayout card;
            final android.widget.FrameLayout overlay;
            ImageView thumbIv;
            TextView iconTv, nameTv, metaTv;

            VH(android.widget.FrameLayout frame, LinearLayout card, android.widget.FrameLayout overlay) {
                super(frame);
                this.card = card; this.overlay = overlay;
                int tSz = (getResources().getDisplayMetrics().widthPixels - dp(8)) / 3;

                thumbIv = new ImageView(card.getContext());
                thumbIv.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, tSz));
                thumbIv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                thumbIv.setBackgroundColor(0xFF0D1117);
                card.addView(thumbIv);

                iconTv = tv("", 0xFFFFFFFF, 36, false);
                iconTv.setGravity(Gravity.CENTER);
                iconTv.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, tSz));
                iconTv.setBackgroundColor(0xFF0D1117);
                iconTv.setVisibility(View.GONE);
                card.addView(iconTv);

                nameTv = tv("", 0xFFE6EDF3, 10, true);
                nameTv.setMaxLines(1);
                nameTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
                card.addView(nameTv);

                metaTv = tv("", 0xFF555555, 9, true);
                card.addView(metaTv);

                // Tap: download normally, or toggle selection in selection mode
                frame.setOnClickListener(v -> {
                    int pos2 = getAdapterPosition();
                    if (pos2 < 0 || pos2 >= items.size()) return;
                    MediaItem item = items.get(pos2);
                    if (selectionMode) toggleSelection(item.getId());
                    else downloadFile(item);
                });

                // Long-press: enter selection mode (or delete if already selecting)
                frame.setOnLongClickListener(v -> {
                    int pos2 = getAdapterPosition();
                    if (pos2 < 0 || pos2 >= items.size()) return true;
                    MediaItem item = items.get(pos2);
                    if (!selectionMode) enterSelectionMode(item.getId());
                    else showItemContextMenu(item);
                    return true;
                });
            }

            void bind(MediaItem item) {
                nameTv.setText(item.getName());
                metaTv.setText(item.getFormattedSize());

                boolean selected = selectedIds.contains(item.getId());
                card.setBackgroundColor(selected ? 0xFF0D2A1A : 0xFF161B22);
                overlay.setVisibility(selected ? View.VISIBLE : View.GONE);

                if (item.getType() == MediaItem.TYPE_IMAGE || item.getType() == MediaItem.TYPE_VIDEO) {
                    thumbIv.setVisibility(View.VISIBLE);
                    iconTv.setVisibility(View.GONE);
                    thumbIv.setImageResource(android.R.color.darker_gray);
                    loadThumb(item);
                } else {
                    thumbIv.setVisibility(View.GONE);
                    iconTv.setVisibility(View.VISIBLE);
                    iconTv.setText(item.getType() == MediaItem.TYPE_AUDIO ? "🎵" : "📄");
                }
            }

            void loadThumb(MediaItem item) {
                if (isHost && serviceBound) {
                    bgPool.execute(() -> {
                        File f = item.getLocalPath() != null ? new File(item.getLocalPath()) : null;
                        Bitmap bmp = f != null ? ThumbnailUtils.getThumbnail(f, item.getMimeType()) : null;
                        mainHandler.post(() -> { if (bmp != null) thumbIv.setImageBitmap(bmp); });
                    });
                } else if (apiClient != null) {
                    bgPool.execute(() -> {
                        try {
                            byte[] d = apiClient.downloadThumb(item.getId());
                            if (d != null) {
                                Bitmap bmp = BitmapFactory.decodeByteArray(d, 0, d.length);
                                mainHandler.post(() -> { if (bmp != null) thumbIv.setImageBitmap(bmp); });
                            }
                        } catch (Exception ignored) {}
                    });
                }
            }
        }
    }

    private class FolderListAdapter extends RecyclerView.Adapter<FolderListAdapter.VH>{
        private final List<FolderItem> items=new ArrayList<>();
        void setItems(List<FolderItem> n){items.clear();items.addAll(n);notifyDataSetChanged();}
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p,int vt){
            LinearLayout row=hBox(0xFF161B22,14);
            row.setPadding(14,14,14,14);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0,0,0,2); row.setLayoutParams(lp); return new VH(row);
        }
        @Override public void onBindViewHolder(@NonNull VH h,int pos){h.bind(items.get(pos));}
        @Override public int getItemCount(){return items.size();}
        class VH extends RecyclerView.ViewHolder{
            TextView iconTv,nameTv,metaTv; Button dlBtn,delBtn;
            VH(LinearLayout row){
                super(row);
                iconTv=tv("📁",0xFFFFFFFF,28,false); iconTv.setPadding(0,0,12,0); row.addView(iconTv);
                LinearLayout info=vBox(0xFF161B22,0);
                nameTv=tv("",0xFFE6EDF3,13,true); nameTv.setMaxLines(1); nameTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
                metaTv=tv("",0xFF555555,10,true);
                info.addView(nameTv); info.addView(metaTv);
                row.addView(info,new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f));
                dlBtn=smallBtn("⬇ ZIP",0xFF1A2B1A,0xFF00FF41);
                delBtn=smallBtn("🗑",0xFF3D0000,0xFFFF4444);
                row.addView(dlBtn); row.addView(delBtn);
            }
            void bind(FolderItem f){
                nameTv.setText(f.getName());
                metaTv.setText(f.getFileCount()+" files · "+f.getFormattedSize()+" · "+f.getUploader());
                ((View)nameTv.getParent().getParent()).setOnClickListener(v->openFolder(f));
                dlBtn.setOnClickListener(v->downloadFolderAsZip(f));
                delBtn.setOnClickListener(v->confirmDeleteFolder(f));
            }
        }
    }

    /** Long-press on an item already in selection mode → context menu */
    private void showItemContextMenu(MediaItem item) {
        String[] options = {"Download this file", "Delete this file", "Select All", "Deselect All"};
        new AlertDialog.Builder(this)
            .setTitle(item.getName())
            .setItems(options, (d, which) -> {
                switch (which) {
                    case 0: // Download
                        downloadFile(item);
                        break;
                    case 1: // Delete
                        confirmDelete(item);
                        break;
                    case 2: // Select All
                        selectedIds.clear();
                        for (MediaItem m : fileAdapter.getItems()) selectedIds.add(m.getId());
                        updateSelectionCount();
                        fileAdapter.notifyDataSetChanged();
                        break;
                    case 3: // Deselect All
                        exitSelectionMode();
                        break;
                }
            })
            .show();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void toast(String m){mainHandler.post(()->Toast.makeText(this,m,Toast.LENGTH_SHORT).show());}
    private int dp(int d){return Math.round(d*getResources().getDisplayMetrics().density);}
    private LinearLayout vBox(int bg,int pad){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setBackgroundColor(bg);l.setPadding(pad,pad,pad,pad);return l;}
    private LinearLayout hBox(int bg,int pad){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);l.setBackgroundColor(bg);l.setPadding(pad,pad,pad,pad);return l;}
    private TextView tv(String t,int c,float sp,boolean mono){TextView v=new TextView(this);v.setText(t);v.setTextColor(c);v.setTextSize(sp);if(mono)v.setTypeface(android.graphics.Typeface.MONOSPACE);return v;}
    private Button btn(String t,int bg,int fg){Button b=new Button(this);b.setText(t);b.setTextColor(fg);b.setBackgroundColor(bg);b.setTypeface(android.graphics.Typeface.MONOSPACE);b.setAllCaps(false);b.setPadding(20,14,20,14);return b;}
    private Button smallBtn(String t,int bg,int fg){Button b=new Button(this);b.setText(t);b.setTextColor(fg);b.setBackgroundColor(bg);b.setTypeface(android.graphics.Typeface.MONOSPACE);b.setAllCaps(false);b.setPadding(10,6,10,6);b.setTextSize(10);return b;}
    private Button tabBtn(String t,boolean active){Button b=new Button(this);b.setText(t);b.setTextColor(active?0xFF0D1117:0xFF888888);b.setBackgroundColor(active?0xFF00FF41:0xFF161B22);b.setTypeface(android.graphics.Typeface.MONOSPACE);b.setAllCaps(false);b.setPadding(16,10,16,10);return b;}
    private CheckBox checkBox(String l,boolean checked){CheckBox cb=new CheckBox(this);cb.setText("  "+l);cb.setChecked(checked);cb.setTextColor(0xFFE6EDF3);cb.setButtonTintList(android.content.res.ColorStateList.valueOf(0xFF00FF41));return cb;}
    private View divider(){View v=new View(this);v.setBackgroundColor(0xFF21262D);v.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,1));return v;}
    private View spacer(int dp){View v=new View(this);int px=Math.round(dp*getResources().getDisplayMetrics().density);v.setLayoutParams(new LinearLayout.LayoutParams(1,px));return v;}
    private TextView sectionHeader(String t){TextView v=tv(t,0xFF00FF41,10,true);v.setPadding(0,8,0,4);return v;}
    private TextView label(String t){return tv(t,0xFF888888,11,true);}
    private EditText editText(String def,int type){EditText et=new EditText(this);et.setText(def);et.setInputType(type);et.setTextColor(0xFFE6EDF3);et.setHintTextColor(0xFF555555);et.setBackgroundColor(0xFF21262D);et.setPadding(16,12,16,12);return et;}
    private LinearLayout.LayoutParams matchWrap(){return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT);}
}
