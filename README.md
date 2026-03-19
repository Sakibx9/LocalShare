# LocalShare

**Offline · Local Network · High-Speed Media Sharing**

A lightweight Android app (~3–4 MB APK) that turns any phone into a media sharing server
over a local Wi-Fi hotspot. No internet required. No accounts. No ads.

---

## Features

| Feature | Details |
|---|---|
| Host a room | Start an HTTP server on your hotspot |
| Join a room | Connect by IP or **auto-scan** the subnet |
| Share any file | Images, video, audio, docs — anything |
| Upload | Chunked multipart upload, 256 KB buffers |
| Download | Streamed file transfer, no memory limit |
| Progress tracking | Per-transfer progress bars |
| Browser access | Join from any browser: `http://192.168.43.1:8765` |
| APK size | ~3 MB (pure Java, R8 + ABI splits) |
| Min Android | API 24 (Android 7.0) |

---

## Architecture

```
┌─────────────────────────────────────────────────────┐
│                     HOST DEVICE                      │
│                                                      │
│  FileServerService (Foreground Service)              │
│    └── LocalHttpServer (Raw socket, port 8765)       │
│          ├── GET  /api/list      → JSON file index   │
│          ├── GET  /api/download  → stream file       │
│          ├── POST /api/upload    → multipart ingest  │
│          ├── GET  /api/ping      → room info         │
│          └── GET  /             → browser web UI     │
│                                                      │
│  Wi-Fi Hotspot (192.168.43.0/24)                    │
└──────────────┬──────────────────────────────────────┘
               │  Wi-Fi
    ┌──────────┴──────────┐
    │    CLIENT DEVICE     │
    │                      │
    │  ApiClient           │
    │    ├── ping()        │
    │    ├── listFiles()   │
    │    ├── uploadFile()  │
    │    └── downloadFile()│
    │                      │
    │  RoomScanner         │
    │    └── 32-thread pool│
    │        scans /24     │
    │        subnet ~2s    │
    │                      │
    │  TransferManager     │
    │    └── 4-thread pool │
    │        queued xfers  │
    └──────────────────────┘
```

---

## Performance

- **TCP_NODELAY** enabled (disables Nagle algorithm)
- **256 KB** send/receive socket buffers
- **8-thread** server pool handles concurrent transfers
- **4-thread** client transfer queue
- Expected throughput: **40–90 MB/s** on 5 GHz hotspot

---

## Build Instructions

### Prerequisites
- Android Studio Hedgehog (2023.1) or newer  
- JDK 17  
- Android SDK API 34

### Steps

```bash
# Clone / extract project
cd LocalShare

# Debug build
./gradlew assembleDebug

# Release build (requires signing config)
./gradlew assembleRelease

# Install directly to connected device
./gradlew installDebug
```

The APK will be at:
```
app/build/outputs/apk/debug/app-debug.apk
```

### Add a signing config (for release)

In `app/build.gradle`, add inside `android {}`:

```groovy
signingConfigs {
    release {
        storeFile file("your-keystore.jks")
        storePassword "yourpass"
        keyAlias "youralias"
        keyPassword "yourpass"
    }
}
buildTypes {
    release {
        signingConfig signingConfigs.release
        // ...
    }
}
```

---

## Usage

### Host a Room

1. **Enable your phone's hotspot** (Settings → Hotspot)
2. Open LocalShare → tap **[ HOST A ROOM ]**
3. A 4-character room code and your IP address are shown
4. Share your IP with others (e.g., `192.168.43.1`)

### Join a Room

**Option A — Manual IP:**
1. Connect your phone to the host's hotspot in Wi-Fi settings
2. Open LocalShare → tap **[ JOIN BY IP ]**
3. Enter the host's IP (default `192.168.43.1` is pre-filled)

**Option B — Auto-scan:**
1. Connect to the host's hotspot
2. Open LocalShare → tap **[ AUTO-SCAN NETWORK ]**
3. App scans all 254 addresses in ~2 seconds
4. Tap Connect when the room is found

**Option C — Browser:**
1. Connect to the hotspot
2. Open any browser → go to `http://192.168.43.1:8765`
3. Upload and download files from the web UI

### Sharing Files

- Tap **＋ SHARE** to pick files (images, video, audio, any file)
- Multiple files can be selected at once
- Progress bar shows upload/download status
- Downloaded files are saved to `Downloads/`

---

## File Structure

```
app/src/main/java/com/localshare/
├── server/
│   ├── LocalHttpServer.java    Zero-dependency HTTP server
│   ├── HttpRequest.java        HTTP/1.1 request parser
│   ├── HttpResponse.java       Streaming response writer
│   ├── MultipartParser.java    Multipart upload parser
│   └── FileServerService.java  Foreground service wrapper
├── client/
│   ├── ApiClient.java          HTTP client
│   ├── RoomScanner.java        Subnet scanner (32 threads)
│   └── TransferManager.java    Upload/download queue
├── model/
│   └── MediaItem.java          File metadata
├── utils/
│   ├── NetworkUtils.java       IP detection helpers
│   └── FileUtils.java          File I/O helpers
└── ui/
    ├── MainActivity.java       Home screen
    └── RoomActivity.java       Room / file browser
```

---

## Notes

- **Hotspot management**: Android requires the user to manually enable their hotspot.
  Programmatic hotspot control requires a system-level signature permission not available
  to third-party apps.
- **Cleartext HTTP**: The app uses `usesCleartextTraffic="true"` since it operates
  only on local LAN — no data ever leaves the device's local network.
- **Storage**: Shared files are stored in `Android/data/com.localshare/files/LocalShare/`
  on external storage. They persist until the app is uninstalled.
- **Large files**: Streaming architecture means files of any size can be transferred
  without loading them into RAM.
