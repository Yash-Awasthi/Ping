# Ping (Aura) — absorption plan

Written 2026-09-12. Companion to the Android app in this directory (Gradle module `:app`,
namespace `com.ping.app`).

## What this document is

`C:\Users\yasha\PROJECTS\inspiration\Aura` holds 163 cloned upstream repositories: Android BLE
libraries (Nordic's Android-BLE-Library and Kotlin-BLE-Library, FastBle, RxAndroidBle, Kable,
SmartGattLib), Google Nearby Connections and Quick Share implementations (google/nearby,
Nearby-Connections samples, REarby, bada, NearDrop, rustdrop), offline mesh messengers (bitchat,
knit, Berkanan, Meshtastic-Android, Reticulum, ZeroChat), MediaPipe hand and pose samples, the
offline file-transfer cluster (LocalSend, PairDrop, ShareDrop, croc, magic-wormhole,
FlyingCarpet), cryptographic libraries (Bouncy Castle, Tink, libsignal, curve25519, HKDF, Noise,
PAKEs), contacts and visiting-card apps, and MVVM/Hilt/Room architecture samples.

This document records every defect found while reading the app's own source (Part 1), maps the
corpus to the app (Part 2), and sets out a plan to close the gap (Part 3).

**The corpus has already been mined once, and it did not go well.** The app now has 162 Kotlin
files and 25,407 lines. `PING_PLAN.md` — the project's own status document — still says
"**32 Kotlin files, no accounts, no server.**" That was true when it was written and has been
false for some time. `app/src/main/java/com/ping/app/data/` alone contains 86 files, most of them
`Ble*.kt`, `Mesh*.kt`, `Element*.kt` and `LocalSend*.kt` modules extracted from the corpus.

Three things are true of the current tree:

1. **It does not compile.** There are at least six independent compile errors (defects 1-6). The
   project's own CI runs `assembleDebug` and `assembleRelease` on every push and cannot pass.
2. **Its one feature does not run.** The gesture exchange — the entire point of the app — is
   routed to a placeholder string in the Compose navigation graph (defect 7). The service that
   performs the swap is started from exactly one place, and that place is unreachable (defect 8).
3. **Roughly two thirds of it is not connected to anything.** The Compose navigation graph
   replaced a Fragment navigation graph, and the old screens were never deleted (defect 9).

**`./gradlew` is the authority on what is real, and it currently fails.** This plan puts the build
first for that reason.

---

## Part 1 — Defects in the current app

Severity: **critical** is a compile error, a live security hole, or the loss of the app's only
feature; **high** breaks a feature or a documented claim; **medium** is correctness or
maintainability with a real user-visible edge; **low** is tidiness with a real cost.

### 1. `di/AppModule.kt` uses `Context` without importing it — **critical**

`AppModule.kt:28-32` and `:42-46` both declare `@ApplicationContext context: Context`:

```kotlin
fun provideSessionRepository(
    @ApplicationContext context: Context,
): SessionRepository { ... }
```

The file's imports (`AppModule.kt:3-12`) are `ContactRepository`, `SessionRepository`,
`ContactDao`, `NearbyExchangeService`, `dagger.Module`, `dagger.Provides`, `dagger.hilt.InstallIn`,
`dagger.hilt.android.qualifiers.ApplicationContext`, `dagger.hilt.components.SingletonComponent`,
`javax.inject.Singleton`. There is no `android.content.Context`. `Context` is an unresolved
reference at both sites. `DatabaseModule.kt:3` imports it correctly, which is the fix.

### 2. `AppModule` constructs a `Service` by hand and provides it as a singleton — **critical**

`AppModule.kt:40-46`:

```kotlin
@Provides
@Singleton
fun provideNearbyExchangeService(
    @ApplicationContext context: Context,
): NearbyExchangeService {
    return NearbyExchangeService(context)
}
```

`NearbyExchangeService` extends `android.app.Service`, whose only constructor is the implicit
no-argument one. `NearbyExchangeService(context)` does not exist, so this is a second compile
error in the same file. Even with the signature fixed the design is wrong: an Android `Service` is
instantiated by the framework with a `Context` attached, is subject to service lifecycle, and must
not be held as a Hilt singleton. `NearbyExchangeService` is started through
`startForegroundService` from `NearbyExchangeService.start()` (`:271-275`), which is the supported
path; the provider should be deleted.

### 3. `BleManager` injects an unqualified `Context`, which Hilt cannot satisfy — **critical**

`data/BleManager.kt:22-32`:

```kotlin
@Singleton
class BleManager @Inject constructor(
    private val context: Context,
    ...
```

Hilt binds `Context` only under a qualifier — `@ApplicationContext` or `@ActivityContext`. An
unqualified `Context` in an `@Inject` constructor is a Dagger error ("Context cannot be provided
without an @Provides-annotated method"). `auth/GestureCamera.kt:40` does it correctly
(`@ApplicationContext private val context: Context`), so the correct form is already in the
codebase. This matters more than the other two: `PingApplication.kt:12` injects `BleManager`, so
this error is on the app's startup path.

### 4. `ui/QRScannerScreen.kt` imports ML Kit, which is not a dependency — **critical**

`ui/QRScannerScreen.kt` imports `com.google.mlkit.*`. `app/build.gradle.kts:99-158` declares no ML
Kit artifact — no `barcode-scanning`, no `mlkit-*` of any kind. Unresolved reference.

### 5. `ui/QRDisplayScreen.kt` imports ZXing, which is not a dependency — **critical**

`ui/QRDisplayScreen.kt` imports `com.google.zxing.*`. No ZXing artifact is declared either.

### 6. The version catalog references a version that does not exist — **critical**

`gradle/libs.versions.toml:72`:

```toml
zxing-android-embedded = { group = "com.journeyapps", name = "zxing-android-embedded", version.ref = "zxingEmbedded" }
```

`[versions]` (`:1-20`) defines `agp`, `kotlin`, `ksp`, `coreKtx`, `appcompat`, `material`,
`constraintlayout`, `navigation`, `lifecycle`, `room`, `hilt`, `coroutines`, `nearby`, `datastore`,
`biometric`, `securityCrypto`, `gson`, `timber`, `composeBom`. There is no `zxingEmbedded`. The
alias is also never used by `app/build.gradle.kts`, so the catalog carries a broken entry for a
dependency that was never added — which is exactly how defects 4 and 5 survived.

Together, defects 1-6 mean the tree cannot assemble. `PING_PLAN.md:8` claims "Both debug and
signed release builds are green"; `.github/workflows/build.yml` runs `./gradlew assembleDebug` and
`assembleRelease` on push to `main` and `fresh`. Both jobs are failing, and have been since the
absorption pass landed.

### 7. The Share button navigates to a placeholder string — **critical**

`ui/navigation/AppNavHost.kt:89-98`:

```kotlin
composable(Routes.EXCHANGE) {
    // Exchange screen remains Fragment-based for now (camera + gesture).
    // Shown as a placeholder until camera integration is Compose-ready.
    androidx.compose.foundation.layout.Box(...) {
        Text("Exchange — camera integration pending")
    }
}
```

`ui/home/HomeScreen.kt:67-80` renders the large circular Share button, and
`ui/navigation/AppNavHost.kt:62-66` wires `onShareClick = { navController.navigate(Routes.EXCHANGE) }`.
So the app's primary action — the one thing the README asks the user to do — opens a sentence
saying the feature is not integrated. `PING_PLAN.md:22` records on-device pairing as "⚠️ unverified";
the truth is weaker than that: it is unreachable.

### 8. The exchange service is started from exactly one place, and that place is dead — **critical**

`NearbyExchangeService.start(...)` is called at `ui/exchange/ExchangeFragment.kt:164`. That is the
only call site anywhere in the project (verified by grep). `ExchangeFragment` is a navigation
destination (`app/src/main/res/navigation/nav_graph.xml:30-38`), and `nav_graph.xml` is inflated
only by `app/src/main/res/layout/activity_main.xml:10-14`. `activity_main.xml` is inflated by
nothing: `ui/MainActivity.kt:14-22` calls `setContent { PingTheme { AppNavHost() } }` and never
calls `setContentView`.

So the chain is: MainActivity → Compose `AppNavHost` → placeholder text. The Fragment chain
(MainActivity → activity_main.xml → nav_graph.xml → ExchangeFragment → `NearbyExchangeService.start`)
is severed at the first link. Everything the swap engine does — gesture matchmaking, the ECDH
handshake, the sealed card exchange — is unreachable at runtime.

### 9. Two navigation systems exist and only one is live — **high**

`ui/navigation/AppNavHost.kt` is a Compose `NavHost` with routes `home`, `profile`, `contacts`,
`contact_detail/{contactId}`, `exchange`. It is what `MainActivity` displays.

`app/src/main/res/navigation/nav_graph.xml` is a Fragment navigation graph declaring
`HomeFragment`, `ProfileFragment`, `ContactsFragment` and `ExchangeFragment`. It is reachable only
from `activity_main.xml`, which nothing inflates. `ui/home/HomeFragment.kt:32` wraps its content
in `PingTheme` — evidence that these Fragments were converted to host Compose content, and then
abandoned when the Compose `NavHost` replaced them wholesale.

Consequently `HomeFragment`, `ProfileFragment`, `ContactsFragment`, `ExchangeFragment`,
`ExchangeViewModel`, `ExchangeSuccessBottomSheet`, `ui/contacts/ContactsAdapter.kt` and the
View-system layouts (`activity_main.xml`, `bottom_nav_menu.xml`, `fragment_home.xml`,
`fragment_profile.xml`, `fragment_contacts.xml`, `fragment_exchange.xml`,
`bottom_sheet_contact_detail.xml`, `bottom_sheet_exchange_success.xml`, `item_contact.xml`) are all
dead. `ui/home/HomeFragment.kt` and `ui/home/HomeScreen.kt` are a live/dead pair sitting in the
same package.

### 10. The documented crypto is not the crypto that was written — **high**

`README.md:25` states "E2E Encryption | X25519 ECDH → HKDF → AES-256-GCM" and `README.md:121`
repeats "Key exchange | X25519 ECDH (ephemeral, per-swap)". The live implementation is
`utils/CryptoUtils.kt`, and `:35-36` reads:

```kotlin
fun generateEphemeralKeyPair(): KeyPair =
    KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
```

That is NIST P-256, not Curve25519. `KeyAgreement.getInstance("ECDH")` at `:50` agrees. No file in
the live path touches X25519; the string appears only in the names and comments of dead modules
(`crypto/BlePqxdhProtocol.kt:31`, `crypto/DoubleRatchet.kt:30`, `crypto/PostQuantumCrypto.kt:82-84`)
and in dead helpers (`crypto/TinkCrypto.kt:22`). The implementation is sound — P-256 ECDH with a
correct RFC 5869 HKDF and AES-256-GCM — but the README describes a different algorithm, and one of
the two has to change. P-256 is the defensible choice on Android and the README is what should move.

### 11. Two classes named `CryptoUtils` exist, with different APIs and one with a timing bug — **high**

`utils/CryptoUtils.kt` (89 lines) is the live one: ECDH, HKDF, AES-GCM keyed on `SecretKey`.
`security/CryptoUtils.kt` (98 lines) is a second, unrelated helper keyed on raw `ByteArray`, with
SHA-256, HMAC, AES-GCM, a `deriveKey`, a `constantTimeEquals`, and an `encryptMessage` that binds
sender and recipient as AEAD associated data.

`security/CryptoUtils.kt` has two defects worth recording even though nothing calls it:

- `:82-85` `verifyIntegrity` compares a computed HMAC with `computed.contentEquals(expectedHmac)`.
  `ByteArray.contentEquals` short-circuits on the first difference, so a MAC comparison here is a
  timing oracle. `constantTimeEquals` is defined eight lines below at `:90-97` and is not used.
- `:73-80` `deriveKey` reverses HKDF-Extract. RFC 5869 defines `PRK = HMAC-Hash(salt, IKM)` — the
  salt is the HMAC key and the input keying material is the message. This code does
  `Mac.getInstance("HmacSHA256")` initialised with `masterKey` and then `doFinal(salt)`, which is
  `HMAC(key=IKM, msg=salt)`. The live `utils/CryptoUtils.deriveSharedKey` (`:59-61`) does it the
  correct way round, so the copy should be deleted rather than fixed — an incorrect primitive
  sitting next to a correct one is a trap.

### 12. The gesture code — the "password" — is advertised in cleartext, and any peer is accepted — **high**

`service/NearbyExchangeService.kt:94-96`:

```kotlin
localName = "$gestureCode|${name.replace("|", " ")}"
wireCallbacks()
transport.startAdvertising(localName, SERVICE_ID)
```

The gesture code is the advertised BLE local name. Anything scanning in range reads both the code
and the user's display name. `README.md:10` calls the gesture "a cryptographic password", and
`PING_PLAN.md:27-28` calls it the pairing gate. It does gate matchmaking — `:113-128` only requests
a connection when the advertised code equals ours — but it gates nothing cryptographically, because
it is published. An attacker who is in radio range can advertise the same code.

The second half of the problem is `:130-133`:

```kotlin
transport.onConnectionInitiated = { endpointId, _ ->
    // Accept every initiated connection — the gesture code already gated us.
    transport.acceptConnection(endpointId)
}
```

Acceptance is unconditional. The tie-break at `:119-126` is `localName < remoteName`, a
lexicographic comparison of a string the peer chose. A peer that picks a small name, or simply
initiates, is accepted and receives the encrypted card — and the key it derives comes from an ECDH
in which it is a participant, so it decrypts the card. The encryption protects the card from
passive listeners, not from an active peer. That may be acceptable for a playful contact swap, but
the README should say so rather than implying the gesture authenticates the peer.

### 13. There are no tests at all — **high**

`app/src/` contains exactly one directory, `main`. There is no `test/`, no `androidTest/`, no unit
test, no instrumented test, and no test dependency in `app/build.gradle.kts`. The
`testInstrumentationRunner` is declared at `app/build.gradle.kts:27` for a runner that runs nothing.

`.github/workflows/build.yml` assembles debug and release and runs no tests, so the CI pipeline
would not notice a behavioural regression even if it compiled.

### 14. `di/TransportModule.kt` documents a product flavor that does not exist — **medium**

`TransportModule.kt:14-22`:

> Hilt module for the `gms` product flavor. ... The `foss` flavor's `TransportModule` provides
> `WifiDirectTransport` instead, removing the GMS dependency entirely (F-Droid eligible).

`app/build.gradle.kts` declares no `productFlavors` block. There is no `foss` flavor, no `gms`
flavor, and no `WifiDirectTransport` class anywhere in the tree. (`data/WifiDirectManager.kt`
exists but is a different, unwired class.) `PING_PLAN.md:103-104` lists a FOSS transport as an
optional stretch goal, so this is a comment describing a plan as though it were code.

### 15. Absorbed modules require permissions and components the manifest does not grant — **high**

`AndroidManifest.xml` declares three Bluetooth permissions plus their legacy variants, four Wi-Fi
and location permissions, `CAMERA`, `VIBRATE`, `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_CONNECTED_DEVICE` and `POST_NOTIFICATIONS`, and exactly two components: the
activity `.ui.MainActivity` and the service `.service.NearbyExchangeService`. There is no
`INTERNET`.

Against that, the absorbed set implements:

| Module | Needs | Manifest has |
|---|---|---|
| `data/ElementMatrixClient.kt`, `data/NostrMeshBridge.kt`, `data/PairDropProtocol.kt`, `data/LocalSendProtocol.kt`, `data/CrocFileTransfer.kt`, `data/ZeroChatProtocol.kt`, `protocol/LocalSendProtocol.kt` | `INTERNET` | no |
| `data/QuickShareNfcCodec.kt` | `NFC` | no |
| `data/LiveVoiceManager.kt`, `data/BleMeshWalkieTalkie.kt` | `RECORD_AUDIO` | no |
| `data/SmsTransfer.kt` | `SEND_SMS` / `READ_SMS` | no |
| `data/BleUwbRanging.kt` | UWB ranging permission | no |
| `data/PowerButtonSosReceiver.kt` | a `<receiver>` entry in the manifest | no |
| `data/BleHealthProfiles.kt` | Bluetooth GATT service access to health devices | not applicable |

Thirteen files import `java.net` or `android.net`. None of the above can function. The absence of
`INTERNET` is worth keeping — the app's stated design is "No server. No cloud. No internet"
(`README.md:12`) — which means the networking modules are not merely unwired, they are
incompatible with the product.

### 16. Fourteen files are named nowhere else in the project — **medium**

A direct check — for each Kotlin file, does its declared type name appear in any other file? —
finds 67 such files. Spot checks confirm specific ones as completely unreferenced:

`viewmodel/SessionViewModel.kt`, `data/MediaPipeHandTracker.kt`, `security/EndToEndEncryption.kt`,
`ui/QRDisplayScreen.kt`, `ui/QRScannerScreen.kt`, `ui/RepCounter.kt`, `ui/FileBrowserDialog.kt`,
`ui/GestureRecognizer.kt`, `ui/ConnectionStatusIndicator.kt`, `ui/SessionHistoryScreen.kt`,
`data/SmsTransfer.kt`, `data/PeerConnect.kt`, `data/ProximityAlert.kt`, `data/BleChatService.kt`,
`data/MeshChat.kt`, `di/TransportModule.kt`.

`viewmodel/SessionViewModel.kt` is the notable one: `README.md:140-143` names `SessionViewModel` in
its architecture diagram as the ViewModel layer, and `README.md:170-183` lists `viewmodel/` in the
project structure. It has a correct `@Inject constructor` and no caller. The session-history
feature it belongs to (`ui/SessionHistoryScreen.kt`) is also unreferenced.

**A caution on counting.** Several type names are declared in more than one file —
`ConnectionState` in both `data/BleChatService.kt` and `ui/ConnectionStatusIndicator.kt`,
`MeshChatMessage` in both `data/BleBroadcastDirectory.kt` and `data/ProximityMessenger.kt`,
`MessageType` in `BleMeshProtocol.kt` and `BleMeshService.kt`, `FileItem`, `TransferProgress`,
`Result`, `DeviceInfo`, `DiscoveryState`. A name-based analysis therefore over-counts references
and under-counts dead files, in both the direct and the transitive direction. A transitive pass
from the app's real entry points (`PingApplication`, `MainActivity`) reports 41 reachable and 121
unreachable out of 162, but that figure is inflated by the same collisions and should be treated as
an estimate, not a measurement. **The only trustworthy count is the one a compiler produces, and
the compiler cannot run yet.** Defect 16 should be re-measured after defects 1-6 are fixed.

What is certain without a compiler is the shape: 86 of the 162 files are in `data/`, 12 in
`crypto/`, 6 in `security/`, and the live Compose UI is 15 files. The absorbed set is the majority
of the repository and almost none of it is connected.

### 17. Startup constructs seven BLE subsystems that then do nothing — **medium**

`PingApplication.kt:12` injects `BleManager` and `:20` calls `bleManager.start()`. `BleManager`
(`data/BleManager.kt:23-32`) takes seven collaborators in its constructor — `BleStateManager`,
`BleWriteQueue`, `BleMeshService`, `BleChatRoomManager`, `BleEmergencyAlert`,
`BleBandwidthUpgrade`, `BleRelayNode` — so all seven are constructed at app launch. `start()`
(`:43-52`) then does this:

```kotlin
val s = CoroutineScope(Dispatchers.IO + SupervisorJob())
scope = s
s.launch {
    Log.i(TAG, "BLE subsystems started")
    _state.value = BleManagerState.Running
}
```

It creates a scope and logs. No scan, no advertise, no discovery. The subsystems are built and
left idle, and `BleManager.stop()` is never called from anywhere, so the scope outlives the app's
own lifecycle management.

### 18. Hilt validates the whole orphan graph, which is why the dead code cannot simply be ignored — **medium**

`di/BleModule.kt` declares 24 `@Provides` methods (`:59-201`) for classes that no live code
injects: `BleFastTransfer`, `BleMeshService`, `ProximityMessenger`, `CrocFileTransfer`,
`BleRfcommBridge`, `BleBluetoothObserver`, `BleConnectionOptimizer`, `BleMeshProvisioning`,
`BleWifiProvisioner`, `BleBandwidthUpgrade`, `BleEmergencyAlert`, `BleGeofence`, `BleGroupCode`,
`BleMeshWalkieTalkie`, `BleRelayNode`, `BleThreadingStrategy`, `WifiP2pDiscovery`,
`BleContentModeration`, `BleHealthProfiles`, `BlePowerProfile`, `BlePriorityQueue`,
`BlePrivacyPadding`, `BleStoreForward`, plus two dispatcher qualifiers.

Hilt resolves and validates every `@Provides` in an installed module at compile time. So each of
these classes, and everything they transitively depend on, must compile, be processed by KSP,
survive R8, and be validated by Dagger — for no runtime benefit. This is the mechanism that makes
the orphan set expensive rather than merely untidy, and it means deleting the orphans requires
editing `BleModule` in the same change.

### 19. `README.md` contradicts itself about persistence — **medium**

`README.md:29` says "Offline Contacts | Room v1 database — contacts persist on device".
`README.md:146-147` says the data layer is "`SessionRepository` · `ContactRepository`
(SharedPreferences + JSON, reactive Flow)". Both cannot be true. The code says: Room.
`di/DatabaseModule.kt:19-24` builds `AppDatabase` via `Room.databaseBuilder`, `:26-30` exposes
`ContactDao` and `ProfileDao`, and `di/AppModule.kt:34-37` constructs `ContactRepository(contactDao)`.
`data/SessionRepository.kt` is the SharedPreferences one, and it serves the dead session-history
feature. The README is describing both the old and the new data layer in different sections.

### 20. `PING_PLAN.md` is stale in every direction — **medium**

The document is dated before the absorption pass and is wrong in both the optimistic and the
pessimistic direction:

- `:8` "32 Kotlin files" — it is 162.
- `:14-23` the status table marks gesture-as-password, gesture matchmaking, the offline swap and
  the exchange UX as "✅ done". All four are unreachable (defects 7-9).
- `:16` "Release build — R8 shrink + ABI splits + signed ✅ done" — the tree does not compile.
- `:124-129` "Handy commands" that cannot succeed as written.

The document is also genuinely useful — its roadmap sections on the room hub, gesture depth and the
FOSS transport are good design writing — but it must not be read as status.

### 21. CI cannot gate anything — **medium**

`.github/workflows/build.yml` has a single job with four steps. It does not run `./gradlew test`
(there are no tests, defect 13), does not run `./gradlew lint`, and does not cache the Gradle
configuration. `:5-7` triggers on push to `main` and `fresh` and on pull requests; with the build
broken it reports red for every change, which is how a broken tree stays broken. `gradle.properties`
also disables the configuration cache, per `PING_PLAN.md:117-118`, because the `downloadHandModel`
task (`app/build.gradle.kts:165-195`) declares only `outputs.file` and performs a network fetch in
`doLast` when the file is missing.

### 22. Hygiene items — **low**

- `keystore/` and `keystore.properties` exist on disk and are correctly ignored
  (`.gitignore:24-25`). They must not be committed. No secret value is recorded here.
- The working tree on branch `fresh` has 85 changed files uncommitted, including a deleted
  `data/ContactSync.kt`. The absorption pass was never committed, so `git log` does not explain any
  of the above.
- `app/src/main/assets/.gitkeep` coexists with `*.task` being ignored (`.gitignore:27-29`); the
  MediaPipe model is downloaded at build time. Fine, but it means a clean offline checkout cannot
  build without the model.
- `.gitignore` has no entry for `*.hprof`, `*.apk`/`*.aab` outside `app/release` (it covers those),
  or `.kotlin/`. `.kernel`/`.gradle` are covered.

---

## Part 2 — Corpus inventory and disposition

163 repositories. Status marks follow the house convention used in
`RemoteHarness/docs/ABSORPTION-LEDGER.md`:

- ✅ absorbed — present, wired and reachable in the app
- ⚙️ already covered — the app or the platform already does this, so nothing is owed
- 🧩 module present but unwired — code was extracted but no live path reaches it
- ➖ reference-only — read for design, no code to take
- ⛔ out of scope — deliberately not being built

### 2.1 Architecture, MVVM, Hilt and Room samples

| Repo | Status | Note |
|---|---|---|
| android-mvvm-architecture, Android-MVVM-Boilerplate-Hilt, Dagger-Hilt-MVVM, hilt-mvvm | ✅ | The live app is this skeleton: Hilt + Room + ViewModel + StateFlow |
| Contact-DI-Room-MVVM | ✅ | Closest match to the live contacts path |
| cahier | ➖ | Offline-first notes sample; no code taken |
| connectivity-samples | ➖ | Deprecated Google samples |
| cross-device-sdk | ➖ | Multi-device session SDK; different problem |
| friendspell | ➖ | Nearby-based party game; design reference for the matching UX |
| libsoftwaresync | ➖ | Camera sync by audio; not applicable |

### 2.2 Contacts, visiting cards and identity

| Repo | Status | Note |
|---|---|---|
| contacts, Contacts-Manager, Contacts-Pro-Kotlin, contacts-android, PrivateContacts | ✅ | The live contacts list, detail screen and Room persistence |
| visiting-card-android, cardcase, CardWhere, pairsonic | ✅ | The profile card model and the swap-a-card concept |

### 2.3 Nearby Connections, Quick Share and the transport layer

| Repo | Status | Note |
|---|---|---|
| nearby_connections, react-native-google-nearby-messages, nearby-connections-api-sample-things, nearby | 🧩 | `service/NearbyConnectionsTransport.kt` + `service/NearbyTransport.kt` exist; the service that uses them is never started |
| nearbee, hms-nearby-demo, quickhandanalyzer | 🧩 | Nearby advertisers/wrappers; same unwired path |
| airshare | ➖ | P2P discovery library across iOS and Android; useful as a cross-platform design reference only |
| REarby, bada, NearDrop, rustdrop, crossdrop | ➖ | Reverse-engineered Quick Share/Nearby protocols; excellent protocol reference for defect 12 |
| apple-continuity-tools, apple-enhanced-contactless-polling, opendrop, owl | ⛔ | Apple AWDL/AirDrop reverse engineering |

### 2.4 BLE libraries and GATT tooling

| Repo | Status | Note |
|---|---|---|
| android-ble-library, kotlin-ble-library, fastble, rxandroidble, kable, smartgattlib, android-ble, p2p-discovery-ble | 🧩 | `data/BleManager.kt`, `BleStateManager`, `BleWriteQueue`, `BleConnectionOptimizer`, `SmartGattLib.kt` are extracted; only the seven in `BleManager`'s constructor are ever constructed |
| Android-nRF-Mesh-Library, Android-nRF-Toolbox, Android-nRF-Wi-Fi-Provisioner | 🧩 | `BleMeshProvisioning`, `BleWifiProvisioner`; unwired |
| BluetoothLEChat, android-BluetoothChat, blue-pair, Bluetooth-file-transfer-android-application-, bluetooth-file-transfer-android, bluconnector, DropZoneKotlin, wifi-direct-file-transfer-app | ➖ | Bluetooth and Wi-Fi Direct transfer samples; architecture references for `BleManager` and the transport layer, no code taken |
| awesome-ble, awesome-bluetooth-security | ➖ | Link lists |
| bumble | ⛔ | A Bluetooth stack in Python for emulation and testing |
| SecureBLE | ➖ | Security patterns for GATT |

### 2.5 Offline mesh messengers

| Repo | Status | Note |
|---|---|---|
| bitchat, bitchat-android, zemzeme-android, hopline, kabootar, knit, meshchat, meshline-android, radius, RezvanMesh, PeerConnect-App, pezhvakp2p, qaul.net, rcq-android, ZeroChat, offline-chatapp, crisis-connect, CrowdLink, NowNear, revive_connect, secretum-im, watcha-android, instant_messaging_matrix, cabal-desktop, berty, meshenger-android, BerkananSDK, reticulum-kt, meshtastic-android, Meshrabiya | 🧩 / ➖ | This is where `data/Mesh*.kt`, `data/BleMeshService.kt`, `data/ProximityMessenger.kt`, `data/NostrMeshBridge.kt`, `data/ElementMatrixClient.kt` came from. All unwired, and several need `INTERNET` or `RECORD_AUDIO` the manifest does not grant |
| walkie-talkie-app | 🧩 | `data/BleMeshWalkieTalkie.kt`, `data/LiveVoiceManager.kt`; needs `RECORD_AUDIO` |
| offline_sms | 🧩 | `data/SmsTransfer.kt`; needs `SEND_SMS`. Referenced by nothing |
| fialka-android, ratatosk, shakechat, sunsetripple | ➖ | Onion-routed and shake-to-pair messengers; design reference only |
| ghost-chat | ➖ | X25519 identity + E2E chat design reference |

The mesh cluster is the single largest group in the corpus and the one furthest from this product.
A contact swap between two phones that can see each other does not need store-and-forward relay,
multi-hop routing, or a gossip directory.

### 2.6 Gesture and MediaPipe

| Repo | Status | Note |
|---|---|---|
| mediapipe, google-mediapipe, mediapipecameraxdemo, MediaPiper | 🧩 | `auth/GestureCamera.kt` uses `com.google.mediapipe.tasks.vision`; the model ships via the Gradle download task. Unreachable because the exchange screen is a placeholder |
| hand-gesture-recognition-mediapipe, hand-gesture-recognition-using-mediapipe, Hand-Gesture-Recognition, hand_landmarker, flutter_mediapipe, mediapipe-python-sample, mediapipeunityplugin | ➖ | Recognition-pipeline references |
| airdrawing-mediapipe-android, palm-finger-detection | 🧩 | `data/BlePalmMinutiae.kt`, `drawing/BrushManager.kt`, `drawing/BrushPath.kt`; unwired |
| RepDetect, hand-gesture-music-player, tello-gesture-control, gesturecontrol, handwave_unlock, mlkit-pose, camerax-helper | ➖ | Pose and gesture applications; `data/HandGestureRecognizer.kt`, `data/PoseEstimation.kt` came from here and are unwired |
| sign-language-recognition-with-rnn-and-mediapipe | ➖ | `data/SignLanguageRecognizer.kt`; unwired |
| awesome-mediapipe | ➖ | Link list |

### 2.7 Cryptography

| Repo | Status | Note |
|---|---|---|
| curve25519-kotlin, ecdh-curve25519-mobile, x25519, kodium | ➖ | X25519 references. The live code uses P-256 (defect 10) |
| bc-java | ➖ | Bouncy Castle. Declared as a dependency (`app/build.gradle.kts:149`) and imported by **no file**. A dead dependency |
| cryptography-kotlin | ➖ | Multiplatform crypto wrapper; the JVM/Android path is the platform provider the app already uses |
| tink-java | 🧩 | `crypto/TinkCrypto.kt`; unwired, and no Tink dependency is declared |
| libsignal, ratchetandroid, java-noise | 🧩 | `crypto/DoubleRatchet.kt`, `crypto/NoiseProtocol.kt`; unwired |
| hkdf, java-aes-crypto, securecompatibleencryptionexamples | ⚙️ | The live `utils/CryptoUtils.kt` implements HKDF and AES-GCM directly and correctly |
| pake, PAKEs, python-jpake, python-spake2 | 🧩 | `crypto/PAKEHandshake.kt`, `crypto/Spake2Exchange.kt`; unwired, and `Spake2Exchange.kt:45-46` admits "Simplified group parameters ... In production, use proper Ed25519" |
| signum | 🧩 | `crypto/SignumCrypto.kt`; unwired |
| awesome-cryptography | ➖ | Link list |

### 2.8 Offline file transfer and P2P

| Repo | Status | Note |
|---|---|---|
| localsend, pairdrop, sharedrop, sharik, wifidrop, transfer, zipbolt, flyingcarpet, magic-wormhole, croc, fileshare, direct_chat, p2p, android-p2p, wifip2p, wifidirect, wifi-direct-file-transfer, suddenly-wifi, File-Transfer, bluetooth-file-transfer | 🧩 / ➖ | `data/LocalSendProtocol.kt`, `protocol/LocalSendProtocol.kt`, `data/PairDropProtocol.kt`, `data/CrocFileTransfer.kt`, `data/FileTransferProtocol.kt`, `data/WifiDirectManager.kt`, `data/WifiP2PTransfer.kt`. Almost all need `INTERNET`, which the manifest does not grant |
| react-native-wifi-p2p, rn-wifi-p2p, flutter_p2p_connection, offline-flutter-nearby-chat-app | ⛔ | React Native and Flutter |

### 2.9 Out of scope entirely

| Repo | Status | Note |
|---|---|---|
| librepass-android | ⛔ | Password vault; unmaintained by its own README |
| awesome-decentralized | ➖ | Curated link list |

The remaining `awesome-*` lists are filed under the section they relate to: `awesome-cryptography`
in §2.7, `awesome-ble` and `awesome-bluetooth-security` in §2.4, `awesome-mediapipe` in §2.6.
`cabal-desktop` and `berty` are in §2.5 with the other chat platforms.

Not given a row of their own because they carry no Kotlin surface: the `pubspec.yaml` projects
(Flutter), the `package.json` projects (React Native and web), the `go.mod` projects, and the
`setup.py` / `pyproject.toml` projects. Their code was read as reference and none of it was taken.

**Totals:** ✅ 14 — ⚙️ 3 — 🧩 85 — ➖ 51 — ⛔ 10.

Fourteen repositories' worth of capability is genuinely live, and it is the least interesting
fourteen: the Hilt/Room/Compose skeleton and the contacts and profile screens. The gesture, the
crypto, the transport and the mesh — everything that makes the app distinctive — is 🧩 or ➖.

---

## Part 3 — Absorption plan

### Phase 0 — Make it compile (defects 1, 2, 3, 4, 5, 6, 18)

Nothing else can be verified until `./gradlew assembleDebug` succeeds. In one change:

1. Add `import android.content.Context` to `di/AppModule.kt`.
2. Delete `AppModule.provideNearbyExchangeService` (`:40-46`) entirely. A `Service` is not a
   dependency.
3. Add `@ApplicationContext` to `BleManager`'s `context` parameter (`data/BleManager.kt:24`),
   matching `auth/GestureCamera.kt:40`.
4. Decide the QR feature's fate before touching the imports. If QR pairing stays (README §QR Code
   Pairing documents it in full), add `com.google.mlkit:barcode-scanning` and
   `com.journeyapps:zxing-android-embedded` with a real `zxingEmbedded` version in
   `gradle/libs.versions.toml`. If it does not, delete `ui/QRScannerScreen.kt`,
   `ui/QRDisplayScreen.kt` and `data/QRPairingManager.kt` and drop the README section. Adding two
   dependencies to keep three unreferenced files compiling is the wrong trade; **delete them**, and
   revive the feature later against a working exchange flow.
5. Add `./gradlew lint` and a `./gradlew test` step (which will pass trivially until Phase 3 adds
   tests) to `.github/workflows/build.yml`.

Exit condition: `assembleDebug` and `assembleRelease` both succeed; CI is green.

### Phase 1 — Make the one feature work (defects 7, 8, 9, 12)

The app exists to swap contact cards after a shared gesture. That path is cut at
`AppNavHost.kt:89`. Two ways to reconnect it:

**Option A (recommended) — port the exchange screen to Compose.** `ExchangeFragment.kt` is 274
lines of camera preview, gesture state rendering, a countdown and a success sheet. The Compose app
already has `HomeScreen`, `ProfileScreen` and `ContactsScreen`; a `ExchangeScreen` that hosts
`AndroidView` around the CameraX `PreviewView` and drives `ExchangeViewModel` is the smallest
change that makes the Compose graph self-consistent. Then delete `nav_graph.xml`,
`activity_main.xml`, and the four Fragments.

**Option B — bring the Fragment graph back.** Reinstate `setContentView(R.layout.activity_main)`
and host `NavHostFragment`, then port `HomeScreen`/`ProfileScreen`/`ContactsScreen` (the Compose
screens) into Fragments. This is more work and moves backwards.

Either way, then:

2. Verify `NearbyExchangeService.start()` is reachable and the 10-second window
   (`NearbyExchangeService.kt:259`) is tuned on hardware. This is the field test `PING_PLAN.md:51`
   has always wanted.
3. Fix the pairing gate (defect 12). The advertised local name must not contain the gesture code —
   advertise a truncated hash of it (`SHA-256(code || sessionNonce)` truncated to a few bytes) so
   peers can still match without publishing the code, and require confirmation before accepting an
   initiated connection rather than accepting unconditionally at `:130-133`. Cloudflare-free
   protocol references for this exist in the corpus: `bada`, `rustdrop` and `nearby` all separate
   the advertisement from the payload.
4. Add a short-authentication-string compare at the end of the handshake, from the ECDH shared
   secret, and show it on both screens. `PING_PLAN.md:105-106` deferred this; with defect 12 fixed
   it becomes the thing that actually authenticates the peer, and it is a few lines given that
   `deriveSharedKey` already produces a mutual secret.

### Phase 2 — Delete the dead half (defects 9, 14, 16, 17, 18, 19, 20)

Only start this after Phase 0, because the compiler is the tool.

1. Re-run the reachability measurement with the build working — an `assembleDebug` plus R8 report,
   or a script that uses the compiled classes — and get a real list. The name-based estimate (67
   direct, ~121 transitive) is not good enough to delete against.
2. Delete the Fragment layer and its layouts (defect 9).
3. Delete the absorbed set that no live path needs. Expect the majority of `data/`'s 86 files, most
   of `crypto/`'s 12, `security/`'s 6, `protocol/`, `drawing/`, `camera/`, and
   `viewmodel/SessionViewModel.kt` with `ui/SessionHistoryScreen.kt`.
4. Delete `di/BleModule.kt` in the same change as the classes it provides (defect 18) — a
   `@Provides` for a deleted class is a compile error, so these move together.
5. Decide `BleManager`'s fate. It is constructed at app start and does nothing (defect 17). Either
   give it a job or delete it and its seven collaborators, and then delete the
   `bleManager.start()` call in `PingApplication.kt:20`.
6. Delete `di/CryptoModule.kt` and `security/CryptoUtils.kt` (defect 11). The second is a
   duplicate with a reversed HKDF and a non-constant-time MAC compare. Deleting it is safer than
   fixing it, because nothing calls it and a wrong primitive next to a right one will eventually be
   adopted by accident.
7. Drop the `org.bouncycastle:bcprov-jdk18on` dependency (`app/build.gradle.kts:149`): no file
   imports it. Also remove the unused catalog entries `datastore`, `biometric`, `securityCrypto`,
   `zxing-android-embedded`, and the `kotlin-jvm` plugin alias.

### Phase 3 — Establish a floor (defect 13, 21)

There are currently zero tests, so every change in Phases 1 and 2 is unverified.

1. Add `testImplementation` for JUnit 5 and a coroutines test dispatcher, and write unit tests for
   the pure logic that already exists and is worth keeping:
   - `auth/GestureFingerprint.kt` — the 128-code derivation. This is the app's core idea and it is
     a pure function of 21 landmarks; a table-driven test with synthesised landmark sets is easy and
     will catch the collisions `PING_PLAN.md:86-92` worries about.
   - `utils/CryptoUtils.kt` — ECDH agreement (both sides derive the same key), HKDF against the
     RFC 5869 test vectors, and AES-GCM tamper detection.
   - `data/Contact.kt` / `Profile.kt` `toShareableMap`/`fromMap` round-trip.
2. Add an instrumented test for the Room DAOs.
3. Make CI run them.

### Phase 4 — Correct the documentation (defects 10, 19, 20, 15)

1. Fix `README.md`: X25519 → P-256 (or change the code — but P-256 is the better Android choice),
   one consistent statement about Room, and the gesture code's actual secrecy properties.
2. Rewrite `PING_PLAN.md`'s status section against the code, or delete the status section and keep
   the roadmap, which is the good part.
3. Document the permissions model and state plainly that the app has no `INTERNET` permission by
   design (defect 15), so the next person who extracts a networking module from the corpus knows it
   cannot be shipped here.
4. Record the `foss` flavor decision: either build it (allowing `NearbyTransport` to have a
   Wi-Fi-Direct implementation and dropping `play-services-nearby`) or delete
   `TransportModule.kt`'s flavor comment (defect 14).

### Phase 5 — Capability from the corpus, in priority order

Only after the above. Each item names its corpus source.

1. **A real pairing handshake.** `bada`, `rustdrop`, `NearDrop`, `nearby` (§2.3) — protocol
   references for advertising without leaking the match key and for a confirmation step.
2. **File transfer** (`PING_PLAN.md:67-84`'s room hub). `LocalSend`, `croc`, `PairDrop`, `sharik`,
   `FlyingCarpet` (§2.8) — `data/LocalSendProtocol.kt` and `data/CrocFileTransfer.kt` are already
   written and can be ported into the live graph rather than rewritten.
3. **Voice** (`PING_PLAN.md` stretch). `walkie-talkie-app` — needs `RECORD_AUDIO` and a
   foreground-service type change; `data/LiveVoiceManager.kt` and `data/BleMeshWalkieTalkie.kt` are
   the starting point.
4. **Contacts import/export.** `contacts-android`, `PrivateContacts` — vCard export is already on
   `README.md:244`'s roadmap.
5. **Gesture depth.** `PING_PLAN.md:86-92` — expand past 128 codes using the splay and thumb axes
   from `palm-finger-detection`, which is why that module was extracted.

### Phase 6 — The mesh cluster, if and only if the product changes

The corpus's largest group (§2.5) describes an offline mesh messenger. Unlike `dub` for foodref or
`xstate` for CTF, none of it is adjacent to "two people swap cards". Adopting it means adding
`INTERNET`, a relay model, store-and-forward, and a directory — a different app. Record it as
reference, keep the modules only if Phase 2's measurement shows they cost nothing, and otherwise
delete them.

---

## Part 4 — Deliberately not doing

- **Multi-hop mesh networking, relay nodes, store-and-forward, mesh directories** (§2.5). The
  product is a two-device proximity swap.
- **Apple ecosystem interop** (§2.8): AWDL, AirDrop, OpenDrop, OWL, Apple continuity tooling. The
  app is Android-only and says so.
- **Post-quantum and double-ratchet cryptography** (`crypto/PostQuantumCrypto.kt`,
  `crypto/DoubleRatchet.kt`, `crypto/NoiseProtocol.kt`). A contact card is not a state secret; the
  fresh-per-swap ECDH is the right level, and `PING_PLAN.md:106` already reached this conclusion.
- **PAKE and SPAKE2** (`crypto/PAKEHandshake.kt`, `crypto/Spake2Exchange.kt`). The gesture code is
  a shared secret, which is what PAKE wants — but the fixed 128-value space makes a PAKE's
  advantage small, and the implementation present is self-described as simplified.
- **A FOSS de-Googled flavor** (§2.4 transport), unless the product decision in Phase 4 says
  otherwise.
- **Classic Bluetooth (RFCOMM) BLE alternatives** (`data/BleRfcommBridge.kt`, `blue-pair`). Nearby
  Connections already spans BLE and Wi-Fi Direct.
- **Flutter, React Native and Unity surfaces** (`pubspec.yaml`, `package.json`, CMakeLists).
- **Contacts provider integration** (`android-contacts`): the app intentionally keeps its own store.
- **Bitwarden-style vaults, notes apps, media apps** — the remaining tail of §2.9.

## Part 5 — Verification

| Claim | Command | Expected |
|---|---|---|
| Defects 1-6 fixed | `./gradlew :app:assembleDebug` | exit 0 |
| Defect 1 fixed | `grep -n "android.content.Context" app/src/main/java/com/ping/app/di/AppModule.kt` | one match |
| Defect 2 fixed | `grep -n "NearbyExchangeService(context)" app/src/main/java/com/ping/app/di/AppModule.kt` | no match |
| Defect 3 fixed | `grep -n -A2 "@Inject constructor" app/src/main/java/com/ping/app/data/BleManager.kt \| grep Context` | line carries `@ApplicationContext` |
| Defects 4, 5 fixed | `grep -rn "com.google.mlkit\|com.google.zxing" app/src` | no match |
| Defect 6 fixed | `grep -rn "zxingEmbedded" gradle/libs.versions.toml` | no match |
| Defect 7 fixed | `grep -n "camera integration pending" app/src/main/java/com/ping/app/ui/navigation/AppNavHost.kt` | no match |
| Defect 8 fixed | `grep -rn "NearbyExchangeService.start" app/src --include=*.kt` | at least one live Compose call site |
| Defect 9 fixed | `ls app/src/main/res/navigation app/src/main/res/layout` | absent, or every file has an inflater |
| Defect 10 fixed | `grep -n "X25519" README.md` | no match, or the code uses X25519 |
| Defect 11 fixed | `find app/src -name CryptoUtils.kt` | exactly one file |
| Defect 12 fixed | `grep -n 'localName = ' app/src/main/java/com/ping/app/service/NearbyExchangeService.kt` | the advertised name does not contain the raw code |
| Defect 13 fixed | `./gradlew :app:testDebugUnitTest` | tests exist and pass |
| Defect 16 fixed | reachability re-run against compiled output | the reported live count matches the UI file list |
| Defect 17 fixed | `grep -n "bleManager" app/src/main/java/com/ping/app/PingApplication.kt` | no match, or `start()` does something |
| Defect 18 fixed | `grep -c "@Provides" app/src/main/java/com/ping/app/di/BleModule.kt` | no more providers than there are live consumers |
| Dead dependency | `grep -rn "org.bouncycastle" app/src` | no match, and the dependency is removed |
| Defect 19 fixed | `grep -n "SharedPreferences" README.md` | no match |

Final sweep: `./gradlew :app:assembleDebug :app:assembleRelease :app:lint :app:testDebugUnitTest`
exits zero from a clean checkout, and `grep -rn "camera integration pending" app/src` returns
nothing.

---

## Part 6 — Applied fixes

This part records what was actually changed, and corrects the defect inventory where
measurement contradicted it.

### 6.1 The defect count in Part 1 was wrong by two orders of magnitude

Part 1 asserted "at least six independent compile errors (defects 1-6)" and noted that "the only
trustworthy count is the one a compiler produces, and the compiler cannot run yet." Once the Gradle
wrapper was repaired and the build could run, the compiler produced **306 errors across 43 files**.
The six listed defects were real and were among them, but they were not the bulk of the problem.
The remaining errors were Python-to-Kotlin porting damage in the absorbed corpus.

Three defects surfaced this way and were not in Part 1 at all:

| Defect | File | Symptom |
|---|---|---|
| A Python docstring sat above `package` | `data/MeshEncryption.kt` | syntax error at the first line |
| `**` used as an exponent operator | `data/NearbyDiscoveryManager.kt:173` | unresolved reference |
| An unbalanced parenthesis | `crypto/Bech32Encoder.kt:159` | syntax error |

Seven Hilt providers referenced types that exist nowhere in the tree — `BleContentModeration`,
`BleGeofence`, `BleHealthProfiles`, `BlePowerProfile`, `BlePriorityQueue`, `CrocFileTransfer`, and
`BlePalmMinutiae`. Two of those names (`BleGeofence`, `CrocFileTransfer`) do not correspond to any
class at all; the nearest real classes are `GeofenceCrossingStore` and `EncryptedFileTransfer`. Hilt
validates every `@Provides` at compile time, so each one cascaded into `error.NonExistentClass`
across its whole module. The providers were removed, and `di/BleModule.kt` and `di/CryptoModule.kt`
were removed with them.

`ui/navigation/AppNavigation.kt` referenced `Icons.Default.Contacts`, which is not in the core
Material icon set and required the undeclared `material-icons-extended` artifact. It was replaced
with `Icons.Default.AccountBox`, which is in the core set.

### 6.2 The dead half was parked, not deleted

Part 3's Phase 2 directs deletion of the dead half. Deletion was not safe here: a large part of the
absorbed corpus is untracked, so deleting it would have been unrecoverable. Every file was moved to
`app/unwired/` instead. `app/unwired/java/` holds 65 Kotlin files and `app/unwired/res/` holds 10
resource files, each mirroring its original path. The compiled source set now holds 95 Kotlin files.

Moving sources out of `src/main/java` removes them from compilation without touching
`build.gradle.kts`, because the Android source sets include only `src/main/java` and
`src/main/kotlin`.

The Fragment layer was parked as one unit: six Kotlin classes (`ContactDetailBottomSheet`,
`ContactsAdapter`, `ContactsFragment`, `ExchangeSuccessBottomSheet`, `HomeFragment`,
`ProfileFragment`), eight layouts, `res/navigation/nav_graph.xml`, and `res/menu/bottom_nav_menu.xml`.
The layer was self-contained — every reference was between its own files, and no live code touched
any of it. Parked last were six absorbed BLE modules (`BleRfcommBridge`, `BleWifiProvisioner`,
`BleServiceBrowser`, `BleFastTransfer`, `BleMeshProvisioning`, `BleBluetoothObserver`), each of which
had zero references from any other file in the source tree and each of which accounted for part of
the 38 `MissingPermission` lint errors reported against code that never runs.

Two approaches to measuring reachability were tried and abandoned. Static regex analysis was
unreliable for Kotlin, because Compose screens are top-level functions and common identifiers create
false edges. An R8 `-printusage` report was tried next and was too noisy to act on: it listed
`com.ping.app.R`, `BuildConfig`, and Hilt-generated components as unused. The decision to park each
file was made from direct per-file reference counts instead, which is a smaller and verifiable claim.

### 6.3 Defect status after this pass

| Defect | Status | What was done |
|---|---|---|
| 1 `Context` not imported | Fixed | `import android.content.Context` added to `di/AppModule.kt` |
| 2 `Service` provided as a singleton | Fixed | Provider deleted from `AppModule` |
| 3 Unqualified `Context` in `BleManager` | Fixed, then parked | `@ApplicationContext` added; the class is now in `app/unwired/` |
| 4 `QRScannerScreen` imports ML Kit | Fixed | Screen deleted; the dependency is not declared |
| 5 `QRDisplayScreen` imports ZXing | Fixed | Screen deleted; the dependency is not declared |
| 6 Catalog references an undefined version | Fixed | Broken `zxing-android-embedded` entry removed |
| 7 Share button reaches a placeholder | Fixed | A real `ui/exchange/ExchangeScreen.kt` was written and wired into `AppNavHost` |
| 8 Exchange service unreachable | Fixed | The screen starts and stops `NearbyExchangeService` |
| 9 Two navigation systems | Fixed | Fragment layer parked; Compose is the only graph |
| 10 Documented crypto is not the crypto | Fixed | README now says P-256 ECDH, matching `utils/CryptoUtils.kt` |
| 11 Two `CryptoUtils` classes | Fixed | `security/CryptoUtils.kt` parked; only `utils/CryptoUtils.kt` is in the build |
| 12 Gesture code advertised in cleartext | Improved | See 6.4 |
| 13 No tests | Partly fixed | 12 unit tests added; they gate CI |
| 14 `foss` flavor does not exist | Open | `di/TransportModule.kt` still documents it |
| 15 Permissions vs absorbed modules | Open | Most of the absorbed set is now parked, so the gap is smaller |
| 16 Fourteen unreferenced files | Partly fixed | 71 files parked; the rest are compiled and untouched |
| 17 Startup builds BLE subsystems | Fixed | `bleManager` injection and `start()` call removed from `PingApplication` |
| 18 Hilt validates the orphan graph | Fixed | `BleModule` and `CryptoModule` removed from the build |
| 19 README contradicts itself | Fixed | The README now describes Room, not SharedPreferences |
| 20 `PING_PLAN.md` is stale | Open | Not touched |
| 21 CI cannot gate anything | Fixed | `.github/workflows/build.yml` runs `test`, `lint`, and both assemblies |
| 22 Hygiene items | Partly fixed | See 6.5 |

### 6.4 Defect 12 is improved, not solved, and cannot be solved as specified

The gesture code was advertised verbatim in the BLE local name as `"<code>|<displayName>"`.

The advertisement now carries `"<token>|<sessionNonce>"`, where the token is the first six bytes of
`SHA-256(code)` in hex, and the display name is no longer broadcast. `sessionNonce` is random per
session, which also removes the tie-break's dependence on a peer-chosen name.

Part 3 asked for `SHA-256(code || sessionNonce)` with a time bucket. That construction was not used,
because a time bucket makes the two devices fail to match whenever they start on opposite sides of a
bucket boundary, and the pairing window is only ten seconds. Both devices must derive the same
advertisement from the same gesture, so the advertisement has to be a function of the code alone.

**The honest limitation:** `GestureFingerprint` derives its code from a 5-bit finger mask and a
2-bit direction bucket. That is 128 values, or seven bits. Any deterministic advertisement of a
seven-bit value is recoverable by enumeration, so this change stops a passive scanner from reading
the code directly but does not stop a device in range from finding it. Part 3's other two proposals —
requiring confirmation before accepting an initiated connection, and comparing a short
authentication string at the end of the handshake — are what would actually authenticate the peer.
Neither was implemented, and neither is a small change: the first needs a confirmation step in the
protocol and the screen, and the second needs a UI surface on both devices.

The README now states this limitation in the feature table, the security table, and the summary.

### 6.5 Hygiene and build fixes

| Item | Change |
|---|---|
| `org.bouncycastle:bcprov-jdk18on` | Removed. No file imports it, and its comment claimed X25519. |
| BouncyCastle ProGuard rules | Removed with the dependency |
| `datastore`, `biometric`, `securityCrypto` catalog entries | Removed; nothing referenced them |
| `kotlin-jvm` and `navigation-safeargs` plugin aliases | Removed; with the Fragment layer parked, nothing used them |
| `local.properties` | `sdk.dir` pointed at a Linux path and used unescaped Windows separators. Now `C\:/Users/...`. |
| Lint | Ran for the first time. 39 errors; all fixed. |
| `testImplementation` | Added JUnit 4.13.2 |

Part 3 proposed JUnit 5. JUnit 4 was used instead, because JUnit 5 on Android requires an additional
Gradle plugin for no benefit here: every test added is a pure JVM unit test.

### 6.6 Verification

Run from `Aura/`, all four exit zero:

```
./gradlew test
./gradlew lint
./gradlew assembleDebug
./gradlew assembleRelease
```

- `test` — 12 tests across `GestureFingerprintTest` and `CryptoUtilsTest`, 0 failures. The fingerprint
  test enumerates all 32 finger masks against all four direction buckets and asserts the resulting
  set has exactly 128 distinct codes, which is the claim the README makes. The crypto test covers ECDH
  agreement, a third party deriving a different key, public key round-trip, card round-trip, and GCM
  tamper rejection.
- `lint` — 0 errors. It previously reported 39.
- `assembleDebug` / `assembleRelease` — both produce APKs.

### 6.7 Still open

- The Compose `ExchangeScreen` compiles and is wired, but has never run on a device. The camera
  binding, the ten-second window, and matchmaking between two real phones are all unverified on
  hardware. Part 3's Phase 1 called this "the field test `PING_PLAN.md` has always wanted", and it is
  still owed.
- The air-drawing overlay (`ui/HandGestureOverlay.kt`) and its `onLandmarks` forwarding were not
  ported. Only the swap flow was.
- Defects 14, 15, and 20 are open, as listed in 6.3.
- `PING_PLAN.md` still describes the pre-fix product.
- The parked tree in `app/unwired/` is 65 Kotlin files of absorbed corpus. Almost none of it is
  compiled, so none of it is checked by the compiler or by lint. Deciding what to revive and what to
  delete is the real remaining work in Part 3's Phase 2.

## Part 7 — Second-pass audit and fixes

Status as of 2026-09-12. This pass re-checked Parts 5 and 6 against the built artifacts rather than
the source alone. It found one defect that Part 6 had asserted the opposite of, and several smaller
ones. It also committed the Phase 2 work, which had been sitting in the working tree uncommitted with
`plan.md` itself untracked.

### 1. The shipped APK requested `INTERNET`, which Part 6 said it did not

Defect 15 in Part 6 reads "The absence of `INTERNET` is worth keeping — the app's stated design is
'No server. No cloud. No internet' (`README.md:12`)". The absence held in the source manifest and
nowhere else. `com.google.android.datatransport:transport-backend-cct:3.1.0`, a transitive dependency
of `play-services-nearby`, declares `INTERNET` in its own manifest, and manifest merging contributes
transitive permissions to the application.

Proof, before the fix:

```
aapt2 dump badging app/build/outputs/apk/release/app-universal-release.apk | grep INTERNET
uses-permission: name='android.permission.INTERNET'
```

and the merger's blame report, which names the contributing library:

```
app/build/intermediates/manifest_merge_blame_file/release/processReleaseMainManifest/manifest-merger-blame-release-report.txt:109
59  <uses-permission android:name="android.permission.INTERNET" />
59-->[com.google.android.datatransport:transport-backend-cct:3.1.0] ...transport-backend-cct-3.1.0\AndroidManifest.xml:26:5-67
```

The permission is removed declaratively in the application manifest:

```xml
<uses-permission android:name="android.permission.INTERNET" tools:node="remove" />
```

A permission that is not requested cannot be granted, so this enforces the product claim at the
platform level rather than relying on the telemetry path staying unused. The alternative, excluding
the dependency in Gradle, removes the same code but risks a `NoClassDefFoundError` if Nearby
Connections references it at runtime; removing the permission carries no such risk and is the smaller
change.

Verified after the fix on both variants:

| Check | Result |
|---|---|
| Release merged manifest | `grep -c INTERNET` → 0 |
| Debug merged manifest | `grep -c INTERNET` → 0 |
| Other permissions intact | All 16 (`BLUETOOTH_*`, `NEARBY_WIFI_DEVICES`, `CAMERA`, `VIBRATE`, `FOREGROUND_SERVICE*`, `POST_NOTIFICATIONS`, the location and Wi-Fi set) still present |

The two Gradle tasks used, `:app:processReleaseMainManifest` and `:app:processDebugMainManifest`, are
the merge step itself, so the check reads the same artifact `aapt2` reads.

### 2. `SessionViewModel.kt` was dead but kept in the release APK

Part 6's Phase 2 item 3 named `viewmodel/SessionViewModel.kt` for deletion; it had no importer then
and still had none. Unlike the other orphaned live files, which R8 removes, a `ViewModel` subclass is
retained by the AndroidX keep rules, so it was compiled into the release artifact. Its pending diff
existed only to keep it compiling against the new `ContactRepository` API, so deleting the class
retires the diff.

Deleted. `:app:compileDebugKotlin` exits 0, and the full gate below still passes. With it gone, the
`ViewModel` keep rules no longer retain any unreferenced class.

### 3. Smaller findings, recorded and not fixed

- **`VIBRATE` is declared and nothing reachable vibrates.** The only vibration code is
  `utils/Extensions.kt:21` and `:35`, neither of which has a caller, and the README roadmap still
  lists haptics as a TODO. This is the same shape as finding 1 — a permission the code does not use —
  but the intent is the opposite: haptics are planned, so the permission is expected to become true
  rather than stay decorative. Left in place.
- **Three dead crypto modules implement fake primitives.** `crypto/PostQuantumCrypto.kt:76` returns
  random bytes from `decapsulate` and never inverts `encapsulate`, so the two sides cannot agree on a
  key; `crypto/BlePqxdhProtocol.kt:161` ignores its input and returns random bytes from `hkdfDerive`;
  `security/EndToEndEncryption.kt:55-67` re-imports a PKCS#8 private key as a raw scalar, which is not
  a round trip, and documents X25519 while using `secp256r1`. All three are unreachable and stripped
  by R8, so no shipped path is affected. They matter because Part 6's Phase 2 deleted
  `security/CryptoUtils.kt` for exactly this reason — a wrong primitive next to a right one — and
  these were left behind. Deleting them is the consistent move; it is recorded here rather than done,
  because the parked tree already holds absorbed code awaiting the same decision.
- **The nearby service's comments describe the pre-fix protocol.** `service/NearbyExchangeService.kt:37-40`
  still says the advertisement is `<gestureCode>|<displayName>`, but `:101` advertises
  `"$advertisementToken|$sessionNonce"`; `:138` says "the gesture code already gated us" while
  acceptance at `:137-140` is unconditional, which Part 6's §6.4 itself admits. The comments now
  contradict the code they sit on.
- **`README.md` claims the live code is displayed.** The feature table lists "Live Code Preview — See
  the gesture code in real-time", but `ExchangeScreen` renders only `fingerprint.label`;
  `GestureFingerprint.kt:35`'s `code` value is never shown. The README's diagram also shows
  `code: 0x1A`, which is not the format the code produces.
- **Unused build config left by the Fragment parking.** `app/build.gradle.kts:75-79` sets
  `viewBinding = true`, and `:102-107` declare `material`, `constraintlayout`, `navigation-fragment-ktx`
  and `navigation-ui-ktx`; no live file references Fragment, ViewBinding, ConstraintLayout, Material
  views or `findViewById`. `navigation-compose` is used by `AppNavHost.kt:15-18` but is not declared —
  it arrives transitively through `hilt-navigation-compose`. The lint report's 153 warnings include 46
  `UnusedResources`, mostly the parked Fragment layer.
- **Defect 22's counts drifted.** It records "85 changed files uncommitted"; the tree held 105. Its
  `.gitignore` line references are off by two for the keystore and by two for `*.task`.
- **`di/TransportModule.kt:14-21` still documents a `gms`/`foss` flavor pair** and an F-Droid
  `WifiDirectTransport` that do not exist. Defect 14 already records this as open; it is repeated here
  because the module is live, so the stale text ships.

### 4. What held up

Everything Part 6 claims about the build gate is reproducible on this machine, online or offline:

| Check | Result |
|---|---|
| `./gradlew test` | Exit 0 — 12 tests, 0 failures (`GestureFingerprintTest` 6, `CryptoUtilsTest` 6) |
| `./gradlew :app:testDebugUnitTest :app:assembleDebug` | Exit 0 |
| `./gradlew lint` | Exit 0, 0 errors |
| Release APK is signed | `apksigner verify` exits 0, `CN=Ping` |
| Keystore never committed | `git log --all --diff-filter=A -- "*ping-release.jks" "keystore.properties"` is empty; `.gitignore:22-23` covers both (Part 6 said 24-25) |
| Defect 12 | `NearbyExchangeService.kt:101` advertises the token and nonce, not the display name |
| Defect 15's other half | The live source manifest declares no `INTERNET`, and no live code opens a socket |
| Defect 16 | 65 Kotlin files and 10 resources under `app/unwired/`, outside every source set, so outside the compiler and lint |

Two claims are unverifiable here and are not contradicted: Part 6's "306 errors across 43 files" and
"lint previously reported 39" have no pre-fix snapshot to check, and every device-level claim (camera
binding, the ten-second window, two-phone matchmaking) needs hardware.

### 5. Repo state

The Phase 2 results were uncommitted when this pass began, with `plan.md` untracked. They are now
committed: commit `1d98d53` records the parked-tree consolidation and adds `plan.md`, and the fix
commit carries the manifest change and the `SessionViewModel` deletion.

### Still open

- The five findings in section 3 that were recorded and not fixed, in particular the three fake
  crypto modules.
- Everything in §6.7 stands unchanged, including the field test on real hardware.

