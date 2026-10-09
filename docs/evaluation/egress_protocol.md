# Protocol: criteria 1 (zero egress) and 9 (profile encryption)

## Criterion 1: zero network egress

Static checks:

1. `./gradlew :app:verifyNoNetworkPermissionRelease` must pass.
2. `scripts/check_apk_permissions.sh app/build/outputs/apk/release/app-release.apk` must pass. Screenshot the output for the report.
3. In the app, open **ⓘ → Privacy** and screenshot the badge and the list of requested permissions.

Dynamic check (traffic observed, not inferred):

Per-app capture is not enough. The app's own UID cannot open sockets, but a library could hand data to
**another process** over Binder IPC, and that process has its own network access. For bundled ML Kit, a
developer observed no app-UID traffic and a Play services "ClientTelemetry" connection that returned a
*disabled* status, and asked Google whether delegated transmission is possible (googlesamples/mlkit
issue #1076). Successful transmission was not shown. This protocol checks for it rather than assuming either way.

1. Use PCAPdroid on the reference phone with **no app filter** (all apps), or an emulator with
   `adb shell tcpdump -i any -w /sdcard/airgaprx.pcap`. Note the app's UID (`adb shell dumpsys package com.airgaprx.app | grep userId`).
2. Baseline: with the app force-stopped, capture 30 minutes of idle traffic. Record bytes sent per
   destination host and per UID, including connections that were already open.
3. Session: start a new capture, do a full session (scan 3 labels, resolve an ambiguous item, add an item by
   name, check interactions, open an alert, install a KB update, import a model), then **keep capturing for
   30 minutes** after the session to catch deferred uploads (Android batches background jobs).
4. **Pass:** (a) zero packets from the app's UID; and (b) no increase, relative to the baseline, in bytes
   sent by Google Play services or other system UIDs to Google logging/telemetry hosts (e.g. `firebaselogging`,
   `play.googleapis.com/log`, `clearcut`) during the session and post-session window. Compare **volumes**,
   not just new connections, because uploads can reuse an existing connection.
5. Control (optional but strong): repeat on an emulator image **without** Google Play services. If (b)
   differs between the two, report it as a finding about third-party SDK telemetry, not a pass.
6. Limitation to state in the report: uploads deferred beyond the capture window, or traffic that cannot
   be attributed because it is encrypted and multiplexed with other Play services traffic, cannot be ruled
   out by this method.
7. Screenshot both captures and keep the `.pcap` files.

## Criterion 9: profile encryption

1. Debug build: `adb shell run-as com.airgaprx.app cat databases/shelf.db > shelf.db`
2. `sqlite3 shelf.db ".tables"` must fail with "file is not a database".
3. `strings shelf.db | grep -i -E "warfarin|paracetamol"` must find nothing.
