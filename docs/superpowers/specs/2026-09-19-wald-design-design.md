# UI/UX-Redesign „Wald" — Design

Datum: 2026-09-19
Status: umgesetzt auf `feat/wald-design-ui`
Vorgänger: `2026-09-15-feldmodus-ux-design.md` (Feldmodus-Ablauf, unverändert)

## Anlass

Rückmeldung aus der Nutzung: der Auslöser ist zu klein und zu weit unten, mit
Handschuhen schlecht zu treffen. Außerdem stand das Theme noch auf den
Template-Farben (`purple_500`/`teal_200`) und der Ergebnis-Bildschirm bestand
aus halbtransparenten schwarzen Balken, die von oben über das Standbild
gestapelt wurden.

## Entscheidungen

| Frage | Entscheidung |
|---|---|
| Umfang | Grunddesign, Live-Sucher und Ergebnis-Bildschirm zusammen |
| Ergebnis-Bildschirm | Karte als Bottom-Sheet von unten; Standbild bleibt sichtbar |
| Stilrichtung | Wald/Grün, heller Papier-/Karton-Look wie ein Naturführer |
| Auslöser | Groß, rund, 88 dp, mittig unten |
| Notrufnummern | Antippbare Anruf-Flächen plus Restpilz-Hinweis |

Bewusst nicht: Dark Mode. Die Kamera-Vorschau ist ohnehin dunkel, ein zweites
Theme wäre Aufwand ohne Nutzen. Bewusst nicht: `DayNight` als Eltern-Theme.

## Farbwelt

Papier/Karton als Grund, Waldgrün als Primärfarbe, Moos als Akzent — statt
Template-Violett. Flächen statt Textfarben: die Ampel wirkt jetzt über eine
kräftige Marke *und* eine zarte Fläche.

| Rolle | Marke | Zarte Fläche | Dunkle Schrift |
|---|---|---|---|
| sicher | `#2E7D32` | `#E7F2E4` | `#1F6B32` |
| Achtung | `#A86400` | `#FBF0DA` | `#7A4A00` |
| giftig | `#B3261E` | `#FBE9E7` | `#8F1D14` |

Die dritte Spalte existiert, weil die Markenfarbe auf der eigenen zarten Fläche
nicht überall lesbar ist. Gemessen (WCAG-Verhältnis Marke/Fläche): sicher 4,45,
Achtung 4,14, giftig 5,58. Für Fließtext ist das bei zwei von drei Urteilen zu
wenig, deshalb dunkle Schriftvarianten. `ViewStylingTest` hält diese Messungen
fest, damit die Annahme nicht still verrutscht.

Anruf-Flächen: Vergiftungsinfo `#C2311F` (weiß darauf 5,60), Notruf `#8F1D14`
(weiß darauf 8,92).

## Aufbau

**Live-Sucher.** Zielrahmen als vier Eckwinkel statt geschlossenem Rahmen.
Qualitätshinweis als Pille direkt unter dem Rahmen, Farbe folgt dem Urteil.
Unten eine Leiste: `Licht` und `Verlauf` als runde 54-dp-Ziele, dazwischen der
Auslöser mit 88 dp, Ring und Beschriftung *darunter* — Text im Kreis war im
ersten Mockup nicht lesbar.

**Ergebnis.** Karte fährt als Bottom-Sheet von unten über das Standbild.
Reihenfolge: Ampel-Marke (`GIFTIG`/`ACHTUNG`/`FREIGABE`), Artname in Serif,
Art plus Konfidenz, Warnkarte, Top-3 als eigene Zeilen, Notfallblock.

**FROZEN-Navigation.** Das Sheet deckt die Steuerleiste. `Neu` und `Verlauf`
wandern deshalb als schwebende Chips oben rechts über den sichtbaren Rest des
Standbilds. Ohne diese Chips gäbe es im eingefrorenen Zustand keinen Weg
zurück zur Kamera und keinen zum Verlauf.

**Notfall.** Fix am unteren Rand, zwei große Flächen mit den Nummern als
Anruf-Link, darunter der Restpilz-Hinweis.

## Notfall-Wortlaut bleibt gepinnt

`ResultFormatter.EMERGENCY_TEXT` war und bleibt der kanonische Satz, den ein
Unit-Test Wort für Wort pinnt. Damit die Nummern nicht an zwei Stellen
gepflegt werden müssen, sind sie als Teile hinterlegt
(`POISON_CONTROL_NUMBER`, `EMERGENCY_NUMBER`); der Satz wird aus denselben
Teilen zusammengesetzt und die Anruf-Flächen zeigen dieselben Werte.
`EmergencyWordingTest` prüft, dass Konstante, Ressource `R.string.emergency_text`
und die Zusammensetzung aus den Teilen nicht auseinanderlaufen. Der Block selbst
trägt den gepinnten Satz als `contentDescription`, damit Vorlesehilfen denselben
Wortlaut lesen.

## Barrierefreiheit

Jedes Antippziel ist mindestens 48 dp: Auslöser 88 dp, Licht und Verlauf 54 dp,
die Chips 48 dp, die Anruf-Flächen 64 dp hoch. Ein Instrumented-Test auf dem
Gerät prüft das und dass die Statuspille unter der Statusleiste beginnt.

## Betroffene Dateien

Neu: `ViewStyling.kt`, `layout/row_top_hit.xml`, neun Drawables, `dimens.xml`.
Geändert: `activity_main.xml`, `MainActivity.kt`, `ResultFormatter.kt`,
`colors.xml`, `themes.xml`, `strings.xml`.

Modell, `labels.txt` und die Sicherheitslogik (`VerdictPolicy`, `ToxicGenus`,
`LookalikeData`, `FrameQuality`) sind unverändert.
