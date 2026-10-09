<h1 align="center">AirGap Rx</h1>

<p align="center">
  <b>An offline drug and supplement interaction checker for Android.</b><br>
  Photograph a label. Find interactions. Nothing leaves your phone.
</p>

<p align="center">
  <img alt="Platform" src="https://img.shields.io/badge/platform-Android%2010%2B-3DDC84">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.1-7F52FF">
  <img alt="Network permission" src="https://img.shields.io/badge/network%20permission-none-0F3D3E">
  <img alt="CI" src="https://github.com/OWNER/REPO/actions/workflows/android.yml/badge.svg">
</p>

<!-- Replace OWNER/REPO above once the repository exists. Add a demo GIF here: docs/media/demo.gif -->
<p align="center"><i>Demo GIF and screenshots go here (docs/media/)</i></p>

> **Not medical advice.** AirGap Rx is a university prototype (AUT COMP826, Milestone 2). It gives
> information only. Do not stop or change any medicine without talking to a pharmacist or prescriber.

## Contents

- [Why](#why)
- [Features](#features)
- [How it works](#how-it-works)
- [Requirements](#requirements)
- [Install and run](#install-and-run)
- [Using the app](#using-the-app)
- [Testing and evaluation](#testing-and-evaluation)
- [Knowledge base](#knowledge-base)
- [Project structure](#project-structure)
- [Limitations](#limitations)
- [Licence and acknowledgements](#licence-and-acknowledgements)

## Why

About half of adults take at least one dietary supplement, and the people who take the most supplements
are often also the people taking several prescription medicines (Kantor et al., 2016). Online interaction
checkers send your full medicine list to a server, and they cover herbal and supplement ingredients unevenly.

AirGap Rx moves the whole check onto the phone. The app **declares no network permission**, so Android
does not let the app open a network connection itself, and you can confirm the permission list in the APK.
That is a strong property but not a complete proof: in principle a library could hand data to another app
over Android IPC (a developer has asked whether bundled ML Kit can do this via Google Play services; it has
not been demonstrated). So the evaluation captures **all device traffic** during and after a full session
rather than relying on the permission list alone. See
[`docs/evaluation/egress_protocol.md`](docs/evaluation/egress_protocol.md).

## Features

| | Feature | Requirement (M1) |
|---|---|---|
| 📷 | **Label capture** with on-device OCR (CameraX + bundled ML Kit). Low-confidence captures ask for a retake and add nothing. | R2 |
| 🔎 | **Ingredient recognition**: section-aware parser (skips excipient and warning sections), fuzzy lexicon spotting, and an *optional, not yet evaluated* on-device language model that can only propose names | R2, R3 |
| ❓ | **Review before saving**: unclear matches ("warfarn") and unknown actives ("ashwagandha") must be decided by the user; close fuzzy matches are accepted provisionally and marked "Matched approximately" with a Change action | R3 |
| 💊 | **Encrypted medication shelf**: SQLCipher, with the key wrapped by Android Keystore | R8 |
| ⚠️ | **Pairwise interaction check** covering prescription medicines, OTC products, supplements, herbs and foods | R4, R6 |
| 🚦 | **Severity ranking**: Major interactions interrupt and must be acknowledged. Minor ones are collapsed. | R5 |
| 🔁 | **Duplicate ingredient warning** when the same active ingredient appears in two products (e.g. two paracetamol products) | R5 |
| 📚 | **Signed, versioned knowledge base**: shows release, compile date, SHA-256 and review status; accepts offline updates from a file; release builds refuse unreviewed or development data | R4 |
| 🛡️ | **EgressGuard**: the app refuses to run if a build ever declares a network permission | R1 |

## How it works

```mermaid
flowchart LR
    A[Camera / photo] -->|frame| B[OCR<br/>ML Kit, on-device]
    B -->|lines + confidence| G{Confidence<br/>gate}
    G -->|low| A
    G -->|ok| C[LabelParser<br/>section + lexicon + optional LLM]
    C -->|mentions| D[IngredientNormaliser]
    D -->|ambiguous / unknown| U[User review]
    U --> S[(Encrypted shelf)]
    D -->|resolved| S
    S --> E[InteractionEngine]
    K[(Signed KB<br/>SQLite)] --> E
    E --> R[SeverityRanker]
    R --> X[ExplanationGenerator] --> V[Results]
```

The architecture is **MVVM** (Jetpack Compose views → ViewModels → domain services). All domain logic
lives in a pure-Kotlin `:core` module that has no Android dependencies, so it can be unit-tested on any
machine. See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the component, class, sequence and
deployment views.

## Requirements

| | Minimum |
|---|---|
| Phone | Android 10 (API 29) or later, 4 GB RAM, rear camera with autofocus |
| Build | Android Studio Ladybug (2024.2) or newer, JDK 17+ (Android Studio's bundled JDK works) |
| Knowledge base tools | Python 3.9+, `pip install cryptography` |

## Install and run

### Option A: install the APK

> **Status:** the Android module has not yet been built successfully; the first CI run is pending. Until
> then use Option B. Debug APKs contain the **development knowledge base** (labelled in the app) and are for
> software testing only.

1. Download `app-debug.apk` from the latest **Actions** run (artifact `airgaprx-debug-apk`). Release APKs are only
   produced for `v*` tags, and only when the bundled knowledge base passed review.
2. On the phone, allow installs from your browser or file manager, then open the APK.
3. Open **AirGap Rx**. When asked, allow camera access. That is the only permission the app requests.

### Option B: build from source

```bash
git clone https://github.com/OWNER/REPO.git
cd REPO

# 1. (Optional) rebuild the knowledge base. A signed development KB is already bundled.
pip install cryptography
python kb/build_kb.py

# 2. Run the domain unit tests (no device needed)
./gradlew :core:test

# 3. Build and install on a connected phone or emulator
./gradlew :app:installDebug
```

Or open the folder in Android Studio, wait for Gradle sync, select the `app` configuration and press **Run ▶**.

> **Windows:** use `gradlew.bat` instead of `./gradlew`.

### Optional: on-device language model

The rule-based parser works without a model. To try the language-model parser, download a MediaPipe
`.task` model (for example Gemma 3 1B int4) **on your computer**, copy it to the phone, then go to
**ⓘ → Label-reading model → Import .task model**. The app copies the file into private storage. It
never downloads anything itself.

## Using the app

1. **My medicines** is the home screen. Tap **Scan a label**.
2. Fill the frame with the ingredients panel and tap the shutter. You can also choose a photo, or add a medicine by name.
3. **Check what was read**: confirm each ingredient, choose the right one where the app isn't sure, and decide what to do with anything not in the knowledge base. Saving stays disabled until every item is resolved.
4. Add all your products, then tap **Check interactions**.
5. Major interactions show a dialog first. Tap any result to see why it happens, what to do, and where the information comes from.
6. **ⓘ** shows the knowledge-base version and checksum, confirms the app has no network permission, and lets you install a KB update from a file.

## Testing and evaluation

| Command | What it checks |
|---|---|
| `./gradlew :core:test` | 39 JVM unit test definitions (see *Verification status* below): text normalisation, fuzzy matching, parser (incl. excipient filtering), normaliser escalation, ranking, duplicate detection, section-aware parsing, KB signature, checksum, downgrade, tamper and review-status rejection |
| `./gradlew :app:verifyNoNetworkPermissionRelease` | Fails if any network permission survives manifest merging (R1) |
| `scripts/check_apk_permissions.sh <apk>` | Lists the APK's permissions with `aapt2` and fails on any network permission (R1) |
| `python kb/build_kb.py && ./gradlew :core:evaluate` | Criteria 4 and 5: normalisation accuracy and detection recall against the compiled KB |
| `./gradlew :app:connectedDebugAndroidTest` | Criteria 2 and 3 on a device: character accuracy, per-label ingredient recall (with/without LLM), retake handling |
| scan 30 labels in the app, then `adb pull …/files/eval/timings.csv` | Criterion 8: shutter-to-review and check latency, peak memory (VmHWM) |
| `./gradlew :app:assembleRelease` | Fails unless the bundled KB was built with `build_kb.py --release` |

### Verification status

- **Implemented and reviewed in source** (two external code reviews).
- **Core logic: provisionally checked.** The 39 `:core` tests have so far been run only with the Kotlin
  command-line compiler against a minimal substitute for JUnit (the build environment could not download
  Gradle dependencies). A normal Gradle/JUnit test report has not yet been produced.
- **Android integration and device behaviour: not yet verified.** The `:app` module has not yet been compiled.

GitHub Actions runs the unit tests, both manifest checks, the debug APK build and the `aapt2` check on every
push, and the release gate on `v*` tags.
The full evaluation plan is in [`docs/evaluation/`](docs/evaluation/README.md).

## Knowledge base

| Source | Used for | Licence |
|---|---|---|
| [DDInter](https://ddinter.scbdd.com/) (Xiong et al., 2022; Tian et al., 2025) | Drug–drug pairs with severity | CC BY-NC-SA 4.0 |
| Curated supplement table (`kb/sources/supplement_interactions.csv`) | Herbal, supplement and food interactions, each with a cited source | see [`REFERENCES.md`](kb/sources/REFERENCES.md) |
| RxNorm (optional) | Ingredient identifiers (RxCUI) and US brand names | NLM terms |
| `synonyms.csv` | NZ brand names (Marevan, Eltroxin, Cartia…) and Latin names | project |

The repository includes a **development KB**. Its drug–drug pairs come from a small fixture labelled
`DEV-FIXTURE`. To build the real KB, download the DDInter CSVs into `kb/sources/ddinter/` and run
`python kb/build_kb.py --release`. See [`kb/README.md`](kb/README.md).

## Project structure

```
├── app/                         Android app (MVVM, Jetpack Compose)
│   └── src/main/kotlin/com/airgaprx/app/
│       ├── ui/                  Views + ViewModels: shelf, capture, review, results, kb (about)
│       ├── data/                KnowledgeBaseRepository, MedicationRepository (SQLCipher), SqliteKnowledgeBase
│       ├── platform/            MlKitOcrService, MediaPipeLlmParser, EgressGuard, KeystorePassphrase
│       └── AppContainer.kt      Manual dependency wiring
├── core/                        Pure-Kotlin domain layer, no Android dependencies
│   └── src/main/kotlin/com/airgaprx/core/
│       ├── recognition/         LabelParser, LexiconLabelParser, HybridLabelParser
│       ├── normalise/           Text, Lexicon (trigram index), IngredientNormaliser
│       ├── reasoning/           InteractionEngine, SeverityRanker, ExplanationGenerator
│       ├── kb/                  KnowledgeBase, KbVerifier, KbInstaller (signed packages)
│       └── pipeline/            RecognitionPipeline, timings
├── kb/                          Knowledge-base compiler + curated sources
├── docs/                        Architecture, UML, evaluation kit
├── scripts/                     APK permission check
└── .github/workflows/           CI
```

## Limitations

- Android only (a deliberate trade-off, see M1 Section 3.4).
- Coverage is limited to the compiled sources. The most complete commercial compendia can't be redistributed, so they can't be used offline.
- The curated supplement table is a draft until every row is reviewed against its source (`reviewed=yes`); rows
  without a supporting source are removed, not just re-flagged.
- The optional language-model parser has not been evaluated yet.
- No dose-level checking, pharmacogenomics or clinician workflows (out of scope, M1 Section 1.2).
- Interaction severity is shown as the source records it. It has not been clinically validated for this app.

## Licence and acknowledgements

Code: MIT. Compiled knowledge-base data that includes DDInter: CC BY-NC-SA 4.0 (non-commercial).

Built by Hossein Mohseni Mofrad for COMP826 Mobile Systems Development, Auckland University of Technology.
Claude (Anthropic) was used as a coding assistant. See the AI-use statement in the report.
