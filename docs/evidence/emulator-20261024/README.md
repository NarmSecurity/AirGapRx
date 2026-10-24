# Emulator functional check, 24 October 2026

**Emulator, not a phone.** Android 15 (API 35) `google_apis` x86_64 system image, Pixel 6 profile
(`sdk_gphone64_x86_64`), WHPX acceleration, no camera source. Debug APK from the clean build at `56f57b1`
(SHA-256 `6A5C457D1F7441CA4C8F7D30252D8F0AE1006229757333CAFA7871592F27A6F6`), development KB
`2026.10.09-0047-dev`. Driven with `adb` (taps, `uiautomator dump`, `screencap`). These screenshots show the
app working on an emulator; they are not the device evidence the report's Figures 1–10 need.

## What worked

| # | Check | Result | Screenshot |
|---|---|---|---|
| 1 | Install and first launch | Installed; MainActivity displayed after 5.2 s (first cold start, `ActivityTaskManager`); bundled KB installed on first run; no crash in the crash buffer at any step | `01-launch.png`, `02-shelf.png` |
| 2 | Shelf home state | Offline badge, development-data banner, empty state | `02-shelf.png` |
| 3 | Add by name | Warfarin and St John's wort added after choosing the suggestion | `05`, `06`, `08` |
| 4 | Interaction check | St John's wort + Warfarin found as Major; interruptive "Major interaction found" dialog; "Evaluated on-device in 7 ms" | `09-after-check.png`, `10-results.png` |
| 5 | Alert detail | Severity, both ingredients, "Why it happens", "What to do", sources with KB release and compile time, disclaimer | `11-alert-detail.png` |
| 6 | KB & privacy screen | Permissions listed as `CAMERA` and the app's receiver permission only; 45 ingredients, 45 interaction records, 37 unreviewed curated rows, checksum, "Signature verified" | `12-kb-privacy.png` |
| 7 | Persistence | Both items present after `am force-stop` and relaunch | `13-shelf-after-force-stop.png` |
| 8 | Shelf encryption (criterion 9, emulator only) | `databases/shelf.db` (12,288 bytes) has no `SQLite format 3` header and contains neither "warfarin" nor "wort"; across the app's private files only the bundled `kb.sqlite` contains those names | — |
| 9 | Camera permission | Requested on first scan; the only runtime permission | `14-camera-permission.png` |
| 10 | Capture screen | Guidance, shutter, gallery and "By name" controls. Preview is black because the emulator has no camera source (not an app result) | `15-capture-screen.png` |
| 11 | Gallery photo → on-device OCR → review | System photo picker (no storage permission); bundled ML Kit OCR ran on-device; timing logged `{ocr=1441, parse=17, normalise=3} total=1461ms` and a `timings.csv` row; excipients (gelatin, magnesium stearate, silicon dioxide) were not listed | `16-photo-picker.png`, `17-review-from-photo.png` |

## Defects found

1. **Warning text added a medicine (open).** On `test-label-sjw.png` (a rendered label, not a real one), the
   review screen listed **warfarin** as an ingredient. Warfarin appears only in "Warnings: Do not take with /
   warfarin. Consult your doctor." Likely cause: `LexiconLabelParser.kt` line 73 returns to ACTIVE mode at
   every new OCR text block, so a warning that ML Kit splits across blocks loses its WARNING mode. The unit
   test `warningTextDoesNotAddOtherMedicines` keeps the warning inside one block, so it passes. Not yet
   confirmed: the app does not log raw OCR blocks.
2. **"Check interactions" is mostly hidden (fixed in the redesign, below).** The "Scan a label" floating
   action button overlapped the "Check interactions" button in the bottom row of the shelf screen
   (`02-shelf.png`, `08-shelf-two-items.png`). It could only be tapped at its left edge.
3. **Add by name gave no hint (fixed in the redesign).** Add stays disabled until a suggestion is chosen,
   which fits "ask, don't guess"; the dialog now says "Choose a match to add it."

## Visual redesign, same day (`redesign/`)

"Dispensary label" direction, approved by Hossein: shelf items as printed labels, severity as pharmacy
warning stickers, a plain-language verdict at the top of results, Atkinson Hyperlegible type (bundled, SIL
OFL), and one bottom action bar. Rebuilt and re-run on the same emulator with no crash:
`01-shelf`, `02-major-dialog`, `03-results`, `04-alert-detail`, `05-add-dialog`, `06-kb-privacy`,
`07-review`, and dark mode `08-dark-shelf`, `09-dark-results`. Text colour pairs were checked against WCAG
2.1 AA: all pass, the lowest being the Major sticker at 5.88:1. The "Scan a label" button's text now appears
in the `uiautomator` dump. Defect 1 (warning text) is unchanged by the redesign: `07-review.png` still lists
warfarin.

## Not checked here
Real camera capture and OCR accuracy, the retake prompt, the no-interaction card, the optional language model,
TalkBack (the "Scan a label" button's text did not appear in the `uiautomator` dump; unconfirmed whether a
screen reader is affected), latency on a 4 GB reference phone, and the packet capture.
