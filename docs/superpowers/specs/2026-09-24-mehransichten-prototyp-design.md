# Mehransichten-Erkennung mit anatomischen Qualitätsnachweisen — Design

**Datum:** 2026-09-24  
**Status:** freigegeben für Spezifikation und Planung  
**Geltungsbereich:** nichtkommerzieller, lokaler Prototyp von Mushroom Exposed

## Ziel

Die App ersetzt den Foto-Auslöser durch eine Hold-to-scan-Interaktion. Während des Gedrückthaltens erfasst sie drei nachweisbare Ansichten eines einzelnen Fruchtkörpers: **Hut**, **Unterseite** (Lamellen oder Poren) und **Stiel/Ring**. Das Ergebnis erscheint automatisch erst, wenn die Ansichten qualitativ ausreichend sind und die Artwahrscheinlichkeiten der ausgewählten Frames konsistent sind.

Ein grüner Status bedeutet ausschließlich **„diese Bildansicht wurde ausreichend erfasst“**. Er bedeutet weder eine sichere Bestimmung noch eine Verzehrfreigabe. Der konservative Verzehrhinweis der App bleibt unverändert.

## Belegter Datensatz- und Lizenzrahmen

FungiTastic-M beschreibt menschlich unterstützte Masken für Hut, Lamellen, Poren, Ring und Stiel.[6] Seine Kaggle-Metadaten nennen CC BY-NC-SA 4.0.[7] Der Prototyp ist deshalb strikt nichtkommerziell. Vor einem kommerziellen Vertrieb oder der Weitergabe eines daraus abgeleiteten Modells sind Herkunft, Attribution und Share-Alike-Pflichten rechtlich gesondert zu prüfen.

Die Datenquelle deckt **nicht** verlässlich die Stielbasis/Volva als eigene Klasse ab. Die Oberfläche nennt deshalb nie „Stielbasis“, sondern **„Stiel / Ring“**.

## Architektur

```
CameraX ImageAnalysis (flüchtige Frames)
  ├─ FrameQualityAnalyzer ────────────────┐
  ├─ ViewpointSegmenter.tflite ───────────┼─> ViewAccumulator
  └─ per-view Keyframe selection ─────────┘       │
                                                   ├─ UI: Hut / Unterseite / Stiel-Ring
                                                   └─ SpeciesConsensus
                                                          │
                                         existing model.tflite on selected frames
                                                          │
                                                    VerdictPolicy → ResultView
                                                          │
                                               HistoryStore + one reference image
```

### 1. `ViewpointSegmenter`

Ein neuer, separater TFLite-Segmentierer arbeitet auf jedem qualitätsgeprüften Analyseframe und produziert Masken für `cap`, `gills`, `pores`, `stipe` und `ring` plus Hintergrund. `Unterseite` gilt als erfasst, wenn `gills` **oder** `pores` die kalibrierte Abdeckungs- und Schärfeanforderung erfüllt. `Stiel / Ring` gilt als erfasst, wenn `stipe` oder `ring` sie erfüllt.

Die Akzeptanzgrenzen werden nicht aus festen UI-Werten abgeleitet. Sie werden auf einer nach Beobachtung getrennten Validierungsmenge kalibriert und als versionierte Modellkonfiguration ausgeliefert.

### 2. `ViewAccumulator`

`ViewAccumulator` besitzt ausschließlich diese Zustände:

- `COLLECTING`: Noch keine ausreichend gute Ansicht.
- `CAPTURED`: Beste akzeptierte Ansicht und zugehöriger Qualitätswert liegen im RAM vor.
- `NEEDS_GUIDANCE`: Seit der konfigurierten Zeit wurde keine gewünschte Ansicht akzeptiert.
- `COMPLETE`: Alle drei Ansichten liegen vor und können an den Konsens übergeben werden.
- `CANCELLED`: Nutzer lässt los, Activity pausiert oder die Kamera endet.

Bei `CANCELLED` und jedem Ergebnis werden alle temporären Bitmaps und Tensoren freigegeben. Es gibt keinen Videoclip, keine temporäre Videodatei und keine Upload-Schnittstelle.

### 3. `SpeciesConsensus`

Für jede `CAPTURED`-Ansicht werden nur die besten Keyframes dem bestehenden Artenmodell übergeben. Die Klassifikationswahrscheinlichkeiten werden ansichtsgewichtet aggregiert. Ein Ergebnis wird nur erzeugt, wenn:

1. alle drei Ansichten `CAPTURED` sind,
2. der Ansichtsdetektor keine unzureichende Abdeckung meldet und
3. der Konsens die auf einem ungesehenen Holdout festgelegte Stabilitätsgrenze erreicht.

Die konkreten Grenzen gehören zur evaluierten Modellkonfiguration, nicht in den UI-Code. Der bestehende `VerdictPolicy`-Schutz bleibt nach dem Konsens der letzte Entscheider für die Darstellung.

### 4. Sichtbare Führung

Die Live-Oberfläche zeigt genau drei ruhige, sequenzielle Zustandszeilen:

1. `Hut zeigen`
2. `Unterseite zeigen`
3. `Stiel / Ring zeigen`

Eine Zeile wird erst grün, wenn `ViewAccumulator` eine belegte Ansicht akzeptiert hat. Bei unzureichender Qualität zeigt sie eine konkrete Anweisung wie „Näher heran und ruhig halten“ oder „Unterseite zeigen“. Ein grüner Status enthält weder Häkchen für Essbarkeit noch eine Sicherheits- oder Freigabeformulierung.

Das sichtbare Leitmotiv ist ein neutraler Sammelring, nicht ein grüner Kameraauslöser. Umgebende Bedienelemente bleiben ruhig; die Fortschrittszeilen sind das einzige dynamische Element.

### 5. Referenzbild und Verlauf

Nach einem Ergebnis speichert `ReferenceImageStore` genau ein Bild: den Frame mit dem höchsten kombinierten Ansichts-, Schärfe- und Konsensscore. Das Bild wird lokal neu als JPEG kodiert und enthält keine übernommene EXIF-/GPS-Metadaten.

`HistoryEntry` referenziert die Bilddatei relativ innerhalb von `files/history/images/`. Verlaufseintrag und Bild sind ein Paar:

- maximal 200 Paare,
- beim Hinzufügen von Paar 201 wird das älteste Bild gelöscht und sein Eintrag entfernt,
- bei I/O-Fehlern wird kein unvollständiger Eintrag sichtbar,
- „Verlauf löschen“ löscht JSONL und sämtliche Referenzbilder gemeinsam,
- ein alter Verlauf ohne Bildreferenz bleibt lesbar.

Es gibt keine Cloud-Synchronisierung und keine automatische Weitergabe.

## Fehlerbehandlung

- Fehlt der Ansichtsdetektor oder stimmt sein Modellvertrag nicht, beginnt keine Mehransichten-Erkennung; die App nennt den Modellfehler und bietet keine simulierten grünen Ansichten.
- Ist ein Frame zu dunkel, überbelichtet oder unscharf, wird er verworfen und die vorhandene Qualitätshilfe wiederverwendet.
- Wird eine Ansicht erkannt, aber nicht ausreichend sichtbar, bleibt sie neutral; die App benennt den nächsten sinnvollen Schritt.
- Fällt der Artenkonsens unter seine Release-Grenze, wird keine Art als stabil dargestellt; `VerdictPolicy` bleibt im CAUTION-Pfad.
- Ein giftiger Top-Treffer, eine giftige Alternative oder ein gefährlicher Doppelgänger überstimmt nie eine harmlose Darstellung.

## Trainings- und Release-Gates

1. **Datenvertrag:** Nur FungiTastic-M-Teilmasken und Bilder mit nachvollziehbarem nichtkommerziellem Prototypzweck; Lizenzhinweis in App und Projektdokumentation.
2. **Leakage-freie Splits:** Alle Fotos derselben Beobachtung liegen ausschließlich in Train, Val oder Test.
3. **Segmentierung:** Metriken je Klasse (`cap`, `gills`, `pores`, `stipe`, `ring`), zusätzlich Fehlerraten für fälschlich grüne Ansichten. Eine globale IoU allein reicht nicht.
4. **Artenkonsens:** Gegen das aktuelle Einzelbildmodell auf identischen, ungesehenen Beobachtungen messen; Annahme nur bei belastbarer Verbesserung und ohne Verschlechterung der Sicherheitsmetriken.
5. **Export:** PyTorch↔TFLite-Parität für Segmentierer und Artenmodell; Modellform, Klassenreihenfolge und Konfigurationshash werden vor APK-Staging geprüft.
6. **Android:** Unit-Tests für Zustandsautomat, Auswahl und Löschung von Bild/Verlaufspaaren; Instrumented Tests für die drei Zustandszeilen, Abbruch ohne Speicherung und automatische Ergebnisanzeige; Emulator-Smoke-Test mit Kamera.
7. **Kein Release-Gate durch UI:** Eine animierte oder grüne UI darf nie ein Modell-Gate ersetzen.

## Nicht im Prototyp

- Kein Speichern oder Hochladen von Video.
- Keine `Stielbasis`-/Volva-Zusage.
- Keine Essbarkeitsfreigabe.
- Kein kommerzieller Export, Vertrieb oder Modell-Upload.
- Kein Multi-Task-Modell; Segmentierung und Artklassifikation bleiben getrennt.

## Sources

[6] https://github.com/BohemianVRA/FungiTastic — FungiTastic official repository
[7] https://www.kaggle.com/datasets/picekl/fungitastic — FungiTastic Kaggle dataset
