# Android-Laufzeitmessung: lokale Fünf-Frame-Stichprobe

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
- Fünf echte lokale FungiTastic-Mini-Bilder (drei Train, zwei Val): Rohbild-SHA-256,
  Observation-ID, Originalabmessungen, Android/Skia-Schärfe direkt aus dem
  produktiven Segmentierer und sechs Kanalflächen bei beiden Aktivierungen
  0,3 und 0,5.
- Python-Schärfe aus denselben Rohbildbytes ohne 300×300-Zwischenschritt,
  absolute Python-/Android-Abweichung und explizite Übereinstimmung der
  Entscheidung an der unveränderten Schärfeschwelle 0,05. Die zwei Val-Frames
  liegen mit Python 0,051292 und 0,047730 direkt ober- und unterhalb der Grenze.
- Ansichtsentscheidungen aus `ViewpointSegmenter` und `ViewAccumulator`;
  Vergleich mit den bereits gepinnten Python-Golden-Entscheidungen:
  alle drei Ansichten, keine Ansicht, nur Unterseite.

Der Bericht ist ausdrücklich `report_kind: android_runtime_sample` und
`release_eligible: false`. Er enthält **keine** `input_hashes`, die eine Bindung
an vollständige val/test-NPZ-Messungen vortäuschen würden. Fünf Frames
beweisen weder die Parität eines ganzen Splits noch neue Fehlalarm-/Recall-Werte
oder den unabhängigen Feldtest. Deshalb ist dies kein Task-3-Freigabebericht.

Die lizenzierten JPGs (CC BY-NC-SA 4.0) bleiben lokal und sind jetzt auch in der
versionierten `.gitignore` ausgeschlossen. Sie sind ausschließlich Test-Assets,
keine produktiven App-Assets. Regeneration: vorhandenes
`app/src/androidTest/python/generate_golden.py` mit dem lokalen Trainingsbestand
und einer Umgebung mit PyTorch. Die Schärfeberechnung nutzt jetzt wie Android
das dekodierte Rohbild direkt als Eingang des 224×224-Pfads. Das quadratische
300×300-Modellbild bleibt ausschließlich Eingabe des Segmentierungsmodells.
Die Plattformdecoder und Bilinear-Implementierungen sind nicht pixelidentisch;
der Bericht begrenzt ihre gemessene absolute Schärfeabweichung auf 0,012 und
prüft unabhängig davon die gleiche Seite der unveränderten 0,05-Grenze.

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
- Python: neuer Rohbildpfad-Regressionscheck 1/1 sowie bestehende
  Schärfe-/Messsuite 16/16 bestanden.
- `lintDebug`, `assembleDebug`, `assembleDebugAndroidTest`: BUILD SUCCESSFUL.
- `connectedDebugAndroidTest` auf API 35: 19 Tests, 0 Fehler, 0 übersprungen.
- Expliziter Instrumentation-Export: OK (5 tests). Persistierter Bericht lokal
  unter `app/build/outputs/viewpoint/viewpoint-runtime-sample.json` (5870 Bytes,
  Schema 2),
  alle Bild-/Modell-Hashes gegen die lokalen Dateien zurückgeprüft.
- Rohbildpfad-Regressionscheck zunächst RED (Helper fehlte), dann GREEN;
  Schärfevergleich zunächst RED (`python_sharpness` fehlte), dann GREEN;
  Fünf-Frame-Erweiterung zunächst RED (3 statt 5), dann GREEN;
  Schema-2-Prüfung zunächst RED (1 statt 2), dann GREEN.
- Neue Berichtsfunktion ursprünglich RED (Messbericht fehlt), dann GREEN.
  Stale-Berichtsprüfung zusätzlich durch Entfernen der Invalidierung RED,
  Originalcode wiederhergestellt und vollständige Suite GREEN.
- Separater read-only Review der Fortsetzungsänderung: keine Befunde.

Die Python-/Android-Schärfen sind 0,526556/0,538407, 0,024861/0,024918,
0,611200/0,613212, 0,051292/0,050651 und 0,047730/0,047570. Die absoluten
Abweichungen betragen 0,011851, 0,000057, 0,002012, 0,000642 und 0,000160.
Alle fünf Frames liegen in beiden Implementierungen auf derselben Seite der
unveränderten 0,05-Grenze; insbesondere bleiben die beiden Val-Grenzfälle
oberhalb beziehungsweise unterhalb. Der 0,012-Wert ist nur die eng gemessene
Plattformabweichung dieser Stichprobe und keine Änderung der Produktschwelle.

Unveränderte Produkt-Hashes:
`viewpoint.json`: `44855f00de8d3bc6e83ee11fcbe4709178e419f73356e6e92e45c6efcdbd4dc0`;
`viewpoint.tflite`: `f1b42948fc5b0ac5983dbc2db86a4964655d1889436e4044ab60ffe13f2b275f`.
Task 4 bleibt für die vollständige val/test-NPZ-Hashbindung und Split-Abdeckung
offen. Der Schärfepfad und die beiden 0,05-Grenzfälle dieser Stichprobe sind
numerisch abgeglichen. Task 5 bleibt durch Feldnachweis und Freigabe gesperrt.
