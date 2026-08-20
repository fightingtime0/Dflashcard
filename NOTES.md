# D'flashcard — project notes

Android flashcard app with SM-2 spaced repetition. Offline-first, no backend.
Built 2026-08-18. Phases 1–3 complete.

## Origin

Replaces `smart_flashcard_helper.py`, a CustomTkinter desktop prototype (still in the repo for
reference, no longer the product). Its data lives in `flashcards_data.json` — 4 decks, 101 cards of
Japanese and Korean vocabulary with example sentences. That file is copied to
`app/src/main/assets/seed.json` and imported on first launch.

The desktop app was **not** spaced repetition: it had no dates, only a `level` 0/1/2 that sorted the
queue at session start, so reopening it meant reviewing everything again. "Bisa" and "Mudah"
behaved identically. Replacing that was the point of the rewrite.

## Stack

Kotlin + Jetpack Compose (Material 3) · Room · WorkManager · kotlinx.serialization
Kotlin 2.2.21 · AGP 8.13.2 · Compose BOM 2026.06.01 · Room 2.8.4 · minSdk 24 / targetSdk 36

Native was chosen because the JDK 17 + Android Studio + SDK toolchain was already installed, while
Flutter/React Native were not — and native gives the best CJK font and IME behaviour, which matters
for this app's content.

## Source layout

Six Kotlin files, one module, no DI framework, no navigation library.

| File | Role |
|---|---|
| `Data.kt` | Room entities, DAO, database |
| `Scheduler.kt` | SM-2, pure functions |
| `Transfer.kt` | Import (legacy JSON / backup / CSV) and backup export |
| `Speech.kt` | TTS + script→voice detection |
| `Reminder.kt` | WorkManager worker, scheduling, notification |
| `Vm.kt` | ViewModel |
| `MainActivity.kt` | All Compose UI |

## Decisions worth not re-litigating

**Package id `com.dflashcard.app` is permanent** once published to Play. Confirmed by the user.

**targetSdk 36** — Play requires it for new submissions from around 2026-08-31. This is why the
project is on AGP 8.13.2 rather than 8.9.x: AGP 8.9 caps `compileSdk` at 35, and downgrading also
drags Compose, Room, lifecycle and activity down with it.

**Every row carries `updatedAt` and a `deletedAt` tombstone; deletes are soft.** The user explicitly
asked for sync-readiness so the same id on two devices can be resolved last-write-wins. Backups
include tombstones so a restore cannot resurrect deleted rows.

**Due means "due by end of local day", not "due before this instant."** Room binds a query parameter
once, so comparing against a captured `now` meant a card saved moments later never became due until
the next launch. This was a real bug found on device — the deck list showed no due counts while 72
cards were due. One definition lives in `Reminder.endOfToday()`.

**SM-2, not FSRS.** FSRS schedules better but needs a review-history table and a parameter optimizer.
The schema is ready for it; `schedule()` is a drop-in replacement point.

**No Apache POI for `.xlsx`** — ~10 MB for a spreadsheet reader. CSV covers it. An xlsx is a zip of
XML if on-device support is ever wanted.

**No `material-icons-extended`** — ~10 MB for one speaker glyph. `res/drawable/ic_speaker.xml`
instead.

**No media columns yet.** Images/audio were deferred; they land as nullable path columns in a small
Room migration, with files in app storage.

## Gotchas

**Gradle's build cache can mask a failing test run.** A `BUILD SUCCESSFUL` was once a cache hit
replaying a result from a different path. Verify test outcomes with `--rerun-tasks`.

**The project folder must not contain an apostrophe.** It was `D'flashcard`; the apostrophe corrupts
the classpath Gradle hands its test worker, so tests compiled but the class could not be loaded.
`assembleDebug` and `assembleRelease` were unaffected, which makes it easy to miss. An empty
`D'flashcard` folder may still sit next to this one — safe to delete.

## Running it

```
C:\Users\User\AppData\Local\Android\Sdk\emulator\emulator.exe -avd Medium_Phone_API_36
.\gradlew installDebug
```

Only one emulator instance may run per AVD; a second launch exits with "Running multiple emulators
with the same AVD". `gradlew` uses the JDK 17 on PATH.

## Tests

26 JVM unit tests, no framework beyond JUnit 4, no Robolectric. `FakeDao` is an in-memory
`FlashcardDao` so import, export and scheduling run without a device. `ImportTest` runs against the
real `seed.json` and asserts 101 cards across 4 decks.

## Open items

- **The reminder notification's rendering is unverified.** Scheduling, permission flow and the due
  query are confirmed on device; `doWork()` itself never ran under automation because WorkManager
  correctly refuses to run before schedule, and the emulator IME swallows synthetic key events so
  the time could not be retyped. Set a reminder two minutes out on a real device to confirm. If it
  fails it will be the small icon or the channel, both inside `Reminder.notify()`.
- **The user is updating Android Studio** (was 2024.3.1 Meerkat, which caps at AGP 8.9.1 and cannot
  open this project).
- **The user is planning UI/UX.** The top bar, list rows and card layout were deliberately kept
  plain so a design can land there without fighting existing decisions.
- Latin-script decks (e.g. the empty `German` deck) get no TTS button; that needs a language field
  on `Deck`.
- Phase 4 candidates, none started: sync, deck sharing, stats/heatmap, home-screen widget.

## Before publishing to Play

Signing keystore, a 512×512 listing icon, privacy policy, store listing. Nothing in the code blocks
this; `assembleRelease` produces a 1.6 MB minified APK.
