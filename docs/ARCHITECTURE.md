# Architecture

These are the Milestone 1 views, updated to match the code as built. Diagram sources are in
`docs/uml/*.puml` (PlantUML). Re-render them with `java -jar plantuml.jar -tpng docs/uml/*.puml`.

## Logical view

**Pattern: MVVM.** Compose screens observe immutable `StateFlow`s exposed by ViewModels. ViewModels
hold no reference to views and coordinate domain services. The domain layer lives in a separate,
pure-Kotlin `:core` Gradle module, so the compiler enforces the layer boundary: `:core` cannot import
Android classes.

![Component view](uml/component.png)

Component diagram changes from M1, following marker feedback:

- Connections between components now use provided (ball) and required (socket) interfaces.
- The diagram stays at component level. ViewModels and screens appear only in the class view.

![Class view](uml/class.png)

`CaptureState`, `RecognisedItem`, `Alert` and `Ingredient`, which M1 left undefined, are now fully
specified. Associations match the properties in the code.

## Process view

![Sequence](uml/sequence.png)

The sequence diagram now has activation bars. The two intervention points, re-capture (R2) and
disambiguation (R3), are `alt`/`opt` fragments. A Major alert is a third, interruptive point (R5).

Navigation follows the M1 state machine (Figure 7): Shelf → Capture (self-loop on retake) → Review
(suspends until every item is resolved) → Shelf → Results → Alert detail.

## Physical view

![Deployment](uml/deployment.png)

Connections are now labelled with the protocol used (Binder IPC, JNI, POSIX file I/O, MTP/ADB), not
the function they serve. No network node appears anywhere in the deployment.

## Design changes since Milestone 1 (for the reflection section)

Each change is listed with what M1 committed to, what was built, why, and what still has to be
**measured** before the report can claim the change worked. "Not yet established" means the
evidence does not exist yet; it must not be presented as done.

| M1 commitment | As built | Why | Still to establish |
|---|---|---|---|
| A quantised small language model parses label text (core to the research question: capture + on-device inference + zero egress) | Deterministic section-aware parser + fuzzy lexicon; optional MediaPipe LLM that only *proposes* names, each still normalised against the KB | Safety (the model cannot introduce an ingredient that is not in the vocabulary or confirmed by the user); testability (deterministic path is unit-tested); risk (get the baseline working first) | Whether the LLM adds measurable value: run `OcrEvaluationTest` on the same held-out labels with and without a model and compare ingredient recall, extra items, latency and peak memory. If this is not done, report the LLM as an uncompleted part of the M1 design, not as evaluated |
| NNAPI / GPU delegate for inference | MediaPipe LLM Inference (OpenCL GPU, CPU fallback) | NNAPI is deprecated from Android 15; MediaPipe LLM Inference is itself in maintenance mode with LiteRT-LM as successor | Whether it runs inside the R7 budget on the reference device |
| RxNorm ingredient identifiers as canonical keys (R3) | Name-based slug keys (`st-johns-wort`); RxCUI attached optionally when RxNorm files are supplied | DDInter keys by its own IDs and names, the curated supplement table has no RxCUIs, and RxNorm does not cover many herbal products. Name-based keys plus a synonym table let three sources merge into one entity | Consequence for matching: merges depend on `synonyms.csv` (e.g. acetaminophen → paracetamol) and salt forms are folded into the parent ingredient. Report the number of DDInter names merged vs. left separate, and any duplicates found |
| SQLite FTS5 for serverless ingredient search (R4) | In-memory trigram index (`Lexicon`) over all KB terms | Android's framework SQLite does not guarantee FTS5; the vocabulary is small enough to hold in memory; fuzzy edit-distance matching tolerates OCR errors in a way prefix/token FTS does not | Search time and memory with the full DDInter vocabulary on the reference device (criterion 8 log shows normalise_ms and peak RSS) |
| Five-second shutter-to-result on a 4 GB device (R7) | Two separately logged intervals: recognition (shutter → Review list) and check (evaluate + rank), with cold/warm run index and per-operation peak RSS where the kernel allows resetting the counter | The Review step in between is user time, so one end-to-end number would measure the participant | Actual measurements: 30 camera runs on a named device; medians and max for each interval reported separately; combined processing time only from paired per-session sums; cold vs warm; memory scope stated. Model vs no-model memory compared in separate fresh processes |
| R1 verified by packet capture over a full session **and** manifest inspection (M1 criterion 1) | Same, plus: merged-manifest Gradle check, `aapt2` APK check, runtime EgressGuard | Building revealed that ML Kit's dependencies add INTERNET through manifest merging, so inspecting the *source* manifest alone would have passed a non-compliant build | Packet capture result. Capture **all device traffic**, not just the app's UID. A developer using bundled ML Kit without network permissions observed no app-UID traffic and a Play services "ClientTelemetry" connection that returned a *disabled* status, and asked whether delegated transmission is possible; no successful transmission was shown (googlesamples/mlkit #1076). The capture checks for it rather than assuming either way |
| Single Android module | `:app` + pure-Kotlin `:core` | The compiler enforces the MVVM domain boundary and the domain tests run without a device | — |
| (not planned) | Duplicate-ingredient alert | The same active ingredient in two products (e.g. paracetamol) is a common overdose route, and it falls out of the shelf model | Covered by unit test; show on device |
| (not planned) | Section-aware parsing: excipient and warning sections skipped; every new OCR block resets to ACTIVE, except that a warning whose sentence is unfinished continues into the next block | Excipients (magnesium stearate, iron oxide) raised false mineral interactions; warnings ("do not take with warfarin") added medicines that are not in the product | Effect on ingredient recall on the held-out labels |

### Defects found and fixed during development (reflection evidence)

| Defect | Found by | Fix | Regression test |
|---|---|---|---|
| "Warnings: Do not take with a product that **contains** warfarin" re-opened an active panel at "contains" | Second external review | Generic headers (contains, ingredients, composition) only at line start; no panel header re-opens ACTIVE mid-warning | `LabelParserTest.containsInsideWarningDoesNotReopenActivePanel`, `genericHeaderMidSentenceIsNotAPanel` |
| "Levothyroxine sodium 100 microgram" not recognised: spelled-out units were not stripped | Criterion-4 harness run on seed cases | Added spelled-out units to `Text.stripDoses` | `TextTest.stripDosesRemovesAmountsButKeepsNumericNames` |
| An ambiguous item vanished when one of its options matched an already-resolved ingredient ("Vitamin E" next to "Vitamin K") | External code review | `normaliseAll` only collapses identical text or identical resolved ingredients | `IngredientNormaliserTest.ambiguousItemIsKeptEvenIfAnOptionIsAlreadyResolved` |
| Parsing stopped at the first excipient header, so actives read later were lost | External code review | Section modes that switch at headers and reset at OCR block boundaries | `LabelParserTest.activesAfterExcipientSectionInSameBlockAreKept`, `newOcrBlockEndsAnExcipientSection` |
| Warning text added other medicines to the product | Found while fixing the above | WARNING mode | `LabelParserTest.warningTextDoesNotAddOtherMedicines` |
| A correctly signed *development* KB could be packaged in a release APK | External code review | Review status in the signed manifest; release policy at runtime; Gradle release gate | `KbPackageTest.releasePolicyRejectsSignedDevelopmentData` |
| `app/build.gradle.kts` did not compile: in the release-gate task, `java` in `java.util.Properties` and `java.util.zip.ZipFile` resolved to a Gradle DSL accessor, not the package ("Unresolved reference: util") | First Gradle build (`docs/evidence/build-20261024-190934.log`) | Import both classes at the top of the script | The next build compiled the script and assembled the debug APK (`build-20261024-191001.log`) |
| The "Scan a label" floating button covered most of the "Check interactions" button on the shelf screen | First emulator run (`docs/evidence/emulator-20261024/02-shelf.png`) | One bottom bar replaces the floating button; the filled button is the next step (scan, then check) | Emulator screenshot after the change (`emulator-20261024/redesign/01-shelf.png`); no automated UI test |
| "Warnings: Do not take with" / "warfarin. Consult your doctor." split across OCR blocks added warfarin to the product | Emulator runs on 24 and 27 Oct (`docs/evidence/emulator-20261027/fig05-review.png`); reproduced by a failing Gradle/JUnit test on 28 Oct | WARNING carries into the next OCR block only while the warning sentence is unfinished (the previous line does not end with `.`, `!` or `?`); otherwise a new block resets to ACTIVE as before | `LabelParserTest.warningSplitAcrossOcrBlocksDoesNotAddOtherMedicines` (fails before, passes after under Gradle); `warningContinuesAcrossSeveralOcrBlocks`, `headerAtLineStartInNewBlockAfterWarningStartsNewPanel`; `activeAfterFinishedWarningInNewBlockIsKept` and `midLinePanelAfterFinishedWarningInNewBlockIsKept` guard against the first fix's side effect (below) |
| After a cold start, every shelf item said "Not in the knowledge base, so it can't be checked" in red until the KB finished loading | Emulator run on 27 Oct (`docs/evidence/emulator-20261027/loading-state-after-force-stop.png`) | A row's status separates "loading", "KB unavailable" and "not in the KB"; while loading it says "Loading the knowledge base…" in neutral text | `ShelfRowStatusTest.knownItemIsCheckingWhileKbLoads`, `knownItemCannotBeCheckedWhenKbFailed` (both fail before, pass after; first `:app` unit tests) |
| "Add by name" found nothing for a partial name ("para" for Paracetamol), because the search scored the typed text against whole terms as if it were a misspelling | Emulator run on 24 Oct (`docs/evidence/emulator-20261027/README.md`, finding 2) | `Lexicon.search` for the two manual-entry dialogs: exact, then terms with a word starting with the typed text (3 letters or more), then fuzzy. The normaliser's `lookup` is unchanged, so label text is never completed into a guess | `LexiconTest.searchFindsIngredientFromTypedPrefix`, `searchMatchesTheStartOfAnyWordInATerm` (both fail before, pass after); `searchPutsExactMatchesFirstAndToleratesTypos`, `lookupDoesNotCompletePrefixes` |

For the first six defects, each named regression test was confirmed to fail on the code before its fix and pass after it. Method
disclosure: these before-and-after runs used the Kotlin command-line compiler with a minimal substitute for
JUnit, because the development environment could not download Gradle dependencies, so the "fails before"
half remains provisional. The "passes after" half is verified under Gradle: all 39 `:core` tests passed
with Gradle 8.11.1 and JUnit 4.13.2 on JDK 21 at `eecc9f0` on 24 Oct 2026
(`docs/evidence/core-test-gradle-20261024-185955.log`). These are individual
defects; fixing them is not evidence of overall accuracy, which comes only from the M1 evaluation.

The split-warning regression failed before the fix under Gradle/JUnit with
`warfarin is only mentioned in a split warning: [paracetamol, warfarin]`
(`docs/evidence/warning-split-before-20261028-143550.log` and the matching
`warning-split-before-results-20261028-143550/` JUnit XML). After the fix, all 42 core tests passed
with zero failures, errors or skips (`docs/evidence/warning-split-after-results-20261028-150903/`),
including both existing excipient regressions. The debug build and merged-manifest check passed
(`docs/evidence/build-20261028-151059.log`). These results cover uncommitted changes based on
`3951f2d`; they do not establish held-out accuracy or performance on a phone.

On the Android 15 emulator, the fixed APK read the same synthetic label and Review listed only
St John's wort, with no warfarin (`docs/evidence/warning-split-emulator-review-20261028-151959.xml`).
The installed APK matched the build SHA-256
`DCB88B9C96C87CD6A7228703ABFC242B289174EB2D2FC93767770AE76170D6B8`
(`docs/evidence/warning-split-emulator-provenance-20261028-151959.txt`). The label image's hash also
matched the original test image, and the emulator crash buffer was empty
(`docs/evidence/warning-split-emulator-label-and-crashes-20261028-152027.txt`). The `aapt2` permission
dump still lists only CAMERA and the app's receiver permission
(`docs/evidence/apk-permissions-warning-fix-20261028-151927.txt`). This is a focused regression check,
not an OCR accuracy evaluation; the report and its figures remain unchanged.

That first fix kept WARNING until the next section header, and review of it found a side effect: an active
read in a new block after a *finished* warning was silently dropped unless a panel header began the line
("Hypericum perforatum (St John's Wort) 300 mg" after "Warnings: Do not take with warfarin." gave no
ingredients). A dropped active is invisible on the Review screen, unlike an extra one, so the rule was
narrowed to unfinished warning sentences. The two new tests failed on the first fix
(`docs/evidence/warning-sentence-before-20261028-165332.log` and `warning-sentence-before-results-20261028-165332/`);
after the change all 44 core tests passed with zero failures, errors or skips
(`docs/evidence/warning-sentence-after-results-20261028-165405/`). The first-build script passed, including
the merged-manifest check (`docs/evidence/build-20261028-165424.log`), and `aapt2` lists only CAMERA and the
receiver permission (`docs/evidence/apk-permissions-warning-sentence-20261028-165424.txt`). On the emulator,
the APK with SHA-256 `325DECFA97E1BB654F67702B08133C9C723F31A7A82B0A47DF54CFB6039D3460` read the same test
label and Review listed only St John's wort; the label hash matched and the crash buffer was empty
(`docs/evidence/warning-sentence-emulator-check-20261028-165718.txt`, `warning-sentence-emulator-review-20261028-165718.png`).
The source change is `docs/evidence/warning-sentence-source-20261028-165737.patch`. A warning that OCR
splits after a full stop ("Do not take with warfarin." / "Or aspirin.") is still read as front text; the
Review screen shows any medicine that adds, and held-out labels will measure how often it happens.

The last two rows were fixed together. Before the fixes, with `Lexicon.search` and `rowStatus` reproducing
the old behaviour, the two search tests failed with empty results
(`docs/evidence/name-search-before-20261028-172601.log`, `name-search-before-results-20261028-172601/`) and
the two row-status tests failed (`docs/evidence/shelf-loading-before-20261028-172735.log`,
`shelf-loading-before-results-20261028-172735/`). After them, 48 `:core` tests and 4 `:app` unit tests passed
with zero failures, errors or skips (`docs/evidence/search-and-loading-after-20261028-172833.log` and its two
results folders), the first-build script passed (`docs/evidence/build-20261028-172904.log`), and `aapt2` lists
only CAMERA and the receiver permission (`docs/evidence/apk-permissions-search-and-loading-20261028-172904.txt`).
On the emulator, the APK with SHA-256 `E7C1CD52BF8FD6A032D072E6BC4F1657E91B2020788739BDCFBA9E85856AC47E`
suggested Paracetamol for "para" and St John's wort for "wort", with an empty crash buffer
(`docs/evidence/search-and-loading-emulator-check-20261028-173145.txt`). The loading text could not be seen
there: on that cold start the KB was ready by the first frame, so it rests on the unit tests alone.
