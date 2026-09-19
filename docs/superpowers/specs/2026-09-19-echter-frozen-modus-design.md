# Echter LIVE/FROZEN-Modus — Design

## Ausgangslage

Die Feldmodus-Spec `docs/superpowers/specs/2026-09-15-feldmodus-ux-design.md`
fordert eine explizite Zustandsmaschine: Im Zustand LIVE führt die bewegte
Vorschau; nach einem Auslöser zeigt FROZEN den ausgelösten Frame als Standbild,
führt genau eine Analyse aus und bleibt bis „Neu“ stabil.

In Version 0.7.0 wird der Bitmap zwar in `MainActivity.frozen` gespeichert, aber
nirgends angezeigt. Die Vorschau und die Live-Qualitätshinweise laufen weiter.
Der Button „Nochmal“ löst aus dem vermeintlichen Ergebniszustand sofort eine
weitere Analyse aus. Damit widerspricht das sichtbare Verhalten der bestehenden
Spec und dem README.

## Ziel

Ein Auslöser muss sichtbar und deterministisch zwischen drei Zuständen wechseln:

- **LIVE:** Kameravorschau, Zielrahmen und Qualitätsfeedback sind aktiv. Der
  Primärbutton heißt „Analysieren“.
- **ANALYSING:** Der ausgelöste Frame liegt als Standbild über der Vorschau.
  Qualitätsfeedback ist aus, der Primärbutton ist deaktiviert und zeigt
  „Analysiere …“. Weitere Auslöser werden ignoriert.
- **FROZEN:** Derselbe Frame und das fertige Ergebnis bleiben unverändert
  sichtbar. Der Primärbutton heißt „Neu“.

„Neu“ wechselt ausschließlich zurück zu LIVE. Erst ein weiterer Druck auf
„Analysieren“ nimmt einen neuen Frame auf.

## Ansatz

### Reine Zustandsmaschine

`FieldMode.kt` enthält Android-unabhängig:

- `FieldMode` mit `LIVE`, `ANALYSING`, `FROZEN`
- `FieldModeAction` mit `CAPTURE`, `RETURN_TO_LIVE`, `IGNORE`
- `FieldModeMachine` mit atomaren Übergängen für Primärbutton,
  fehlenden Kameraframe und Abschluss der Analyse
- `acceptsQualityUpdates`, nur in LIVE wahr

Der Zustand ist `@Volatile`, weil der Main-Thread schreibt und der
Quality-Executor liest. Übergangslogik wird vollständig per JUnit getestet.

### Standbild-Overlay

Ein an alle Seiten des `PreviewView` gebundenes `ImageView` mit `centerCrop`
liegt direkt über der Vorschau und beginnt mit `visibility="gone"`. Beim
Auslösen erhält es exakt den von `PreviewView.bitmap` gelieferten Frame. Beim
Zurückkehren zu LIVE wird Drawable und Bitmap-Referenz entfernt.

Die CameraX-Pipeline bleibt gebunden. Dadurch gibt es weder Rebind-Latenz noch
einen unzuverlässigen Surface-Snapshot. Live-Qualitätsframes werden weiterhin
ordnungsgemäß geschlossen, aber außerhalb von LIVE weder berechnet noch in die
UI geschrieben.

### UI-Übergänge

- Capture erfolgreich: Overlay sichtbar; Zielrahmen und Qualitätshinweis weg;
  Button deaktiviert mit „Analysiere …“.
- Analyse erfolgreich oder fehlgeschlagen: Zustandswechsel zu FROZEN; Button
  aktiviert mit „Neu“; Ergebnis beziehungsweise Fehlermeldung bleibt stehen.
- Capture noch nicht verfügbar: zurück zu LIVE, bestehender Toast; kein
  festhängender Busy-Zustand.
- „Neu“: Overlay und alte Ergebniselemente ausblenden, Bereitschaftstext
  herstellen, Zielrahmen und Live-Qualitätsfeedback wieder zulassen.

Modell, Vorverarbeitung, VerdictPolicy, Verwechslungsdaten und Historie bleiben
unverändert.

## Verworfene Alternativen

- **CameraX unbind/rebind:** friert nicht auf allen Geräten zuverlässig den
  letzten Surface-Frame ein und erzeugt sichtbare Wiederanlaufzeit.
- **ImageCapture-Use-Case:** liefert hochwertige Fotos, vergrößert aber Scope,
  Latenz und Rotations-/Dateihandling, obwohl bereits ein passender Preview-
  Bitmap vorliegt.
- **Nur Busy-Boolean:** verhindert Mehrfachtipps, modelliert aber LIVE und
  FROZEN nicht und lässt „Neu“ sowie Qualitätsupdates weiter fehleranfällig.

## Akzeptanzkriterien

1. Ein Druck in LIVE zeigt den aufgenommenen Frame als Standbild und startet
   genau eine Analyse.
2. Während ANALYSING sind weitere Primäraktionen wirkungslos und es erscheinen
   keine Live-Qualitätsupdates.
3. Erfolg und Inferenzfehler enden in FROZEN; Standbild und Ergebnis bleiben bis
   „Neu“ sichtbar.
4. „Neu“ kehrt ohne Analyse zu LIVE zurück; ein danach folgender Druck nimmt
   einen neuen Frame auf.
5. Ein fehlender Preview-Frame lässt den Zustand in LIVE zurückfallen.
6. Alle Zustandsübergänge sind durch JUnit-Regressionstests gedeckt.
7. Unit-Suite und `assembleDebug` sind grün; ein Android-Smoke-Test bestätigt
   Buttontexte, stabile Standbildanzeige, Rückkehr zu LIVE und erneutes Auslösen.
