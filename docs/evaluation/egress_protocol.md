# Protocol: criteria 1 (zero egress) and 9 (profile encryption)

## Criterion 1: zero network egress

Static checks:

1. `./gradlew :app:verifyNoNetworkPermissionRelease` must pass.
2. `scripts/check_apk_permissions.sh app/build/outputs/apk/release/app-release.apk` must pass. Screenshot the output for the report.
3. In the app, open **ⓘ → Privacy** and screenshot the badge and the list of requested permissions.

Dynamic check (traffic observed, not inferred):

Per-app capture is not enough. The app's own UID cannot open sockets, but a library can hand data to
**another process** over Binder IPC, and that process has its own network access. Bundled ML Kit has been
reported to open a "ClientTelemetry" connection to Google Play services (googlesamples/mlkit issue #1076).
So capture **all** device traffic and compare against a baseline.

1. Use PCAPdroid on the reference phone with **no app filter** (all apps), or an emulator with
   `adb shell tcpdump -i any -w /sdcard/airgaprx.pcap`.
2. Baseline: with the app closed, capture 5 minutes of idle traffic.
3. Session: start a new capture and do a full session — scan 3 labels, resolve an ambiguous item, add an
   item by name, check interactions, open an alert, install a KB update, import a model. Record start/end times.
4. **Pass:** (a) zero packets from the app's UID; and (b) no new connections during the session window
   from Google Play services or other system apps to Google logging/telemetry hosts
   (e.g. `firebaselogging`, `play.googleapis.com/log`, `clearcut`) that are absent from the baseline.
5. Control (optional but strong): repeat on an emulator image **without** Google Play services. If (b)
   differs between the two, report it as a finding about third-party SDK telemetry, not a pass.
6. Screenshot both captures and keep the `.pcap` files.

## Criterion 9: profile encryption

1. Debug build: `adb shell run-as com.airgaprx.app cat databases/shelf.db > shelf.db`
2. `sqlite3 shelf.db ".tables"` must fail with "file is not a database".
3. `strings shelf.db | grep -i -E "warfarin|paracetamol"` must find nothing.
