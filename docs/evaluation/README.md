# Evaluation kit

Each file maps to a Milestone 1 criterion (Table 3). Seed rows are examples. **Expand each file to the
size promised in M1 before reporting results**, and record where every expected value comes from.

| Criterion | File / tool | Target size (M1) | How to run |
|---|---|---|---|
| 1 Zero egress | `scripts/check_apk_permissions.sh`, Gradle `verifyNoNetworkPermission*`, packet capture during a full session (PCAPdroid or `tcpdump` on an emulator) | full session | see `egress_protocol.md` |
| 2 Recognition accuracy | `app/src/androidTest/.../OcrEvaluationTest.kt` with label photos + transcriptions | 60 labels | `./gradlew :app:connectedDebugAndroidTest` |
| 3 Low-confidence handling | same harness, `degraded/` photos | 20 captures | same |
| 4 Normalisation | `normalisation_cases.csv` | 100 strings | `python kb/build_kb.py && ./gradlew :core:evaluate` |
| 5 Detection recall | `detection_pairs.csv` (from an independent source) | 50 + 50 pairs | same |
| 6 KB integrity | `KbPackageTest` (unit) + manual sideload of a tampered zip | 1 | `./gradlew :core:test` |
| 7 Severity ranking | `ReasoningTest` + manual check on device | 1 profile | `./gradlew :core:test` |
| 8 Latency + memory | `OcrEvaluationTest` timings + Android Studio Profiler | 30 runs | logcat tag `AirGapRx.Timing` |
| 9 Profile encryption | pull `shelf.db` with `adb` (debug build: `run-as`) and try to open it with `sqlite3` | 1 | see `egress_protocol.md` |
| 10 Usability | 5 participants, task script + SUS or MARS | 5 | `usability_protocol.md` |

Results are written to `results/` so they can be committed and referenced in the report.
