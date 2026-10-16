# Ping roadmap

What is still to do, most important first.

## 1. Field-test on real phones
Two or more phones with Play Services are needed; emulators cannot do BLE or Wi-Fi Direct.
- Pair two phones by gesture end to end, including a wrong gesture in the same bucket failing the check.
- Run a file room with a host and two guests: gesture check, approval, download, guest-to-guest relay.
- Tune the limits that came from one hand on one phone: pinch, finger gaps, thumb, palm edge-on,
  motion (circle and wave), `COMMIT_FRAMES` and `WINDOW_SECONDS`. The Practice screen prints the
  raw numbers for this.
- Soak-test the foreground services when the app is backgrounded.

## 2. Gestures
- Let people define their own gesture sets.
- Swipes and other motions beyond the circle and the wave.
- Work out left and right hand from more than the detector's call, to stop it flickering.

## 2b. Roll call
- Measure a full class (100+ phones) on real hardware; tune the scan and announce windows.
- Check-in is proximity only: a phone carried by a friend counts as present.

## 3. File room
- Resume an interrupted transfer.
- Keep a shared folder's subfolder layout; files currently arrive flat.
- More than seven guests.

## 5. Platform
- A de-Googled transport (Wi-Fi Direct without Play Services) behind the `NearbyTransport` interface.
- An iOS companion.
- Translations.
- Re-enable the Gradle configuration cache by making `downloadHandModel` cache-safe.

## 6. Cleanup
- Remove the palm-shade measurement from `GestureCamera`; it does not separate palm from back.
- Run the shrunk release build through the gesture and room flows on a phone.

## Not planned
Anything that needs the `INTERNET` permission (mesh relay, Matrix, Nostr, LocalSend, croc,
PairDrop). The manifest removes the permission on purpose, so networking code cannot ship in
this app.
