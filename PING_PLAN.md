# Ping — status & roadmap

Ping is a lean, single-purpose Android app built around one idea:

> Open app → tap Share → do a hand gesture → a nearby phone doing the **same** gesture
> connects, both people confirm a six-digit code, and the phones swap contact cards,
> fully offline.

**~40 Kotlin files, no accounts, no server, no `INTERNET` permission.** Debug and release
builds, lint, and the unit tests are green.

---

## Status

| Area | State |
|---|---|
| Gesture-as-password (`GestureFingerprint`) | ✅ done, unit-tested |
| Gesture matchmaking (`NearbyExchangeService`) | ✅ done — advertises a hash token, rejects non-matching peers |
| Offline swap — P-256 ECDH + AES-256-GCM | ✅ done, unit-tested |
| Peer check — six-digit short authentication string, both users confirm | ✅ done, unit-tested |
| Exchange UX — countdown, retry, confirm screen, permission prompt | ✅ done |
| Home / Profile / Contacts screens (Compose) | ✅ done |
| Contact export — add to phone, share as vCard | ✅ done, unit-tested |
| Contacts search (name, phone, email, social, note), favourites first | ✅ done, unit-tested |
| Haptics on gesture lock, confirm screen and completed swap | ✅ done, untested on a device |
| Room DAO tests | ✅ written (`androidTest`), compile in CI; need a device to run |
| Release build — R8 shrink + ABI splits + signed | ✅ done |
| On-device pairing (two phones) | ✅ verified once on a Realme and a Samsung; timing not tuned |
| File-sharing room hub (host approves guests, on-demand pull, guest-to-guest relay via host) | ✅ done, protocol and relay unit-tested in-process; not yet run on real phones |
| App icon / branding for "Ping" | ✅ adaptive icon with themed layer |
| Gesture depth (splay flag, optional two-gesture password) | ✅ done, unit-tested |
| Repeat swap refreshes the saved contact | ✅ done, unit-tested |

### How it works today
- **Gesture = password.** `GestureFingerprint` maps MediaPipe's 21 hand landmarks to a
  128-value code: a 5-bit finger mask (each finger extended/curled) + a 4-way
  hand-direction bucket. It's a pure function of the pose, so two strangers doing the
  same gesture derive the same code with no enrollment.
- **Matchmaking.** `NearbyExchangeService` advertises `<sha256(code) prefix>|<nonce>` over
  GMS Nearby Connections (BLE + Wi-Fi Direct), only requests a connection to a peer whose
  token equals ours, and only accepts incoming connections whose token equals ours, inside
  a 10-second window (`WINDOW_SECONDS`). The code space is 128, so the token hides the code
  from a casual scanner but not from anyone who enumerates.
- **Peer check.** After the ephemeral P-256 keys are swapped, both phones show
  `CryptoUtils.shortAuthString(keyA, keyB)`. No card is sent until the local user taps
  "It matches"; tapping "It differs" ends the session.
- **Swap.** P-256 ECDH → HKDF-SHA256 → AES-256-GCM sealed card JSON (`CryptoUtils`).
  Fresh keys every swap; nothing long-lived.
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
- Confirm the tie-break (`localName < remoteName`, where the name ends in a random nonce)
  reliably picks one initiator; if both sometimes request, add a short random back-off.
- Check that the six-digit confirm screen appears on both phones and that a card only
  moves after both taps.

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
- App icon / branding pass for "Ping" (currently reuses the old launcher icon).
- Re-enable Gradle config cache by making `downloadHandModel` cache-safe.

### 5 — Optional / stretch 🔵
- **FOSS transport** (Wi-Fi Direct, no Play Services) reinstated behind the unchanged
  `NearbyTransport` interface, for a de-Googled build.
- **Mesh, relay, voice and networking modules** from the absorbed corpus were deleted from
  the build. Anything that needs `INTERNET` cannot ship here; the manifest removes it.

---

## Permissions

The app requests Bluetooth (scan/advertise/connect), Nearby Wi-Fi devices, location (needed
by BLE scanning below Android 12), camera, vibrate, notifications and foreground-service
(connected device). It deliberately has **no `INTERNET` permission**:
`AndroidManifest.xml` removes the one that `play-services-nearby`'s telemetry dependency
would otherwise inject. Do not port a networking module (Matrix, Nostr, LocalSend, croc,
PairDrop) into this tree; it cannot work and would break the "nothing leaves your phone"
promise.

## Known limitations
- **One two-phone run only** — pairing worked once; `COMMIT_FRAMES`, `WINDOW_SECONDS` and the finger threshold are untuned.
- **128-code space** — see roadmap §3. Gesture depth is the next protocol change.
- **Peer authentication is manual** — the six-digit compare only protects users who
  actually compare the digits.
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
