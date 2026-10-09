# First build and device session: evidence checklist

Status before this checklist: implemented and reviewed in source; core logic provisionally checked
(kotlinc + substitute JUnit); Android integration and device behaviour not yet verified.

## 1. Build and real tests (Windows, from the repo folder)

```
.\gradlew.bat :core:test :app:assembleDebug --console=plain > docs\evidence\build-YYYYMMDD.log 2>&1
```

Keep:
- [ ] `docs/evidence/build-YYYYMMDD.log` (the whole log, including failures if any)
- [ ] `core/build/reports/tests/test/index.html`: the normal Gradle/JUnit report. Save a screenshot and copy the folder to `docs/evidence/core-test-report/`
- [ ] `app/build/outputs/apk/debug/app-debug.apk` (record its SHA-256: `certutil -hashfile app-debug.apk SHA256`)
- [ ] Output of `.\gradlew.bat :app:verifyNoNetworkPermissionDebug`

If the build fails, paste the first error block into the conversation. Record what failed and how it was fixed: this is development-process evidence.

## 2. Device

- [ ] Phone make/model, Android version, RAM (Settings → About phone), screenshot

## 3. Full journey (screenshot each step)

1. [ ] First launch: shelf screen with the Development data banner and Offline badge
2. [ ] Scan a label → Review list
3. [ ] Change one ingredient with **Change**, and resolve an ambiguous item if one appears
4. [ ] Save → item on the shelf
5. [ ] Add a second product (scan or "Add by name")
6. [ ] Check interactions → results (a Major interaction dialog, if your pair has one)
7. [ ] Open an alert's details
8. [ ] Choose a photo from the gallery instead of the camera
9. [ ] Delete a saved item
10. [ ] Force-close and reopen the app → shelf items are still there (encrypted persistence)
11. [ ] ⓘ screen: KB release, checksum, data status, permissions list

Write down anything that did not work as expected, even small things. Failures found here are useful report material.

## 4. Timing log from this session

```
adb pull /sdcard/Android/data/com.airgaprx.app/files/eval/timings.csv docs\evidence\
python scripts\summarise_timings.py docs\evidence\timings.csv
```

(Formal criterion-8 results come later: 30 camera runs on the reference device with reviewed data.)
