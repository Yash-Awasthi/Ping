# ✌️ Ping

> **Same gesture → instant contact swap. No internet. No accounts. No QR codes.**

[![build](https://github.com/Yash-Awasthi/Ping/actions/workflows/build.yml/badge.svg)](https://github.com/Yash-Awasthi/Ping/actions/workflows/build.yml)
[![release](https://img.shields.io/github/v/release/Yash-Awasthi/Ping)](https://github.com/Yash-Awasthi/Ping/releases/latest)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-36+-green.svg)](https://developer.android.com)
[![BLE](https://img.shields.io/badge/Connectivity-BLE%20/%20Wi-Fi%20Direct-blue.svg)]()
[![Privacy](https://img.shields.io/badge/Privacy-100%25%20On--Device-brightgreen.svg)]()

**Ping** turns a shared hand gesture into an offline handshake. Two people agree on a gesture, hold it to the camera, and Ping matches on that pose to find the right device over Bluetooth LE — swapping contact cards with end-to-end encryption.

The gesture decides *who* to connect to. It is not a secret: it is derived from the pose alone, so it has only a few hundred possible values (far more with the optional two-gesture password), and a device in radio range can enumerate them. What authenticates the peer is a six-digit code both phones show after the keys are exchanged: the cards are released only when both users confirm the digits match.

No server. No cloud. No account. Your data never leaves your phone.

---

## ✨ Features

| Feature | Description |
|---------|-------------|
| 📡 **Connection Status** | Animated indicator — scanning (blue pulse), connecting (amber spin), paired (green check), error (red X) |
| 🤝 **Gesture Matchmaking** | About 40 named hand poses plus circle and wave motions, two-hand gestures, left or right hand, or chain two gestures — no enrollment needed |
| 🖐️ **Practice Mode** | Live label, finger count and raw measurements for your hand, with a guide to every gesture; never connects |
| 📤 **Export All** | Share every listed contact as one `.vcf` file |
| 🗂️ **File Room** | Host or join a room by gesture; share file names, pull files on demand over a Nearby star link. The host approves every guest and both phones show a link code to compare |
| 📡 **Offline P2P** | BLE + Wi-Fi Direct via Google Nearby Connections |
| 🔐 **E2E Encryption** | P-256 ECDH → HKDF-SHA256 → AES-256-GCM, fresh keys every swap |
| 👤 **Contact Cards** | Name, phone, email, social handles, short note |
| 🎯 **Auto-Matchmaking** | Phones only connect when the advertised gesture tokens match |
| ✅ **Peer Check** | Compare a six-digit code on both phones before any card is sent |
| 📷 **Live Gesture Preview** | See the detected pose and its stability before it locks |
| 🔎 **Contact Search** | Filter by any field; favourites sort first |
| 📳 **Haptics** | Pulse on gesture lock, on the confirm screen, and on a finished swap |
| 📇 **Contact Export** | Add a received card to the phone's contacts, or share it as a vCard |
| 💾 **Offline Contacts** | Room v1 database — contacts persist on device |
| 🔒 **Permission Denied UX** | Clear explanations when camera/Nearby permissions are missing |

---

## 🎬 How It Works

```
    ┌─────────────┐           ┌─────────────┐
    │   Phone A   │           │   Phone B   │
    │             │           │             │
    │  ✌️ gesture  │           │  ✌️ gesture  │
    │      │      │           │      │      │
    │  5-bit mask │           │  5-bit mask │
    │  + direction│           │  + direction│
    │      │      │           │      │      │
    │  code:F31A0 │           │  code:F31A0 │  ← Same gesture = same code
    │      │      │           │      │      │
    │  BLE advertise          │  BLE advertise
    │      │      │           │      │      │
    └──────┼──────┘           └──────┼──────┘
           │                         │
           │    Nearby Connections    │
           │   (only matches if      │
           │    codes are equal)     │
           └─────────────────────────┘
                     │
              ECDH key exchange
                     │
        both users confirm a 6-digit code
                     │
            AES-256-GCM sealed card
                     │
               💾 Saved to Contacts
```

### The Gesture Code

`GestureFingerprint` maps MediaPipe's 21 hand landmarks to:
- **5-bit finger mask** — each finger extended or curled, with the thumb judged by how straight it is and how far it sits from the index knuckle
- **Direction** — up, right, down or left (a lone thumb uses its own direction, so 👍 and 👎 are stable)
- **Gaps, pinch, crossed fingers and palm side** — the details behind ✌️ 🖖 🖐️ 👌 🤏 🤞 ✋ 🤚
- **Left or right hand** — from the detector, trusted only when recent frames agree
- **Motion** — a circle drawn in the air (either way round) or a wave, tracked over the last second
- **Two hands together** — the second hand's finger mask is added

The frame is rotated and mirrored to match the preview before detection, so directions mean what you see on screen. Two strangers doing the same gesture derive the same code with zero enrollment. The emoji shown is a best guess for the user; only the code decides matching. Some poses cannot be told apart by a 2D camera (👊 vs ✊, 🫵, 🤌 vs 🤏).

---

## 🚀 Quick Start

### Prerequisites

- Android SDK (platform 36)
- JDK 17–21
- Two physical Android phones with Google Play Services (emulators can't do BLE)

### Build

```bash
export JAVA_HOME=/opt/android-studio/jbr  # or any JDK 17–21
export ANDROID_HOME=$HOME/Android/Sdk

./gradlew :app:assembleDebug
```

The MediaPipe hand model (~8 MB) downloads automatically on first build.

### Install on Two Phones

```bash
adb devices  # list connected phones

adb -s SERIAL_A install -r app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL_B install -r app/build/outputs/apk/debug/app-debug.apk
```

### Try It

1. Open **Ping** on both phones
2. Grant camera + nearby permissions
3. Open **Profile** → fill in your contact card → Save
4. Both tap **Share** → hold the **same gesture** (e.g., open palm pointing up)
5. Watch them connect and swap! 🎉

### Watch Logs

```bash
adb -s SERIAL_A logcat -s Ping:* NearbyExchangeService:* GestureCamera:*
```

---

## 🔒 Security

| Layer | Implementation |
|-------|---------------|
| Key exchange | P-256 (secp256r1) ECDH, ephemeral per swap |
| Key derivation | HKDF-SHA256 |
| Encryption | AES-256-GCM (authenticated encryption) |
| Keys | Fresh every swap — nothing long-lived |
| Transport | BLE + Wi-Fi Direct (no internet) |
| Data | All on-device (Room v1 database) |
| Matchmaking | The gesture is advertised only as a truncated SHA-256 token. With so few possible values a device in range can still recover it and try to join. |
| Peer authentication | A six-digit short authentication string over both public keys. Users compare it on both screens; no card is sent until both confirm, and a man in the middle produces different digits on each phone. |
| Permissions | No `INTERNET` permission, by design: the manifest removes the one a Play Services dependency injects. Networking code cannot ship in this app. |

---

## 🏗️ Architecture

Ping uses **MVVM** with Hilt dependency injection:

```
┌─────────────────────────────────────────────┐
│                  UI Layer                    │
│  Compose Screens ← StateFlow ← ViewModel    │
├─────────────────────────────────────────────┤
│              ViewModel Layer                 │
│  HomeViewModel · ProfileViewModel           │
│  ContactsViewModel · ExchangeViewModel      │
│  (Hilt @Inject, one per Compose screen)     │
├─────────────────────────────────────────────┤
│               Data Layer                     │
│  ContactRepository · ProfileRepository      │
│  (Room database, reactive Flow)             │
├─────────────────────────────────────────────┤
│              Service Layer                   │
│  NearbyExchangeService                      │
│  NearbyConnectionsTransport (BLE + Wi-Fi)   │
├─────────────────────────────────────────────┤
│              Crypto Layer                    │
│  P-256 ECDH → HKDF-SHA256 → AES-256-GCM     │
└─────────────────────────────────────────────┘
```

### DI Setup

```kotlin
@HiltViewModel
class ExchangeViewModel @Inject constructor(
    private val gestureCamera: GestureCamera,
) : ViewModel()
```

## 📂 Project Structure

```
Aura/
├── app/src/main/java/com/ping/app/
│   ├── auth/           # GestureFingerprint, GestureCamera (MediaPipe)
│   ├── data/           # Room DAOs, repositories, transport bridges
│   ├── di/             # Hilt modules
│   ├── model/          # Contact, Profile, ExchangeSession
│   ├── service/        # NearbyExchangeService and its transport
│   ├── ui/             # Compose screens
│   └── utils/          # CryptoUtils (P-256 ECDH + HKDF + AES-256-GCM)
├── PING_PLAN.md        # Roadmap
└── README.md
```

---

## 📊 Gesture Code Space

| Input | Bits | Range |
|-------|------|-------|
| Index finger | 1 | extended/curled |
| Middle finger | 1 | extended/curled |
| Ring finger | 1 | extended/curled |
| Pinky finger | 1 | extended/curled |
| Thumb | 1 | extended/curled |
| Hand direction | 2 | left/right/up/down |
| Gaps (open hand only) | 3 | which neighbours are apart |
| Pinch, crossed, palm side, hand side | 1–2 each | flags |
| Motion | 3 | none / circle either way / wave |
| **Total** | | **thousands of codes** |

---

## 🗺️ Roadmap

- [ ] **iOS companion** — cross-platform gesture exchange
- [ ] **Custom gesture sets** — user-defined gesture alphabets

---

## 🤝 Contributing

Contributions welcome! Open an issue or PR. See [`PING_PLAN.md`](PING_PLAN.md) for the full roadmap.

---

## 📄 License

[MIT](LICENSE)
