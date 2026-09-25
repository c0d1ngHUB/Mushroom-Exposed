# Drei-Ansichten-Kalibrierung Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Die Erfassung von Hut, Unterseite und Stiel/Ring mit dem vorhandenen Segmentierer gezielt verbessern, ohne Fehlalarme oder den Recall einer Ansicht gegenüber dem aktuellen Betrieb zu verschlechtern.

**Architecture:** Ein neuer, reiner Python-Betriebspunkt-Evaluator entspricht exakt dem Android-Frame-Entscheid für fünf Kanäle. Auf `val` gewählte Kanal-Schwellen werden auf `test` gegen den ausgelieferten Betrieb verglichen. Android interpretiert einen versionierten v2-Vertrag und lässt v1 unverändert. Ein separates, fail-closed Staging übernimmt nur die geprüfte Konfiguration, nie das Arten- oder Ansichtsmodell.

**Tech Stack:** Python 3, NumPy, pytest, PyTorch (bestehende Messpipeline), Kotlin, Android Views/CameraX, TensorFlow Lite, JUnit 4, Gradle/Android-Emulator.

**Spec:** `docs/superpowers/specs/2026-09-25-ansichten-kalibrierung-design.md`

## Global Constraints

- Zwei Repositories: `APP=/home/m3kky/projects/Mushroom-Exposed`; `TRAIN=/home/m3kky/projects/Mushroom-Exposed-training/.worktrees/feat-viewpoint` als gegenwärtiger Branch mit Segmentierer-Messcode. Alle unten genannten Pfade sind relativ zum jeweiligen Repo. Bei Ausführung isolierte Worktrees/Branches anlegen und `export APP=/pfad/zum/app-worktree TRAIN=/pfad/zum/trainings-worktree` auf die tatsächlich gewählten absoluten Pfade setzen; die unzusammenhängenden lokalen Änderungen im Trainings-`main` und das ungetrackte `.superpowers/` im App-Repo nicht anfassen.
- Ziel-Asset bleibt `skip-300-seed42` mit 300 × 300 Input und sechs Kanälen; `viewpoint.tflite`, `model.tflite`, `labels.txt` bleiben bei der Kalibrierung byte-identisch. Kein FungiTastic-Download, kein GPU-Training.
- `background` ist kein Evidenzkanal. `cap` belegt Hut; `gills` ODER `pores` belegen Unterseite; `stipe` ODER `ring` belegen Stiel/Ring. Hut-Dominanz mindestens 1,0; Schärfegrenze unverändert 0,05. Nur Ansichtsgrün, niemals Verzehrfreigabe.
- `val` wählt; `test` beurteilt. Frame-Fehlalarmquote pro Ansicht <= 0,20, Recall >= 0,50; auf identischen `test`-Frames weder höhere Fehlalarmquote noch niedrigerer Recall als v1. Mindestens eine Recall-Verbesserung, sonst keine Übernahme.
- Bekannte Testsplit-Sichtung offenlegen: Sie ist kein unberührter externer Nachweis. Ohne zusätzlichen, getrennt dokumentierten und beschrifteten Feldtest kein produktives Staging. Herkunft FungiTastic-M: CC BY-NC-SA 4.0, nichtkommerziell.
- Fail-closed für fehlende, fremde, nicht endliche oder unvollständige v2-Werte, Modell-Hash-/Tensorform-Mismatch und fehlendes Gate. v1 bleibt bei allen vorbereitenden Codeänderungen funktionsfähig.

## File Structure

TRAIN `src/viewpoint_operating_point.py`: reine operative Frame-Entscheidung und Zähler; `src/measure_viewpoint_views.py`: vorhandene GPU-Inferenz, erweitert um Schärfe und dokumentierte Coverage-Matrizen; `src/calibrate_viewpoint_v2.py`: deterministische Wahl und v1-v2-Gate; `src/stage_viewpoint_config.py`: ausschließlich JSON-Staging nach Gate/Feldnachweis. Je Modul eigene Tests unter `tests/`.

APP `ViewpointConfig.kt`/`ViewAccumulator.kt`: v2-Vertrag und Akzeptanzgrenzen; `ViewpointSegmenter.kt`: fünf Kanal-Betriebspunkte; `MainActivity.kt`: Parser/Modell-Hash-/Formabgleich beim Laden. Bestehende Testklassen ergänzen, ein echter TFLite-Lauf unter `app/src/androidTest/`. Keine UI-Neugestaltung oder Änderung an `SpeciesConsensus`.

## Review Focus

- Lamellen belegt, Poren unbelegt: nur ein qualifizierter Lamellenkanal darf die Unterseite markieren; Test in Task 1 und 4.
- Fehlender Poren- oder Ringwert bzw. NaN: v2 wird vollständig abgelehnt, nicht auf die lockere Lamellenschwelle zurückgesetzt; Test in Task 3 und 4.
- Ein Kanal ohne Vorhersagen oder ohne annotierte Fälle: 0,0 Fehlalarm ist kein Beweis für Verlässlichkeit; Test in Task 1/3.
- Konfiguration nennt den richtigen Lauf, aber falsche Modellbytes/Tensorform: keine Ansicht wird akzeptiert und kein Staging erfolgt; Test in Task 4/5.
- Release-Gate ohne Feldnachweis oder mit falschem Split bzw. umgestellter `test`-Schwelle: Assets bleiben byte-identisch; Test in Task 3/5.

---

### Task 1: Reiner Python-Frame-Entscheid (TRAIN)

**Files:**
- Create: `src/viewpoint_operating_point.py`
- Create: `tests/test_viewpoint_operating_point.py`

**Interfaces:**
- Consumes: `PART_NAMES` aus `src.viewpoint_model`, `STEP_CHANNELS` aus `src.calibrate_viewpoint`, normalisierte Coverage-Matrizen `dict[float, np.ndarray[N,6]]`, normalisierte Schärfe `np.ndarray[N]`.
- Produces: `frame_flags(coverages, tau_by_channel, min_coverage_by_channel, dominance_by_view, sharpness, min_sharpness) -> dict[str, np.ndarray[N,bool]]` und `frame_counts(flags, truth) -> dict[str, dict]`. Bei ungültigem Kanal, missing grid point oder Schärfe-Form/NaN `ViewEvidenceError`. `frame_counts` ruft `point_stats` aus `src.viewpoint_view_evidence` auf; `truth` hat Form `[N,6]`.

- [ ] **Step 1: Failing tests** — konstruierte Frames: Lamellen mit 0,04 Fläche über eigener Grenze 0,02, Poren 0,03 unter eigener Grenze 0,05; Hut 0,06 und Unterseite 0,08 (Hut verliert bei k=1), scharfer/unscharfer Frame, toter Kanal.

```python
flags = frame_flags({0.3: cov03, 0.5: cov05},
    {"cap": .5, "gills": .3, "pores": .5, "stipe": .5, "ring": .5},
    {"cap": .01, "gills": .02, "pores": .05, "stipe": .01, "ring": .01},
    {"cap": 1., "underside": 0., "stipe_ring": 0.},
    np.array([.2, .01]), .05)
assert flags["underside"].tolist() == [True, False]
assert flags["cap"].tolist() == [False, False]
```

- [ ] **Step 2: Red** — `cd "$TRAIN" && pytest tests/test_viewpoint_operating_point.py -q`; expected ImportError.
- [ ] **Step 3: Minimal implementation** — per channel `own[c] = mean(mask_probability_c > tau[c])` comes from the passed coverage matrix at that tau. Mark a view only if some own channel **reaches** its own area (`>=`, matching `ViewAccumulator.accept`), AND `max(own) >= dominance[view] * max(all foreign non-background coverages)`, AND sharpness >= 0.05. Check exact channel keys, all finite ranges [0,1], all shapes, and positive denominators; never infer a missing tau grid point.

```python
qualified = any(float(own[c][i]) >= area[c] for c in channels)
flagged[i] = qualified and own_max >= k[view] * foreign_max and sharpness[i] >= min_sharpness
```

- [ ] **Step 4: Green** — same pytest command; add tests that a missing tau, empty channel, NaN and unlabelled `truth` do not silently pass.
- [ ] **Step 5: Commit** — `git add src/viewpoint_operating_point.py tests/test_viewpoint_operating_point.py && git commit -m 'feat(viewpoint): model the per-channel app decision'`.

### Task 2: Messdaten einschließlich Schärfe reproduzierbar machen (TRAIN)

**Files:**
- Modify: `src/measure_viewpoint_views.py`
- Create: `tests/test_measure_viewpoint_operating_point.py`

**Interfaces:**
- Consumes: unverändertes Modell/Masken-Cache, `TAU_GRID=(0.3,0.5)`, Task-1-Frame-Entscheid.
- Produces: `out/viewpoint/operating-skip-300-seed42-val.npz` und `out/viewpoint/operating-skip-300-seed42-test.npz` mit `coverage_03`, `coverage_05`, `truth`, `sharpness`, `observation_ids` in deterministischer Reihenfolge; daneben je ein gleichnamiges JSON mit `run_id`, `split`, `model_sha256`, `splits_sha256`, Anzahl und SHA-256 des NPZ. Kein App-Asset.

- [ ] **Step 1: Failing tests** — winziger Fixture-Datensatz mit zwei RGB-Bildern (homogen und weißes Rechteck auf schwarzem Grund) sowie synthetischen Coverage-Werten; `sharpness_for_rgb224()` muss dieselbe Int-Graustufen- und Betrag-Laplace-Varianz wie `FrameQualityAnalyzer`/`ViewpointSegmenter.normalisedSharpness` liefern, auf 0..1 begrenzt. Eine fehlende Observation-ID und vertauschte Matrixform sind Fehler.

```python
assert sharpness_for_rgb224(np.full((224, 224, 3), 100, dtype=np.uint8)) == 0.0
rectangle = np.zeros((224, 224, 3), dtype=np.uint8)
rectangle[32:192, 32:192] = 255
assert sharpness_for_rgb224(rectangle) > 0.05
```

- [ ] **Step 2: Red** — `cd "$TRAIN" && pytest tests/test_measure_viewpoint_operating_point.py -q`; expected missing helper.
- [ ] **Step 3: Implement** — resize exactly like `Bitmap.createScaledBitmap(...,224,224,true)`, integer gray `(299*r+587*g+114*b)//1000`, interior `abs(4*c-u-d-l-r)`, variance and division by `1000.0`; assert parity against the Kotlin fixture within documented resize tolerance. Derive observation IDs from persisted split rows, never from row index; write NPZ/metadata once after model inference. Reject `--limit` for releasable reports. Keep the existing v1 JSON report intact.

```python
lap = np.abs(4*gray[1:-1,1:-1] - gray[:-2,1:-1] - gray[2:,1:-1] - gray[1:-1,:-2] - gray[1:-1,2:])
sharpness = min(max(float(lap.var()) / 1000.0, 0.0), 1.0)
```

- [ ] **Step 4: Green** — pytest focused; run `python -m src.measure_viewpoint_views --help` to verify CLI remains callable. Record that the existing `test` split has been consulted before; do not label it blind.
- [ ] **Step 5: Commit** — `git add src/measure_viewpoint_views.py tests/test_measure_viewpoint_operating_point.py && git commit -m 'feat(viewpoint): persist frame evidence for channel calibration'`.

### Task 3: Auswahl auf val, unverändertes Urteil auf test (TRAIN)

**Files:**
- Create: `src/calibrate_viewpoint_v2.py`
- Create: `tests/test_calibrate_viewpoint_v2.py`

**Interfaces:**
- Consumes: Task-2-NPZ und JSON für `val`/`test`, Task-1-Frame-Entscheid, v1-Konfiguration aus APP `app/src/main/assets/viewpoint.json` und unveränderten Modell-Hash.
- Produces: `calibrate(val_data, tau_grid=(.3,.5), area_grid=(.001,.002,.003,.005,.01,.02,.03,.05,.10)) -> dict` und `judge(chosen, test_data, baseline) -> dict` mit v2-Werten, val/test-Zählern, gepaarten v1-v2-Abweichungen, explizitem `accept`, `reason`, Report-/Modell-Hashes. Nach jedem Versuch wird eine Entscheidung unter `out/viewpoint/operating-decision-skip-300-seed42.json` geschrieben; kein Asset.

- [ ] **Step 1: Failing tests** — v1-baseline bei tau .5 und Flächen .001/.01/.001; nur `val` bestimmt die Werte, `test` muss exakt denselben Satz sehen. Ein Test verbessert Recall, aber verschlechtert Unterseiten-Fehlalarm: `accept == False`; bei identischem Recall überall ebenfalls False; bei einer echten Verbesserung, unveränderten übrigen Ansichten und Fehlerquote <= .20 True. Missing hashes/splits, gleiche Observation-IDs über Splits, leere Belege/Meldungen, NaN und ein fehlender fünfteiliger Kanalvertrag False/Exception.

```python
assert judge(chosen, test_data, baseline)["accept"] is False  # false-alarm regression
assert judge(same_as_v1, test_data, baseline)["accept"] is False  # no improvement
```

- [ ] **Step 2: Red** — `cd "$TRAIN" && pytest tests/test_calibrate_viewpoint_v2.py -q`; expected missing module.
- [ ] **Step 3: Implement** — für jede der 2^5 Tau-Kombinationen die unabhängigen Flächenpaare der Unterseite und Stiel/Ring sowie die Hutfläche aus dem festen 9er-Gitter prüfen; bei Hut-Dominanz fremde Kanäle mit ihren *gewählten* Tau-Werten rechnen. Wähle nach (Summe Recall, negatives Summe Fehlalarm, höhere Flächen, stabile Kanalreihenfolge) unter der val-Grenze. Testwerte nur einmal für diesen eingefrorenen Kandidaten berechnen, niemals nachträglich über `test` nachjustieren. v1 auf exakt denselben `test`-Frames durch Task 1 nachrechnen, nicht Zahlen aus alten Berichten kopieren. Gate verlangt pro Ansicht Kandidat-Fehlalarm <= min(0,20, v1-Fehlalarm), Kandidat-Recall >= max(0,50, v1-Recall), mindestens eine echte Recall-Steigerung. Zähler/paired disagreements auf Beobachtungsebene zusätzlich berichten, nicht als Ersatz für die festen Grenzen. Die Schärfe aus Task 2 gehört zu beiden Pfaden. CLI nimmt `--run-id skip-300-seed42 --baseline "$APP/app/src/main/assets/viewpoint.json"` und prüft NPZ-/JSON-Hashes, Split-Namen und disjunkte Observation-IDs.

```python
acceptable = all(new[s]["false_alarm_rate"] <= min(.20, old[s]["false_alarm_rate"])
    and new[s]["recall"] >= max(.50, old[s]["recall"]) for s in STEPS)
accept = acceptable and any(new[s]["recall"] > old[s]["recall"] for s in STEPS)
```

- [ ] **Step 4: Green** — focused pytest, dann `pytest tests/test_viewpoint_view_evidence.py tests/test_viewpoint_release_gate.py -q` für bestehenden Gate-Regressionsschutz.
- [ ] **Step 5: Commit** — `git add src/calibrate_viewpoint_v2.py tests/test_calibrate_viewpoint_v2.py && git commit -m 'feat(viewpoint): gate per-channel operating points'`.

### Task 4: Versionierter App-Vertrag und Laufzeitparität (APP)

**Files:**
- Modify: `app/src/main/java/com/example/mushroomexposed/ViewpointConfig.kt`
- Modify: `app/src/main/java/com/example/mushroomexposed/ViewpointSegmenter.kt`
- Modify: `app/src/main/java/com/example/mushroomexposed/ViewAccumulator.kt`
- Modify: `app/src/main/java/com/example/mushroomexposed/MainActivity.kt`
- Modify: `app/src/test/java/com/example/mushroomexposed/ViewpointConfigTest.kt`
- Modify: `app/src/test/java/com/example/mushroomexposed/ViewAccumulatorTest.kt`
- Modify: `app/src/test/java/com/example/mushroomexposed/ShippedViewpointContractTest.kt`
- Create: `app/src/androidTest/java/com/example/mushroomexposed/ViewpointRuntimeTest.kt`
- Create: `app/src/androidTest/assets/viewpoint_real_frame.png` (aus einem dokumentierten, beschrifteten FungiTastic-M-Frame; nur im Test-APK, mit Herkunft/CC BY-NC-SA 4.0 im Testkommentar)

**Interfaces:**
- Consumes: v2 JSON `{schema_version:2, mask_on_by_channel:{cap,gills,pores,stipe,ring}, min_coverage_by_channel:{cap,gills,pores,stipe,ring}, dominance_factor_by_view:{cap,underside,stipe_ring}, min_sharpness:0.05, input_size:300, output_channels:6, tflite_sha256:sha256 of shipped model}`. v1 ohne schema_version bleibt original.
- Produces: `ViewpointConfig.parseOrNull(text, actualSha256, inputSize, outputChannels): ViewAcceptance?`, `ViewpointSegmenter(..., acceptance: ViewAcceptance?)`; `ViewAccumulator` akzeptiert nur bereits qualifizierte v2-Evidenz, v1-Form bleibt unverändert.

- [ ] **Step 1: Failing tests** — v1-JSON ergibt unverändert alte Grenzen. Für v2: Lamellenfläche .04 bei Schwelle .02 wird als Unterseite anerkannt, Porenfläche .03 bei Schwelle .05 nicht; fremder Hut schlägt bei Dominanz k=1 einen kleineren Hutkanal. Fehlender Kanal, Extra-Kanal, NaN, Hash-Mismatch, input_size 448, output_channels 5, fehlende Dominanz und ungültige Schema-Version liefern `null`. Im Test-APK verarbeitet ein **echter**, beschrifteter und lizenzierter Testframe aus dem Training-Korpus das unveränderte TFLite-Asset; der erwartete Masken-/Ansichtsstatus wird zuvor mit Task 1 und 2 auf demselben Bild ermittelt und als Test-Golden gepinnt. Ein kompletter Test ruft `masksFor`, `evidenceFor` und `ViewAccumulator` auf und vergleicht die Entscheidungen, nicht nur die Tensorform. Keine Testframe-Datei aus synthetischen RGB-Pixeln als Ersatz für die Paritätsprüfung. Test der ausgelieferten v1-Assets bleibt grün.

```kotlin
assertNull(ViewpointConfig.parseOrNull(v2WithoutPores, modelSha, 300, 6))
assertNull(ViewpointConfig.parseOrNull(validV2, wrongSha, 300, 6))
assertNotNull(ViewpointConfig.parseOrNull(validV1, modelSha, 300, 6))
```

- [ ] **Step 2: Red** — `cd "$APP" && ./gradlew :app:testDebugUnitTest --tests '*ViewpointConfigTest*' --tests '*ViewAccumulatorTest*'`; expected missing v2 overload/behavior. Instrumented test zunächst gegen ausgeliefertes v1-Asset ausführen, damit der reale Tensorpfad nicht ungetestet bleibt.
- [ ] **Step 3: Implement** — v2-Parser validiert exakt fünf Namen, Zahlen endlich und in [0,1], cap-Dominanz >=1 und Metadaten inkl. tatsächlichem SHA-256 der gemappten Modellbytes. Ein `ViewAcceptance` trägt optional die fünf `ChannelOperatingPoint(tau, area)`; v1 hat keine und durchläuft ausschließlich den alten Codepfad. Der Segmentierer zählt pro Kanal `mask > tau`, lässt eine Ansicht nur bei `coverage_c >= area_c` passieren und gibt den höchsten qualifizierten eigenen Wert als Evidence; der Wettbewerb nutzt die echten Flächen der fremden Kanäle. Die pro-Ansicht-Mindestfläche im Accumulator ist für v2 das Minimum der gemessenen Teilkanalschwellen (Hut: Hut, Unterseite: min(Lamellen, Poren), Stiel/Ring: min(Stiel, Ring)); sie darf den bereits qualifizierten Kanal nicht ablehnen. `MainActivity.loadViewpointModel()` konstruiert den Segmentierer erst nach vollständig geprüftem Tupel; bei Mismatch `viewAcceptance/viewAccumulator = null` und kein grünes Ansichts-Häkchen.

```kotlin
val eligible = channels.filter { coverage[it] >= acceptance.channelPoints.getValue(it).area }
if (eligible.isNotEmpty()) evidence += ViewEvidence(step, eligible.maxOf { coverage[it] }, sharpness, frameId, foreignMax)
```

- [ ] **Step 4: Green** — fokussierte Unit-Tests; `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`; Emulator `./gradlew :app:connectedDebugAndroidTest` (falls Redmi-Installation von MIUI blockiert: dokumentiert auf API-35-Emulator statt stiller Auslassung). Der Test lädt das echte Asset und einen echten Testframe; wenn beides nicht verfügbar ist, kein grüner Runtime-Nachweis und kein Staging.
- [ ] **Step 5: Commit** — `git add app/src/main/java/com/example/mushroomexposed/{ViewpointConfig,ViewpointSegmenter,ViewAccumulator,MainActivity}.kt app/src/test/java/com/example/mushroomexposed/{ViewpointConfigTest,ViewAccumulatorTest,ShippedViewpointContractTest}.kt app/src/androidTest/java/com/example/mushroomexposed/ViewpointRuntimeTest.kt app/src/androidTest/assets/viewpoint_real_frame.png && git commit -m 'feat(app): interpret measured channel operating points'`.

### Task 5: Staging-Sperre und reale Messung (TRAIN, danach APP)

**Files:**
- Create: TRAIN `src/stage_viewpoint_config.py`
- Create: TRAIN `tests/test_stage_viewpoint_config.py`
- Create: TRAIN `docs/superpowers/notes/viewpoint-v2-evaluation-2026-09-25.md`
- Modify only after all gates: APP `app/src/main/assets/viewpoint.json`, `app/src/test/java/com/example/mushroomexposed/ShippedViewpointContractTest.kt`

**Interfaces:**
- Consumes: akzeptierte Task-3-Entscheidung mit Hashes + separates Feldtest-Protokoll mit Bild-/Beobachtungs-IDs, Annotationen für alle drei Ansichten und überprüfter menschlicher Freigabe; vorhandenes APP `viewpoint.tflite`.
- Produces: `stage_config(decision_path: Path, field_report: Path, app_assets: Path, dry_run: bool, user_approved: bool = False) -> Path` oder `StagingRefused`; `user_approved` darf erst nach expliziter, außerhalb des Berichts dokumentierter Freigabe gesetzt werden. JSON-v2-Asset nur nach allen Prüfungen, Modell-Asset unverändert.

- [ ] **Step 1: Failing tests** — in temp App-Assets `viewpoint.tflite` mit bekannten Bytes und alte `viewpoint.json`; Ablehnung bei `accept:false`, fehlendem/ungeprüftem Feldbericht, fremdem Modell-Hash, fehlendem Kanal, fehlender v2-Unterstützung oder vertauschtem Split lässt beide Dateien unverändert. `--dry-run` schreibt nie; ein vollständig geprüfter Testfall schreibt nur `viewpoint.json` und erhält das Modell byte-identisch.

```python
before = (assets/'viewpoint.json').read_bytes(), (assets/'viewpoint.tflite').read_bytes()
with pytest.raises(StagingRefused):
    stage_config(rejected, field_report, assets, dry_run=False, user_approved=True)
assert before == ((assets/'viewpoint.json').read_bytes(), (assets/'viewpoint.tflite').read_bytes())
```

- [ ] **Step 2: Red** — `cd "$TRAIN" && pytest tests/test_stage_viewpoint_config.py -q`; expected missing module.
- [ ] **Step 3: Implement** — prüfe alle Eingaben **vor** dem Schreiben, auch Feldbericht und SHA-256/300/6; schreibe v2-JSON in temporäre Datei im Zielverzeichnis und ersetze nach vollständigem Validate atomar nur die JSON-Datei. Das bestehende `stage_viewpoint.py` (kopiert auch TFLite) wird für diesen Konfigurationswechsel nicht benutzt. Feldbericht ohne menschlich bestätigte Bildzuordnung **und explizite Freigabe von Markus außerhalb des Berichts** besteht nicht; ein Textfeld `approved_by` allein ist keine Autorisierung. Dokumentiere Testsplit-Sichtung und v1-v2-Zähler inkl. gepaarter Abweichungen; wenn Gate oder Feldtest scheitern, dokumentiere Ablehnung und beende ohne Assetänderung.

```python
if not decision['accept'] or not user_approved or not field_report.get('verified_annotations'):
    raise StagingRefused('gate or field evidence missing')
if sha256(app_assets/'viewpoint.tflite') != decision['model_sha256']:
    raise StagingRefused('asset differs from measured model')
```

- [ ] **Step 4: Green + run** — focused pytest, dann bestehende Python-Suite `pytest tests/test_viewpoint_view_evidence.py tests/test_viewpoint_release_gate.py tests/test_stage_viewpoint.py -q`. Mit vorhandenem 300er Checkpoint nur Mess-/Kalibrierungsläufe ausführen; verfügbare GPU und Datenprereqs vorher read-only prüfen. `python -m src.measure_viewpoint_views --run-id skip-300-seed42 --split val`, dasselbe für `test`, danach `python -m src.calibrate_viewpoint_v2 --run-id skip-300-seed42 --baseline "$APP/app/src/main/assets/viewpoint.json"`. Falls Feldnachweis noch aussteht: `--dry-run`, dokumentierter Blocker, **kein Staging**. Sonst Staging, App-Kompletttests und Hash-Kette JSON→APK→Emulator sowie Hold-to-scan-Feldtest (Hut ohne Unterseite, dann Unterseite, Stiel/Ring; Loslassen vor drittem Haken bricht ab).
- [ ] **Step 5: Commit** — zuerst nur Trainingcode, Tests und Messnotiz (`git add src/stage_viewpoint_config.py tests/test_stage_viewpoint_config.py docs/superpowers/notes/viewpoint-v2-evaluation-2026-09-25.md && git commit -m 'feat(viewpoint): stage only gated channel config'`). Nur falls wirklich freigegeben: im APP-Repo `git add app/src/main/assets/viewpoint.json app/src/test/java/com/example/mushroomexposed/ShippedViewpointContractTest.kt && git commit -m 'feat(app): ship gated viewpoint operating point'`. Niemals eine akzeptierte Zahl aus einem roten Test ableiten.

## Abschlussprüfung

- Spec-Kriterien gegen Berichte, Tests und App-Asset einzeln abhaken; `git diff --check` und beide `git status --short` prüfen. v1 ist ein korrektes Ergebnis, wenn die v2-Schwellen die Gates nicht schaffen oder Feldnachweis fehlt; dann klar „nicht ausgeliefert“ berichten statt eine Verbesserung zu behaupten.
- Nach diesem Teilprojekt den separaten, schon freigegebenen Artenmodell-Entwurf `docs/superpowers/specs/2026-09-24-sichere-erkennung-ui-design.md` und den Trainingsplan für Quellenbalance auf dem aktuellen Trainings-Branch erneut prüfen; nur sein eigenes GBIF-/PVV-/Risiko-Gate darf `model.tflite` ändern.
