# Ping roadmap

What is still to do, most important first.

## 1. Field-test on real phones
Two or more phones with Play Services are needed; emulators cannot do BLE or Wi-Fi Direct.
- Pair two phones by gesture end to end, including a wrong gesture in the same bucket failing the check.
- Run a file room with a host and two guests: gesture check, approval, download, guest-to-guest relay.
- Tune the limits that came from one hand on one phone: pinch, finger gaps, thumb, palm edge-on,
  motion (circle, wave and swipes), the hand-shape cut-off `SHAPE_MIN`, `COMMIT_FRAMES` and
  `WINDOW_SECONDS`. The Practice screen prints the raw numbers for this.
- Check the hand-shape sign on a phone: a right hand should read negative `shape` in Practice.
- Soak-test the foreground services when the app is backgrounded.

## 2. Roll call
- Measure a full class (100+ phones) on real hardware; tune the scan and announce windows.
- Check-in is proximity only: a phone carried by a friend counts as present.

## 3. File room
- Resume an interrupted transfer.
- Keep a shared folder's subfolder layout; files currently arrive flat.
- More than seven guests.

## 5. Platform
- An iOS companion.
- Translations.

## 6. Cleanup
- Run the shrunk release build through the gesture and room flows on a phone.

## Not planned
Anything that needs the `INTERNET` permission (mesh relay, Matrix, Nostr, LocalSend, croc,
PairDrop). The manifest removes the permission on purpose, so networking code cannot ship in
this app.
