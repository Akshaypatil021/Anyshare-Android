# 🚀 Anyshare - Complete 7-Phase Development Roadmap

> **प्रोजेक्टचे नाव:** Anyshare (Peer-to-Peer Offline High-Speed File Transfer)  
> **प्लॅटफॉर्म:** Native Android (Kotlin)  
> **कॉन्टेक्स्ट:** MCA Semester 3 Project  
> **मूळ उद्देश:** कोणत्याही इंटरनेट, सर्व्हर किंवा बाह्य राउटरशिवाय दोन अँड्रॉइड फोनदरम्यान वाय-फाय डायरेक्ट (Wi-Fi Direct P2P) चा वापर करून मोठ्या फाइल्स अतिशय जलद गतीने (High Speed) ट्रान्सफर करणे.

---

## 📌 १. सिस्टीम आर्किटेक्चर आणि डेटा फ्लो (Architecture Flow)

```
[ Sender Device ]                                           [ Receiver Device ]
       │                                                             │
       ├──── 1. Wi-Fi Direct Init & Group Creation ──────────────────┤
       │     (Group Owner / Server Socket: 192.168.49.1:8888)        │
       │                                                             │
       ├──── 2. Generate QR Code (SSID + Pass + IP + Port)           │
       │                                 ▲                           │
       │                                 │ (Camera Scan)             │
       │                                 └───────────────────────────┤
       │                                                             │
       ├◄─── 3. Client Connects to Wi-Fi P2P Group ─────────────────┤
       │                                                             │
       ├──── 4. TCP Handshake & Metadata Header (File Info) ────────►│
       │        {fileName, fileSize, mimeType, checksum}             │
       │                                                             │
       ├──── 5. High-Speed Binary Streaming (64KB/128KB Chunks) ────►│
       │        (Live Speed Meter, Progress %, ETA Calculation)      │
       │                                                             │
       └──── 6. Transfer Complete & Save to Scoped Storage ─────────►│
                (Downloads/Anyshare via MediaStore API)
```

---

## 🗺️ २. सविस्तर ७ फेजेसचा रोडमॅप (7-Phase Roadmap)

### 🔹 Phase 1: पायाभरणी (Setup, Architecture & Permissions)
* **उद्देश:** Java वरून शुद्ध Kotlin वर मायग्रेशन, आवश्यक मॉडर्न लायब्ररीज जोडणे आणि अँड्रॉइडचे किचकट परमिशन्स मॅनेज करणे.
* **करायची कामे:**
  - [x] **Kotlin & Gradle कॉन्फिगरेशन:**
    - Gradle मध्ये Kotlin Android Plugin, Kotlin Coroutines, Lifecycle & ViewModel समाविष्ट करणे.
  - [x] **लायब्ररीज इंटिग्रेशन (`libs.versions.toml`):**
    - `CameraX` (Core, Camera2, Lifecycle, View) QR कोड स्कॅनिंगसाठी.
    - `ZXing Embedded / Core` QR कोड जनरेट करण्यासाठी.
    - `Kotlin Coroutines Android` असिंक्रोनस सॉकेट I/O साठी.
    - `Material Components 3` आणि `ViewBinding`.
  - [x] **Comprehensive Android Permissions:**
    - Android 13+ (API 33+): `NEARBY_WIFI_DEVICES`, `POST_NOTIFICATIONS`.
    - Android 12 आणि त्याखालील: `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`.
    - सर्व व्हर्जन्ससाठी: `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `WAKE_LOCK`, Storage.
  - [x] **Dynamic Permission Request Handler:** 
    - `PermissionManager.kt` आणि आधुनिक Material 3 Home Screen सह `MainActivity.kt` तयार केले.
* **आउटपुट:** क्लीन आर्किटेक्चर, सर्व आवश्यक लायब्ररीज जोडलेला व सर्व परमिशन्स विनाअडथळा मंजूर होणारा बेस प्रोजेक्ट (✅ पूर्ण).

---

### 🔹 Phase 2: Wi-Fi Direct कोर इंजिन (P2P Discovery & Group Formation)
* **उद्देश:** इंटरनेट किंवा राउटरशिवाय दोन फोन एकमेकांना शोधणे आणि थेट Wi-Fi P2P लिंक तयार करणे.
* **करायची कामे:**
  - [x] **`WifiP2pManager` आणि `Channel` रॅपर:** 
    - `WifiDirectManager.kt` आणि `P2pModels.kt` तयार केले.
  - [x] **BroadcastReceiver / State Flow:**
    - `WifiDirectBroadcastReceiver.kt` आणि `StateFlow`s (`connectionState`, `peersList`, `thisDevice`, `isP2pEnabled`).
  - [x] **Autonomous Group Formation:**
    - सेंडर फोनवर `createAutonomousGroup()` द्वारे `192.168.49.1` वर Group Owner निर्मिती.
* **आउटपुट:** दोन प्रत्यक्ष डिव्हाइसेस कोणत्याही राउटरशिवाय थेट Wi-Fi Direct द्वारे कनेक्ट होणे (✅ पूर्ण).

---

### 🔹 Phase 3: QR Code पॅरिंग सिस्टीम (Zero-Mistake Pairing)
* **उद्देश:** चुकीच्या फोनला फाईल पाठवण्याची चूक टाळण्यासाठी QR स्कॅनने सेकंदात अचूक ऑथेंटिकेशन व कनेक्शन करणे.
* **करायची कामे:**
  - [x] **Connection Payload Model:**
    - `QrConnectionPayload.kt` (SSID, Passphrase, Host IP, Port, Token, PIN Code).
  - [x] **Sender Side (QR Generator):**
    - `QrCodeGenerator.kt` (ZXing द्वारे हाय-कॉन्ट्रास्ट 600px QR बिटमॅप) व `dialog_qr_display.xml`.
  - [x] **Receiver Side (CameraX QR Scanner):**
    - `QrImageAnalyzer.kt` व `QrScannerActivity.kt` (लाइव्ह कॅमेरा व्ह्यूफाइंडर व ऑटो-डिकोड).
  - [x] **Manual PIN Fallback:**
    - ४-अंकी PIN डायलॉग फॉलबॅक पर्याय उपलब्ध.
* **आउटपुट:** QR कोड स्कॅन करताच शून्य मानवी हस्तक्षेपात सुरक्षित पेअरिंग पूर्ण होणे (✅ पूर्ण).

---

### 🔹 Phase 4: डेटा ट्रान्सफर प्रोटोकॉल (TCP Sockets & High-Speed Chunking)
* **उद्देश:** कोणत्याही आकाराच्या फाइल्स (GBs मध्ये असल्या तरी) विनातक्रार आणि कमाल वेगाने सलग ट्रान्सफर करणे.
* **करायची कामे:**
  - [x] **Custom Packet Protocol:**
    - `TransferModels.kt` (Header Packet: Magic byte, fileName, fileSize, mimeType, chunkSize).
    - **५१२ KB ते १ MB ब्लॉक साईझ:** लहान ६४ KB ऐवजी किमान ५१२ KB ते १ MB बफर चंक्स (`TransferConfig.MIN_CHUNK_SIZE = 512KB`, `MAX_CHUNK_SIZE = 1MB`, `SO_SNDBUF/RCVBUF = 1MB`).
  - [x] **Socket Server & Client Coroutines:**
    - `FileSender.kt` (सॉकेट स्ट्रीमिंग, 1MB बफर, हँडशेक व `Dispatchers.IO`).
    - `FileReceiver.kt` (सर्व्हर सॉकेट, 1MB बफर, चंक राइटिंग व ऑटो-क्लीनअप).
  - [x] **Real-time Transfer Metrics Engine:**
    - `TransferMetricsCalculator.kt` (स्लाइडिंग विंडो स्पीड `MB/s`, अचूक `ETA`, प्रोग्रेस `%`, तात्काळ `cancel()`).
* **आउटपुट:** सेंडरकडून रिसिव्हरकडे डेटा ५१२ KB - १ MB चंक्समध्ये सुरक्षितपणे ट्रान्सफर होणे आणि लाइव्ह आकडेवारी मिळणे (✅ पूर्ण).

---

### 🔹 Phase 5: बॅकग्राउंड ट्रान्सफर आणि स्टोरेज (Service & Scoped Storage)
* **उद्देश:** ॲप मिनिमाइझ झाले किंवा स्क्रीन बंद झाली तरी ट्रान्सफर सुरळीत चालू राहणे आणि रिसिव्ह झालेली फाईल फोन स्टोरेजमध्ये सुरक्षित सेव्ह होणे.
* **करायची कामे:**
  - [x] **Foreground Service (`TransferService.kt`):**
    - Android 14+ `dataSync` टाईपसह Ongoing Notification, लाइव्ह प्रोग्रेस बार, स्पीड, ETA आणि "Cancel" ॲक्शन बटण.
  - [x] **Scoped Storage & MediaStore API:**
    - `StorageManager.kt` द्वारे Android 10+ वर `MediaStore.Downloads` मधील `Downloads/Anyshare` मध्ये फाईल सुरक्षितपणे सेव्ह करणे व `FileProvider` द्वारे उघडणे.
  - [x] **WakeLock & Screen Retention:**
    - `WakeLockManager.kt` (CPU Partial WakeLock आणि Wi-Fi High-Performance Lock) मुळे स्क्रीन ऑफ असतानाही बॅकग्राउंड ट्रान्सफर अखंड चालणे.
* **आउटपुट:** ॲप बॅकग्राउंडमध्ये असतानाही ट्रान्सफर पूर्ण होणे आणि फाईल फोनच्या फाइल मॅनेजरमध्ये लगेच उपलब्ध होणे (✅ पूर्ण).

---

### 🔹 Phase 6: UI व ५ मुख्य स्क्रीन्स (Complete UX Flow)
* **उद्देश:** ॲपला एक आधुनिक, सुंदर, डार्क/लाइट सपोर्ट असलेला Material 3 लुक देणे.
* **स्क्रीन्सचे तपशील:**
  1. [x] **Screen 1: Home Screen (`MainActivity.kt` & `activity_main.xml`)**
     - मोठे **Send** आणि **Receive** बटन्स, Wi-Fi चिप व Quick History Card.
  2. [x] **Screen 2: Send File Screen (`SendActivity.kt` & `activity_send.xml`)**
     - कॅटेगरीनुसार फाईल पिकर (व्हिडिओ, इमेजेस, ऑडिओ, ॲप्स, डॉक्स) व सुंदर QR कोड निर्मिती.
  3. [x] **Screen 3: Receive File Screen (`QrScannerActivity.kt` & `activity_qr_scanner.xml`)**
     - CameraX लाइव्ह व्ह्यूफाइंडर, फ्लॅशलाइट टॉगल व ४-अंकी PIN इनपुट.
  4. [x] **Screen 4: Transfer Progress Screen (`TransferActivity.kt` & `activity_transfer.xml`)**
     - लाइव्ह प्रोग्रेस बार आणि %, थेट ट्रान्सफर स्पीड (`MB/s`), ETA टाइमर व सुरक्षित "Cancel" बटण.
  5. [x] **Screen 5: Completed / History Screen (`HistoryActivity.kt` & `activity_history.xml`)**
     - ट्रान्सफर सक्सेस ग्रीन बॅनर, "Open File", "Send Another" आणि संपूर्ण हिस्टरी यादी.
* **आउटपुट:** ५ ही स्क्रीन्सचे अखंड आणि स्मूथ नेव्हिगेशन (✅ पूर्ण).

---

### 🔹 Phase 7: ऑप्टिमायझेशन, एरर हँडलिंग व टेस्टिंग (Polish & Readiness)
* **उद्देश:** ॲप कोणत्याही परिस्थितीत क्रॅश न होता स्टेबल चालणे आणि कमाल ट्रान्सफर स्पीड गाठणे.
* **करायची कामे:**
  - [x] **बफर आणि स्पीड ऑप्टिमायझेशन:**
    - ५१२ KB ते १ MB ब्लॉक साईझ आणि ५ GHz Wi-Fi Direct बँड प्राधान्य (`setGroupOperatingBand`).
  - [x] **सर्वसमावेशक एरर हँडलिंग:**
    - फाईल स्वीकारण्यापूर्वी `StatFs` द्वारे स्टोरेज स्पेस प्री-चेक (`ERR_STORAGE_FULL`).
    - सडन डिस्कनेक्शन टाइमआऊट सेफ्टी (`SocketTimeoutException`) आणि ऑटो-क्लीनअप.
    - तात्काळ कॅन्सलेशनवर अपूर्ण (corrupted) फाईल त्वरित डिलीट करणे.
  - [x] **डिव्हाइस डायग्नोस्टिक्स व ऑटोमेटेड टेस्ट्स:**
    - `P2pDiagnostics.kt` (हार्डवेअर, 5GHz, स्टोरेज व बॅटरी रिपोर्ट).
    - `TransferMetricsTest.kt` आणि `QrPayloadTest.kt` (यूनिट टेस्ट्स).
* **आउटपुट:** १००% प्रोडक्शन-रेडी, अत्यंत वेगवान आणि बग-मुक्त Anyshare ॲप्लिकेशन (✅ पूर्ण).

---

## 📋 ३. प्रगती ट्रॅकिंग तक्ता (Progress Checklist)

| टप्पा / Phase | कामाचा भाग | सद्यस्थिती (Status) |
| :--- | :--- | :---: |
| **Phase 1** | Kotlin सेटअप, लायब्ररीज आणि Android Permissions | ✅ Completed |
| **Phase 2** | Wi-Fi Direct (P2P) डिस्कव्हरी व ग्रुप फॉर्मेशन | ✅ Completed |
| **Phase 3** | QR Code जनरेटर व कॅमेरा स्कॅनर पॅरिंग | ✅ Completed |
| **Phase 4** | TCP Socket प्रोटोकॉल, चंकिंग व लाइव्ह स्पीड मीटर | ✅ Completed |
| **Phase 5** | Foreground Service, Scoped Storage व WakeLock | ✅ Completed |
| **Phase 6** | ५ मुख्य स्क्रीन्स डिझाईन आणि नेव्हिगेशन इंटिग्रेशन | ✅ Completed |
| **Phase 7** | 5 GHz स्पीड ट्यूनिंग, एरर हँडलिंग आणि फायनल टेस्टिंग | ✅ Completed |

---

*दस्तऐवज निर्मिती दिनांक: ऑक्टोबर २०२६ | Anyshare Development Architecture*
