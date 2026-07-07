# Ping

**Do the same hand gesture near someone → swap contact cards. Offline. Playful.**

Two people agree on a gesture ("let's both do a fist pointing up"), open Ping, tap
**Share**, and hold that gesture to the front camera. Ping turns the hand pose into a
short **gesture code**; only phones showing the *same* code within a ~10-second window
find each other and swap cards over a direct offline radio link. No account, no
internet, no QR, no server.

---

## How it works

```
open app → tap Share → hold gesture → camera locks a code
        → phone advertises that code over Nearby (BLE / Wi-Fi Direct)
        → the only peer it connects to is one showing the SAME code
        → ECDH handshake + AES-256-GCM → cards swapped → saved to Contacts
```

- **Gesture = password.** `GestureFingerprint` maps MediaPipe's 21 hand landmarks to
  a 5-bit finger mask (each finger extended/curled) + a 4-way hand-direction bucket →
  128 distinct codes. It's a pure function of the pose, so two strangers doing the same
  gesture derive the same code with no enrollment.
- **Matchmaking.** `NearbyExchangeService` advertises `code|name` and only requests a
  connection to a peer whose advertised code equals ours (`onEndpointFound`).
- **Crypto.** Ephemeral X25519 ECDH → HKDF-SHA256 → AES-256-GCM sealed card JSON
  (`CryptoUtils`). Fresh keys every swap; nothing long-lived.
- **Card fields.** Name, phone, email, social/handle, and a short note.

### On the Exchange screen

The camera shows a live **gesture-code chip** ("✌️ pointing up") as you move your hand,
so both people can confirm they're on the same gesture *before* it locks. Once a code
holds steady it locks, and a **10-second countdown** runs while it searches for a match.
If nobody matches in time, a **Try again** button restarts the capture without leaving
the screen.

---

## Build & install

Requirements: Android SDK (platform 36), JDK 17–21. The build uses the Android Studio
bundled JBR by default.

```bash
export JAVA_HOME=/opt/android-studio/jbr      # or any JDK 17–21
export ANDROID_HOME=$HOME/Android/Sdk

./gradlew :app:assembleDebug                  # builds app/build/outputs/apk/debug/app-debug.apk
```

The MediaPipe `hand_landmarker.task` model (~8 MB) is downloaded into
`app/src/main/assets/` automatically on first build (`downloadHandModel` task). For an
offline build, drop the file there manually.

### Signed release APKs

```bash
./gradlew :app:assembleRelease
```

Produces R8-shrunk, per-ABI signed APKs in `app/build/outputs/apk/release/`:

| APK | Size | Use |
|---|---|---|
| `app-arm64-v8a-release.apk` | ~25 MB | modern phones — **install this** |
| `app-armeabi-v7a-release.apk` | ~20 MB | older 32-bit devices |
| `app-universal-release.apk` | ~54 MB | any device |

Signing reads `keystore.properties` at the repo root (gitignored). Without it,
`assembleRelease` still builds but leaves the APK unsigned. See
[`PING_PLAN.md`](PING_PLAN.md#release-signing) for the keystore setup.

### Try it on two phones

Nearby Connections needs **real devices** (emulators can't do BLE/Wi-Fi Direct). Both
phones need Google Play Services.

```bash
# with two phones plugged in, list them:
adb devices

# install on each (replace SERIALs):
adb -s SERIAL_A install -r app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL_B install -r app/build/outputs/apk/debug/app-debug.apk
```

Then on each phone: grant camera + nearby/location permissions → open **Profile**,
fill in your card, Save → back to **Home** → tap **Share** → both hold the *same*
gesture (e.g. open palm pointing up) at the same time. They should connect and swap.

Watch the logs while testing:

```bash
adb -s SERIAL_A logcat -s Ping:* NearbyExchangeService:* GestureCamera:*
```

---

## Project layout

```
app/src/main/java/com/ping/app/
  auth/       GestureFingerprint, GestureCamera (MediaPipe HandLandmarker)
  service/    NearbyExchangeService, NearbyConnectionsTransport, NearbyTransport
  crypto      → utils/CryptoUtils (X25519 + AES-256-GCM)
  data/       Room v1: Contact + Profile (DAOs, repos, DI)
  model/      Contact, Profile, ExchangeSession
  ui/         home / exchange / contacts / profile
```

See [`PING_PLAN.md`](PING_PLAN.md) for what's next — the file-sharing room hub, polish,
and known limitations.

## License

MIT — see [`LICENSE`](LICENSE).
