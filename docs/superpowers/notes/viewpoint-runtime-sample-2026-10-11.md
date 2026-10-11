# Android-Laufzeitmessung: lokale Drei-Frame-Stichprobe

Stand: 2026-10-11, App-Worktree `feat/viewpoint-operating-point`.

Die vorhandene v2-Implementierung wird mit den unveränderten Assets geprüft.
`ViewpointRuntimeTest.measuredSampleReportContainsRealFrameIdentityAndBothActivationGrids`
exportiert nach erfolgreicher Bildidentitäts- und Ansichtsprüfung
`files/viewpoint-runtime-sample.json` im privaten Speicher des Testgeräts.
Ein alter Bericht wird vor dem Lauf entfernt; Fehler hinterlassen keinen
scheinbar aktuellen Erfolg. Veröffentlichung erfolgt erst am Ende über eine
lokale temporäre Datei.

## Inhalt und Aussagegrenze

- Tatsächlich geladener Modell-SHA-256, Input-/Output-Tensorformen, verwendeter
  v2-Testvertrag, Kanalreihenfolge, Android-API und Messzeitpunkt.
- Drei echte lokale FungiTastic-Mini-Trainbilder: Rohbild-SHA-256,
  Observation-ID, Originalabmessungen, Android/Skia-Schärfe direkt aus dem
  produktiven Segmentierer und sechs Kanalflächen bei beiden Aktivierungen
  0,3 und 0,5.
- Ansichtsentscheidungen aus `ViewpointSegmenter` und `ViewAccumulator`;
  Vergleich mit den bereits gepinnten Python-Train-Golden-Entscheidungen:
  alle drei Ansichten, keine Ansicht, nur Unterseite.

Der Bericht ist ausdrücklich `report_kind: android_runtime_sample` und
`release_eligible: false`. Er enthält **keine** `input_hashes`, die eine Bindung
an vollständige val/test-NPZ-Messungen vortäuschen würden. Drei Trainframes
beweisen weder die Parität eines ganzen Splits noch neue Fehlalarm-/Recall-Werte
oder den unabhängigen Feldtest. Auch der Vergleich aller numerischen
Python-Schärfe-/Coverage-Werte auf identischen Rohbildern bleibt separat zu
vervollständigen. Deshalb ist dies kein Task-3-Freigabebericht.

Die lizenzierten JPGs (CC BY-NC-SA 4.0) bleiben lokal und sind jetzt auch in der
versionierten `.gitignore` ausgeschlossen. Sie sind ausschließlich Test-Assets,
keine produktiven App-Assets. Regeneration: vorhandenes
`app/src/androidTest/python/generate_golden.py` mit dem lokalen Trainingsbestand
und einer Umgebung mit PyTorch; dessen bisherige Schärfeberechnung nutzt das
300-px-Zwischenbild, während Android direkt das Rohbild auf 224 px skaliert.
Für den vollständigen Paritätsnachweis muss diese Vorverarbeitung zuerst
abgeglichen werden; die drei gepinnten Ansichtsentscheidungen bleiben der
begrenzte Vergleich dieser Stichprobe.

## Reproduktion und Export

Im App-Worktree mit vorhandenem SDK und gebootetem Emulator:

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -e class com.example.mushroomexposed.ViewpointRuntimeTest com.example.mushroomexposed.test/androidx.test.runner.AndroidJUnitRunner
# Erst nach OK (5 tests) exportieren:
mkdir -p app/build/outputs/viewpoint
adb -s emulator-5554 exec-out run-as com.example.mushroomexposed cat files/viewpoint-runtime-sample.json > app/build/outputs/viewpoint/viewpoint-runtime-sample.json
```

Gradles Testverwaltung entfernt die installierten Testpakete nach dem Lauf.
Deshalb wird für den persistenten Export anschließend explizit installiert und
instrumentiert. Alle Ausgaben liegen unter dem ignorierten `app/build/`.
Produktives `viewpoint.json`, `viewpoint.tflite`, `model.tflite` und `labels.txt`
bleiben unverändert; keine Schwellenübernahme und kein Staging.

## Verifizierter Lauf am 2026-10-11

- `testDebugUnitTest`: 167 Tests, 0 Fehler, 0 übersprungen.
- `lintDebug`, `assembleDebug`, `assembleDebugAndroidTest`: BUILD SUCCESSFUL.
- `connectedDebugAndroidTest` auf API 35: 19 Tests, 0 Fehler, 0 übersprungen.
- Expliziter Instrumentation-Export: OK (5 tests). Persistierter Bericht lokal
  unter `app/build/outputs/viewpoint/viewpoint-runtime-sample.json` (3551 Bytes),
  alle Bild-/Modell-Hashes gegen die lokalen Dateien zurückgeprüft.
- Neue Berichtsfunktion zunächst RED (Messbericht fehlt), dann GREEN.
  Stale-Berichtsprüfung zusätzlich durch Entfernen der Invalidierung RED,
  Originalcode wiederhergestellt und vollständige Suite GREEN.
- Separater read-only Review der Fortsetzungsänderung: keine Befunde.

Die realen Android-Schärfen der drei Rohbilder sind 0,538407, 0,024918 und
0,613212 bei Größen 300×225, 300×188 und 300×288. Die bisherigen
Python-Golden-Kommentare nennen bei den letzten beiden Frames 0,023820 und
0,487850 aus der anderen Skalierung. Der Ansichtsstatus stimmt in dieser
Stichprobe trotzdem überein. Die numerische Abweichung darf bei der vollständigen
Paritätsprüfung weder ignoriert noch durch eine gelockerte Gate-Toleranz verdeckt
werden.

Unveränderte Produkt-Hashes:
`viewpoint.json`: `44855f00de8d3bc6e83ee11fcbe4709178e419f73356e6e92e45c6efcdbd4dc0`;
`viewpoint.tflite`: `f1b42948fc5b0ac5983dbc2db86a4964655d1889436e4044ab60ffe13f2b275f`.
Task 4 bleibt für die vollständige val/test-Hashbindung, repräsentative Stichprobe
und numerische Parität offen. Task 5 bleibt durch Feldnachweis und Freigabe gesperrt.
