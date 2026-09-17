# HTTP Server for Android

A lightweight HTTP file server that runs directly on your Android device. Share files with any device on your local Wi-Fi network — no cables, no cloud, no accounts.

![Android](https://img.shields.io/badge/Android-8.0%2B-green)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9-blue)
![License](https://img.shields.io/badge/License-MIT-yellow)

## Features

- **Simple one-button server** — press START, get a URL, done
- **File manager in the browser** — browse folders, download files
- **Upload files from any device** — drag & drop or click to upload
- **Delete and create folders** — full file management from the browser
- **Two modes** — serve a custom HTML page or browse a folder
- **Configurable port** — 1024 to 65535
- **Permission toggles** — disable upload or delete/rename if you don't need them
- **Dark themed web UI** — works on desktop and mobile browsers
- **Live request log** — see what's happening in real time

## How it works

The app starts a multi-threaded HTTP server using `ServerSocket`. Each client connection is handled in its own thread. Files are accessed via the Storage Access Framework (`DocumentFile`), so it works on Android 10+ without requiring `MANAGE_EXTERNAL_STORAGE` permission.

## Requirements

- Android 8.0 (API 26) or higher
- Both devices (phone and client) must be on the same Wi-Fi network
- Port must be 1024–65535 (ports below 1024 require root)

## Installation

### From source

You need:
- Linux (or WSL/macOS with adjustments)
- JDK 17
- Android SDK with `platforms;android-34` and `build-tools;34.0.0`
- Gradle 8.5 (or use the wrapper)

```bash
# Clone or copy the project
cd ~/projects/HttpServer2

# Generate the Gradle wrapper (first time only)
gradle wrapper --gradle-version 8.5

# Build the debug APK
./gradlew assembleDebug
```

The APK will be at:
```
app/build/outputs/apk/debug/app-debug.apk
```

Install it:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### From a prebuilt APK

1. Download the APK from the [Releases](../../releases) page.
2. Enable **Install from unknown sources** in your Android settings.
3. Open the APK and install.

## Usage

1. **Open the app** and tap **Settings**.
2. Set the **port** (default `8080`).
3. Choose a **mode**:
   - **Serve custom HTML** — returns the HTML you write in the settings.
   - **File manager** — browse, download, upload files in the selected folder.
4. If using **File manager**, tap **Choose folder** and pick a directory.
5. Toggle **Allow file upload** and **Allow delete / create folder** as needed.
6. Tap **Save**, return to the main screen.
7. Tap **START**. The URL appears at the top, e.g. `http://192.168.1.100:8080`.
8. On any device in the same Wi-Fi network, open that URL in a browser.

### Example

```
Phone IP:  192.168.1.100
Port:      8080
URL:       http://192.168.1.100:8080
```

Open this URL on your PC → file manager appears → drag files to upload, click to download, delete as needed.

## API

All endpoints return `Connection: close` (HTTP/1.1, no keep-alive).

| Method | Path              | Description                                  |
|--------|-------------------|----------------------------------------------|
| GET    | `/`               | HTML page or directory listing               |
| GET    | `/api/stats`      | JSON stats: requests, bytes sent/received    |
| GET    | `/file/<path>`    | Download a file                              |
| POST   | `/upload?name=X&path=Y` | Upload a file (raw body, no multipart) |
| POST   | `/delete`         | Delete file/folder (`path=...` form param)   |
| POST   | `/mkdir`          | Create folder (`path=...&name=...`)          |

### Example: `GET /api/stats`

```json
{
  "requests": 42,
  "bytes_sent": 1048576,
  "bytes_received": 524288
}
```

## Settings reference

| Setting               | Default | Description                                    |
|-----------------------|---------|------------------------------------------------|
| Port                  | 8080    | HTTP port (1024–65535)                         |
| Mode                  | File manager | Custom HTML or file manager                |
| Custom HTML           | Built-in example | HTML served in HTML mode             |
| Shared folder         | —       | Folder accessible in file manager mode         |
| Allow file upload     | On      | Enable `POST /upload`                          |
| Allow delete / mkdir  | On      | Enable `POST /delete` and `POST /mkdir`        |

## Security notes

- **No authentication.** Anyone on your Wi-Fi network who knows the IP and port can access the server.
- **No HTTPS.** Traffic is plain HTTP. Do not use it on untrusted networks.
- **Read/write access is limited** to the folder you selected via SAF. The app cannot escape that folder.
- **Use only on trusted local networks.** Do not expose the port to the internet.

## Limitations

- **No HTTP/1.1 keep-alive** — each request opens a new TCP connection.
- **No authentication** — see Security notes.
- **No TLS / HTTPS.**
- **No range requests** — large file streaming works, but resumable downloads are not supported.
- **Unbounded thread creation** — each client gets a new thread; a flood of connections could exhaust memory. Fine for home use, not for public servers.

## Building

```bash
cd ~/projects/HttpServer2

# First time only — download Gradle if not installed system-wide
cd /tmp
wget https://services.gradle.org/distributions/gradle-8.5-bin.zip
unzip gradle-8.5-bin.zip

# Generate wrapper and build
cd ~/projects/HttpServer2
/tmp/gradle-8.5/bin/gradle wrapper --gradle-version 8.5
chmod +x gradlew
./gradlew assembleDebug
```

## Project structure

```
HttpServer2/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/example/httpserver2/
│       │   ├── HttpServer.kt          # core server logic
│       │   ├── MainActivity.kt        # main screen
│       │   └── SettingsActivity.kt    # settings screen
│       └── res/
│           ├── layout/                # UI layouts
│           ├── values/                # strings
│           └── xml/                   # network security config
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── gradle/wrapper/
```

## License

MIT License. See [LICENSE](LICENSE) for details.

## Contributing

Pull requests are welcome. For major changes, open an issue first.

## Disclaimer

This software is provided "as is", without warranty of any kind. Use at your own risk. The author is not responsible for any damage caused by misuse.
