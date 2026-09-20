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

**Nachtrag (Vollreport):** Die Vollfassung verlangt zusätzlich, dass die
zerstörerische Aktion der normalen Rückkehr *optisch untergeordnet* ist. Der
Vollreport nannte zwei gleich gewichtige Vollbreiten-Knöpfe. `Löschen` ist
deshalb jetzt ein randloser Textknopf unter dem primären
`Zurück zur Kamera` und trägt nur die dunkle Gefahrfarbe als Text.

### 5 (Low) Maschinenformatierung

`Cantharellus_cibarius` statt `Cantharellus cibarius`, ISO-Zeitstempel statt
lokalem Datum, eine dichte Verlaufszeile mit redundanten `!`/`⚠`-Signalen.

Entscheidung: `displayName()` ersetzt den Unterstrich, `displayTimestamp()`
lokalisiert auf `dd.MM.yyyy, HH:mm`. **Das Speicherformat bleibt unverändert** —
`history.jsonl` behält den maschinenlesbaren Stempel, nur die Anzeige übersetzt
ihn. Ein unlesbarer Alt-Wert fällt unverändert durch.

**Nachtrag (Vollreport):** Die Vollfassung fordert drei Dinge mehr, die in der
ersten Runde fehlten.

- **Struktur statt einer dichten Zeile:** `ResultFormatter.historyRow()` zerlegt
  einen Eintrag in Art, Urteil und Datum. Die Anzeige setzt daraus drei Zeilen:
  Art kursiv, Urteil mit Zeichen und Wort, darunter ruhig Datum und Hinweis.
- **Kursivsatz:** Artnamen in Unterzeile und Verlauf sind kursiv gesetzt — ein
  Fachname, keine Überschrift.
- **Ein erklärtes Warnzeichen:** Vorher standen `☠` **und** `⚠` nebeneinander,
  beide unerklärt. Jetzt trägt nur das Urteil ein Zeichen; das
  Verwechslungsrisiko steht als Wort `Verwechslungsrisiko` in der Metazeile.

### Ergänzung: Warnpalette bei Verwechslungsrisiko (Finding 1)

Die Vollfassung verlangt, dass ein Ergebnis mit gefährlicher
Verwechslungswarnung in der Warnpalette bleibt. Das war bereits durch
`VerdictPolicy.decide()` abgesichert (drops to `CAUTION`) und ist jetzt
zusätzlich gepinnt: `DarstellungsFormatTest` prüft, dass kein essbarer Treffer
im Achtung-Zustand ein Haken-Zeichen trägt.

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

## Testfenster statt fester Länge

`BedienungTest.blockOf()` liest ein Element bis zu seinem `/>`, nicht bis zu
einer festen Zeichenzahl. Ein festes Fenster griff Attribute des
Nachbarelements ab: der Test war damit grün, obwohl das geprüfte Attribut gar
nicht mehr im Ausschnitt lag. Beim Gegenbeweis fiel das auf, weil Gradle die
Test-Task ohne `--rerun-tasks` als aktuell ansah und das Grün nichts bedeutete.

## Betroffene Dateien

Geändert: `strings.xml`, `colors.xml`, `activity_main.xml`,
`AndroidManifest.xml`, `MainActivity.kt`, `ResultFormatter.kt`,
`ResultFormatterTest.kt`, `SystemBarsLayoutTest.kt`.

Unverändert: Modell, `labels.txt`, `lookalikes.txt` und die Sicherheitslogik
(`VerdictPolicy`, `ToxicGenus`, `LookalikeData`, `FrameQuality`).
