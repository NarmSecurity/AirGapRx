#!/usr/bin/env bash
# Evaluation criterion 1 (static): list every permission in a built APK and fail on any network permission.
# Usage: scripts/check_apk_permissions.sh app/build/outputs/apk/release/app-release.apk
set -euo pipefail
APK="${1:?usage: $0 path/to/app.apk}"
AAPT2="$(ls -d "${ANDROID_HOME:-$ANDROID_SDK_ROOT}"/build-tools/*/aapt2 | sort -V | tail -1)"
echo "Permissions declared by $APK:"
"$AAPT2" dump permissions "$APK" | tee /tmp/apk_permissions.txt
if grep -Eq "android.permission.(INTERNET|ACCESS_NETWORK_STATE|ACCESS_WIFI_STATE|CHANGE_NETWORK_STATE|CHANGE_WIFI_STATE)" /tmp/apk_permissions.txt; then
  echo "FAIL: network permission present (R1 violated)"; exit 1
fi
echo "PASS: no network permission declared (R1)"
