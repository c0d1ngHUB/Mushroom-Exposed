# Mushroom Exposed

An Android app for automatic mushroom identification via video using CameraX and a TensorFlow Lite model.

## Features

- Field mode: live preview with a centre target frame and quality hints ("mehr Licht",
  "näher heran / ruhig halten"), a shutter button that freezes one frame and analyses it.
  The result stays on screen until the next press — no flickering live lines.
- On-device classification of 663 Central-European species with German display names
- Edibility verdict (essbar / giftig / unbekannt) with a confidence threshold: an
  "essbar" headline is never granted below 40 % confidence
- Lookalike warnings: when the species found has a dangerous doppelgänger
  (Knollenblätterpilz, Pantherpilz, Gifthäubling, …), the verdict card drops to a
  caution tone and names the species to compare against
- Emergency block on every non-green result: Vergiftungsinformationszentrale
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
```

## Layout

```
app/src/main/java/com/example/mushroomexposed/
  MainActivity.kt     camera, shutter/freeze, inference, history UI
  VerdictPolicy.kt    edibility decision incl. lookalike override (unit-tested)
  ResultFormatter.kt  result card formatting (unit-tested)
  FrameQuality.kt     luminance / sharpness / overexposure hints (unit-tested)
  LookalikeData.kt    parser for assets/lookalikes.txt (unit-tested)
  History.kt          append-only JSONL history store, cap 200 (unit-tested)
  ModelContract.kt    model/label class-count guard
```

## License

MIT

## Disclaimer

This app is for educational purposes only. Do not rely solely on its output for
mushroom consumption. Always consult an expert before eating wild mushrooms.
