# Evaluation kit

Each file maps to a Milestone 1 criterion (Table 3). Seed rows are examples. **Expand each file to the
size promised in M1 before reporting results**, and record where every expected value comes from.

| Criterion | File / tool | Target size (M1) | How to run |
|---|---|---|---|
| 1 Zero egress | `scripts/check_apk_permissions.sh`, Gradle `verifyNoNetworkPermission*`, packet capture during a full session (PCAPdroid or `tcpdump` on an emulator) | full session | see `egress_protocol.md` |
| 2 Recognition accuracy | `OcrEvaluationTest.recognitionAccuracyAndParserComparison`: label photos + `NAME.txt` transcriptions + `NAME.ingredients.txt` expected actives. Reports character accuracy **and** end-to-end ingredient recall per label, for the deterministic parser and (if a model is imported) deterministic + LLM | 60 labels | `./gradlew :app:connectedDebugAndroidTest` (fails if a photo set or transcriptions are missing) |
| 3 Low-confidence handling | same harness, `degraded/` photos | 20 captures | same |
| 4 Normalisation | `normalisation_cases.csv` | 100 strings | `python kb/build_kb.py && ./gradlew :core:evaluate` |
| 5 Detection recall | `detection_pairs.csv` (expected values from a source independent of the KB) | 50 + 50 pairs | same. Reports coverage, covered-pair recall/FP rate and **end-to-end recall** (uncovered pairs count as misses) |
| 6 KB integrity | `KbPackageTest` (unit) + manual sideload of a tampered zip | 1 | `./gradlew :core:test` |
| 7 Severity ranking | `ReasoningTest` + manual check on device | 1 profile | `./gradlew :core:test` |
| 8 Latency + memory | App records every real shutter tap: shutter→review ms (camera capture + OCR + parse + normalise), check ms, and peak RSS (VmHWM) to `files/eval/timings.csv` | 30 camera runs on the named reference device | `adb pull /sdcard/Android/data/com.airgaprx.app/files/eval/timings.csv`; report median and max, plus Android Studio Profiler capture for peak memory |
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
- Report unknown ingredients and uncovered pairs explicitly. The thresholds are the academic acceptance
  targets set in M1; meeting them does not establish clinical safety.
