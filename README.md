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
| **2** | **شبكة السند** — *The chain network* | The chain as a relationship between teacher and student rather than a list of names to recite: a chain view, plus the *Missing Narrator*, *Order the Chain* and *Sanad Builder* drills. | When the matn's mastery reaches **0.5** (or as soon as the chain has been studied before). |
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

The library is a shelf of **books**, and a book can hold **sections** (a chapter, a door), which can hold sections of
their own. There is no ranking of books into levels: the library shows what is there, one book at a time.

| Book | Arabic | Source | Status |
|------|--------|--------|--------|
| **The Forty Hadith of an-Nawawi** | **الأربعون النووية** | The **42 hadiths** of *Al-Arba'in An-Nawawiyya* (Imam an-Nawawi's forty-hadith collection, as numbered in the Arabic edition used), each with its Companion and the takhrij the author gives, and, for 19 of them, the full chain of a narration in the six books | The only book today. |

The shelf says that more books will be added in future updates, and the structure is ready for them: a book is a row of
`catalog_group`, a section is a row whose parent is another group, and each hadith is filed in the deepest group that
names it and carries the `position` it is taught at across the whole catalog. A later catalog adds its books, sections
and hadiths by syncing, and an existing install keeps its progress.

The catalog comes from the verified pipeline described [below](#scholarly-verified-data-pipeline); nothing in the seed
was typed in by hand or produced by a language model. Earlier versions of the app also carried a ten-hadith selection
from Sahih al-Bukhari and the hadiths of *'Umdat al-Ahkam*; the update that made the Forty Hadith the whole catalog
removes them from a device that has them, together with the progress, narrator history and collection entries on them.

---

## Features

The app has three tabs — **Home**, **Library** and **Insights** — and these screens:

- **Home (daily study):** your streak, the reviews that are due with an estimate of the sitting ("3 hadiths · ~4 mins"),
  and the next new hadith to learn. A backlog is offered five at a time rather than as one long list.
- **Library:** the books on a shelf. Open a book to see its sections and its hadiths, with how far you have come through
  all of it, and search the hadith text (with or without tashkeel) inside the book or section you are in. Back goes out
  of a search, then up one section at a time. Open any hadith to read, study or review it, or file it into one of your
  **collections** (*مجموعاتي*), the thematic sets that are yours alone.
- **Hadith reader:** adjustable text size, a transient Tashkeel on/off toggle, and copy (copies exactly the text
  being displayed).
- **Review:** spaced-repetition sessions scheduled by the dual-decay engine, with a per-narrator chain heatmap that
  shows which links of a chain are holding. A long backlog is worked through in batches of five with a pause between
  them, and a stuck layer-1 card can give hints (the Companion's first letter, then the takhrij) without revealing the
  matn. Tapping a locked Sanad explains why the chain waits for the matn.
- **Sanad drills** (open on a hadith once its Sanad tree is, or on every hadith in study for an advanced student):
  *Missing Narrator* (name the narrator who was taken out of a chain of two or more, from four different people —
  never one who is in the chain or a spelling of one, and never a bare «سفيان» or «يحيى» that names no one — with the
  narrator's canonical name and whom he received the hadith from and passed it to shown after the answer),
  *Order the Chain* (put a chain of three or more in the order it was received, Companion at the top, by tapping two
  narrators to swap them or with the arrows; nothing is judged until *Verify Order*, which marks each place green or red
  and rates the round from the mistakes made on the way) and *Sanad Builder* (assemble the chain slot by slot, each
  placement checked at once). A drill a chain is too short for is not offered, and the screen says why.
- **Insights:** the *Narrator Network* graph (narrators your chains share and where each stands), *Mastery Analytics*
  (how well the matn and the sanad hold right now), and *Memory Clocks* (how long your memory holds before a review).
- **Starting point:** asked once at the first launch and in Settings (*Your starting point*): beginner starts from zero; an
  intermediate (memorized the Forty once, now rusty) or an expert can grade each hadith from its opening words (weak /
  good / very good / excellent) or skip and let every hadith start as weak or good. Grading writes the same mastery
  records a review would, never touches a hadith that already has progress, and does not count against the daily limit.
  Library rows and the reader show the word for how well the matn is held.
- **Settings** (opened from Insights): language (Arabic / English, switched at runtime and persisted), *Unlock All
  Layers* for advanced students (opens the Sanad tree and transmission terms on every hadith), JSON **backup export
  and restore** through the system file picker, reset, and an About page.
- **Test on the whole book:** a *Test yourself on the Forty* section on Home runs the self-graded check over every hadith in
  order, ten per sitting with a break to stop at; each answer sets that hadith's status (it can lower one too), and a score
  closes the test.
- **Review now:** a *Review now* button on Home (shown whenever studied hadiths are not due yet, due ones or not) starts a
  sitting over the hadiths held least firmly, five at a time with the usual "enough for today" break, so the app still
  nudges toward coming back tomorrow. Reviews done early feed the scheduler like any other.
- **Pacing guardrail:** the third new hadith of a day asks first ("Consistency Over Volume"); the choice stays yours.

---

## Technical highlights and architecture

### Offline first, with an optional account

The hadith catalog is a bundled asset (`app/src/main/assets/seed_hadiths.json`) loaded into a local Room database on
first launch, and the app is whole without a network or an account; there is no analytics. The only permission is
`INTERNET`, used for the optional Google sign-in (Firebase Authentication through Credential Manager). Signed in, the
learner's progress (mastery records, narrator review events, starting point) is kept in Cloud Firestore under
`users/{uid}` and synced: on sign-in, on opening the app, shortly after a burst of reviews, and from *Sync now* in
Settings. It also syncs when the app is left, on pulling down on Home, and about every twelve hours in the background
(WorkManager, on a network). A hadith's record comes from whichever side reviewed it last, narrator events are added from both sides, and
when a phone and an account both hold progress the first sync asks how to combine them. `firestore.rules` (tested
against the emulator with `npm test` in `firebase-tests/`) lets each learner touch only their own data. Backups are
still plain JSON files saved and restored through the Android document picker.

The library itself can also be updated from the cloud without an app release: an admin account (the Firebase custom claim
`admin`, set with `admin-tools/set-admin.js`) edits `catalog_hadith`, `catalog_narrator` and `catalog_group` in
Firestore, and every app, signed in or not, reads them at start (one small read when nothing changed) and applies them to
Room in one transaction. The bundled catalog stays as the offline start and is uploaded once with
`admin-tools/upload-seed.js`; a row the cloud has written is never overwritten by a bundled catalog update, and an edit
never touches a learner's progress. The edits are made on a small web page (`admin-web/`, Firebase Hosting, Google sign-in,
with a history of every change and a revert; see `admin-web/README.md`).

### Non-destructive catalog migrations

The shipped catalog carries a `catalogVersion`, mirrored by `SeedLoader.CATALOG_VERSION`. When the app opens with a
newer catalog than the database holds, it **syncs** rather than reseeds: new hadiths, narrators and groups are added,
existing hadith rows are never replaced, and the learner's review state is untouched. Removing hadiths is a decision a
catalog version makes once (catalog 4 retired Bukhari and 'Umdat al-Ahkam), not a standing rule that deletes whatever a
catalog omits, so a later catalog can bring a book back. Groups carry no foreign key to or from a hadith, so a catalog
can move a hadith between sections or drop a section without a cascade ever reaching the learner's progress. Schema
changes ship as real Room migrations (the current schema is **v8**, migrating from v4), and `CatalogMigrationTest`,
`CollectionsMigrationTest`, `GroupsMigrationTest` and `ChainSourceMigrationTest` exercise the upgrade paths — from each earlier schema and from a
stale catalog — asserting that the learner's progress survives each one. A backup that holds progress on a retired
hadith still restores: the retired hadiths are skipped and the import says how many.

### Scholarly-verified data pipeline

`tools/import_hadiths.py` is the only way hadith content enters the app.

- It builds `seed_hadiths.json` from **published open datasets pinned by commit and sha256**, using only the Python
  standard library. Texts, chains, and takhrij are never written by hand or generated.
- The Forty Hadith come with the author's own takhrij note, from which the collector is read (*agreed upon*, *narrated
  by Muslim*, or the author's wording where he cites a book outside the two Sahihs); the Companion is the name an-Nawawi
  gives.
- **Chains** are read from the [LK Hadith Corpus](https://github.com/ShathaTm/LK-Hadith-Corpus) (the six books with the
  isnad apart from the matn). An-Nawawi quotes the wording of the books, so a chain is the isnad of the narration whose
  wording he quotes: `tools/choose_narrations.py` takes the narration that holds his words best (at least 0.85 in the
  book of a collector he names, 0.99 in any other), and the build refuses it unless its isnad is one line of named
  narrators ending with his Companion. Nothing is repaired or guessed: a hadith whose best narration cannot be followed
  (a relative named only as «أبيه», parallel chains that never meet) keeps the Companion alone, and the reader names the
  book each chain comes from. 19 of the 42 have a full chain today.
- What *is* curated by hand is limited to the ids that keep a hadith, a narrator and a group the same row from one
  catalog to the next, the books and sections with their titles, and the neutral wording of fields that no dataset
  provides (`tools/data/selection.json`).
- The records it uses are committed as `tools/data/source_snapshot.json`, so the seed is **reproducible offline**
  and can be audited without network access.
- `SeedDataIntegrityTest` re-proves every matn and every narrator in the shipped seed against that snapshot's
  source text on every test run.

### One narrator, one identity

The Forty Hadith spell the same Companion differently (*Ibn 'Umar* in two hadiths, *Abu 'Abd ar-Rahman 'Abd Allah ibn
'Umar ibn al-Khattab* in a third), so the catalog's 123 narrator rows (the Companions and the narrators of the chains) name only **112 different people**.
The app resolves this at read time and changes no row, so the database, its foreign keys and the learner's per-narrator
history stay as the catalog wrote them (no migration):

- `NarratorNormalizer` reads a name the way a narration writes it: «عن أبي هريرة رضي الله عنه» becomes «أبو هريرة»
  (no «عن», blessing or tashkeel; the noun of a kunya in the nominative; «ابن» as «بن»). Titles such as *Amir
  al-Mu'minin* are kept as display metadata, out of the name.
- `NarratorAliases` is the curated table of who is who, kept whole so that a book added later finds its people already
  placed; the groups of books the catalog does not carry today change nothing. Each merge rests on the same kunya, ism
  or lineage, or on the same place in parallel chains of one hadith; look-alikes (*'Abd Allah ibn 'Umar* and *ibn
  'Amr*) are left apart, and `NarratorAliasesSeedTest` pins the exact set of merges against the shipped catalog.
- `NarratorDirectory` applies the table to the rows in use. A person's canonical id is always the id of a real row.
  The narrator network shows one node per person, a person's recall tally adds up his rows, review through a narrator
  covers every spelling, a drill never offers him as his own distractor, and the Companion hint and label are taken
  from the canonical name.

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

The suite holds **1084 tests** (plus 37 rules and admin-page tests in `firebase-tests/` and 8 upload tests in `admin-tools/`): JUnit 4 unit tests for the scheduler, use cases, repositories and the data layer
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
python -I tools/choose_narrations.py <LK clone>   # which narration holds each hadith's chain (needs a clone at the pinned commit)
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
│       │   │   │              #   missinglink, orderchain, graph, heatmap, analytics, settings, navigation, theme
│       │   │   └── di/        # AppContainer (manual dependency injection)
│       │   ├── assets/        # seed_hadiths.json — the bundled hadith catalog (generated)
│       │   └── res/           # strings (ar default, en), fonts (Amiri), themes
│       ├── test/              # JVM + Robolectric unit and Compose UI tests
│       └── androidTest/       # instrumented test scaffold
├── tools/
│   ├── import_hadiths.py      # the data pipeline: fetch / build / audit
│   ├── choose_narrations.py   # picks the narration whose isnad is each hadith's chain
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
  - [`fawazahmed0/hadith-api`](https://github.com/fawazahmed0/hadith-api) — the Arabic edition of the Forty Hadith with
    Tashkeel (`ara-nawawi`); released under the Unlicense.
  - [`ShathaTm/LK-Hadith-Corpus`](https://github.com/ShathaTm/LK-Hadith-Corpus) — the chains of the hadiths, read
    from the isnad of the narration in the six books. The repository states no licence; its README asks for
    citation: Altammami, Atwell & Alsalka, "The Arabic-English Parallel Corpus of Authentic Hadith", IJASAT 2019.
    The app credits it in *About*.

This app is a study aid. It is not a substitute for learning hadith from qualified teachers, and the grading and
takhrij it shows are those of the sources above.
