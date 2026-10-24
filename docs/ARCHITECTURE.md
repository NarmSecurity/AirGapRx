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
| (not planned) | Section-aware parsing: excipient and warning sections skipped, sections reset at OCR block boundaries | Excipients (magnesium stearate, iron oxide) raised false mineral interactions; warnings ("do not take with warfarin") added medicines that are not in the product | Effect on ingredient recall on the held-out labels |

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

Each regression test above was confirmed to fail on the code before its fix and pass after it. Method
disclosure: these before-and-after runs used the Kotlin command-line compiler with a minimal substitute for
JUnit, because the development environment could not download Gradle dependencies, so the "fails before"
half remains provisional. The "passes after" half is verified under Gradle: all 39 `:core` tests passed
with Gradle 8.11.1 and JUnit 4.13.2 on JDK 21 at `eecc9f0` on 24 Oct 2026
(`docs/evidence/core-test-gradle-20261024-185955.log`). These are individual
defects; fixing them is not evidence of overall accuracy, which comes only from the M1 evaluation.
