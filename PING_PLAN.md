# Ping roadmap

What is still to do, most important first.

## 1. Field-test on real phones
Two or more phones with Play Services are needed; emulators cannot do BLE or Wi-Fi Direct.
- Pair two phones by gesture end to end, including a wrong gesture in the same bucket failing the check.
- Run a file room with a host and two guests: gesture check, approval, download, guest-to-guest relay.
- Fill a room with sixteen guests and check that Nearby keeps the star up; walk out of range
  mid-download and resume.
- Tune the limits that came from one hand on one phone: pinch, finger gaps, thumb, palm edge-on,
  motion (circle, wave and swipes), the hand-shape cut-off `SHAPE_MIN`, `COMMIT_FRAMES` and
  `WINDOW_SECONDS`. The Practice screen prints the raw numbers for this.
- Check the hand-shape sign on a phone: a right hand should read negative `shape` in Practice.
- Soak-test the foreground services when the app is backgrounded.
- Run `MigrationTest` on a device (`./gradlew :app:connectedDebugAndroidTest`) against the
  exported 1, 2 and 3 schemas.
- Camera bitmap reuse: check frames never show a stale or torn image when the rotation or
  resolution changes mid-session.
- BLE tie-break: two L2CAP phones that discover each other at once must settle on one link.
- Swap timing grace of 1.5 s before the larger advertisement requests: check it neither stalls
  a swap nor double-connects on slow phones.

## 2. Roll call
- Measure a full class (100+ phones) on real hardware; tune the scan and announce windows.
- Check-in is proximity only: a phone carried by a friend counts as present.

## 3. File room
- Resume after the link to the host drops: today the room closes, a rejoin is a new room, and
  leaving deletes the kept `.part` files. Resume works while the guest stays in the room.

## 4. Platform
- An iOS companion.

## 5. Cleanup
- Run the shrunk release build through the gesture and room flows on a phone.

## Not planned
Anything that needs the `INTERNET` permission (mesh relay, Matrix, Nostr, LocalSend, croc,
PairDrop). The manifest removes the permission on purpose, so networking code cannot ship in
this app.
