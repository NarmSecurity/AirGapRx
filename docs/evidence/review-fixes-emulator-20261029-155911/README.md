# Seven review fixes: evidence, 29 October 2026

Uncommitted changes based on `250d845`, including the earlier uncommitted label-edge fixes.
No report, existing report figure or export was changed. No commit or remote operation was made.

## Automated verification

- Before fixes: `../review-fixes-before-20261029-154511.log` and matching core/app XML folders:
  65 core tests, 9 failures; 12 app tests, 8 failures. Model import was first extracted with its
  existing delete-before-open behaviour so its failures test the original filesystem behaviour.
- A parser guard caught an intermediate over-broad warning rule:
  `../review-fixes-intermediate-20261029-155318.log` (68 core tests, 1 failure; 16 app tests pass).
  The corrected rule allows a finished warning to be followed by a Contains panel and preserves
  explicit-active context through generic panel headers.
- Before the transaction fix: `../product-save-before-20261029-155455.log` and matching device XML.
  Two isolated SQLCipher tests ran; the rollback test failed because the first new ingredient
  remained after the second insert failed. The success test passed.
- Final: `../review-fixes-after-20261029-155826.log` and matching core/app/device XML folders:
  **69 core tests, 16 app unit tests and 2 emulator instrumentation tests pass**, zero failures.
  The instrumentation fixture checks its isolated database path before any insert, and uses
  separate key preferences and storage; it does not write to the normal shelf database.
- `../build-20261029-160033.log`: first-build script and merged-manifest check pass.
  `apk-permissions.txt`: APK has CAMERA and the app receiver permission only.

Earlier failed setup/build logs remain: offline instrumentation dependencies were initially
missing, and `product-save-before-20261029-155147.log` records a constructor compile error fixed
by qualifying `kotlin.runCatching`. These are not behavioural test failures.

## Emulator UI results

The installed APK hash matches
`6A48D969E9CC3B6BEB7F6FAAF394BDE7FC60AB1DDA262FEF48C0FC65967A3251`
(`provenance.txt`). Android 15/API 35 emulator only; input labels are synthetic.

- `first-label-review.png/xml`: split warning ending "contains warfarin." yields Paracetamol only.
- `second-label-review.png/xml`: the explicitly declared calcium hydrogen phosphate stays visible
  as an unknown requiring a decision, alongside recognised Paracetamol and a spotted Calcium
  candidate. It is no longer silently removed. Nothing from either scan was saved.
- `corrupt-update-rejected.png/xml`: selecting the truncated ZIP displays an update-rejected
  message and the prior KB remains available. The crash buffer is empty.
- Model replacement has eight JVM unit tests covering null/throwing providers, interrupted copy,
  empty/unsafe input, atomic same-name replacement and publication failure. Validation proves
  readable, nonempty bytes, not MediaPipe model compatibility; it has not been tested with a
  real model in this session.

## Instrumentation cleanup and shelf restoration

The Gradle runner's default cleanup uninstalled the development APK and removed its existing
three-item test shelf. The database test fixtures were isolated, but runner cleanup was not.
`shelf-after-reinstall.xml` records the empty shelf. The known Paracetamol, St John's wort and
Warfarin entries were restored through Add by name (`shelf-restored.xml`); original IDs,
timestamps and ordering could not be recovered.

The project now enables `android.injected.androidTest.leaveApksInstalledAfterRun=true`.
The option was verified in the installed AGP 8.7.3 BooleanOption implementation. The connected
tests passed again (`../product-save-preserve-20261029-160557.log`), and
`shelf-preserved-after-tests.xml` confirms the installed app and restored shelf survived cleanup.
The final shelf is in `shelf-final.xml`; no scanned item or corrupted update was saved.

## Remaining limits

Warning detection remains a text heuristic; held-out accuracy and phone behaviour are unmeasured.
The tests and these focused UI checks do not establish clinical safety. A later report update
needs current counts, code/build provenance, and screenshots where behaviour changed.
