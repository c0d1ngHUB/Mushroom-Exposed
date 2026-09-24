# Mehransichten-Android Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Einen lokalen Hold-to-scan-Flow bauen, der drei modellbelegte Ansichten sammelt, automatisch einen Mehransichten-Konsens zeigt und genau ein EXIF-freies Referenzbild je Verlaufseintrag speichert.

**Architecture:** `ViewAccumulator` ist ein reiner Kotlin-Zustandsautomat, der aus Schärfe und Segmentierer-Evidenz die drei Ansichtsschritte ableitet. `ReferenceImageStore` besitzt Bild- und JSONL-Lebenszyklus atomar; `MainActivity` vermittelt nur zwischen CameraX, den beiden TFLite-Modellen und den getesteten Komponenten.

**Tech Stack:** Kotlin, Android ViewBinding, CameraX 1.3.4, TensorFlow Lite 2.15.0, JUnit 4, Espresso.

**Spec:** `docs/superpowers/specs/2026-09-24-mehransichten-prototyp-design.md`

## Global Constraints

- Kein Video, keine temporäre Bilddatei und kein Upload; nur ein neu kodiertes, EXIF-freies Referenz-JPEG je fertigem Ergebnis.
- Maximal 200 atomare Verlauf-Bild-Paare; `clear()` entfernt beide Seiten; alte JSONL-Zeilen ohne Bildreferenz bleiben lesbar.
- Grün bedeutet nur „Ansicht ausreichend erfasst“, nie Essbarkeit oder Sicherheit.
- Produktionscode nennt ausschließlich `Hut`, `Unterseite` und `Stiel / Ring`, niemals `Stielbasis`.
- Der Flow scheitert geschlossen, falls `viewpoint.tflite`, sein Vertrag oder der Artenkonsens fehlt.
- Portrait-Lock, 48-dp-Mindestziele, Notfallblock und konservative `VerdictPolicy` bleiben erhalten.

## Review Focus

- Loslassen während ein Frame analysiert wird: keine nachträgliche Anzeige, keine Datei und kein hängender Status.
- Ein Lamellen- oder Poren-Maskentreffer erfüllt genau die Unterseitenzeile, aber keinen Hut oder Stiel/Ring.
- Ein alter Verlauf ohne `image`-Feld lässt sich anzeigen und löschen.
- Der 201. Eintrag entfernt Bild und JSONL-Zeile desselben ältesten Eintrags, nicht nur eines von beiden.
- Fehlender oder klasseninkompatibler Segmentierer erzeugt keinen grünen Zustand.

---

### Task 1: Ausgangszustand an den Mehransichten-Schnitt anpassen

**Files:**
- Modify: `app/src/main/java/com/example/mushroomexposed/MainActivity.kt`
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/res/drawable/bg_shutter.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/test/java/com/example/mushroomexposed/BedienungTest.kt`
- Modify: `app/src/test/java/com/example/mushroomexposed/ViewStylingTest.kt`

**Interfaces:**
- Removes the uncommitted single-capture UI experiment and preserves the committed `VerdictPolicy` contract.
- Produces a clean baseline for `ViewAccumulator` without `shutterAction` or the obsolete static capture copy.

- [ ] **Step 1: Restore only superseded, uncommitted capture-flow files**

Run:
```bash
git restore app/src/main/java/com/example/mushroomexposed/MainActivity.kt \
  app/src/main/res/drawable/bg_shutter.xml app/src/main/res/layout/activity_main.xml \
  app/src/main/res/values/strings.xml app/src/test/java/com/example/mushroomexposed/BedienungTest.kt \
  app/src/test/java/com/example/mushroomexposed/ViewStylingTest.kt
```
Expected: no mixed tap-shutter/hold-scan implementation remains; committed `cc9ed93` remains in history.

- [ ] **Step 2: Verify baseline**

Run: `./gradlew testDebugUnitTest --rerun-tasks`
Expected: `BUILD SUCCESSFUL` before any new behavior is written.

- [ ] **Step 3: Commit only if the restore itself changes tracked content**

Run:
```bash
git status --short
git diff --check
```
Expected: no commit for a pure restore; no unrelated plan/spec file is deleted.

### Task 2: Pure Ansichtszustand und Evidenz

**Files:**
- Create: `app/src/main/java/com/example/mushroomexposed/ViewAccumulator.kt`
- Create: `app/src/test/java/com/example/mushroomexposed/ViewAccumulatorTest.kt`

**Interfaces:**
- Produces `enum class ViewStep { CAP, UNDERSIDE, STIPE_RING }`.
- Produces `data class ViewEvidence(val step: ViewStep, val coverage: Float, val sharpness: Float, val frameId: Long)`.
- Produces `fun accept(evidence: ViewEvidence): ViewProgress` and `fun cancel(): ViewProgress`.

- [ ] **Step 1: Write failing state-machine tests**

```kotlin
@Test fun `gills evidence completes only the underside step`() {
    val progress = ViewAccumulator(acceptance).accept(gillsEvidence)
    assertTrue(progress.captured.contains(ViewStep.UNDERSIDE))
    assertFalse(progress.captured.contains(ViewStep.CAP))
    assertFalse(progress.captured.contains(ViewStep.STIPE_RING))
}

@Test fun `cancel clears all temporary captures`() {
    val accumulator = ViewAccumulator(acceptance)
    accumulator.accept(capEvidence)
    assertEquals(emptySet<ViewStep>(), accumulator.cancel().captured)
}
```

- [ ] **Step 2: Run red**

Run: `./gradlew testDebugUnitTest --tests '*ViewAccumulatorTest*' --rerun-tasks`
Expected: compilation failure because `ViewAccumulator` does not exist.

- [ ] **Step 3: Implement minimal state machine**

```kotlin
data class ViewAcceptance(val minCoverage: Float, val minSharpness: Float)
data class ViewProgress(val captured: Set<ViewStep>, val next: ViewStep?, val complete: Boolean)
```
`accept()` replaces a prior frame for the same step only when its `coverage * sharpness` score is larger; `cancel()` empties every in-memory reference.

- [ ] **Step 4: Verify focused tests**

Run: `./gradlew testDebugUnitTest --tests '*ViewAccumulatorTest*' --rerun-tasks`
Expected: PASS for cap, gills, pores, stipe/ring, replacement and cancellation.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/mushroomexposed/ViewAccumulator.kt \
  app/src/test/java/com/example/mushroomexposed/ViewAccumulatorTest.kt
git commit -m "feat(app): accumulate verified mushroom views"
```

### Task 3: Paarweiser Bildverlauf

**Files:**
- Modify: `app/src/main/java/com/example/mushroomexposed/History.kt`
- Create: `app/src/main/java/com/example/mushroomexposed/ReferenceImageStore.kt`
- Modify: `app/src/test/java/com/example/mushroomexposed/HistoryStoreTest.kt`
- Create: `app/src/test/java/com/example/mushroomexposed/ReferenceImageStoreTest.kt`

**Interfaces:**
- Extends `HistoryEntry` with `val image: String? = null`.
- Produces `fun append(entry: HistoryEntry, jpeg: ByteArray? = null): HistoryEntry` in `HistoryStore`.
- Produces `BitmapJpegEncoder.encode(bitmap: Bitmap): ByteArray`; device integration is the only caller.
- Produces `fun clear()` that deletes `history.jsonl` and `history/images/*.jpg` together.

- [ ] **Step 1: Write failing persistence tests**

```kotlin
@Test fun `201st append evicts the oldest entry and its jpeg`() {
    repeat(201) { store.append(entry(it), jpeg(it)) }
    assertEquals(200, store.readNewestFirst().size)
    assertFalse(File(images, "0.jpg").exists())
    assertTrue(File(images, "200.jpg").exists())
}

@Test fun `legacy line without image remains decodable`() {
    assertNull(decode(legacyJsonLine)?.image)
}
```

- [ ] **Step 2: Run red**

Run: `./gradlew testDebugUnitTest --tests '*HistoryStoreTest*' --tests '*ReferenceImageStoreTest*' --rerun-tasks`
Expected: failure because `image` and `ReferenceImageStore` are absent.

- [ ] **Step 3: Implement image-before-entry atomic path**

Write supplied JPEG bytes to a sibling temporary file, rename it to `files/history/images/<uuid>.jpg`, then rewrite JSONL. On image write or JSONL rewrite failure delete the newly created file and leave the prior history untouched. `BitmapJpegEncoder` uses `Bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)` before it calls this boundary; device tests verify that Android-only encoding path. The JVM tests deliberately use literal JPEG bytes because this project has no Robolectric runtime.

- [ ] **Step 4: Verify lifecycle**

Run: `./gradlew testDebugUnitTest --tests '*HistoryStoreTest*' --tests '*ReferenceImageStoreTest*' --rerun-tasks`
Expected: PASS for cap, legacy decode, failed write cleanup and clear with literal JPEG bytes. The Android encoder itself is verified later by an instrumented test.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/mushroomexposed/History.kt \
  app/src/main/java/com/example/mushroomexposed/ReferenceImageStore.kt \
  app/src/test/java/com/example/mushroomexposed/HistoryStoreTest.kt \
  app/src/test/java/com/example/mushroomexposed/ReferenceImageStoreTest.kt
git commit -m "feat(app): retain bounded local reference images"
```

### Task 4: Segmentierer-Vertrag und Mehransichts-Konsens

**Files:**
- Create: `app/src/main/java/com/example/mushroomexposed/ViewpointContract.kt`
- Create: `app/src/main/java/com/example/mushroomexposed/SpeciesConsensus.kt`
- Create: `app/src/test/java/com/example/mushroomexposed/ViewpointContractTest.kt`
- Create: `app/src/test/java/com/example/mushroomexposed/SpeciesConsensusTest.kt`

**Interfaces:**
- `ViewpointContract.requireOutputChannels(channels: Int): Int` requires exactly six channels: background, cap, gills, pores, stipe, ring.
- `SpeciesConsensus.add(step: ViewStep, probabilities: FloatArray)` and `result(): FloatArray?` returns null before all three steps and aggregates only one selected frame per step.

- [ ] **Step 1: Write failing contract and consensus tests**

```kotlin
@Test fun `five segmentation channels fail closed`() {
    assertFailsWith<IllegalStateException> { ViewpointContract.requireOutputChannels(5) }
}
@Test fun `consensus needs all three views`() {
    val consensus = SpeciesConsensus(3)
    consensus.add(ViewStep.CAP, floatArrayOf(.9f, .1f))
    assertNull(consensus.result())
}
```

- [ ] **Step 2: Run red**

Run: `./gradlew testDebugUnitTest --tests '*ViewpointContractTest*' --tests '*SpeciesConsensusTest*' --rerun-tasks`
Expected: compilation failure because both production classes are absent.

- [ ] **Step 3: Implement fixed model contract and log-probability aggregation**

Use the geometric mean in log space with `max(probability, 1e-8f)` for cap, underside and stipe/ring. `result()` returns null until every `ViewStep` has one vector with the exact species class count.

- [ ] **Step 4: Verify focused tests**

Run: `./gradlew testDebugUnitTest --tests '*ViewpointContractTest*' --tests '*SpeciesConsensusTest*' --rerun-tasks`
Expected: PASS for channel mismatch, missing view, shape mismatch and deterministic ranking.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/mushroomexposed/ViewpointContract.kt \
  app/src/main/java/com/example/mushroomexposed/SpeciesConsensus.kt \
  app/src/test/java/com/example/mushroomexposed/ViewpointContractTest.kt \
  app/src/test/java/com/example/mushroomexposed/SpeciesConsensusTest.kt
git commit -m "feat(app): gate species consensus on three verified views"
```

### Task 5: CameraX Hold-to-scan and guided UI

**Files:**
- Modify: `app/src/main/java/com/example/mushroomexposed/MainActivity.kt`
- Modify: `app/src/main/java/com/example/mushroomexposed/FieldMode.kt`
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/test/java/com/example/mushroomexposed/FieldModeMachineTest.kt`
- Modify: `app/src/test/java/com/example/mushroomexposed/FeldmodusLayoutTest.kt`
- Modify: `app/src/androidTest/java/com/example/mushroomexposed/SystemBarsLayoutTest.kt`

**Interfaces:**
- Replaces `FieldModeAction.CAPTURE` with `START_SCAN`, `STOP_SCAN`, `RETURN_TO_LIVE`, `IGNORE`.
- `MainActivity` calls `ViewAccumulator.cancel()` on release, pause and camera unbind.
- The layout exposes `viewCap`, `viewUnderside`, `viewStipeRing`; each is a visible status row.

- [ ] **Step 1: Write failing touch/state tests**

```kotlin
@Test fun `release during collection cancels without frozen result`() {
    val machine = FieldModeMachine()
    assertEquals(FieldModeAction.START_SCAN, machine.onPrimaryDown())
    assertEquals(FieldModeAction.STOP_SCAN, machine.onPrimaryUp())
    assertEquals(FieldMode.LIVE, machine.state)
}
```
Add an XML test asserting the three IDs and exact strings `Hut zeigen`, `Unterseite zeigen`, `Stiel / Ring zeigen`.

- [ ] **Step 2: Run red**

Run: `./gradlew testDebugUnitTest --tests '*FieldModeMachineTest*' --tests '*FeldmodusLayoutTest*' --rerun-tasks`
Expected: failure because hold events and view rows do not exist.

- [ ] **Step 3: Implement press/release listener and UI rendering**

Install `setOnTouchListener` on the primary control; return `true` only for `ACTION_DOWN`, `ACTION_UP` and `ACTION_CANCEL`. The analyzer samples at a configured cadence from `ImageAnalysis`, not by retaining a video. On `ViewProgress.complete`, calculate consensus and call the existing `render()` only after its model gate succeeds.

- [ ] **Step 4: Verify Android behavior**

Run:
```bash
./gradlew testDebugUnitTest --rerun-tasks
ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest --rerun-tasks
```
Expected: all unit tests pass; device tests confirm portrait lock, system-bar clearance, cancellation without persistence and automatic result after synthetic complete progress.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/mushroomexposed/MainActivity.kt \
  app/src/main/java/com/example/mushroomexposed/FieldMode.kt \
  app/src/main/res/layout/activity_main.xml app/src/main/res/values/strings.xml \
  app/src/test/java/com/example/mushroomexposed/FieldModeMachineTest.kt \
  app/src/test/java/com/example/mushroomexposed/FeldmodusLayoutTest.kt \
  app/src/androidTest/java/com/example/mushroomexposed/SystemBarsLayoutTest.kt
git commit -m "feat(app): guide hold-to-scan across verified views"
```

### Task 6: App integration gate

**Files:**
- Modify: `README.md`
- Modify: `app/src/main/assets/NOTICE.txt`
- Test: `app/src/androidTest/java/com/example/mushroomexposed/SystemBarsLayoutTest.kt`

- [ ] **Step 1: Add failing asset/prototype restriction test**

Assert `NOTICE.txt` names FungiTastic-M, CC BY-NC-SA 4.0 and `nichtkommerzieller Prototyp`; assert absent viewpoint asset keeps the scan action unavailable.

- [ ] **Step 2: Run red**

Run: `./gradlew testDebugUnitTest --rerun-tasks`
Expected: failure because the notice and fail-closed asset rule are absent.

- [ ] **Step 3: Document and enforce gate**

Add the exact asset provenance and noncommercial limitation. `loadViewpointModel()` must throw a named missing-model error before any green view is rendered.

- [ ] **Step 4: Final verification**

Run:
```bash
./gradlew testDebugUnitTest --rerun-tasks
./gradlew assembleDebug
ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest --rerun-tasks
```
Expected: green suite, build and device flow; report known dependency warnings separately.

- [ ] **Step 5: Commit**

```bash
git add README.md app/src/main/assets/NOTICE.txt app/src/androidTest/java/com/example/mushroomexposed/SystemBarsLayoutTest.kt
git commit -m "docs(app): declare noncommercial multi-view prototype gate"
```
