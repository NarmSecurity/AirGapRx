# Report figures from the emulator, 30 October 2026 (final build)

**Emulator, not a phone.** Same AVD as `../emulator-20261024/` (Android 15, API 35, `google_apis` x86_64,
Pixel 6 profile, no camera source). Light mode. Commit `744c2ad` on https://github.com/NarmSecurity/AirGapRx, with a clean code tree. When these were
captured the same code was commit `f25b7e6` (`provenance.txt`); the 30 Oct history rewrites changed the ID, not
the code. The installed APK's SHA-256 matched the build:
`59D2C1F7F3195830EC394C35DF80152C600972B84A48F5FFC40EC51C2B292AA8` (`../app-debug-20261030-213115.sha256`).

The same build passed 71 `:core` tests (12 of them `KbPackageTest`), 16 `:app` unit tests and the 2
`MedicationRepositoryTest` tests on this emulator (`../final-verification-20261030-213115.log` and its three
results folders). `aapt2` lists only `CAMERA` and the app's receiver permission
(`../apk-permissions-debug-20261030-213115.txt`). Label hashes, timing lines and the empty crash buffer are in
`provenance.txt`.

The shelf held Warfarin, St John's wort and Paracetamol.

| File | Report figure |
|---|---|
| `fig01-shelf.png` | Figure 1 |
| `fig02-shelf-after-force-stop.png` | Figure 2 (after `am force-stop` and relaunch) |
| `fig03-capture.png` | Figure 3 (preview black: no camera source) |
| `fig04-retake.png` | Figure 4: gallery photo of `../emulator-20261029/test-label-blurred.png`; OCR confidence 32%, nothing added |
| `fig05-review.png` | Figure 5: gallery photo of `../emulator-20261024/test-label-sjw.png`; Review lists only St John's wort |
| `fig06-which-is-it.png` | Figure 6: gallery photo of `../emulator-20261029/test-label-misspelt.png` ("Warfarn 5 mg"); read as "warfarn", Warfarin offered as a choice, no "strength" item |
| `fig07-major-dialog.png` | Figure 7 |
| `fig08-results.png` | Figure 8 (one Major, one Moderate) |
| `fig09-alert-detail.png` | Figure 9 |
| `fig10-kb-privacy.png` | Figure 10 |

All labels are synthetic test images, not photos of real products. Nothing was saved to the shelf from
Figures 4–6; Figures 7–9 check the three medicines already on the shelf.
