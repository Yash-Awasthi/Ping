# Ping — plan & next steps

This session stripped the old "Aura" project (207 Kotlin files of post-quantum crypto,
ZK proofs, enterprise MDM, satellite/LoRa, iOS/Wear/desktop, etc.) down to a working
**32-file** core that does the one thing you actually wanted:

> Open app → tap Share → do a hand gesture → a nearby phone doing the **same** gesture
> connects and swaps contact cards, fully offline.

The debug APK builds green (`./gradlew :app:assembleDebug`).

---

## What works now (this session)

- **Gesture-as-password.** `GestureFingerprint` (5-finger mask + hand angle → 128 codes),
  a pure function of the pose so strangers match with no enrollment.
- **Gesture matchmaking.** `NearbyExchangeService` advertises `code|name` and connects
  only to a peer with the same code, inside a 10-second window (`WINDOW_MS`).
- **Offline swap.** GMS Nearby Connections (BLE + Wi-Fi Direct), X25519 ECDH + AES-256-GCM.
- **Simple UX.** Home (Share) → Exchange (camera → status → success sheet) → Contacts;
  Profile edits the card. Fields: name / phone / email / social / note.
- **Single-flavor build**, package `com.ping.app`, Room v1 (Contact + Profile).

### Still to verify on hardware
Nearby needs two real phones with Play Services — could not be exercised in this
environment. See README "Try it on two phones". Watch for the items under **Risks** below.

---

## Next steps — priority order

### 1. Field-test the core loop (do this first)
- Two phones, same gesture, confirm connect + swap. Tune constants if flaky:
  - `GestureCamera.COMMIT_FRAMES` (currently 10 frames ≈ 0.5 s to lock a gesture).
  - `NearbyExchangeService.WINDOW_MS` (10 s pairing window).
  - `GestureFingerprint` finger-extension threshold (`* 1.15`) and the 4 angle buckets —
    if two people "doing the same thing" don't match, loosen; if unrelated poses collide,
    tighten or add more angle buckets.
- Decide whether the deterministic tie-break (`localName < remoteName`) reliably picks one
  initiator; if both sometimes request, add a short random back-off.

### 2. Gesture UX polish
- Show the **detected gesture + code** live so both people can confirm they match ("you're
  both on ✊ up") *before* searching — much easier than blind trial.
- A visible countdown for the 10-second window, and a clear "no match — try again" retry
  button (state already exists: `ExchangeSession.State.NO_MATCH`).
- Optional richer fingerprint (your "2^5 + angle + etc" idea): add finger-splay, thumb
  direction, or a second angle axis to grow the code space beyond 128 if collisions show up.

### 3. The file-sharing room (your feature #3, deferred this session)
Concept: a **hub** where the admin's phone is the center; everyone shares only **file
names** (a manifest), and actual bytes transfer on demand via a **star** connection.

Recommended design on top of the current transport:
- Reuse `NearbyConnectionsTransport` with `Strategy.P2P_STAR` (host advertises, guests
  discover) instead of `P2P_CLUSTER`.
- New `RoomHubService` (mirror `NearbyExchangeService`):
  - **Host** = hub: accepts all guests, keeps a roster, broadcasts a merged manifest
    (`List<FileEntry{ id, name, size, ownerEndpoint }>`) to everyone.
  - **Guests** = spokes: on join, send the host their own file-name manifest (names only,
    no bytes). Host merges + rebroadcasts.
  - **On-demand pull:** guest taps a file → request routed host→owner → owner streams bytes
    (Nearby `Payload.Type.FILE`/`STREAM`) back through the host, or host brokers a direct
    guest↔owner leg. Bytes never move until requested — matches your "share names, fetch on
    demand" intent.
- Room entry gate: reuse the **gesture code** as the room password so only people doing the
  agreed gesture can join the hub (nice consistency with the 1:1 flow).
- UI: `RoomFragment` with Host/Join toggle, live roster count, a manifest list (name + owner
  + size), and a download action per row with progress.
- Data: a `FileEntry` model; no DB needed unless you want a received-files history.

### 4. Reliability & edge cases
- Foreground-service lifecycle: cancel cleanly on app background; the window job + shutdown
  path exist but need real-device soak testing.
- Permission denial UX (currently silent) — add an in-screen prompt on the Exchange screen
  if camera/nearby are denied.
- Handle two peers locking *different* codes gracefully (already ignored, but surface a hint).

### 5. Release polish (later)
- Re-enable R8 + resource shrink and ABI splits for a much smaller APK (debug is 62 MB;
  MediaPipe native libs dominate). The old build had this — reintroduce a minimal version.
- App icon / branding pass for "Ping" (currently reuses the old launcher icon).
- Optional: FOSS transport path (Wi-Fi Direct) if you want a no-Google-Services build — the
  old `WifiDirectTransport` was deleted but can be reinstated behind the `NearbyTransport`
  interface, which is unchanged.

---

## Risks / known limitations
- **Not tested on hardware** — the whole pairing path is unverified on real BLE.
- **Code space is 128** — fine for playful use; two nearby pairs doing the same gesture at
  the same moment could cross-connect. Mitigate with the live-code confirmation (step 2) and
  a bigger fingerprint (step 2) if it ever bites.
- **No MITM protection** (SAS was intentionally removed for simplicity). For a playful
  proximity app that's an acceptable trade; revisit if the room hub carries sensitive files.
- **Config cache disabled** in `gradle.properties` (the model-download task isn't
  cache-compatible). Harmless; can be re-enabled by making that task cache-safe.

---

## Handy commands
```bash
export JAVA_HOME=/opt/android-studio/jbr ANDROID_HOME=$HOME/Android/Sdk
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s Ping:* NearbyExchangeService:* GestureCamera:*
```
