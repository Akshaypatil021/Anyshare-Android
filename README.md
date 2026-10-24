# ⚡ Anyshare - High-Speed Offline P2P File Sharing

[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![API](https://img.shields.io/badge/API-26%2B%20%28Android%208.0%2B%29-blue.svg)](https://android-arsenal.com/api?level=26)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin%201.9-purple.svg)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/License-Apache%202.0-orange.svg)](LICENSE)

Anyshare is a modern, privacy-first, cross-version Android file-sharing application designed for fast, seamless, and completely offline peer-to-peer data transfers using **Wi-Fi Direct (P2P)** and **High-Throughput TCP Sockets**.

Inspired by LocalSend and modern Material 3 design, Anyshare functions with **zero mobile data consumption**, no internet dependency, and full hardware compatibility spanning **Android 8.0 (Oreo) to Android 15 (Vanilla Ice Cream)**.

---

## 🌟 Key Features

* **⚡ Ultra-High-Speed P2P Transfers**: Direct device-to-device streaming via Wi-Fi Direct with speeds reaching up to 30-50 MB/s.
* **📶 Universal Hardware Compatibility**:
  * Prioritized **2.4 GHz band fallback** (`GROUP_OWNER_BAND_2GHZ`) ensures older single-band phones pair reliably with modern flagship 5 GHz devices.
  * Resilient **128 KB - 256 KB chunking** prevents memory pressure / OutOfMemory on devices with 1 GB - 2 GB RAM.
  * 60-second connection timeout protects against eMMC write stalls on older devices.
* **📷 Instant QR Code Pairing**:
  * Senders can host an autonomous P2P group and generate a secure QR code.
  * Receivers can scan the QR code to pair and receive incoming streams without waiting for peer discovery.
* **🛡️ Smart On-Demand Permissions**:
  * **Android 13+**: Uses `NEARBY_WIFI_DEVICES` (`neverForLocation`), completely eliminating the need for GPS/Location.
  * **Android 8 - 12**: Automatically detects and manages location requirements per OS framework standards.
* **🎨 Modern Material 3 UI & Serene Animations**:
  * Serene rotating avatar ring with dynamic dark/light theme status bar icon adaptation.
  * Live MB/s speed gauge, % progress bar, and estimated time remaining (ETA).
* **🔄 Resilient Foreground Service**:
  * Runs on `TransferService` with `FOREGROUND_SERVICE_DATA_SYNC` and `WakeLock` to prevent the OS from killing transfers when the screen turns off or the app is minimized.
* **📂 Automatic Public Downloads Export**:
  * Received files are validated and exported immediately to `Downloads/Anyshare` with full visibility in Google Files and system gallery.

---

## 🏗️ Architecture & Modules

```
com.example.anyshare
├── history/          # Local SQLite / Room & shared storage transfer logging
├── p2p/              # Wi-Fi Direct broadcast receiver, discovery & group management
├── permission/       # Dynamic on-demand runtime permission handlers
├── qr/               # ZXing QR code generator & camera scanner
├── service/          # Foreground Service & WakeLock execution manager
├── storage/          # Scoped Storage & public Downloads exporter
├── transfer/         # TCP Socket streaming engine, P2P network binder & metrics
├── ui/               # Material 3 Activities (Main, Send, Transfer, History, Settings)
└── utils/            # Device configuration & Activity transition animations
```

---

## 🚀 Getting Started

### Prerequisites
* **Android Studio** Hedgehog / Iguana / Jellyfish or later
* **JDK 17**
* **Android SDK** API 26 (Min SDK) up to API 34 (Target SDK)

### Build & Run
```bash
# Clone the repository
git clone https://github.com/YOUR_USERNAME/anyshare-android.git
cd anyshare-android

# Build Debug APK
./gradlew assembleDebug

# Output APK location:
# app/build/outputs/apk/debug/app-debug.apk
```

---

## 📱 How to Use

1. Install `app-debug.apk` on both devices.
2. Turn **ON Wi-Fi** on both phones (no internet or router required; Mobile Data can be OFF).
3. **Sender**: Open **Send** tab ➔ Pick documents, photos, or apps ➔ Tap the receiving device from Nearby Devices.
4. **Receiver**: Tap **Accept** on the incoming Wi-Fi Direct prompt ➔ Files stream directly to `Downloads/Anyshare`.

---

## 📄 License
This project is licensed under the Apache License 2.0 - see the LICENSE file for details.
