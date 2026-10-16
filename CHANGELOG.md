# Changelog

All notable changes to Ping are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.7.0] - 2026-10-17

### Breaking
- Cannot swap with versions before 0.7.0. Swaps and rooms now advertise under the
  `com.ping.app.swap.v2` and `com.ping.app.room.v2` service ids, so older installs no longer
  see these phones, and an older peer that does show up is reported as outdated. Update both
  phones.

### Added
- Gesture-keyed PAKE (CPace-style on P-256): the gesture is the only pairing check, replacing
  the six-digit confirmation code. Three failed peers end a swap.
- BLE L2CAP transport for phones without Play Services (Android 10+).
- Custom gestures recorded and named on the Practice screen, plus swipe left, right, up and
  down as motions.
- Contact backup and restore as JSON, with an optional passphrase (PBKDF2-SHA256 and
  AES-GCM); restoring merges without duplicates.
- Share your own card as a `.vcf` from Profile or a launcher shortcut.
- Per-swap field choice: pick which card fields a swap sends; the choice is remembered.
- File room: resume interrupted downloads, keep subfolders, up to sixteen guests, room notes
  posted through the host, and a cap on wrong-gesture guesses.
- Hindi, Spanish, French, German and Brazilian Portuguese translations.
- One-time how-to card on Home linking to Practice; stability thirds and tick and error
  haptics.
- Exported Room schemas and a migration test from version 1.

### Changed
- Advertisements carry a versioned 4-bit gesture bucket instead of the gesture code.
- The swap protocol moved into `ExchangeEngine`; failed swap requests are retried.
- Faster gesture camera and BLE paths: reused frame bitmaps and GCM ciphers, a baseline
  profile, fewer recompositions, and contact counts in SQL.
- Accessibility: readable favourite star and back button, announced room status changes.
- The hand landmarker model is pinned to version 1 and checked against its SHA-256.

### Fixed
- Roll call start race, unlisted rolls, and the swap service left running during roll call.
- Room join holes (silent links, slow knocks, growing relays) and download slots leaking on
  failure.
- The database is no longer wiped on upgrade, and a downgrade wipes instead of crashing.
- Restoring a backup keeps people who share a phone apart, and a sealed backup that lost its
  passphrase never falls back to a plain file.

## [0.6.0]

Last release with six-digit swap codes.

[0.7.0]: https://github.com/Yash-Awasthi/Ping/compare/v0.6.0...v0.7.0
[0.6.0]: https://github.com/Yash-Awasthi/Ping/releases/tag/v0.6.0
