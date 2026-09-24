# Sichere Erkennung und Feld-UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Die Android-App zeigt Bestimmungen klar, führt die Bildaufnahme präzise und interpretiert Erkennungswahrscheinlichkeiten niemals als Verzehrerlaubnis.

**Architecture:** `VerdictPolicy` bleibt die einzige Quelle für Verzehrtext und -ton. `ResultFormatter` formatiert nur die von dieser Policy zugelassene Darstellung. Ressourcen und Layout machen die neue Feldsprache sichtbar; Instrumented-Tests prüfen die semantisch kritischen XML-Eigenschaften am Gerät.

**Tech Stack:** Kotlin, Android Views/ViewBinding, CameraX, TensorFlow Lite, JUnit 4, Espresso.

**Spec:** `docs/superpowers/specs/2026-09-24-sichere-erkennung-ui-design.md`

## Global Constraints

- Android `minSdk = 26`, bestehendes Portrait-Lock bleibt unverändert.
- Keine neue Netzwerk-, Font- oder Analyseabhängigkeit.
- `giftig` bleibt roter Gefahrton mit den Nummern 01 406 43 43 und 144.
- Nur `VerdictPolicy` entscheidet über Verzehr-Einschätzung; keine Vorhersagearten-Sperrliste.
- App-Asset `model.tflite` bleibt unverändert, bis der Trainingsplan alle Gates bestanden hat.

## Review Focus

- 99-%-Treffer mit `essbar` muss trotz hoher Konfidenz als nicht verzehrbewertbar erscheinen.
- `giftig` mit niedriger Konfidenz muss weiter Gefahrton und Notfalltext zeigen.
- Fehlende oder leere Trefferliste darf keine positive Einschätzung erzeugen.
- Der gute Qualitätszustand darf keine Garantie wie „Gut — auslösen“ kommunizieren.
- Touch- und Screenreader-Interaktion des Auslösers muss die sichtbare Aktion „Bild analysieren“ nennen.

---

### Task 1: Konservative Verzehr-Policy

**Files:**
- Modify: `app/src/main/java/com/example/mushroomexposed/VerdictPolicy.kt:96-121`
- Modify: `app/src/test/java/com/example/mushroomexposed/VerdictPolicyTest.kt`

**Interfaces:**
- Consumes: `VerdictPolicy.decide(verdict: String?, confidence: Float, ...)`.
- Produces: Für alle nicht-giftigen Urteile `VerdictDecision(headline = "Verzehr nicht bewertbar", tone = VerdictTone.CAUTION, ...)`.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `edible result at high confidence remains caution until a gated model exists`() {
    val decision = VerdictPolicy.decide("essbar", confidence = 0.99f)
    assertEquals("Verzehr nicht bewertbar", decision.headline)
    assertEquals(VerdictTone.CAUTION, decision.tone)
    assertTrue(decision.warning.contains("nicht auf die App verlassen"))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests '*VerdictPolicyTest.edible result at high confidence*' --rerun-tasks`

Expected: FAIL because the current policy returns `essbar` and `SAFE`.

- [ ] **Step 3: Write minimal implementation**

Replace the two current non-danger `essbar` branches in `baseDecision` with one branch returning `"Verzehr nicht bewertbar"`, `CAUTION` and `"Die App kann den Verzehr nicht bewerten — nie auf die App verlassen; Pilzberatung fragen."`. Keep the danger branch unchanged.

- [ ] **Step 4: Add the empty/unknown guard test and verify green**

```kotlin
@Test
fun `unknown result remains caution without a clearance`() {
    assertEquals(VerdictTone.CAUTION, VerdictPolicy.decide(null, 0.99f).tone)
}
```

Run: `./gradlew testDebugUnitTest --tests '*VerdictPolicyTest*' --rerun-tasks`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/mushroomexposed/VerdictPolicy.kt app/src/test/java/com/example/mushroomexposed/VerdictPolicyTest.kt
git commit -m "fix(app): withhold edible verdict until model gate passes"
```

### Task 2: Ergebnisdarstellung an die Policy binden

**Files:**
- Modify: `app/src/main/java/com/example/mushroomexposed/ResultFormatter.kt:146-170`
- Modify: `app/src/test/java/com/example/mushroomexposed/ResultFormatterTest.kt`
- Modify: `app/src/test/java/com/example/mushroomexposed/SicherheitsdarstellungTest.kt`

**Interfaces:**
- Consumes: `VerdictDecision` from Task 1 and `List<RankedSpecies>`.
- Produces: `ResultView` mit `CAUTION` und Notfalltext bei jedem nicht-giftigen Ergebnis.

- [ ] **Step 1: Write the failing formatter test**

```kotlin
@Test
fun `high confidence edible prediction keeps caution result and emergency guidance`() {
    val decision = VerdictPolicy.decide("essbar", 0.99f)
    val view = ResultFormatter.format(listOf(RankedSpecies("Steinpilz", "Boletus_edulis", "essbar", 0.99f)), decision)
    assertEquals(VerdictTone.CAUTION, view.tone)
    assertEquals(ResultFormatter.EMERGENCY_TEXT, view.emergency)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests '*ResultFormatterTest.high confidence edible*' --rerun-tasks`

Expected: FAIL because the current `SAFE` result has no emergency text.

- [ ] **Step 3: Implement no formatter shortcut**

Do not add special cases to `ResultFormatter`; make the Task-1 decision flow through unchanged and adapt existing tests that asserted `SAFE` only where the spec supersedes them.

- [ ] **Step 4: Verify all formatter/security tests**

Run: `./gradlew testDebugUnitTest --tests '*ResultFormatterTest*' --tests '*SicherheitsdarstellungTest*' --rerun-tasks`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/mushroomexposed/ResultFormatter.kt app/src/test/java/com/example/mushroomexposed/ResultFormatterTest.kt app/src/test/java/com/example/mushroomexposed/SicherheitsdarstellungTest.kt
git commit -m "test(app): pin conservative result rendering"
```

### Task 3: Aufnahmeführung und neutraler Auslöser

**Files:**
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/res/values/colors.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/com/example/mushroomexposed/MainActivity.kt:117-195`
- Modify: `app/src/test/java/com/example/mushroomexposed/BedienungTest.kt`
- Modify: `app/src/test/java/com/example/mushroomexposed/ViewStylingTest.kt`
- Modify: `app/src/androidTest/java/com/example/mushroomexposed/SystemBarsLayoutTest.kt`

**Interfaces:**
- Consumes: existing `shutterButton`, `qualityHint`, `R.string.cd_shutter`.
- Produces: sichtbare Textaktion `Bild analysieren`; guter Frame-Hinweis `Pilz vollständig im Rahmen`; Auslöser mit heller Fläche und dunklem Ring ohne `moss`/`verdict_safe`-Füllung.

- [ ] **Step 1: Write the failing unit/XML tests**

```kotlin
@Test
fun shutterNamesTheVisibleCaptureAction() {
    assertTrue(strings().contains("name=\"shutter_action\">Bild analysieren"))
    assertTrue(layout().contains("@string/shutter_action"))
}

@Test
fun shutterDoesNotUseApprovalGreen() {
    val button = blockOf(layout(), "@+id/shutterButton")
    assertFalse(button.contains("@color/moss"))
    assertFalse(button.contains("@color/verdict_safe"))
}
```

- [ ] **Step 2: Run tests to verify red**

Run: `./gradlew testDebugUnitTest --tests '*BedienungTest*' --tests '*ViewStylingTest*' --rerun-tasks`

Expected: FAIL because no `shutter_action` resource exists and the current shutter uses its green visual treatment.

- [ ] **Step 3: Implement the smallest UI change**

Add `shutter_action` and replace `hint_ok` with `Pilz vollständig im Rahmen`. Use it for the Button text and content description. Change only shutter-specific drawable/background tokens to `paper`/`ink` and retain its 88-dp target, centered placement and existing enabled/disabled behavior.

- [ ] **Step 4: Verify unit and device regression tests**

Run: `ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest --rerun-tasks`

Expected: PASS, including centered shutter, portrait lock, system-bar safety and the new visible action assertion.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/layout/activity_main.xml app/src/main/res/values/colors.xml app/src/main/res/values/strings.xml app/src/main/java/com/example/mushroomexposed/MainActivity.kt app/src/test/java/com/example/mushroomexposed/BedienungTest.kt app/src/test/java/com/example/mushroomexposed/ViewStylingTest.kt app/src/androidTest/java/com/example/mushroomexposed/SystemBarsLayoutTest.kt
git commit -m "feat(app): clarify capture guidance and neutralize shutter"
```

### Task 4: Release verification

**Files:**
- Modify: `README.md:5-34`

- [ ] **Step 1: Document the temporary conservative verdict**

State that the app reports a model identification hint but does not assess consumption until a newly gated model is shipped.

- [ ] **Step 2: Run complete quality gates**

Run:

```bash
./gradlew testDebugUnitTest --rerun-tasks
./gradlew lintDebug
./gradlew assembleDebug
ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest --rerun-tasks
```

Expected: all commands exit 0; report baseline warnings separately if any remain.

- [ ] **Step 3: Smoke test**

Install `app/build/outputs/apk/debug/app-debug.apk` on `emulator-5554`, open `com.example.mushroomexposed/.MainActivity`, capture an emulator screenshot and inspect that the primary action reads `Bild analysieren` and the status screen has no green filled approval button.

- [ ] **Step 4: Commit**

```bash
git add README.md
git commit -m "docs(app): explain conservative identification verdict"
```
