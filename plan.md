# plan

## repo name mismatch
folder Aura, remote Ping (`https://github.com/Yash-Awasthi/Ping.git`). folder stays Aura, remote stays Ping. no rename.

## HARD CALLS — decided

H1 — gradlew dirty. mode flip only, 100755 -> 100644, no content change. windows/ntfs git bash can't hold exec bit. set `core.fileMode false` local config. stops the flip for good, no toss needed.

H2 — local-dirty branch. same commit as origin/local-dirty, 0 diff. 1 commit ahead of main: `0d27552 snapshot dirty gradlew before branch cleanup`, itself an empty mode-only diff. dead branch, safe to delete. not deleted here per no-delete rule. flag: delete `local-dirty` (local + origin) next time branch cleanup is allowed.

H3 — fade_in.xml vs fade_out.xml. reverse alpha of each other (0.0->1.0 vs 1.0->0.0, both 200ms). kept as two files. standard android in/out pair, one line each, merging saves nothing and forces every caller to pass a direction flag. not a real dupe.

H4 — slide_up.xml vs slide_down.xml. reverse translate AND different interpolator (decelerate on up, accelerate on down). kept as two files, same reasoning as H3, plus they are not actually reverses of the same curve so a merged version would need a parameter anyway.

H5 — plan/status source of truth. README.md + PING_PLAN.md. no PROGRESS.md, not writing one. PING_PLAN.md already carries live status table + numbered roadmap, README.md carries setup. this file (plan.md) is the branch-cleanup record, not a third status doc.

merge check: main and origin/main matched at 0/0 diverged, no real conflict found.

## state — what's broken, half-built

- pairing path unverified on real hardware. no two-phone BLE/Wi-Fi Direct test run yet, whole gesture-match-and-swap flow only compiles, never confirmed live.
- file-sharing room hub not started. design is written in PING_PLAN.md roadmap item 2, zero code.
- app icon still the old launcher icon, no "Ping" branding pass.
- gradle config cache disabled, `downloadHandModel` task not cache-safe.
- gesture code space is 128 values, collision risk between two nearby pairs doing the same gesture at once, no mitigation yet.
- no MITM/SAS protection, dropped on purpose for the 1:1 flow, would matter if room hub carries real files.

## next steps, concrete

1. field-test core loop on two real phones with play services (emulator can't do BLE/Wi-Fi Direct). tune `GestureCamera.COMMIT_FRAMES`, `NearbyExchangeService.WINDOW_SECONDS`, and `GestureFingerprint` thresholds off real results. watch via `adb logcat -s Ping:* NearbyExchangeService:* GestureCamera:*`. skipped here, no hardware in this session.
2. confirm the `localName < remoteName` tie-break always picks one initiator on device; add random back-off only if both sides sometimes request.
3. once field-tested, start `RoomHubService` (star topology on `NearbyConnectionsTransport`, `P2P_STAR`) per the design already in PING_PLAN.md roadmap item 2.
4. ~~add permission-denial UX on the exchange screen~~ — done. `RequiredPermissions` (new, shared with `MainActivity`) is checked in `ExchangeFragment` before the camera starts; on denial it shows an in-screen prompt with an "Open Settings" button (`ACTION_APPLICATION_DETAILS_SETTINGS` deep-link) instead of the capture UI, and re-checks on `onResume` in case the user granted it and came back.
5. branding/icon pass for "Ping" naming. skipped here, needs an actual icon asset from the user, not fabricating one.
6. ~~add a minimal CI workflow~~ — done. `.github/workflows/build.yml` runs `assembleDebug` and `assembleRelease` on push to main/fresh and on pull requests, using `gradle/actions/setup-gradle` for the build cache. release build works unsigned in CI since `keystore.properties` is gitignored and the build already falls back to no signing config when it's absent.
