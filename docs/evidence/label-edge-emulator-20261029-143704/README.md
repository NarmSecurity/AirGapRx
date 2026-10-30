# Label-edge fixes: focused emulator checks, 29 October 2026

Android 15 / API 35, AVD `AirGapRx_API35`, emulator only. The APK contains uncommitted changes
based on `250d845`. Its SHA-256 is
`6B0EB6A5D1004C090557F788722C92AD9D255215D4A5BB1C4407D2480F10A73E`.
The installed APK matches this hash (`provenance.txt`). `source.patch` records the source/test
changes; `source-and-inputs.sha256` records their hashes and the four input image hashes, all of
which matched the images on the emulator.

## Before and after tests

- Before production changes, 52 core tests ran and four new regressions failed:
  `../label-edge-before-20261029-143212.log` and
  `../label-edge-before-results-20261029-143212/`. The failures showed the joined dose remaining in
  the name, its normalisation returning `Unrecognised`, a `strength` ingredient being added, and
  aspirin being added from a warning continuation.
- An intermediate run failed a guard whose expected text included `herb`, a word existing
  packaging-word cleanup intentionally removes. The guard was corrected to use `complex`;
  the failed log and XML were kept (`../label-edge-after-20261029-143428.log`).
- Final result: 56 core tests and 4 app tests, zero failures, errors or skips:
  `../label-edge-after-20261029-143538.log`,
  `../label-edge-after-core-results-20261029-143538/` and
  `../label-edge-after-app-results-20261029-143538/`.
- `../build-20261029-143626.log`: first-build script succeeds, including the merged-manifest
  check. `apk-permissions.txt`: `aapt2` lists only CAMERA and the app's receiver permission.

## Emulator results

| Input | Observed Review result | Evidence |
|---|---|---|
| Original `../emulator-20261029/test-label-misspelt.png`, previously read with a joined dose | One ambiguous item, read as `warfarn`, offering Warfarin plus "None of these"; saving remains disabled pending a decision | `joined-dose-review.png`, `joined-dose-review.xml` |
| Original `../emulator-20261029/test-label-misspelt-2.png`, containing `Strength: 5 mg` | One ambiguous `warfarn` item; no separate `strength` item | `strength-review.png`, `strength-review.xml` |
| `warning-continuation-label.png`: "Do not take with warfarin." followed by a separately spaced "Or aspirin." | St John's wort only; no warfarin or aspirin | `warning-review.png`, `warning-review.xml` |
| Original `../emulator-20261024/test-label-sjw.png` with its unfinished split warning | St John's wort only; no warfarin | `original-warning-review.png`, `original-warning-review.xml` |

The additional warning image is synthetic, rendered by `generate_warning_label.py`; it is not a
photo of a real product. OCR block IDs are exercised directly by the unit regressions; raw ML Kit
blocks were not logged during these emulator checks. These checks establish the visible result
for these images, not general OCR accuracy or performance on a phone.

No item was saved from Review. The before/after shelf UI dumps contain the same three products:
Paracetamol, St John's wort and Warfarin (`shelf-before.xml`, `shelf-after.xml`). The app was left
on Shelf. The existing emulator was reused and left running. Its crash buffer was empty.

`picker-for-joined-dose.png` is an intermediate capture-screen image taken during transition;
`picker-ready.png` is the settled photo picker. Neither is a report figure.

## Remaining limits and report work

Warning continuation uses punctuation and explicit whole-word "or" / "and" cues. Other warning
wording/layouts and missing punctuation remain evaluation risks. The fix preserves numeric
ingredient names and unknown actives in the tested cases; held-out accuracy is still unmeasured.

The report, its Word/PDF exports and existing figures were not changed. A later report update
needs the current test count (56 core, 4 app), defect count (14, of which 12 have named regression
tests), the removal of the extra `strength` item in Figure 6, and refreshed build provenance for
any new captures.
