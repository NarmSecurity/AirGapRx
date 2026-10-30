# KB/engine review probe provenance

These are standalone JVM source-launcher reproductions against the current compiled core JAR,
not Gradle/JUnit results. No production, test-source, report or existing evidence files were edited.

- `kb-probe-20261029-153612.java` and `.txt`: successful execution, showing that truncated input
  escapes `KbInstaller.install` as `EOFException` rather than `InstallResult.Rejected`.
- `engine-probe-20261029-153628.java` and `.txt`: successful execution, showing Major severity
  paired with the first Minor record's management, plus suppression of duplicate alerts when
  two distinct shelf entry IDs share the same product-name string.
- `kb-probe-20261029-153552.txt` and `engine-probe-20261029-153552.txt`: failed evidence-copy
  attempts. The per-call sandbox TEMP directory did not contain the earlier source files; no
  probe executed in these attempts. They are retained as failed setup logs, not bug evidence.

The model-path example is a lexical path-normalisation demonstration only, not an Android
exploit. The `.task` suffix requirement limits writable filenames. No claim of code execution,
secret overwrite, other-app access or Android sandbox escape was tested or established.
