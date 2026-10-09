# Evaluation kit

Each file maps to a Milestone 1 criterion (Table 3). Seed rows are examples. **Expand each file to the
size promised in M1 before reporting results**, and record where every expected value comes from.

| Criterion | File / tool | Target size (M1) | How to run |
|---|---|---|---|
| 1 Zero egress | `scripts/check_apk_permissions.sh`, Gradle `verifyNoNetworkPermission*`, packet capture during a full session (PCAPdroid or `tcpdump` on an emulator) | full session | see `egress_protocol.md` |
| 2 Recognition accuracy | `OcrEvaluationTest.recognitionAccuracyAndParserComparison`. Every photo needs `NAME.txt` (transcription) **and** `NAME.ingredients.txt` (expected actives) or the run fails. Reports character accuracy, actives resolved automatically, actives only *offered* as an option (candidate retrieval, not user choice), and expected actives not in the KB (kept as coverage gaps) | 60 labels | `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.parser=deterministic`, then again with `parser=llm` (each run is a fresh process, so cold start and memory peak are per configuration) |
| 3 Low-confidence handling | same harness, `degraded/` photos | 20 captures | same |
| 4 Normalisation | `normalisation_cases.csv`. Verdicts: correct, correct option offered (retrieval only), wrong/missed; expected names not in the KB are reported as coverage gaps and must be escalated | 100 strings | `python kb/build_kb.py && ./gradlew :core:evaluate` |
| 5 Detection recall | `detection_pairs.csv` (expected values from a source independent of the KB). Headline numbers assume **no** user choice: an ambiguous input counts as unresolved. Reports coverage, covered-pair recall/FP, end-to-end recall; a separate line labelled *assumption* shows the result if the first suggestion were always chosen | 50 + 50 pairs | same |
| 6 KB integrity | `KbPackageTest` (unit) + manual sideload of a tampered zip | 1 | `./gradlew :core:test` |
| 7 Severity ranking | `ReasoningTest` + manual check on device | 1 profile | `./gradlew :core:test` |
| 8 Latency + memory | App log `files/eval/timings.csv`: `recognition_ms` (shutter tap → Review list ready: capture + OCR + parse + normalise) and `check_ms` (evaluate + rank) reported **separately**; `run_index` (1 = cold); `peak_rss_kb` with `peak_scope` (per operation if the kernel peak counter could be reset, otherwise since process start) | 30 camera runs on the named reference device, cold and warm separated | `adb pull /sdcard/Android/data/com.airgaprx.app/files/eval/timings.csv`; report median and max per source; corroborate memory with an Android Studio Profiler capture |
| 9 Profile encryption | pull `shelf.db` with `adb` (debug build: `run-as`) and try to open it with `sqlite3` | 1 | see `egress_protocol.md` |
| 10 Usability | 5 participants, task script + SUS or MARS | 5 | `usability_protocol.md` |

Results are written to `results/` so they can be committed and referenced in the report.

**Rules for reportable results**

- Run evaluation only against a KB built with `python kb/build_kb.py --release`. The JVM harness prints a
  warning when the KB is development data; those numbers test the software, not the system.
- Keep evaluation inputs separate from anything used to tune the parser (the unit-test fixtures and the
  seed rows in these CSVs are tuning/examples). Collect the 60 labels, 100 strings and 100 pairs fresh.
- Expected interaction answers must come from a source the KB was not built from (e.g. Medsafe data
  sheets or NZ Formulary). Record the source in the `independent_source` column.
- **Change from M1 for criterion 8.** M1 specified one shutter-to-result time. The Review step between
  recognition and the interaction check is user think-time, so the system is measured as two intervals
  (recognition, check) and the report states this change. The sum of the two medians is a lower bound on
  shutter-to-result, not a measurement of it.
- Report unknown ingredients and uncovered pairs explicitly. The thresholds are the academic acceptance
  targets set in M1; meeting them does not establish clinical safety.
