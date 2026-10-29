# Report figures from the emulator, 29 October 2026

**Emulator, not a phone.** Same AVD as `../emulator-20261024/` (Android 15, API 35, `google_apis` x86_64,
Pixel 6 profile, no camera source). Light mode. Commit `cf4eb4f` with a clean code tree; the installed APK's
SHA-256 matched the build: `E7C1CD52BF8FD6A032D072E6BC4F1657E91B2020788739BDCFBA9E85856AC47E`
(`../app-debug-20261028-172904.sha256`; the rebuild at `cf4eb4f` in `../build-20261029-124326.log` was up to
date). Hashes of every label image, the timing lines and the empty crash buffer are in `provenance.txt`.

At `cf4eb4f`: 48 `:core` tests (`../core-test-results-20261029-124326/`) and 4 `:app` unit tests
(`../app-unit-test-results-20261029-124326/`) pass, and `aapt2` lists only `CAMERA` and the app's receiver
permission (`../apk-permissions-debug-20261029-124326.txt`).

The shelf held Paracetamol, St John's wort and Warfarin.

| File | Report figure |
|---|---|
| `fig01-shelf.png` | Figure 1 |
| `fig02-shelf-after-force-stop.png` | Figure 2 (after `am force-stop` and relaunch) |
| `fig03-capture.png` | Figure 3 (preview black: no camera source) |
| `fig04-retake.png` | Figure 4: gallery photo of `test-label-blurred.png`, a Gaussian-blurred copy of `../emulator-20261024/test-label-sjw.png`; OCR confidence 32%, nothing added |
| `fig05-review.png` | Figure 5: gallery photo of `test-label-sjw.png`; Review lists only St John's wort (warfarin from the warning is no longer added) |
| `fig06-which-is-it.png` | Figure 6: gallery photo of `test-label-misspelt-2.png`, a rendered label reading "Warfarn" |
| `fig07-major-dialog.png` | Figure 7 |
| `fig08-results.png` | Figure 8 (one Major, one Moderate) |
| `fig09-alert-detail.png` | Figure 9 |
| `fig10-kb-privacy.png` | Figure 10 |

All label images are synthetic test images, not photos of real products. Nothing was saved to the shelf
from Figures 4–6.

## Findings (open, not fixed)

1. **A dose joined to a name is not stripped.** On `test-label-misspelt.png` ("Warfarn 5 mg"), OCR read
   "warfarn5 mg", and Review offered no candidate: "Not in the knowledge base"
   (`misspelt-label-dose-joined-review.png`). The item stays visible for the user to resolve, so nothing is
   silently dropped, but a near-miss name with a joined dose is not matched. Figure 6 uses a second label
   with the strength on its own line.
2. **A field label inside an ingredient panel is asked about.** On `test-label-misspelt-2.png`, "Strength:
   5 mg" inside the "Each tablet contains:" panel gave an unknown item "strength" (Figure 6). The user can
   mark it "Not an ingredient".
3. **The loading text was again not visible:** the knowledge base was ready by the first frame.
