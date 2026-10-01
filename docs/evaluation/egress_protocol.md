# Protocol: criteria 1 (zero egress) and 9 (profile encryption)

## Criterion 1: zero network egress

Static checks:

1. `./gradlew :app:verifyNoNetworkPermissionRelease` must pass.
2. `scripts/check_apk_permissions.sh app/build/outputs/apk/release/app-release.apk` must pass. Screenshot the output for the report.
3. In the app, open **ⓘ → Privacy** and screenshot the badge and the list of requested permissions.

Dynamic check (traffic observed, not inferred):

1. On the reference phone, install PCAPdroid (it captures traffic without root) and filter it to `com.airgaprx.app`.
   On an emulator you can run `adb shell tcpdump -i any -w /sdcard/airgaprx.pcap` instead.
2. Start the capture. Do a full session: scan 3 labels, resolve an ambiguous item, add an item by name,
   check interactions, open an alert, install a KB update, import a model.
3. Stop the capture. **Pass:** zero packets attributed to the app's UID. Screenshot the result.

## Criterion 9: profile encryption

1. Debug build: `adb shell run-as com.airgaprx.app cat databases/shelf.db > shelf.db`
2. `sqlite3 shelf.db ".tables"` must fail with "file is not a database".
3. `strings shelf.db | grep -i -E "warfarin|paracetamol"` must find nothing.
