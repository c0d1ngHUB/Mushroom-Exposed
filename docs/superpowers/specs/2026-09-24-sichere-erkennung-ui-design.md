# Sichere Erkennung und Feld-UI — Design

**Datum:** 2026-09-24  
**Status:** von Markus freigegeben

## Ziel

Die Feld-App soll Nutzern ein fotografisches Pilzmerkmal ruhig und verständlich erfassen lassen, ohne Erkennungswahrscheinlichkeit als Verzehrerlaubnis erscheinen zu lassen. Die echte Qualitätssteigerung erfolgt modellseitig und wird erst nach einem bestandenen Dual-Source-Gate ausgeliefert.

## Befund und Sicherheitsgrenze

Das ausgelieferte seed-17-Modell erreicht für drei Klassen auf der bereinigten, eingefrorenen Holdout-Schnittmenge nur 1/43 korrekte Top-1-Treffer und erzeugt mindestens eine falsche, grün bleibende essbar-Einschätzung. Die Eingabeart ist bei einer Fehlklassifikation im Client nicht bekannt. Eine Sperrliste der *vorhergesagten* Arten wäre daher eine unbelegte, unvollständige Heuristik und wird nicht gebaut.

Bis ein Modellkandidat alle Gates besteht, gilt deshalb:

- `giftig` bleibt eine rote Warnung mit Notfallblock.
- Jede andere Modellklassifikation wird als **Bestimmungshinweis — Verzehr nicht bewertbar** dargestellt, auch bei hoher Konfidenz und `essbar`-Label.
- Die Top-3 bleiben sichtbar, damit Menschen Alternativen vergleichen können; eine Prozentzahl ist Ranginformation, keine Sicherheitszusage.

## Visuelle Richtung

Die App bleibt ein zurückhaltender Naturführer: Papier, Tinte, gedämpftes Waldgrün und klare Notfallfarben. Der eine erinnerbare Moment ist der Aufnahmeauslöser: ein helles, optisches Messwerkzeug mit dunklem Ring, nicht ein grün gefülltes „Go“.

### Tokens

| Rolle | Wert | Zweck |
|---|---:|---|
| Papier | `#FFFAF6EE` | Aufnahme- und Kartenfläche |
| Tinte | `#FF1E241C` | primäre Schrift und Auslöserring |
| Wald | `#FF2F5D3A` | Navigation und nicht-sicherheitskritische Akzente |
| Moos | `#FF6B8F3A` | nur Qualitäts-/Fokusakzent, nie Verzehrurteil |
| Achtung | `#FFA86400` | unbewerteter Verzehr und Verwechslungsrisiko |
| Gefahr | `#FFB3261E` | giftiger Befund und Notfallaktionen |

Die vorhandene Schriftfamilie bleibt, damit keine Download-Abhängigkeit entsteht. Fachnamen bleiben kursiv; Text bleibt in Sentence Case. Keine zusätzlichen All-Caps-Label, Häkchen oder positiven Ampelsymbole.

## Feld-Flow

```text
Kamera
 ├─ Status: „Bereit zum Bestimmen“
 ├─ kurze, konkrete Bildhilfe: Licht / zu hell / ruhig halten / Pilz ins Quadrat
 └─ neutraler Auslöser „Bild analysieren“
       ↓
Eingefrorenes Bild + „Analysiere …"
       ↓
Ergebnis
 ├─ Giftig: rote Warnung + erreichbarer Notfallblock
 └─ Bestimmungshinweis: gelber Hinweis + Top-3 zum Vergleichen
       ↓
„Neu aufnehmen“ oder Verlauf
```

`Gut — auslösen` wird durch einen beschreibenden Bildausschnitt-Hinweis ersetzt, weil der bisherige Wortlaut eine Qualitätsgarantie suggeriert. Die CTA bleibt über Content-Description und sichtbare Beschriftung konsistent: **Bild analysieren** / **Neu aufnehmen**.

## Modellseitige Verbesserung

Die pauschale GBIF-Deckelung wird nicht zum Auslieferungsweg: sie verschlechterte sowohl PVV-Top-3 als auch GBIF-Top-1. Stattdessen erhält der Trainings-DataLoader reproduzierbare, quellenbalancierte Sampling-Gewichte. Pro Quelle (PVV/SoFa als regionaler Pool vs. GBIF) wird je Epoche dieselbe Zielmasse gezogen, ohne vorhandene Bilder zu löschen. Klassen-Gewichte im Loss bleiben unverändert.

Ein Kandidat darf nur exportiert und in die App staged werden, wenn:

1. GBIF-Macro-Top-1 gegenüber seed-17 mit 95-%-KI-Untergrenze > 0 besteht;
2. PVV-Macro-Top-3 nicht regressiert;
3. die bekannte Fehlfreigabe-Messung dokumentiert und nicht schlechter ist;
4. PyTorch–TFLite-Parität, Klasse/Label-Vertrag und Asset-Identität grün sind.

Ein fehlgeschlagenes Gate verändert nie `app/src/main/assets/model.tflite`.

## Akzeptanzkriterien

- Ein `essbar`-Label mit 99 % kann keinen grünen/verharmlosenden Verzehrtext, Haken oder SAFE-Ton auslösen.
- Ein `giftig`-Label bleibt rot und zeigt den bestehenden Notfallblock.
- Der Auslöser nutzt keine grün gefüllte Freigabeoptik und trägt die sichtbare Aktion „Bild analysieren“.
- Qualitäts-Hinweise sind konkret, nicht als Garantie formuliert, und der gute Zustand beschreibt den Bildausschnitt.
- Source-Balance ist deterministisch, erhält alle Trainingsbeispiele und gleicht Quellenmasse aus.
- APK, Unit- und Instrumented-Tests laufen grün; ein neuer Modellkandidat wird nur bei allen Gate-Erfolgen staged.
