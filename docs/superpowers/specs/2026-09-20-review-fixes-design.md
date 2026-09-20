# UI/UX-Review 2026-09-20 — Fixes

Datum: 2026-09-20
Status: umgesetzt auf `main`
Vorgänger: `2026-09-19-wald-design-design.md`

## Anlass

Ein UI/UX-Review meldete fünf reproduzierbare Findings: zwei High, zwei Medium,
eins Low. Positiv vermerkt wurden grosse Touch-Ziele, die Content-Descriptions,
die korrekten Systemleisten im Portrait und die erreichbaren Notrufnummern.

## Findings und Entscheidungen

### 1 (High) Widersprüchliche Sicherheitssignale

Bei 60 % erschien „essbar" zugleich grün, mit der Marke `FREIGABE` und einem
`✓` — während derselbe Bildschirm vor dem tödlichen Ölbaumpilz warnte. Auch im
gelben Zustand standen `✓` neben 57 % und 19 %.

Entscheidung: „essbar" ist eine **Modellschätzung, keine Freigabe**.

- Marke `FREIGABE` → `Modellschätzung` (neutral).
- Die Fläche des essbaren Urteils trägt nicht mehr `verdict_safe`, sondern
  `verdict_neutral*`. Grün bleibt der Warnfarbe vorbehalten.
- Das `✓` entfällt für essbare Treffer; nur `☠` bleibt, weil eine giftige Art
  das Einzige ist, was in der Trefferliste wirklich warnen muss.
- Der Ton `SAFE` und die Überschrift „essbar" bleiben: die Skala ist jetzt
  neutral statt Freigabe, nicht stumm.

### 2 (High) Landscape nicht sicher nutzbar

Qualitätshinweis und Statusbereich überschnitten sich, die Top-3 wurde
abgeschnitten, der Notfallblock erschien erst nach manuellem Scrollen.

Entscheidung: **Portrait-Lock** im Manifest, wie im Review als kurzfristige
Option genannt. Ein eigenes Landscape-Layout bleibt offen und ist nicht Teil
dieser Änderung; die Sperre verhindert nur den unsicheren Zustand.

### 3 (Medium) Auslöser 73 dp links der Bildschirmmitte

Ursache: `android:gravity="center_vertical"` auf der horizontalen
Steuergruppe — vertikal ausgerichtet, horizontal blieb der Inhalt links kleben.

Entscheidung: `android:gravity="center"`. Am Gerät gemessen: 73,5 dp Abweichung
vorher, 0 dp nachher.

### 4 (Medium) Verlauf-Löschen immer aktiv

Im leeren Zustand blieb der prominente rote Knopf anklickbar; bei vorhandenen
Einträgen fehlte jede Rückfrage.

Entscheidung: Der Knopf folgt dem Zustand (`isEnabled` aus, 50 % Deckkraft bei
leerem Verlauf). Gelöscht wird erst nach einem `AlertDialog` mit Abbrechen als
Standardantwort — Löschen ist unwiderruflich.

### 5 (Low) Maschinenformatierung

`Cantharellus_cibarius` statt `Cantharellus cibarius`, ISO-Zeitstempel statt
lokalem Datum, eine dichte Verlaufszeile mit redundanten `!`/`⚠`-Signalen.

Entscheidung: `displayName()` ersetzt den Unterstrich, `displayTimestamp()`
lokalisiert auf `dd.MM.yyyy, HH:mm`, die Verlaufszeile trägt nur noch `☠` plus
Warnflag und ihre Farbe ist für essbar neutral. **Das Speicherformat bleibt
unverändert** — `history.jsonl` behält den maschinenlesbaren Stempel, nur die
Anzeige übersetzt ihn. Ein unlesbarer Alt-Wert fällt unverändert durch.

## Tests

Neu (Unit): `SicherheitsdarstellungTest`, `DarstellungsFormatTest`,
`AusrichtungTest`, `BedienungTest`.
Neu (instrumented): `shutterIsCentredOnTheScreen`, `theActivityRequestsPortraitOnly`,
`anEdibleVerdictIsNeverPaintedAsAClearance`, `theClearButtonFollowsTheHistoryState`.

Jeder neue Test wurde **gegen den alten Zustand geprüft** und schlägt dort fehl
— sonst bewacht er nichts:

| Test | Befund im alten Zustand |
|---|---|
| `shutterIsCentredOnTheScreen` | „off by 193px (73.52381dp)" |
| `theActivityRequestsPortraitOnly` | `expected:<1> but was:<-1>` |
| `anEdibleVerdictIsNeverPaintedAsAClearance` | „was 'FREIGABE'" |

Der Portrait-Test prüft `requestedOrientation`, nicht die zufällig vorliegende
`Configuration.orientation`: auf einem hochkant gehaltenen Gerät wäre die
Laufzeitausrichtung auch ohne Sperre portrait, der Test also wertlos.

## Betroffene Dateien

Geändert: `strings.xml`, `colors.xml`, `activity_main.xml`,
`AndroidManifest.xml`, `MainActivity.kt`, `ResultFormatter.kt`,
`ResultFormatterTest.kt`, `SystemBarsLayoutTest.kt`.

Unverändert: Modell, `labels.txt`, `lookalikes.txt` und die Sicherheitslogik
(`VerdictPolicy`, `ToxicGenus`, `LookalikeData`, `FrameQuality`).
