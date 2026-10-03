# Mushroom Exposed

An Android app for automatic mushroom identification via video using CameraX and a TensorFlow Lite model.

## Features

- Field mode: live preview with a centre target frame and quality hints ("mehr Licht",
  "näher heran / ruhig halten"). **Hold-to-scan**: holding the ring collects three
  verified views one after another — Hut, Unterseite, Stiel / Ring — and each is
  ticked off visibly as it is captured. With the segmenter and its configuration
  loaded, the app waits until all three are covered before it analyses, freezes
  the frame and shows one result. Releasing early cancels without
  leaving a result, a history entry or a file.
- Multi-view consensus: the three captured views are each run through the species
  model and their probability vectors are combined as a geometric mean in log space,
  so one confident view cannot outvote two weak ones. The conservative `VerdictPolicy`
  remains the final decision-maker after that.
- On-device classification of 663 Central-European species with German display names
- Edibility verdict (essbar / giftig / unbekannt) with a confidence threshold: an
  "essbar" headline is never granted below **60 %** confidence. The threshold was
  raised from 40 % to 60 % on 2026-09-19, after measuring on the frozen GBIF set
  that 40 % released 27 poisonous images as edible across four models — 60 %
  cuts that to 19 while keeping 83–93 % of the correct releases.
- An "essbar" result is presented as a **model estimate, not a clearance**: the
  badge reads *Modellschätzung*, the surface stays neutral (no green, no ✓) and
  only a poisonous hit carries a mark. Green once read as *FREIGABE* next to a
  warning about the deadly death cap — a contradiction the review of 2026-09-20
  flagged. Neutral keeps the estimate visible without implying permission.
- Toxic-release guards on the verdict card (measured 2026-09-19). A shortlist
  containing a poisonous species, or a warning genus (`Amanita`, `Clitocybe` —
  derived at runtime as genera with ≥2 poisonous and no edible member), drops an
  "essbar" card to the caution tone without taking the release away. Measured on
  the frozen set: all 13 dangerous releases above the threshold land on caution,
  none stays green.
- Lookalike warnings: when the species found has a dangerous doppelgänger
  (Knollenblätterpilz, Pantherpilz, Gifthäubling, …), the verdict card drops to a
  caution tone and names the species to compare against
- Emergency block on every result that is not an edible estimate: Vergiftungsinformationszentrale
  **01 406 43 43** (24 h) and Notruf **144**, plus "Restpilz und Erbrochenes aufbewahren"
- Local history (time, species, confidence, tone) in `files/history/history.jsonl`,
  capped at 200 entries, **no photos stored**, clearable from the UI
- Torch toggle, tap-to-focus, runtime camera permission

## Requirements

- Android 8.0 / API 26 or newer on the device (`minSdk = 26`).
- JDK 17 or newer and Android SDK Platform 35 to build (`compileSdk = 35`).
  Android Studio can install the SDK; a command-line build uses `ANDROID_HOME`
  or a local, ignored `local.properties` containing `sdk.dir=/path/to/android-sdk`.
- Use the checked-in Gradle wrapper (Gradle 8.11.1, Android Gradle Plugin 8.7.3).
- Model assets are already tracked in `app/src/main/assets/`: `model.tflite`,
  `labels.txt`, `lookalikes.txt`, `viewpoint.tflite` and `viewpoint.json`. The
  [Mushroom-Exposed-training](https://github.com/c0d1ngHUB/Mushroom-Exposed-training)
  repository stages the species assets with `src/verify_and_stage.py`.
  `viewpoint.json` records `src/stage_viewpoint.py` as historical staging
  provenance; that script is not in the current training checkout. The
  segmenter and contract are already tracked here. Keep them together when
  updating assets.

## Build

```bash
./gradlew assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # JVM tests, including the shipped asset contract
./gradlew lintDebug              # Android lint

# instrumented layout regression (needs a booted device/emulator).
# Set ANDROID_SERIAL when a physical device is also attached: Gradle otherwise
# picks every device and MIUI aborts the install with INSTALL_FAILED_USER_RESTRICTED.
ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest
```

## Layout

```
app/src/main/java/com/example/mushroomexposed/
  MainActivity.kt          camera, hold-to-scan, inference, history UI
  FieldMode.kt             LIVE/SCANNING/ANALYSING/FROZEN state machine (unit-tested)
  ViewAccumulator.kt       keeps the best accepted evidence per view (unit-tested)
  ViewpointContract.kt     fixed six-channel segmenter contract (unit-tested)
  ViewpointSegmenter.kt    frame -> per-view mask coverage + sharpness
  SpeciesConsensus.kt      log-space geometric mean over the three views (unit-tested)
  VerdictPolicy.kt         edibility decision incl. lookalike override (unit-tested)
  ResultFormatter.kt       result card formatting (unit-tested)
  FrameQuality.kt          luminance / sharpness / overexposure hints (unit-tested)
  LookalikeData.kt         parser for assets/lookalikes.txt (unit-tested)
  History.kt               append-only JSONL history store, cap 200 (unit-tested)
  ReferenceImageStore.kt   bounded local reference JPEGs (unit-tested)
  ModelContract.kt         model/label class-count guard
  ToxicGenus.kt            warning-genus detection from labels.txt (unit-tested)
```

## Noncommercial multi-view prototype

The multi-view path adds a viewpoint segmenter (`viewpoint.tflite`) that is trained
on **FungiTastic-M**, licensed **CC BY-NC-SA 4.0**. That licence forbids commercial
use, so this prototype and any APK built from it are a **nichtkommerzieller
Prototyp** — noncommercial only, attribution required, derivatives under the same
terms. Full text: `app/src/main/assets/NOTICE.txt`.

**The segmenter is shipped with its calibrated contract.** The tracked
`viewpoint.json` names run `skip-300-seed42`, a 300 px input, six output channels
and the SHA-256 of `viewpoint.tflite`. `ViewpointConfig` reads the coverage,
sharpness and dominance thresholds from that file; do not replace them with UI
constants. `ShippedViewpointContractTest` checks the actual staged files and
model hash.

If the segmenter cannot load, has an incompatible channel count, or its
configuration cannot be parsed, the three-view capture path remains unavailable:
no view row can be marked captured. The current shutter handler then falls back
to **single-frame species classification**, rather than refusing every capture.
It does not fabricate three-view evidence. If the species model itself is
unavailable, capture returns to the live state without a result.

The [device-test note](docs/superpowers/notes/geraetetest-tensorform-2026-09-25.md)
records the NHWC tensor-layout fix and historical test results on 2026-09-25.
Those results are not a fresh verification of another checkout or device;
manual field testing was still listed as open in that note.

## Release state

The shipping asset is the **GBIF-expanded seed-17 model** (`model.tflite`, 663
classes, 288 px input, sha256 `001d6aed…`), staged on 2026-09-19 in commit
`9c0d163`. It was measured against the 224 px baseline on the frozen GBIF holdout
(unseen by both models — the baseline never trained on GBIF, the candidate had
the holdout excluded):

| Gate | Result | Basis |
|---|---|---|
| GBIF | macro top-1 **+0.4114**, 95 % CI low +0.2886 | 492 images / 43 species |
| PVV | macro top-3 0.7979 → 0.8412, not worse | 143 images / 127 species |

All three seeds pass, PyTorch↔TFLite parity max |p| = 2.83e-06. Identity was
verified at every hop (seed run → asset → APK → installed app).

**Scope of those numbers (corrected 2026-09-23):** the +0.4114 was measured over
**492 images / 43 species**, because the seed-17 training run read only
`gb_dataset/obs_index.json` — a side product of the crawl that listed 45 classes
while 587 had image folders on disk, so 542 classes never reached training or
gate. The frozen holdout file itself is not that narrow: it resolves to
**5,560 images / 557 species**, and 5,695 of them are measurable. The 43 was an
artifact of the index bug, not a property of the gate. That bug is fixed
(`load_gbif()` now reads the directory), but **the gate has not been re-run on
the wider basis yet** — a re-run on both bases is the open item.

So: the numbers above are valid for those 43 species and justify neither a
quality claim nor a rejection for the rest of the label space. Measured cause and
the fix: [gate coverage note](https://github.com/c0d1ngHUB/Mushroom-Exposed-training/blob/main/docs/superpowers/notes/gate-abdeckung-2026-09-23.md)
in the training repo.

**Known safety gap in the shipped model (measured 2026-09-23).** Three classes
carried as "essbar" recognise themselves almost not at all, and clear images of
a *different* edible species as edible while crossing the 0.60 threshold: on the
frozen holdout basis (43 images) **1 of them stays green with no warning at
all**. No lookalike entry exists for any of the three. Which three, and the full
numbers: [thin-edible release note](https://github.com/c0d1ngHUB/Mushroom-Exposed-training/blob/main/docs/superpowers/notes/thin-edible-freigaben-2026-09-23.md). No claim
is made here about whether those species are edible — only that the model does
not identify them and nothing catches it. The release decision is open.

## License

The repository code is covered by [MIT](LICENSE). The bundled model/data assets
have separate provenance and noncommercial restrictions described in
[NOTICE.txt](app/src/main/assets/NOTICE.txt); the code license does not remove
those restrictions.

## Disclaimer

This app is for educational purposes only. Do not rely solely on its output for
mushroom consumption. Always consult an expert before eating wild mushrooms.
