# Better Stack: Ping

Upgrade plan built from the code itself (`gradle/libs.versions.toml`, `app/build.gradle.kts`, the import graph under `app/src/main/java`, the manifest and CI), with every target version read from Google Maven, Maven Central and the Gradle Plugin Portal on 2026-10-12. "Newest" includes alpha/beta/RC builds; "Stable" is the newest final release.

## What the code actually uses

- Single-module Android app, ~8.6k lines of Kotlin, 100% Jetpack Compose UI (`MainActivity : ComponentActivity`).
- Hilt for DI (`@HiltViewModel`, `@Inject` in services and repositories), `hiltViewModel()` from `androidx.hilt.navigation.compose`.
- Room for contacts/profile, compiled with KSP.
- Coroutines + `Flow` (40 `kotlinx.coroutines.flow` imports), no LiveData anywhere.
- Google Nearby Connections for the offline BLE / Wi-Fi Direct transport.
- CameraX `PreviewView` wrapped in `AndroidView` (`PracticeScreen.kt`, `ExchangeViewModel.kt`) feeding MediaPipe `HandLandmarker`.
- Gson, used once (`NearbyExchangeService.kt`) to serialise the contact card.
- Crypto is hand-rolled on JCA: HKDF over `HmacSHA256`, AES-GCM, SHA-256 (`utils/CryptoUtils.kt`).
- Icons come from `androidx.compose.material.icons` (34 imports).
- Unit tests on JUnit 4; instrumented Room DAO test.

## Dead weight to delete first

| Dependency | Evidence | Action |
|---|---|---|
| `androidx.lifecycle:lifecycle-livedata-ktx` | Zero `LiveData` references in `app/src` | Remove |
| `androidx.appcompat:appcompat` | `MainActivity` extends `ComponentActivity`; no AppCompat class used | Remove |
| `com.google.android.material:material` | Only pulled in for the XML theme parent `Theme.MaterialComponents.DayNight.NoActionBar` | Change `themes.xml` parent to `android:Theme.Material.NoActionBar`, then remove |
| JetBrains `compose/dev` Maven repo in `settings.gradle.kts` | Jetpack Compose resolves from `google()`; repo is unused | Remove from both repository blocks |

## Toolchain

| Item | Current | Stable | Newest | Pick | Notes |
|---|---|---|---|---|---|
| Gradle wrapper | 8.13 | 9.8.1 | 9.8.1 | **9.8.1** | Required by AGP 9 |
| Android Gradle Plugin | 8.13.2 | 9.4.1 | 9.5.0-alpha09 | **9.5.0-alpha09** (fallback 9.4.1) | AGP 9 has built-in Kotlin support |
| Kotlin | 2.0.0 | 2.4.21 | 2.5.0-Beta1 | **2.5.0-Beta1** (fallback 2.4.21) | K2 compiler, faster Compose compiler |
| KSP | 2.0.0-1.0.21 | 2.3.12 | 2.3.12 | **2.3.12** | KSP2; version is no longer tied to Kotlin |
| JDK (build + CI) | 17 | 25 LTS | 27 | **25** | Set `kotlin { jvmToolchain(25) }`; compile target 21 |
| compileSdk / targetSdk | 36 / 35 | 37 | 37 | **37 / 37** | targetSdk 35 is already behind Play policy |
| foojay resolver | 0.10.0 | 1.0.0 | 1.0.0 | **1.0.0** | |

AGP 9 migration notes that apply to this build file:

- Delete the `org.jetbrains.kotlin.android` plugin (`libs.plugins.kotlin.android`) from both build files. AGP 9 compiles Kotlin itself; keep `kotlin.plugin.compose`.
- `kotlinOptions { jvmTarget = "17" }` is gone. Replace with `kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_21; optIn.add("kotlin.RequiresOptIn") } }`.
- `compileOptions` moves to `JavaVersion.VERSION_21`.
- Turn on `org.gradle.configuration-cache=true` in `gradle.properties`. The `downloadHandModel` task already declares `outputs.file`, but the `afterEvaluate` hook must become `tasks.named(...)` wiring for config-cache safety.

## Libraries

| Library | Current | Stable | Newest | Pick |
|---|---|---|---|---|
| Compose BOM | 2024.09.00 | 2026.09.00 | 2026.10.00 (`compose-bom-alpha`) | **2026.10.00 alpha BOM** |
| Material 3 (via BOM) | 1.3.x | 1.4.0 | 1.5.0-beta01 | **1.5.0-beta01** (Material 3 Expressive) |
| `material-icons-core` | transitive | n/a | n/a | **Add explicitly** — Material 3 1.4+ no longer pulls it in, so the 34 icon imports break without it |
| `activity-compose` | 1.8.2 | 1.13.0 | 1.14.0-alpha03 | **1.14.0-alpha03** |
| `core-ktx` | 1.13.1 | 1.19.1 | 1.19.1 | **1.19.1** |
| Lifecycle (`runtime`, `viewmodel`, `runtime-compose`) | 2.8.2 | 2.11.0 | 2.12.0-alpha04 | **2.12.0-alpha04** |
| Navigation | `navigation-compose` 2.7.7 | Navigation 3 1.2.0 | 1.3.0-alpha02 | **Navigation 3 1.3.0-alpha02** |
| Hilt / Dagger | 2.51.1 | 2.60.1 | 2.60.1 | **2.60.1** |
| Hilt Compose | `hilt-navigation-compose` 1.1.0 | `hilt-lifecycle-viewmodel-compose` 1.4.0 | 1.4.0 | **`hilt-lifecycle-viewmodel-compose` 1.4.0** |
| Room | 2.6.1 | Room 3 (`androidx.room3`) 3.0.3 | 3.1.0-alpha01 | **Room 3 3.1.0-alpha01** (fallback `androidx.room` 2.8.5) |
| Coroutines | 1.8.1 | 1.11.0 | 1.11.0 | **1.11.0** |
| JSON | Gson 2.11.0 | kotlinx-serialization-json 1.11.0 | 1.12.0-RC | **kotlinx-serialization 1.12.0-RC** |
| Nearby | 19.1.0 | 19.5.1 | 19.5.1 | **19.5.1** |
| CameraX | 1.3.4 | 1.6.2 | 1.7.0-alpha03 | **1.7.0-alpha03** + `camera-compose` |
| MediaPipe `tasks-vision` | 0.10.14 | 1.1.0 | 1.1.0 | **1.1.0** |
| Timber | 5.0.1 | 5.0.1 | 5.0.1 | keep |
| Unit tests | JUnit 4.13.2 | JUnit Jupiter 6.1.3 | 6.1.3 | **JUnit 6** (pure JVM tests only) |
| `androidx.test.ext:junit` / `runner` | 1.2.1 / 1.6.2 | 1.3.0 / 1.7.0 | same | **1.3.0 / 1.7.0** |
| `room-testing` | 2.6.1 (hardcoded) | follows Room | | Move into the version catalog |

Why each non-trivial swap is worth it for this code:

- **Navigation 3**: `AppNavHost.kt` is 129 lines of string routes. Nav3 makes the back stack a plain `SnapshotStateList` of typed keys, which suits the exchange → room → roll-call flows that currently pass IDs through route strings.
- **Hilt Compose artifact**: `hiltViewModel()` moved to `androidx.hilt.lifecycle.viewmodel.compose`. Update the imports in every screen (`HomeScreen.kt` and the rest).
- **Room 3**: Kotlin-first and coroutine-only, matching the existing `Flow`-based DAOs. It is a package rename (`androidx.room` → `androidx.room3`), so the change is mechanical. Run `ContactDaoTest` on a device before and after.
- **kotlinx-serialization over Gson**: one call site. Release builds run with `isMinifyEnabled = false` partly because reflection-based libraries complicate R8. Serialization is compile-time and R8-safe, so this removes one blocker to turning shrinking back on.
- **CameraX 1.7 + `camera-compose`**: `CameraXViewfinder` replaces the `AndroidView { PreviewView }` bridge in `PracticeScreen.kt` and the `PreviewView` parameter on `ExchangeViewModel.startCamera`. The view model then stops holding a View reference.
- **MediaPipe 1.1.0**: the first 1.x line. After it lands, retest whether R8 still breaks hand detection (the reason in `build.gradle.kts` for leaving minify off). If it passes, enable minify and add keep rules for `com.google.mediapipe.**`.

## Version catalog after the upgrade

```toml
[versions]
agp = "9.5.0-alpha09"
kotlin = "2.5.0-Beta1"
ksp = "2.3.12"
coreKtx = "1.19.1"
lifecycle = "2.12.0-alpha04"
nav3 = "1.3.0-alpha02"
room3 = "3.1.0-alpha01"
hilt = "2.60.1"
hiltCompose = "1.4.0"
coroutines = "1.11.0"
serialization = "1.12.0-RC"
nearby = "19.5.1"
camerax = "1.7.0-alpha03"
mediapipe = "1.1.0"
timber = "5.0.1"
composeBom = "2026.10.00"
activityCompose = "1.14.0-alpha03"
junit = "6.1.3"
```

The Compose BOM key must point at `androidx.compose:compose-bom-alpha` for the alpha line.

## CI (`.github/workflows/build.yml`)

| Action | Current | Latest |
|---|---|---|
| `actions/checkout` | v5 | v7.0.1 |
| `actions/setup-java` | v5, JDK 17 | v6.0.1, JDK 25 |
| `gradle/actions/setup-gradle` | v4 | v6.4.0 |
| `actions/upload-artifact` | v4 | v7.0.2 |

Also add `--configuration-cache` to the Gradle steps once it is enabled.

## Security and hardening

- `CryptoUtils.kt` hand-rolls HKDF. Use Tink (`com.google.crypto.tink:tink-android`) for HKDF and AES-GCM, or keep the code and add RFC 5869 test vectors to `CryptoUtilsTest`.
- Recheck legacy `BLUETOOTH` / `BLUETOOTH_ADMIN` and location permissions in the manifest. With `minSdk 26` they are still needed for API ≤ 30 devices, so cap them with `android:maxSdkVersion="30"`.

## Order of work

1. Delete the dead dependencies and the unused Maven repo, then build.
2. Gradle 9.8.1 + JDK 25 + AGP 9.x + Kotlin 2.5 + KSP 2.3. Fix `kotlinOptions` and drop the Kotlin Android plugin.
3. Compose BOM, Material 3, add `material-icons-core`, activity, lifecycle, core-ktx.
4. Hilt 2.60.1 + `hilt-lifecycle-viewmodel-compose` import change.
5. Room 3 package rename; run the DAO instrumented test.
6. Gson → kotlinx-serialization.
7. CameraX 1.7 + `camera-compose`, MediaPipe 1.1.0; test on a device, then retry R8.
8. Navigation 3 rewrite of `AppNavHost.kt`.
9. targetSdk 37, JUnit 6, CI action bumps.

Each step should leave `./gradlew test assembleDebug lint` green before the next one starts.
