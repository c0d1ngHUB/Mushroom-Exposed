# Mushroom Exposed

An Android app for automatic mushroom identification via video using CameraX and a TensorFlow Lite model.

## Features

- Real-time video preview using CameraX
- On-device mushroom classification with TensorFlow Lite
- Displays edibility (edible/poisonous/unknown), rarity, and whether more evidence is needed
- Requests camera permission at runtime

## Requirements

- Android SDK 21+
- Android Studio (for building)
- A TensorFlow Lite model file named `model.tflite` placed in `app/src/main/assets/`

## Build

1. Clone the repository.
2. Place your TensorFlow Lite model (`model.tflite`) in `app/src/main/assets/`.
3. Open the project in Android Studio and build/run on a device or emulator.

## License

MIT

## Disclaimer

This app is for educational purposes only. Do not rely solely on its output for mushroom consumption. Always consult an expert before eating wild mushrooms.