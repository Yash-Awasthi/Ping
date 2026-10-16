# ✌️ Ping

> **Same gesture → instant contact swap. No internet. No accounts. No QR codes.**

[![build](https://github.com/Yash-Awasthi/Ping/actions/workflows/build.yml/badge.svg)](https://github.com/Yash-Awasthi/Ping/actions/workflows/build.yml)
[![release](https://img.shields.io/github/v/release/Yash-Awasthi/Ping)](https://github.com/Yash-Awasthi/Ping/releases/latest)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-36+-green.svg)](https://developer.android.com)
[![BLE](https://img.shields.io/badge/Connectivity-BLE%20/%20Wi-Fi%20Direct-blue.svg)]()
[![Privacy](https://img.shields.io/badge/Privacy-100%25%20On--Device-brightgreen.svg)]()

**Ping** turns a shared hand gesture into an offline handshake. Two people agree on a gesture, hold it to the camera, and Ping matches on that pose to find the right device over Bluetooth LE — swapping contact cards with end-to-end encryption.

The gesture is the only check. It is the password of a PAKE key exchange: two phones derive the same key only if they hold the same gesture, and no card or file moves until each phone has proved that to the other. There is no code to read out or compare. The gesture comes from the pose alone, so a single gesture has only a few hundred possible values (far more with the optional two-gesture password). A stranger in range cannot test guesses offline, but each connection they make is one guess, so Ping allows only three failed attempts per swap.

No server. No cloud. No account. Your data never leaves your phone.

---

## ✨ Features

| Feature | Description |
|---------|-------------|
| 📡 **Connection Status** | Animated indicator — scanning (blue pulse), connecting (amber spin), paired (green check), error (red X) |
| 🤝 **Gesture Matchmaking** | About 40 named hand poses plus circle, wave and swipe motions, two-hand gestures, left or right hand, or chain two gestures — no enrollment needed |
| ⭐ **Custom Gestures** | Record and name your own one- or two-pose gestures in Practice, then start a swap or room from them with one tap; the code comes from the poses, so the other person can just make them live |
| 🖐️ **Practice Mode** | Live label, finger count and raw measurements for your hand, with a guide to every gesture and a place to record your own; never connects |
| 📤 **Export All** | Share every listed contact as one `.vcf` file |
| 📥 **Import** | Load contacts from a `.vcf` file (vCard 2.1, 3.0 or 4.0) |
| 💾 **Back up & Restore** | Save every contact to a JSON file you choose, private memos, stars and swap times included; restoring merges into the list without duplicating anyone |
| ✏️ **Edit & Memo** | Edit any contact and keep a private "where we met" memo that is never swapped or shared; only your own backup keeps it |
| 🧑‍🏫 **Roll Call** | Offline attendance for a whole class: the teacher's phone scans while students tap Check in; no connections, so 100+ students fit one scan. Class-list CSV import, a live seat grid that lights up as students arrive, per-student attendance rates, manual ticks, session history and CSV export |
| ⚡ **Quick Launch** | Long-press the icon or add the Quick Settings tile to open a swap in one tap |
| ⚠️ **Mismatch Notice** | Shows when nearby phones are holding a different gesture instead of timing out silently |
| 🗂️ **File Room** | Host or join a room by gesture, up to sixteen guests; share files or a whole folder (its subfolders are recreated on download), pull files on demand over a Nearby star link, each checked against its SHA-256. A failed download keeps what arrived and resumes from there. Guests start listening before their gesture locks, the host can let everyone in at once, and Download all fetches the whole room with live speed and time left. Guests prove the room's gesture before the host sees them, and the host approves every guest |
| 📡 **Offline P2P** | BLE + Wi-Fi Direct via Google Nearby Connections; without Play Services, BLE advertising plus an encrypted L2CAP channel |
| 🔐 **E2E Encryption** | Gesture-keyed PAKE on P-256 → HKDF-SHA256 → AES-256-GCM, fresh keys every swap |
| 👤 **Contact Cards** | Name, phone, email, social handles, short note |
| 🎯 **Auto-Matchmaking** | Phones only try peers whose advertised gesture bucket matches; the gesture check decides |
| ✅ **Gesture Proof** | The gesture keys the exchange itself; nothing is sent until both phones prove they hold it |
| 📷 **Live Gesture Preview** | See the detected pose and its stability before it locks |
| 🔎 **Contact Search** | Filter by any field; favourites sort first |
| 📳 **Haptics** | Pulse on gesture lock and on a finished swap |
| 📇 **Contact Export** | Add a received card to the phone's contacts, or share it as a vCard |
| 💾 **Offline Contacts** | Room v1 database — contacts persist on device |
| 🌐 **Languages** | English, Hindi, Spanish, French, German and Brazilian Portuguese; on Android 13+ pick one per app in system settings |
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
           │   (connects within the  │
           │   same 4-bit bucket)    │
           └─────────────────────────┘
                     │
      PAKE keyed by the gesture code
                     │
     both phones prove the same key
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
- **Left or right hand** — the detector's call plus the way the fingers bend (the hand's chirality), smoothed over recent frames so it does not flicker
- **Motion** — a circle drawn in the air (either way round), a wave, or a swipe left, right, up or down, tracked over the last second
- **Two hands together** — the second hand's finger mask is added

The frame is rotated and mirrored to match the preview before detection, so directions mean what you see on screen. Two strangers doing the same gesture derive the same code with zero enrollment. The emoji shown is a best guess for the user; only the code decides matching. Some poses cannot be told apart by a 2D camera (👊 vs ✊, 🫵, 🤌 vs 🤏).

---

## 🚀 Quick Start

### Prerequisites

- Android SDK (platform 37.2)
- JDK 25
- Two physical Android phones (emulators can't do BLE). With Google Play Services they use Nearby Connections; without it they need Android 10+ and Bluetooth 5 extended advertising

### Build

```bash
export JAVA_HOME=/path/to/jdk-25
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
| Key exchange | CPace-style PAKE on P-256: the gesture code is hashed to the generator, fresh scalar per attempt, bound to both advertisements and the link's own token |
| Key derivation | HKDF-SHA256 |
| Encryption | AES-256-GCM (authenticated encryption) |
| Keys | Fresh every swap — nothing long-lived |
| Transport | BLE + Wi-Fi Direct via Nearby Connections, or BLE L2CAP when Play Services is missing (no internet). The L2CAP link runs an ephemeral P-256 ECDH handshake and seals every frame with AES-256-GCM under per-direction keys and counter nonces. |
| Data | All on-device (Room v1 database) |
| Matchmaking | The advertisement carries only a 4-bit bucket of the gesture, which narrows who to try without giving the gesture away. |
| Peer authentication | HMAC key-confirmation tags over both shares. A peer without the gesture fails, and a relay in the middle fails because each leg has a different link token. One online guess per connection; a swap gives up after three failed peers, a guest after three failed rooms. |
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
│  BleL2capTransport (no Play Services)       │
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
| Motion | 3 | none / circle either way / wave / swipe in four directions |
| **Total** | | **thousands of codes** |

---

## 🗺️ Roadmap

- [ ] **iOS companion** — cross-platform gesture exchange

---

## 🤝 Contributing

Contributions welcome! Open an issue or PR. See [`PING_PLAN.md`](PING_PLAN.md) for the full roadmap.

---

## 📄 License

[MIT](LICENSE)
