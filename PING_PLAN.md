# Ping — status & roadmap

Ping began as *Aura*: 207 Kotlin files of post-quantum crypto, ZK proofs, enterprise
MDM, satellite/LoRa transports, and iOS/Wear/desktop modules — an app that didn't
actually work. It was rebuilt around the one thing it was always meant to do:

> Open app → tap Share → do a hand gesture → a nearby phone doing the **same** gesture
> connects and swaps contact cards, fully offline.

**207 Kotlin files → 32.** Both debug and signed release builds are green.

---

## Status

| Area | State |
|---|---|
| Gesture-as-password (`GestureFingerprint`) | ✅ done |
| Gesture matchmaking (`NearbyExchangeService`) | ✅ done |
| Offline swap — X25519 ECDH + AES-256-GCM | ✅ done |
| Exchange UX — live code chip, countdown, retry, success sheet | ✅ done |
| Home / Profile / Contacts screens | ✅ done |
| Release build — R8 shrink + ABI splits + signed | ✅ done |
| **On-device pairing (two phones)** | ⚠️ **unverified** |
| File-sharing room hub | ⛔ not started |
| App icon / branding for "Ping" | ⛔ not started |

### How it works today
- **Gesture = password.** `GestureFingerprint` maps MediaPipe's 21 hand landmarks to a
  128-value code: a 5-bit finger mask (each finger extended/curled) + a 4-way
  hand-direction bucket. It's a pure function of the pose, so two strangers doing the
  same gesture derive the same code with no enrollment.
- **Matchmaking.** `NearbyExchangeService` advertises `code|name` over GMS Nearby
  Connections (BLE + Wi-Fi Direct) and only requests a connection to a peer whose
  advertised code equals ours, inside a 10-second window (`WINDOW_SECONDS`).
- **Swap.** Ephemeral X25519 ECDH → HKDF-SHA256 → AES-256-GCM sealed card JSON
  (`CryptoUtils`). Fresh keys every swap; nothing long-lived.
- **Card:** name / phone / email / social / note.

### Build outputs
```bash
./gradlew :app:assembleDebug      # app-debug.apk (~62 MB, unshrunk)
./gradlew :app:assembleRelease    # signed, R8-shrunk, per-ABI:
                                  #   arm64-v8a   ~25 MB   ← install this
                                  #   armeabi-v7a ~20 MB
                                  #   universal   ~54 MB
```

---

## Roadmap

### 1 — Field-test the core loop (do this first) 🔴
Nearby needs **two real phones with Play Services** (emulators can't do BLE/Wi-Fi
Direct), so the pairing path is still unverified. Tune if flaky:
- `GestureCamera.COMMIT_FRAMES` — frames a code must hold to lock (10 ≈ 0.5 s).
- `NearbyExchangeService.WINDOW_SECONDS` — pairing window (10 s).
- `GestureFingerprint` finger-extension threshold (`* 1.15`) and the 4 angle buckets —
  loosen if two people "doing the same thing" don't match; tighten if unrelated poses
  collide.
- Confirm the tie-break (`localName < remoteName`) reliably picks one initiator; if both
  sometimes request, add a short random back-off.

Watch it live:
```bash
adb logcat -s Ping:* NearbyExchangeService:* GestureCamera:*
```

### 2 — The file-sharing room hub 🟡
A **hub** where the host's phone is the centre: everyone shares only **file names** (a
manifest); actual bytes move on demand over a **star** link. Design on top of the
existing transport:

- Reuse `NearbyConnectionsTransport` with `Strategy.P2P_STAR` (host advertises, guests
  discover) instead of `P2P_CLUSTER`.
- New `RoomHubService` mirroring `NearbyExchangeService`:
  - **Host = hub:** accepts all guests, keeps a roster, broadcasts a merged manifest
    (`List<FileEntry{ id, name, size, ownerEndpoint }>`).
  - **Guests = spokes:** on join, send the host their file-name manifest (names only).
    Host merges + rebroadcasts.
  - **On-demand pull:** guest taps a file → request routed host→owner → owner streams
    bytes (`Payload.Type.FILE`/`STREAM`) back. Bytes never move until requested.
- **Entry gate:** reuse the gesture code as the room password — only people doing the
  agreed gesture can join (consistent with the 1:1 flow).
- UI: `RoomFragment` (Host/Join toggle, live roster, manifest list, per-row download +
  progress). Data: a `FileEntry` model; DB only if you want a received-files history.

### 3 — Gesture depth & anti-collision 🟡
The code space is 128 — fine for playful use, but two nearby pairs doing the same
gesture at the same instant could cross-connect. If that shows up in testing:
- Grow the fingerprint: finger-splay, thumb direction, or a second angle axis (your
  "2^5 + angle + etc" idea) to expand well beyond 128.
- Add an optional 2-gesture *sequence* ("palm then fist") for a much larger space.
- Surface a hint when two peers lock *different* codes so they realise they're mismatched.

### 4 — Reliability & polish 🟢
- Foreground-service lifecycle: soak-test cancel-on-background; the window job + shutdown
  path exist but are untested on-device.
- Permission-denial UX: the Exchange screen currently just fails silently if
  camera/nearby are denied — add an in-screen prompt with a Settings deep-link.
- App icon / branding pass for "Ping" (currently reuses the old launcher icon).
- Re-enable Gradle config cache by making `downloadHandModel` cache-safe.

### 5 — Optional / stretch 🔵
- **FOSS transport** (Wi-Fi Direct, no Play Services) reinstated behind the unchanged
  `NearbyTransport` interface, for a de-Googled build.
- **SAS / MITM protection** if the room hub ever carries sensitive files (a 6-digit
  short-authentication-string compare — deliberately dropped for the playful 1:1 flow).
- **vCard export** and "add to phone contacts" from the contact detail sheet.
- **CI**: a minimal GitHub Actions workflow (`assembleDebug` + `assembleRelease`) to keep
  the build green; the old heavyweight CI was removed in the rewrite.

---

## Known limitations
- **Not tested on hardware** — the entire pairing path is unverified on real BLE.
- **128-code space** — see roadmap §3.
- **No MITM protection** — SAS was intentionally removed for the playful 1:1 flow.
- **Config cache disabled** (`gradle.properties`) — the model-download task isn't
  cache-compatible yet. Harmless.

---

## Handy commands
```bash
export JAVA_HOME=/opt/android-studio/jbr ANDROID_HOME=$HOME/Android/Sdk
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-arm64-v8a-release.apk
adb logcat -s Ping:* NearbyExchangeService:* GestureCamera:*
```

### Release signing
Signing reads `keystore.properties` (gitignored) at the repo root:
```properties
storeFile=keystore/ping-release.jks
storePassword=…
keyAlias=…
keyPassword=…
```
Without it, `assembleRelease` still builds but leaves the APK unsigned. Generate a key:
```bash
keytool -genkeypair -v -keystore keystore/ping-release.jks -alias ping \
  -keyalg RSA -keysize 2048 -validity 10000
```
