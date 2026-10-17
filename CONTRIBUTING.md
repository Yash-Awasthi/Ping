# Contributing to Ping

Offline contact swaps and file rooms, paired by a shared hand gesture.

## Quick Start

```bash
git clone https://github.com/Yash-Awasthi/Ping.git
cd Ping

./gradlew :app:testDebugUnitTest   # JVM unit tests
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
```

Benchmarks such as `PakeBench` are tagged `bench` and skipped by default. Run them with
`./gradlew :app:testDebugUnitTest -Pbench --tests '*PakeBench'`.

`MigrationTest` and the DAO tests are instrumented and need a device or emulator:
`./gradlew :app:connectedDebugAndroidTest`.

## Tech Stack

| Component | Technology | Version |
|-----------|------------|---------|
| Language | Kotlin | 2.4 |
| Platform | Android | API 26+ (Android 8.0), target 37 |
| DI | Hilt | 2.60 |
| Async | Coroutines | 1.11 |
| UI | Jetpack Compose | BOM 2026.10 (alpha) |
| Database | Room 3 | 3.1 (alpha) |
| Nearby | Google Nearby Connections | 19.5 |
| BLE fallback | Android BLE L2CAP | API 29+ |
| Testing | JUnit | 6.1 |

minSdk is 26, but the BLE L2CAP transport (`BleL2capTransport`, used when Play Services is
missing) needs API 29 (Android 10). Below 29 the app always uses Nearby Connections; see
`di/TransportModule.kt`. Guard any new L2CAP code with `@RequiresApi(Build.VERSION_CODES.Q)`.

## Project Structure

```
app/src/main/java/com/ping/app/
├── auth/
│   ├── CustomGesture.kt
│   ├── GestureCamera.kt
│   ├── GestureFingerprint.kt
│   ├── GestureLock.kt
│   ├── GestureNames.kt
│   ├── Handedness.kt
│   └── MotionTracker.kt
├── data/
│   ├── local/
│   │   ├── AppDatabase.kt
│   │   ├── ContactDao.kt
│   │   └── ProfileDao.kt
│   ├── ContactBackup.kt
│   ├── ContactRepository.kt
│   ├── ProfileRepository.kt
│   └── Result.kt
├── di/
│   ├── AppModule.kt
│   ├── DatabaseModule.kt
│   ├── Dispatchers.kt
│   └── TransportModule.kt
├── model/
│   ├── Contact.kt
│   ├── ExchangeSession.kt
│   └── Profile.kt
├── rollcall/
│   ├── RollCall.kt
│   ├── RollCallData.kt
│   ├── RollCallProtocol.kt
│   ├── RollCallScreen.kt
│   └── RollCallViewModel.kt
├── room/
│   ├── RoomHub.kt
│   ├── RoomHubService.kt
│   ├── RoomProtocol.kt
│   ├── RoomScreen.kt
│   └── RoomViewModel.kt
├── service/
│   ├── BleL2capTransport.kt
│   ├── BleLink.kt
│   ├── BleWire.kt
│   ├── ExchangeEngine.kt
│   ├── NearbyConnectionsTransport.kt
│   ├── NearbyExchangeService.kt
│   ├── NearbyTransport.kt
│   ├── NotificationChannels.kt
│   └── Pairing.kt
├── ui/
│   ├── components/
│   │   ├── Brand.kt
│   │   └── GestureGuide.kt
│   ├── contacts/
│   │   ├── ContactDetailScreen.kt
│   │   ├── ContactDetailViewModel.kt
│   │   ├── ContactExport.kt
│   │   ├── ContactFilter.kt
│   │   ├── ContactsScreen.kt
│   │   └── ContactsViewModel.kt
│   ├── exchange/
│   │   ├── ExchangeScreen.kt
│   │   └── ExchangeViewModel.kt
│   ├── home/
│   │   ├── HomeScreen.kt
│   │   └── HomeViewModel.kt
│   ├── navigation/
│   │   ├── AppNavHost.kt
│   │   └── AppNavigation.kt
│   ├── practice/
│   │   └── PracticeScreen.kt
│   ├── profile/
│   │   ├── ProfileScreen.kt
│   │   └── ProfileViewModel.kt
│   ├── theme/
│   │   └── Theme.kt
│   ├── ConnectionStatusIndicator.kt
│   ├── MainActivity.kt
│   ├── QuickLaunch.kt
│   └── SwapTileService.kt
├── utils/
│   ├── CryptoUtils.kt
│   ├── Extensions.kt
│   ├── Pake.kt
│   └── RequiredPermissions.kt
└── PingApplication.kt
```

## Development Guidelines

- Inject dependencies with Hilt; expose screen state from ViewModels as `StateFlow`.
- Keep radio and file work off the main thread on the injected `@IO` and `@Default` dispatchers.
- Add user-facing text to `res/values/strings.xml`; translators fill the `values-xx` files.
- Changing an entity means bumping the `AppDatabase` version, adding a `Migration`, and
  committing the schema KSP writes to `app/schemas/`.
- The manifest removes `INTERNET` on purpose. Do not add networking code.

### Security

Pairing is a gesture-keyed PAKE. Do not hand-roll key exchange or compare secrets with `==`;
use `utils/Pake.kt` for the exchange and key confirmation, and `utils/CryptoUtils.kt` for
HKDF, passphrase keys and AES-GCM. Their tests in `app/src/test/java/com/ping/app/utils/`
show the intended use.

## Pull Request Checklist

- [ ] `./gradlew :app:testDebugUnitTest` and `./gradlew :app:lintDebug` pass
- [ ] New schema JSON committed if the database changed
- [ ] Proguard rules updated if needed
- [ ] README and CHANGELOG updated for user-visible changes

## Release signing

Signing reads `keystore.properties` (gitignored) at the repo root:

```properties
storeFile=keystore/ping-release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Without it, `assembleRelease` still builds but leaves the APK unsigned. To generate a key:

```bash
keytool -genkeypair -v -keystore keystore/ping-release.jks -alias ping \
  -keyalg RSA -keysize 2048 -validity 10000
```

Pushing a `v*` tag runs `.github/workflows/release.yml`, which builds the release APKs and
attaches them to the GitHub release. It signs them when the repository secrets
`KEYSTORE_BASE64` (the base64 `.jks`), `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` are
set, and uploads unsigned APKs otherwise.
