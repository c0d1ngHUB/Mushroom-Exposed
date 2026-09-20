# Mushroom Exposed

An Android app for automatic mushroom identification via video using CameraX and a TensorFlow Lite model.

## Features

- Field mode: live preview with a centre target frame and quality hints ("mehr Licht",
  "näher heran / ruhig halten"), a shutter button that freezes one frame and analyses it.
  The result stays on screen until the next press — no flickering live lines.
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

- Android SDK 26+
- Android Studio (for building)
- The TFLite model and label file in `app/src/main/assets/` (`model.tflite`,
  `labels.txt`), both produced by the
  [Mushroom-Exposed-training](../Mushroom-Exposed-training) repo
  (`src/verify_and_stage.py` stages them, including `lookalikes.txt`)

## Build

```bash
./gradlew assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # unit tests: verdict policy, history, lookalikes, quality

# instrumented layout regression (needs a booted device/emulator).
# Set ANDROID_SERIAL when a physical device is also attached: Gradle otherwise
# picks every device and MIUI aborts the install with INSTALL_FAILED_USER_RESTRICTED.
ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest
```

## Layout

```
app/src/main/java/com/example/mushroomexposed/
  MainActivity.kt     camera, shutter/freeze, inference, history UI
  FieldMode.kt        LIVE/ANALYSING/FROZEN field-mode state machine (unit-tested)
  VerdictPolicy.kt    edibility decision incl. lookalike override (unit-tested)
  ResultFormatter.kt  result card formatting (unit-tested)
  FrameQuality.kt     luminance / sharpness / overexposure hints (unit-tested)
  LookalikeData.kt    parser for assets/lookalikes.txt (unit-tested)
  History.kt          append-only JSONL history store, cap 200 (unit-tested)
  ModelContract.kt    model/label class-count guard
  ToxicGenus.kt       warning-genus detection from labels.txt (unit-tested)
```

## Release state

Shipping asset is **v0.6.0** (`model.tflite`, sha256 starts `001d6aed`). The GBIF
candidate (663 classes, 288 px input) passes the release gate on all three seeds
but is **not** staged — the gate only covers 43 of 664 classes (6.5 %), so its
numbers justify neither staging nor rejection for the rest. See the training
repo's `docs/superpowers/notes/` for the measurements.

## License

MIT

## Disclaimer

This app is for educational purposes only. Do not rely solely on its output for
mushroom consumption. Always consult an expert before eating wild mushrooms.
