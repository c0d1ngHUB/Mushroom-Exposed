# Gerätetest-Befund: Tensorform des Segmentierers (2026-09-25)

## Was gefunden wurde

`viewpoint.tflite` gibt **NHWC `[1, 300, 300, 6]`** aus. `ViewpointSegmenter.masksFor()`
hat die Ausgabe als **NCHW `[1, 6, 90000]`** entgegengenommen:

```kotlin
val output = arrayOf(Array(outputChannels) { FloatArray(inputWidth * inputHeight) })
interpreter.run(input, output)   // wirft
```

Laufzeitfehler auf echtem Gerät:

```
java.lang.IllegalArgumentException: Cannot copy from a TensorFlowLite tensor
(serving_default_output_0_output) with shape [1, 300, 300, 6] to a Java object
with shape [1, 6, 90000].
    at org.tensorflow.lite.TensorImpl.throwIfDstShapeIsIncompatible
```

**Wirkung:** jeder Hold-to-scan wäre abgestürzt — die App hätte **nie** ein
Ergebnis geliefert. Nicht ein Fehlerfall, sondern jeder Fall.

## Warum auf der JVM unsichtbar

Die Unit-Tests prüfen den Vertrag gegen Fixtures: `ViewAcceptance` mit Karten,
`ViewEvidence` mit Werten, `ViewpointConfig` gegen JSON-Text. Kein Unit-Test
lädt ein echtes Modell, weil TFLite im JVM-Test nicht verfügbar ist. Die
Tensorform ist damit grundsätzlich nicht prüfbar — sie ist eine Eigenschaft der
Binärdatei, nicht des Codes.

Aufgefallen ist es erst, als der **ausgelieferte** Segmentierer im Gerätetest
zum ersten Mal wirklich geladen und einmal durchlaufen wurde. Vorher nagelten
zwei Instrumented-Tests den Zustand „kein Segmentierer" fest und liefen deshalb
grün, ohne den Ladepfad je zu betreten.

## Der Fix

```kotlin
val buffer = Array(1) { Array(inputHeight) { Array(inputWidth) { FloatArray(outputChannels) } } }
interpreter.run(input, buffer)
// danach NHWC -> [C][H*W] umordnen
```

In Commit `d2ec1c6`. Ergebnis: 14/14 instrumented, 152 Unit-Tests, Lint sauber.

## Die Lektionen

1. **Ein Modell-Asset erst ausliefern, wenn ein Gerätetest es wirklich geladen
   und einmal durchlaufen hat.** Ein Test, der nur „kein Asset vorhanden"
   prüft, betritt den Ladepfad nie und kann den Fehler nicht finden.
2. **Tensorformen sind auf der JVM nicht prüfbar.** NHWC vs. NCHW ist eine
   Eigenschaft des exportierten Graphen. Wer die Form im Code annimmt statt sie
   zur Laufzeit zu lesen, baut einen Fehler, den nur das Gerät zeigt.
3. **Ein Fail-closed-Test, der an der Abwesenheit einer Datei hängt, wird beim
   Ausliefern zum falschen Test.** Er muss am **gemeldeten Zustand** hängen
   (`viewsAvailable = false`), nicht an einer Datei — dann überlebt die
   Zusicherung den Auslieferungsschritt.

## Offen

- APK `mushroom-exposed-viewpoint-d2ec1c6.apk` (sha256 `a060bc8c…`) liegt auf
  dem Redmi unter `/sdcard/Download/`. Manuell installieren und Hold-to-scan im
  Feld prüfen.
- MIUI blockiert `connectedAndroidTest` mit `INSTALL_FAILED_USER_RESTRICTED`.
  Gerätetests auf dem Emulator fahren (`xikey_api35`), APK manuell installieren.
