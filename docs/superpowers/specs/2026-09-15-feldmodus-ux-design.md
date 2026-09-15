# Feldmodus: UX-Ausbau der Pilze-App — Designdokument

## Ziel

Die App soll im Wald ohne Erklärungsbedarf bedienbar sein: ruhiges, stabiles
Ergebnis statt flackernder Live-Zeilen, klare Kameraführung, nachvollziehbarer
Verlauf und Warnhinweise, die den gefährlichsten Fehlerfall abdecken — die
Verwechslung einer essbaren Art mit einem giftigen Doppelgänger.

Das Modell selbst bleibt unverändert (Assets aus v0.5.0). Dieser Schritt ändert
ausschließlich die App.

## Umfang

Enthalten:

- Hybrid-Feldmodus: Live-Vorschau führt, Auslöser friert das Bild ein, Analyse
  einmal pro Auslöser, Ergebnis bleibt stehen.
- Kameraführung: Zielrahmen, Taschenlampe, Tap-to-Focus, Live-Hinweise zur
  Bildqualität.
- Ergebnisanzeige: Ampel-Kopf mit Artname (deutsch/wissenschaftlich), Konfidenz,
  Verzehr-Urteil, Top-3, Doppelgänger-Warnzeile.
- Verlauf: lokale Liste (Zeit, Art, Konfidenz, Ampel) ohne Fotos.
- Warnhinweise: deutscher Notfall-Block, Verwechslungspaare (Lookalikes).
- Entlastung des Main-Threads (Bitmap-Konvertierung und Inferenz laufen nicht
  mehr auf dem UI-Thread).

Nicht enthalten:

- Änderungen am Modell, am Training oder an `labels.txt`.
- Neue Erfassung von Fotos in der Historie.
- Lockerung der bestehenden Verzehrlogik: `VerdictPolicy` bleibt streng, das
  Schwellenverhalten für „essbar" (0.40) wird nicht abgesenkt.
- Automatisierte Kamera-Instrumentierungstests (bewusst manuell, siehe
  Teststrategie).

## Kerninteraktion: Hybrid-Feldmodus

Zwei Zustände, in `MainActivity` als Zustandsmaschine:

- **LIVE (Sucher):** Kameravorschau, Zielrahmen in der Mitte, Live-Hinweiszeile
  zur Bildqualität, Auslöser und Taschenlampe aktiv. Keine Klassifikation.
- **FROZEN (Ergebnis):** Der ausgelöste Frame wird als Standbild angezeigt
  (statt Vorschau), die Ergebnis-Karte ist eingeblendet, Buttons „Neu" und
  „Verlauf". Keine weitere Analyse; das Ergebnis bleibt stehen, bis „Neu"
  gedrückt wird.

Regeln:

- Genau eine Analyse pro Auslöser, ausgeführt im vorhandenen
  `inferenceExecutor`, niemals auf dem Main-Thread.
- Ein Auslöser während laufender Analyse wird ignoriert (Busy-Flag).
- Analysiert wird der **quadratische Mittelausschnitt** (Zielrahmen) des
  eingefrorenen Frames, nicht das ganze Bild. Das entspricht dem
  Trainingsformat und macht den Rahmen zur echten Führung statt zur Dekoration.
- Die Activity wird auf Hochformat festgelegt, damit Vorschau-Geometrie und
  Zustandsmaschine einfach und stabil bleiben.

## Kamera und Bildqualität

- **Zielrahmen:** Quadrat mit 80 % der kurzen Bildkante, mittig, dünne
  Umrandung + Text „Pilz in den Rahmen".
- **Taschenlampe:** Umschaltbutton, nur eingeblendet wenn
  `camera.cameraInfo.hasFlashUnit()`.
- **Tap-to-Focus:** Tippen in die Vorschau setzt Fokus- und Belichtungspunkt
  (`CameraControl.startFocusAndMetering`), sichtbares kurzes Feedback am
  Tipp-Punkt.
- **Bildqualität** (`FrameQuality.kt`, reine Kotlin-Logik ohne Android-Import):
  aus dem Vorschau-Frame werden mittlere Luminanz, Laplace-Varianz (Schärfe)
  und der Anteil überbelichteter Pixel berechnet. Daraus ein Hinweis:

  | Bedingung | Hinweis |
  |---|---|
  | Luminanz < 0.18 | „Mehr Licht" |
  | Luminanz > 0.82 oder überbelichtet > 30 % | „Zu hell — abschatten" |
  | Schärfe (Laplace-Varianz über Graustufen 0–255) < 60 | „Näher heran / ruhig halten" |
  | sonst | kein Hinweis (Rahmenfarbe wechselt auf „bereit") |

  Startwerte: Luminanzfenster 0.18–0.82, Überbelichtungsanteil 30 %,
  Laplace-Varianz 60 (bei 224×224-Graustufen) als Konstante in
  `FrameQualityPolicy`. Diese Werte werden im Smoke-Test am Redmi an echten
  Pilzfotos nachgezogen; die kalibrierten Werte stehen kommentiert im Code.

## Ergebnisanzeige

Aufbau der Ergebnis-Karte, von `ResultFormatter.kt` (reine Funktionen,
JUnit-testbar) als Text/Beschriftung geliefert:

1. **Kopf:** Ampel-Farbe (bestehendes `VerdictTone`) + deutscher Artname groß,
   wissenschaftlicher Name klein darunter, Konfidenz in Prozent.
2. **Verzehr-Urteil** aus `VerdictPolicy` — unverändert in der Logik.
3. **Doppelgänger-Warnzeile** (gelb), wenn für die erkannte Art ein
   `gefaehrlich`-Eintrag existiert.
4. **Top-3-Liste** mit Konfidenzen (wie bisher, ohne Emoji-Rauschen, dafür mit
   Verzehrkennzeichnung).
5. **Notfall-Block**, eingeblendet bei `DANGER` und `CAUTION`:

   > Bei Verdacht auf Pilzvergiftung: Vergiftungsinformationszentrale
   > 01 406 43 43 (24 h) oder Notruf 144. Restpilz und Erbrochenes aufbewahren.

Alle sichtbaren Texte liegen in `res/values/strings.xml`.

## Verlauf (JSONL)

- Datei `filesDir/history.jsonl`, eine JSON-Zeile pro Erkennung:

```json
{"ts":"2026-09-15T12:20:17Z","sci":"Agaricus_essettei","de":"Dünnfleischiger Anis-Champignon","conf":0.71,"verdict":"essbar","lookalike":true}
```

- Kein Foto, keine Bilddaten, keine Standortdaten.
- Schreiben im `Dispatchers.IO`-Kontext; ein Schreibfehler darf die Erkennung
  nicht abbrechen (Toast „Verlauf nicht verfügbar").
- Anzeige in `HistoryActivity` (eigenes Layout `activity_history.xml`),
  neueste zuerst, mit Ampel-Farbe je Eintrag.
- Grenze: 200 Einträge. Beim Überschreiten werden die ältesten entfernt.
- Button „Verlauf löschen" mit Bestätigungsdialog.
- Unlesbare Zeilen werden beim Einlesen übersprungen, nicht als Fehler
  behandelt.

`HistoryStore` (in `History.kt`) kapselt Datei, Cap, Append, Lesen und Löschen;
sein Konstruktor nimmt ein Verzeichnis, damit Tests mit einem temporären
Verzeichnis laufen.

## Warnungen und Verwechslungspaare

### Datenquelle

Grundlage sind die bereits lokal gecachten deutschen Wikipedia-Texte des
Trainings-Repos (`out/wiki_cache/*.txt`, 510 Artikel) sowie die dort
vorhandenen `Artabgrenzung`- und `Verwechslung`-Abschnitte. Beispielbefund:

> `Agaricus_essettei` / Artabgrenzung: „Besonders die jungen Fruchtkörper
> ähneln stark denen tödlich giftiger Knollenblätterpilze (Grüner
> Knollenblätterpilz, Kegelhütiger Knollenblätterpilz)."

Kein Webzugriff zur Laufzeit, keine Datenquelle in der App außer der
erzeugten Asset-Datei.

### Extraktion

Neues Skript `src/extract_lookalikes.py` im Trainings-Repo:

- Eingaben: `out/wiki_cache/*.txt`, `out/classes_v2.json`,
  `out/verdict_final.json`, `out/labels_app.txt`.
- Abschnitte mit Überschrift `Artabgrenzung`, `Verwechslung`, `Ähnlich\w*`,
  `Giftigkeit`, `Toxikolog\w*` werden satzweise geprüft.
- Ein Paar wird nur übernommen, wenn der genannte Doppelgänger auf eine Klasse
  des eigenen Artensatzes auflösbar ist (deutscher Name aus `de_rest`/`display`
  oder wissenschaftliches Binom).
- Art der Warnung:
  - `gefaehrlich`, wenn das Ziel in `verdict_final.json` als `giftig` geführt
    wird oder der Satz Giftnachweis enthält (`tödlich giftig`, `stark giftig`,
    `sehr giftig`, `giftig`, `giftverdächtig`).
  - sonst `achtung`.
- Jeder Eintrag speichert den Belegsatz mit, damit die Warnung nachprüfbar ist.
- Kuratierte Ergänzung `src/lookalikes_curated.json` (Klassiker wie
  Knollenblätterpilz- und Pantherpilz-Verwechslungen bei Champignons und
  Röhrlingen) wird eingemischt und **gewinnt** gegen automatisch extrahierte
  Einträge desselben Paars.
- Ausgaben: `out/lookalikes.txt` (App-Format) und `out/lookalikes_report.json`
  (Anzahl Paare, Quellen, Belegsätze).
- `src/verify_and_stage.py` kopiert `out/lookalikes.txt` zusätzlich in die App
  (`app/src/main/assets/lookalikes.txt`).

### Dateiformat `lookalikes.txt`

Eine Zeile pro erkannter Art, Pipe-getrennt; erste Spalte der Klassenschlüssel
der App, danach beliebig viele `art:ziel`-Paare, letzte Spalte der Belegsatz:

```
Agaricus_essettei|gefaehrlich:Amanita_phalloides|gefaehrlich:Amanita_virosa|Junge Fruchtkörper ähneln stark tödlich giftigen Knollenblätterpilzen.
```

Zeilen mit `#` sind Kommentare. Unbekannte Schlüssel oder unbekannte
`art`-Werte werden ignoriert.

### Anzeige und Policy

- `LookalikeData.kt` parst die Datei zu `Map<String, List<Lookalike>>`
  (`LookalikeKind.GEFAEHRLICH` / `ACHTUNG`), mit deutschen Anzeigenamen der
  Zielarten aus `labels.txt`.
- `VerdictPolicy.decide(verdict, confidence, lookalike)`:
  - `GEFAEHRLICH` → die Kopfzeile wird **niemals grün**; sie fällt mindestens auf
    `CAUTION` und der Belegsatz wird an die Warnung angehängt.
  - `ACHTUNG` → Warnung wird ergänzt, Ton bleibt unverändert.
  - Standardwert `null` → das bestehende Verhalten bleibt für alle bisherigen
    Tests unverändert.
- Fehlt oder bricht `lookalikes.txt`, läuft die App ohne Doppelgänger-Warnungen
  weiter. Kein Absturz, keine leere Ergebnisanzeige.

## Komponenten und Dateistruktur

App (`Mushroom-Exposed`):

- `app/src/main/java/com/example/mushroomexposed/MainActivity.kt` (ändern):
  Zustandsmaschine LIVE/FROZEN, Zielrahmen, Auslöser, Taschenlampe,
  Tap-to-Focus, Verlaufs-Navigation, Analyse im Executor.
- `.../History.kt` (neu): `HistoryEntry`, `HistoryStore` (JSONL, Cap 200).
- `.../FrameQuality.kt` (neu): `FrameQuality`, `QualityHint`,
  `FrameQualityPolicy` (reine Berechnung, keine Android-Abhängigkeit).
- `.../LookalikeData.kt` (neu): Parser und Modelle der Doppelgänger-Daten.
- `.../ResultFormatter.kt` (neu): reine Formatierung von Kopf, Top-3-Zeilen und
  Notfalltext aus Entscheidung + Lookalikes.
- `.../VerdictPolicy.kt` (ändern): Lookalike-Parameter.
- `.../HistoryActivity.kt` (neu): Verlaufsliste und Löschen.
- `app/src/main/res/layout/activity_main.xml` (ändern) und
  `activity_history.xml` (neu), Zielrahmen-/Taschenlampen-Drawables,
  `values/strings.xml` (Texte).
- `app/src/main/assets/lookalikes.txt` (neu, aus dem Trainings-Repo gestaged).
- `app/build.gradle.kts`: `versionCode = 7`, `versionName = "0.6.0"`.
- Tests: `HistoryStoreTest`, `FrameQualityTest`, `LookalikeDataTest`,
  `ResultFormatterTest`, erweiterter `VerdictPolicyTest`.

Training (`Mushroom-Exposed-training`):

- `src/extract_lookalikes.py` (neu), `src/lookalikes_curated.json` (neu),
  `tests/test_extract_lookalikes.py` (neu), `src/verify_and_stage.py`
  (erweitern), `README.md` (Abschnitt zu Lookalike-Daten).

## Fehlerbehandlung

- Modell oder Labels fehlen: Fehlertext wie bisher, Auslöser deaktiviert.
- Kamera-Bind schlägt fehl: Meldung, Auslöser deaktiviert.
- Inferenzfehler pro Auslöser: Ergebnis-Karte zeigt die Fehlermeldung im
  FROZEN-Zustand, „Neu" bleibt bedienbar.
- Historie nicht lesbar/schreibbar: Erkennung läuft weiter, Hinweis-Toast.
- `lookalikes.txt` fehlt/kaputt: keine Doppelgänger-Warnungen, sonst volle
  Funktion.
- Taschenlampe auf Geräten ohne Blitz: Button wird nicht eingeblendet.
- Erster Frame noch unbelichtet (Luminanz ≈ 0): Hinweis „Mehr Licht" statt
  Auslöser-Sperre; der Auslöser bleibt immer bedienbar.

## Teststrategie

- JUnit (`./gradlew testDebugUnitTest`) für alle reinen Komponenten:
  Verlaufsdatei (Append, Reihenfolge, Cap, Löschen, korrupte Zeilen),
  Bildqualität (Schwellen), Lookalike-Parsing (gültig, unbekannt, defekt),
  Formatierung, Verzehrlogik inklusive Lookalike-Fall.
- Python `unittest` für die Lookalike-Extraktion (Belegsatz wird gefunden,
  nicht-giftige Ähnlichkeit wird `achtung` oder verworfen, nicht auflösbarer
  Name wird verworfen, kuratierte Einträge gewinnen).
- Geräte-Smoke-Test am Redmi (USB):
  `./gradlew testDebugUnitTest assembleDebug`, Installation, Start,
  Prozess- und Kamera-Client-Prüfung, Logcat-Suche nach `Failed to load model`,
  `Inference error`, `Frame error`; manuell: Auslöser → Ergebnis bleibt stehen,
  Neu, Taschenlampe, Tap-to-Focus, Verlauf öffnen/löschen, Flugmodus-Test
  (Historie bleibt lokal verfügbar).
- Keine automatisierten Kamera-Instrumentierungstests: Sie wären nur mit
  Emulator-Fake-Kamera sinnvoll und würden die echte Kamerakette nicht prüfen.

## Auslieferung

- Arbeit auf `feat/feldmodus-ux`, Conventional Commits.
- Merge nach `main` erst nach bestandenem Smoke-Test; danach APK-Handoff als
  `/home/m3kky/projects/DM_APK_Android/mushroom-exposed-0.6.0-debug-<merge-commit>.apk`
  mit `cmp -s`-Verifikation gegen `app/build/outputs/apk/debug/app-debug.apk`.
- Das Modell-Asset (`model.tflite`, `labels.txt`) bleibt in diesem Schritt
  unverändert; `lookalikes.txt` kommt neu hinzu.
