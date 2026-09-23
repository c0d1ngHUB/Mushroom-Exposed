# Instrumented-Lauf auf dem AVD (23.09.2026)

Beantwortet einen offenen Punkt: die **vier neuen Tests aus dem Review-Fix-Schnitt
vom 20.09. waren noch nie auf einem Gerät gelaufen.** Das letzte
Instrumented-Ergebnis im Repo kannte nur vier der acht Tests — die vier, die der
Review-Fix-Commit `6ee41e4`/`d562ad7` hinzufügte, waren unverifiziert.

Jetzt läuft die volle Suite gegen `HEAD` (`62ed76f`).

## Ergebnis

```
<testsuite name="...SystemBarsLayoutTest" tests="8" failures="0" errors="0" skipped="0"
           time="9.321" timestamp="2026-09-23T17:31:51" />
```

Alle acht Testfälle, einschließlich der vier neuen:

| Test | Zeit | Was er pinnt |
|---|---|---|
| `shutterIsCentredOnTheScreen` | 1.002 | Auslöser mittig (Finding 3: war 73,5 dp daneben) |
| `anEdibleVerdictIsNeverPaintedAsAClearance` | 1.293 | essbar nie als Freigabe (Finding 1: war „FREIGABE") |
| `theActivityIsLockedToPortrait` | 1.185 | Portrait-Sperre (Finding 2: war `expected:<1> but was:<-1>`) |
| `theClearButtonFollowsTheHistoryState` | 1.345 | Löschen-Knopf folgt dem Zustand (Finding 4) |
| `primaryOverlaysStayOutsideSystemBars` | 0.694 | Systemleisten (v0.7.0) |
| `frozenChipsReplaceTheCoveredControlBar` | 0.688 | eingefrorener Zustand (v0.7.0) |
| `toxicVerdictShowsReachableEmergencyBlock` | 1.362 | Notrufnummern erreichbar |
| `failedAnalysisKeepsPreviousResultSheetHidden` | 1.293 | kein Alt-Ergebnis nach Fehlschlag |

## Reproduktion

```bash
export ANDROID_SDK_ROOT=/home/m3kky/android-sdk
/home/m3kky/android-sdk/emulator/emulator -avd xikey_api35 \
  -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot
# ANDROID_SERIAL ist Pflicht, sonst zieht Gradle auch das Redmi und MIUI
# bricht mit INSTALL_FAILED_USER_RESTRICTED ab.
cd ~/projects/Mushroom-Exposed
ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest
```

`--rerun-tasks` war **nicht** nötig: die Task war nicht auf „executed" gestellt,
das Ergebnis-XML ist neu geschrieben (`timestamp="2026-09-23T17:31:51"`, vorher
`2026-09-20T06:55:57`). Ein grünes `UP-TO-DATE` hätte nichts bewiesen — die
Zeitstempel sind der Beleg, nicht der Exit-Code.