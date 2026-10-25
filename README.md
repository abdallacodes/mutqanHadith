# متقن الحديث · Mutqan Hadith

**An offline-first Android app for layered hadith memorization and transmission-chain (*isnad*) mastery.**

[![Build & Test](https://github.com/abdallacodes/mutqanHadith/actions/workflows/build.yml/badge.svg)](https://github.com/abdallacodes/mutqanHadith/actions/workflows/build.yml)
[![Latest release](https://img.shields.io/github/v/release/abdallacodes/mutqanHadith)](https://github.com/abdallacodes/mutqanHadith/releases/latest)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
![Min SDK 30](https://img.shields.io/badge/minSdk-30-3DDC84)
![Kotlin](https://img.shields.io/badge/Kotlin-2.2-7F52FF)

Mutqan Hadith is part of the **Mutqan Suite**: a family of apps that help you master knowledge by the method
scholars used — break the material into layers, verify it through mutual recall, and review it at spaced
intervals. This app applies that method to the memorization of the Prophetic hadith, text *and* chain.

The app ships in Arabic (the default) and English, and every hadith, narrator and study record lives on the device.
It requests **no permissions at all** — in particular, not `INTERNET`.

---

## Table of contents

1. [Pedagogical methodology: the 3-layer memorization engine](#pedagogical-methodology-the-3-layer-memorization-engine)
2. [Curriculum](#curriculum)
3. [Features](#features)
4. [Technical highlights and architecture](#technical-highlights-and-architecture)
5. [Quickstart](#quickstart)
6. [Repository structure](#repository-structure)
7. [License and data attribution](#license-and-data-attribution)

---

## Pedagogical methodology: the 3-layer memorization engine

A hadith is two things: the *matn* (the text) and the *sanad* (the chain of narrators who transmitted it). Memorizing
both at once is where most learners stall, so Mutqan Hadith teaches them in order and lets the learner's own recall
decide when the next layer opens. There is no fixed timetable and no manual "level up" — the layer a hadith is
studied at is derived purely from how stable the learner's memory of it has become.

| Layer | Name | What is studied | When it opens |
|:-----:|------|-----------------|---------------|
| **1** | **لبّ الحديث** — *The core of the hadith* | The matn, the Companion (*Sahabi*) who narrates it, and the *takhrij* (the collectors who recorded it). One focus at a time, with a full-Tashkeel toggle in the reader so the learner can recall the text unvocalized and check against the vocalized form. | Always. |
| **2** | **شبكة السند** — *The chain network* | The chain as a relationship between teacher and student rather than a list of names to recite: a chain view, plus the *Sanad Builder* and *Missing Link* drills. | When the matn's mastery reaches **0.5** (or as soon as the chain has been studied before). |
| **3** | **صيغ الأداء والضبط** — *Transmission formulas and reliability* | The terms of transmission (`حدّثنا`, `أخبرنا`, `عن`, …) at each link, and each narrator's standing (reliability tier). | When the sanad's mastery reaches **0.6**. |

Gating is evaluated from stored stabilities alone, so it is deterministic, testable, and migrates cleanly: users who
already had chain progress before the layers existed keep it (grandfathered through `sanadLastReviewedAt`), and a
hadith's chain is introduced a day after its matn so the two are not learned in the same sitting.

### Dual-decay memory engine

Matn and sanad are scheduled **independently** by `DualDecayScheduler`:

- Each part keeps its own *stability* and follows an FSRS-style power-law forgetting curve aimed at a **0.9** desired
  retention.
- The sanad's clock runs **1.75×** faster than the matn's — chains are forgotten sooner than text, so they are reviewed
  sooner.
- A **weakest-link penalty**: dropping a single narrator cuts the sanad's stability to 30–60 % of its value (scaled by
  the share of the chain that was missed) and caps the review rating at *Hard*.
- A hadith is next due at `min(matnDue, sanadDue)`.

The scheduler is a pure Kotlin class with no Android dependencies, covered by unit tests. Its tunables live in
`SchedulerConfig`.

---

## Curriculum

The library is organized in three levels, each a book of increasing demand.

| Level | Arabic | Source | Status |
|:-----:|--------|--------|--------|
| **1** | **الأصول والأسس** — *Foundations* | The **42 hadiths** of *Al-Arba'in An-Nawawiyya* (Imam an-Nawawi's forty-hadith collection, as numbered in the Arabic edition used) | Complete. |
| **2** | **أحاديث الأحكام** — *Legal rulings* | Hadiths from *Umdat al-Ahkam* | **In progress** — the level exists in the app and the catalog, and its data is being prepared. |
| **3** | **دواوين السنّة المسندة** — *The musnad compendia* | Core chain narrations from *Sahih al-Bukhari* | A **10-hadith selection** from Sahih al-Bukhari today. |

Level 1 and Level 3 data come from the verified pipeline described [below](#scholarly-verified-data-pipeline); nothing
in the seed catalog was typed in by hand or produced by a language model.

---

## Features

The app has three tabs — **Home**, **Library** and **Insights** — and these screens:

- **Home (daily study):** your streak, the reviews that are due, and the next new hadith to learn.
- **Library:** the hadiths by level and chapter; open any of them to read, study or review.
- **Hadith reader:** adjustable text size, a transient Tashkeel on/off toggle, and copy (copies exactly the text
  being displayed).
- **Review:** spaced-repetition sessions scheduled by the dual-decay engine, with a per-narrator chain heatmap that
  shows which links of a chain are holding.
- **Chain drills:** *Sanad Builder* (assemble the chain in order) and *Missing Link* (find the narrator that was
  removed).
- **Insights:** the *Narrator Network* graph (narrators your chains share and where each stands), *Mastery Analytics*
  (how well the matn and the sanad hold right now), and *Memory Clocks* (how long your memory holds before a review).
- **Settings** (opened from Insights): language (Arabic / English, switched at runtime and persisted), JSON **backup
  export and restore** through the system file picker, reset, and an About page.

---

## Technical highlights and architecture

### 100 % offline

The manifest declares no `<uses-permission>` at all. The hadith catalog is a bundled asset
(`app/src/main/assets/seed_hadiths.json`) loaded into a local Room database on first launch; no analytics, no
accounts, no network code. Backups are plain JSON files that the user saves and restores through the Android
document picker.

### Non-destructive catalog migrations

The shipped catalog carries a `catalogVersion`, mirrored by `SeedLoader.CATALOG_VERSION`. When the app opens with a
newer catalog than the database holds, it **syncs** rather than reseeds: new hadiths, narrators and levels are added,
existing hadith rows are never replaced, and the learner's review state is untouched. Schema changes ship as real Room
migrations (the current schema is **v5**, migrating from v4), and `CatalogMigrationTest` exercises the upgrade paths —
from the earlier schema and from a stale catalog — asserting that the learner's progress survives each one.

### Scholarly-verified data pipeline

`tools/import_hadiths.py` is the only way hadith content enters the app.

- It builds `seed_hadiths.json` from **published open datasets pinned by commit and sha256**, using only the Python
  standard library. Texts, chains, and takhrij are never written by hand or generated.
- What *is* curated by hand is limited to *which* hadiths to include (`tools/data/selection.json`) and the neutral
  wording of fields that no dataset provides.
- The records it uses are committed as `tools/data/source_snapshot.json`, so the seed is **reproducible offline**
  and can be audited without network access.
- `SeedDataIntegrityTest` re-proves every matn and every narrator in the shipped seed against that snapshot's
  source text on every test run.

### Stack

| Area | Choice |
|------|--------|
| Language | Kotlin 2.2, Coroutines + Flow |
| UI | Jetpack Compose (BOM-managed), Material 3, Navigation Compose, AppCompat (per-app locale switching) |
| Persistence | Room 2.8 with KSP, versioned migrations |
| Architecture | Clean-architecture layering (`data` → `domain` ← `ui`); MVVM with unidirectional state flow — ViewModels expose an immutable `UiState` through `StateFlow` |
| Dependency injection | A small hand-written container (`di/AppContainer.kt`); no DI framework |
| Typography | Amiri, bundled with the app |
| Build | Gradle 9.6 (wrapper), Android Gradle Plugin 9.4, Gradle version catalog |
| Targets | `minSdk 30`, `targetSdk 37` |

### Testing

The suite exceeds **500 tests**: JUnit 4 unit tests for the scheduler, use cases, repositories and the data layer
(Room against an in-memory database under Robolectric), Compose UI tests via `compose-ui-test-junit4`, localization
parity tests that keep the Arabic and English string tables in step, and the data-integrity tests above. UI tests
are pinned to the Arabic locale so they are deterministic.

### CI/CD

`.github/workflows/build.yml` runs the unit tests and builds the release APK on pushes and pull requests to `main`;
pushing a `v*` tag signs the APK with the project's release key, verifies the signature, and attaches it to a GitHub
Release.

---

## Quickstart

**Prerequisites:** JDK 17 or newer (CI uses Temurin 25; Android Studio's bundled JBR works) and the Android SDK
(platform 37). Set `sdk.dir` in `local.properties`, or let Android Studio create it.

```bash
# Clone
git clone https://github.com/abdallacodes/mutqanHadith.git
cd mutqanHadith

# Run the full unit-test suite
./gradlew testDebugUnitTest

# Run a single test class — e.g. the seed-data integrity proof
./gradlew testDebugUnitTest --tests "com.mutqan.hadith.data.SeedDataIntegrityTest"

# Build an installable debug APK  ->  app/build/outputs/apk/debug/
./gradlew assembleDebug

# Build a release APK  ->  app/build/outputs/apk/release/
./gradlew assembleRelease

# The CI check in one go
./gradlew testDebugUnitTest assembleDebug
```

On Windows use `gradlew.bat` in place of `./gradlew`.

### Release signing

`assembleRelease` signs with the release key only when `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` are set in
the environment and a keystore is found (`$KEYSTORE_FILE`, `app/keystore/release.keystore`, or
`~/.android/mutqan-hadith-release.keystore`). Otherwise it falls back to the debug key, so a local release build still
installs. No keys or passwords are stored in this repository.

### Regenerating the hadith data

```bash
python -I tools/import_hadiths.py build   # regenerate the seed from the committed snapshot and audit it (offline)
python -I tools/import_hadiths.py audit   # check the seed on disk is exactly what the snapshot produces, and is sound
python -I tools/import_hadiths.py fetch   # re-download the pinned source files and refresh the snapshot (network)
```

Whenever the seed's content changes, bump `CATALOG_VERSION` in both `tools/import_hadiths.py` and `SeedLoader.kt` so
existing installs pick it up.

---

## Repository structure

```text
.
├── app/
│   └── src/
│       ├── main/
│       │   ├── java/com/mutqan/hadith/
│       │   │   ├── data/      # Room: entities, DAOs, database & migrations, repositories, seed loader, backup, text utils
│       │   │   ├── domain/    # models, repository interfaces, use cases, dual-decay scheduler, drills, graph, analytics
│       │   │   ├── ui/        # Compose screens + ViewModels: home, library, insights, study, review, sanadbuilder,
│       │   │   │              #   missinglink, graph, heatmap, analytics, settings, navigation, theme
│       │   │   └── di/        # AppContainer (manual dependency injection)
│       │   ├── assets/        # seed_hadiths.json — the bundled hadith catalog (generated)
│       │   └── res/           # strings (ar default, en), fonts (Amiri), themes
│       ├── test/              # JVM + Robolectric unit and Compose UI tests
│       └── androidTest/       # instrumented test scaffold
├── tools/
│   ├── import_hadiths.py      # the data pipeline: fetch / build / audit
│   └── data/                  # selection.json (which hadiths) and source_snapshot.json (the source records used)
├── licenses/                  # third-party license texts (Amiri font, SIL OFL 1.1)
├── .github/workflows/         # CI: tests, signed release APK, GitHub Release on tags
├── release.py                 # patch-release helper: commit, tag, push (the tag triggers CI)
├── build.gradle.kts, settings.gradle.kts, gradle/   # build configuration and version catalog
└── LICENSE
```

---

## License and data attribution

**Source code** is released under the [MIT License](LICENSE).

**Third-party material keeps its own terms:**

- **Amiri** font (`app/src/main/res/font/`) — © The Amiri Project Authors, under the
  [SIL Open Font License 1.1](licenses/OFL-Amiri.txt).
- **Hadith data** is not covered by this project's MIT license; it comes from the following sources, pinned by commit in
  `tools/import_hadiths.py` and recorded in the seed's `provenance` block:
  - [`fawazahmed0/hadith-api`](https://github.com/fawazahmed0/hadith-api) — Arabic editions with Tashkeel
    (`ara-nawawi` and `ara-bukhari`); released under the Unlicense.
  - [`ShathaTm/LK-Hadith-Corpus`](https://github.com/ShathaTm/LK-Hadith-Corpus) — the Sahih al-Bukhari narrations with the
    chain separated from the text, extracted from Sunnah.com and manually checked by its authors. The repository states
    no license and asks for citation: Altammami, S., Atwell, E. & Alsalka, A., *"The Arabic-English Parallel Corpus of
    Authentic Hadith"*, International Journal on Islamic Applications in Computer Science and Technology (IJASAT), 2019.

This app is a study aid. It is not a substitute for learning hadith from qualified teachers, and the grading and
takhrij it shows are those of the sources above.
