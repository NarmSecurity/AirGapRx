# Report figures from the emulator, 27 October 2026

**Emulator, not a phone.** Same AVD as `../emulator-20261024/` (Android 15, API 35, `google_apis` x86_64,
Pixel 6 profile, no camera source). The installed APK's SHA-256 was checked on the device and matched the
final build of commit `f81afa3`:
`5b9358aebcfee4caa4ed5400a7f8faf9a81328657fac88e4db7c5ae8a77187ea`
(`../app-debug-20261024-220407.sha256`). Light mode.

The shelf held Paracetamol, St John's wort and Warfarin. Paracetamol had been added on the emulator
between sessions.

| File | Report figure |
|---|---|
| `fig01-shelf.png` | Figure 1 |
| `fig02-shelf-after-force-stop.png` | Figure 2 (after `am force-stop` and relaunch) |
| `fig03-capture.png` | Figure 3 (preview black: no camera source) |
| `fig05-review.png` | Figure 5 (gallery photo of `../emulator-20261024/test-label-sjw.png`) |
| `fig07-major-dialog.png` | Figure 7 |
| `fig08-results.png` | Figure 8 (one Major, one Moderate) |
| `fig09-alert-detail.png` | Figure 9 |
| `fig10-kb-privacy.png` | Figure 10 |

Other captures: `camera-permission-prompt.png`, `ocr-in-progress.png`, `fig01-shelf-first-capture.png`
(superseded) and `loading-state-after-force-stop.png` (see finding 1).

## Findings (open, not fixed)

1. **Misleading text while the KB loads.** Immediately after a cold start, every shelf item showed
   "Not in the knowledge base, so it can't be checked" in red until the KB finished loading
   (`loading-state-after-force-stop.png`). The rows have no resolved ingredient during `KbState.Loading`.
2. **Partial names don't match in "Add by name".** On 24 Oct, typing "para" gave "No match in the
   knowledge base", although Paracetamol is in the KB.
3. **The warning-text defect is still open:** `fig05-review.png` lists Warfarin, read from the label's
   warning.
4. **Emulator timings are not representative.** OCR of the same test label took 15,274 ms on this cold
   emulator (`AirGapRx.Timing` log line), against 1,441 ms on 24 Oct. Neither is a criterion 8 result.

`../app-dependencies-debugRuntimeClasspath-20261027.txt` (Gradle `:app:dependencies`) shows
`com.google.mlkit:text-recognition:16.0.1` → `play-services-mlkit-text-recognition-common` →
`com.google.android.datatransport:transport-backend-cct:2.3.3`, the library whose manifest requests
`INTERNET` in the merger report.
