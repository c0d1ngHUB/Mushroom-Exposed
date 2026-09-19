# Echter LIVE/FROZEN-Modus Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Der Feldmodus zeigt nach dem Auslösen tatsächlich den aufgenommenen Frame als stabiles Standbild und kehrt über „Neu“ kontrolliert zur Live-Vorschau zurück.

**Architecture:** Eine kleine Android-unabhängige `FieldModeMachine` besitzt alle LIVE/ANALYSING/FROZEN-Übergänge. `MainActivity` rendert diese Zustände mit einem `ImageView`-Overlay und lässt den CameraX-Analyzer außerhalb von LIVE keine Qualitäts-UI aktualisieren.

**Tech Stack:** Kotlin 2.1.21, Android/CameraX, View Binding, JUnit 4, Gradle 8.11.1.

**Spec:** `docs/superpowers/specs/2026-09-19-echter-frozen-modus-design.md` (ergänzt die bindende Feldmodus-Spec `docs/superpowers/specs/2026-09-15-feldmodus-ux-design.md`)

## Global Constraints

- Zustände heißen exakt `LIVE`, `ANALYSING`, `FROZEN`.
- Primäraktionen heißen exakt `CAPTURE`, `RETURN_TO_LIVE`, `IGNORE`.
- Live-Qualitätsupdates sind ausschließlich in `LIVE` zulässig.
- Ein fehlender Preview-Frame muss von `ANALYSING` nach `LIVE` zurückführen.
- Erfolg und Inferenzfehler müssen von `ANALYSING` nach `FROZEN` führen.
- „Neu“ darf keine Analyse starten; es wechselt ausschließlich von `FROZEN` nach `LIVE`.
- Der analysierte Bitmap und der im Standbild-Overlay gezeigte Bitmap sind dieselbe Aufnahme.
- Modell, Modellvorverarbeitung, VerdictPolicy, Historie und Assets bleiben unverändert.
- Keine neue Runtime-Abhängigkeit und kein Foto wird persistent gespeichert.
- Keine automatisierten Kamera-Instrumentierungstests; Kamera/UI werden im Geräte- oder Emulator-Smoke-Test geprüft.

---

### Task 1: Testbare Feldmodus-Zustandsmaschine

**Files:**
- Create: `app/src/main/java/com/example/mushroomexposed/FieldMode.kt`
- Create: `app/src/test/java/com/example/mushroomexposed/FieldModeMachineTest.kt`

**Interfaces:**
- Consumes: keine Projektkomponente.
- Produces: `enum class FieldMode`, `enum class FieldModeAction`, `class FieldModeMachine`, `state: FieldMode`, `acceptsQualityUpdates: Boolean`, `onPrimaryAction(): FieldModeAction`, `onCaptureUnavailable()`, `onAnalysisFinished()`.

- [ ] **Step 1: Failing Tests schreiben**

```kotlin
package com.example.mushroomexposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldModeMachineTest {
    @Test
    fun `live primary action starts one analysis and suppresses quality`() {
        val machine = FieldModeMachine()

        assertEquals(FieldModeAction.CAPTURE, machine.onPrimaryAction())
        assertEquals(FieldMode.ANALYSING, machine.state)
        assertFalse(machine.acceptsQualityUpdates)
        assertEquals(FieldModeAction.IGNORE, machine.onPrimaryAction())
    }

    @Test
    fun `analysis completion freezes until primary action returns to live`() {
        val machine = FieldModeMachine()
        machine.onPrimaryAction()

        machine.onAnalysisFinished()

        assertEquals(FieldMode.FROZEN, machine.state)
        assertFalse(machine.acceptsQualityUpdates)
        assertEquals(FieldModeAction.RETURN_TO_LIVE, machine.onPrimaryAction())
        assertEquals(FieldMode.LIVE, machine.state)
        assertTrue(machine.acceptsQualityUpdates)
    }

    @Test
    fun `missing preview frame returns to live`() {
        val machine = FieldModeMachine()
        machine.onPrimaryAction()

        machine.onCaptureUnavailable()

        assertEquals(FieldMode.LIVE, machine.state)
        assertTrue(machine.acceptsQualityUpdates)
    }
}
```

- [ ] **Step 2: RED verifizieren**

Run:

```bash
./gradlew --offline testDebugUnitTest --tests "com.example.mushroomexposed.FieldModeMachineTest"
```

Expected: Kotlin-Kompilierung schlägt wegen fehlender `FieldModeMachine`, `FieldMode` und `FieldModeAction` fehl.

- [ ] **Step 3: Minimale Implementierung schreiben**

```kotlin
package com.example.mushroomexposed

enum class FieldMode { LIVE, ANALYSING, FROZEN }

enum class FieldModeAction { CAPTURE, RETURN_TO_LIVE, IGNORE }

class FieldModeMachine {
    @Volatile
    var state: FieldMode = FieldMode.LIVE
        private set

    val acceptsQualityUpdates: Boolean
        get() = state == FieldMode.LIVE

    fun onPrimaryAction(): FieldModeAction = when (state) {
        FieldMode.LIVE -> {
            state = FieldMode.ANALYSING
            FieldModeAction.CAPTURE
        }
        FieldMode.ANALYSING -> FieldModeAction.IGNORE
        FieldMode.FROZEN -> {
            state = FieldMode.LIVE
            FieldModeAction.RETURN_TO_LIVE
        }
    }

    fun onCaptureUnavailable() {
        if (state == FieldMode.ANALYSING) state = FieldMode.LIVE
    }

    fun onAnalysisFinished() {
        if (state == FieldMode.ANALYSING) state = FieldMode.FROZEN
    }
}
```

- [ ] **Step 4: GREEN und Regressionen verifizieren**

Run:

```bash
./gradlew --offline testDebugUnitTest --tests "com.example.mushroomexposed.FieldModeMachineTest"
./gradlew --offline testDebugUnitTest
```

Expected: beide Befehle `BUILD SUCCESSFUL`, keine Testfehler.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/mushroomexposed/FieldMode.kt \
  app/src/test/java/com/example/mushroomexposed/FieldModeMachineTest.kt
git commit -m "feat(app): model live and frozen field states"
```

---

### Task 2: Standbild-Overlay und kontrollierte UI-Übergänge

**Files:**
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/com/example/mushroomexposed/MainActivity.kt`
- Test: `app/src/test/java/com/example/mushroomexposed/FieldModeMachineTest.kt` (bestehende Übergangsverträge)

**Interfaces:**
- Consumes: `FieldModeMachine` und `FieldModeAction` aus Task 1.
- Produces: View-Binding-Feld `frozenImage`; `MainActivity`-Methoden `captureAndAnalyse()`, `showAnalysingMode(Bitmap)`, `showFrozenControls()`, `showLiveMode()`.

- [ ] **Step 1: Bestehenden RED-Vertrag gegen die fehlerhafte Integration festhalten**

Vor Produktionsänderungen verifizieren:

```bash
test "$(grep -c 'frozenImage' app/src/main/res/layout/activity_main.xml)" -gt 0
```

Expected: Exit 1, weil das Standbild-Overlay fehlt. Zusätzlich zeigt die bereits grüne Zustandsmaschinen-Suite den einzuhaltenden Übergangsvertrag; die Android-Integration wird gemäß Spec per Build und Smoke-Test geprüft.

- [ ] **Step 2: Standbild-Overlay hinzufügen**

Direkt nach `PreviewView` in `activity_main.xml` einfügen:

```xml
<ImageView
    android:id="@+id/frozenImage"
    android:layout_width="0dp"
    android:layout_height="0dp"
    android:contentDescription="@string/frozen_frame"
    android:scaleType="centerCrop"
    android:visibility="gone"
    app:layout_constraintTop_toTopOf="parent"
    app:layout_constraintBottom_toBottomOf="parent"
    app:layout_constraintStart_toStartOf="parent"
    app:layout_constraintEnd_toEndOf="parent" />
```

Am bestehenden `qualityHint` zusätzlich `android:visibility="gone"` setzen;
der erste berechnete Qualitätswert blendet ihn ein. So erscheint vor dem ersten
Kameraframe kein leerer schwarzer Hinweis.

- [ ] **Step 3: Zustandsbeschriftungen ergänzen**

In `strings.xml` `analyse_again` durch diese Ressourcen ersetzen:

```xml
<string name="analysing">Analysiere …</string>
<string name="new_capture">Neu</string>
<string name="frozen_frame">Aufgenommenes Pilzbild</string>
```

- [ ] **Step 4: Primäraktion an die Zustandsmaschine anbinden**

In `MainActivity` bei den übrigen Feldern ergänzen:

```kotlin
private val fieldMode = FieldModeMachine()
```

`onShutter()` ersetzen und die Capture-Helfer ergänzen:

```kotlin
private fun onShutter() {
    when (fieldMode.onPrimaryAction()) {
        FieldModeAction.CAPTURE -> captureAndAnalyse()
        FieldModeAction.RETURN_TO_LIVE -> showLiveMode()
        FieldModeAction.IGNORE -> Unit
    }
}

private fun captureAndAnalyse() {
    if (interpreter == null) {
        fieldMode.onCaptureUnavailable()
        toast(getString(R.string.model_missing))
        return
    }
    val bitmap = previewView.bitmap
    if (bitmap == null) {
        fieldMode.onCaptureUnavailable()
        toast(getString(R.string.frame_missing))
        return
    }
    showAnalysingMode(bitmap)
    analyse(bitmap)
}

private fun showAnalysingMode(bitmap: Bitmap) {
    frozen = bitmap
    binding.frozenImage.setImageBitmap(bitmap)
    binding.frozenImage.visibility = View.VISIBLE
    binding.targetFrame.visibility = View.GONE
    binding.qualityHint.text = ""
    binding.qualityHint.visibility = View.GONE
    binding.resultHeadline.setText(R.string.analysing)
    binding.resultHeadline.setTextColor(0xFFFFFFFF.toInt())
    binding.resultSubline.visibility = View.GONE
    binding.resultTops.visibility = View.GONE
    binding.warningText.visibility = View.GONE
    binding.emergencyText.visibility = View.GONE
    binding.shutterButton.setText(R.string.analysing)
    binding.shutterButton.isEnabled = false
}
```

Der an `showAnalysingMode` und `analyse` übergebene Bitmap muss dieselbe lokale
Variable sein; es darf kein zweiter `previewView.bitmap`-Abruf erfolgen.

- [ ] **Step 5: Erfolg, Fehler und Rückkehr rendern**

Im erfolgreichen `runOnUiThread` von `analyse` zuerst `fieldMode.onAnalysisFinished()`, dann bestehendes `render`/`recordHistory` und `showFrozenControls()` aufrufen. Im Fehlerzweig ebenfalls `fieldMode.onAnalysisFinished()`, die Fehlermeldung setzen und `showFrozenControls()` aufrufen.

`showFrozenControls()` aktiviert den Primärbutton mit `R.string.new_capture`.

Diese beiden Helfer ergänzen:

```kotlin
private fun showFrozenControls() {
    binding.shutterButton.setText(R.string.new_capture)
    binding.shutterButton.isEnabled = true
}

private fun showLiveMode() {
frozen = null
binding.frozenImage.setImageDrawable(null)
binding.frozenImage.visibility = View.GONE
binding.targetFrame.visibility = View.VISIBLE
binding.qualityHint.visibility = View.GONE
binding.qualityHint.text = ""
binding.shutterButton.isEnabled = true
binding.shutterButton.setText(R.string.shutter)
binding.resultHeadline.text = getString(R.string.ready, labels.size)
binding.resultHeadline.setTextColor(0xFFFFFFFF.toInt())
binding.resultSubline.visibility = View.GONE
binding.resultTops.visibility = View.GONE
binding.warningText.visibility = View.GONE
binding.emergencyText.visibility = View.GONE
}
```

Im erfolgreichen `runOnUiThread`-Block:

```kotlin
runOnUiThread {
    fieldMode.onAnalysisFinished()
    render(view)
    recordHistory(best, bestProbability, decision, hint != null)
    showFrozenControls()
}
```

Den Fehlerblock ersetzen durch:

```kotlin
} catch (e: Exception) {
    runOnUiThread {
        fieldMode.onAnalysisFinished()
        binding.resultHeadline.text = getString(R.string.inference_failed, e.message)
        showFrozenControls()
    }
}
```

- [ ] **Step 6: Quality-Analyzer außerhalb von LIVE sperren**

Am Anfang von `analyseQuality`, innerhalb des `try`, ergänzen:

```kotlin
if (!fieldMode.acceptsQualityUpdates) return
```

Im `runOnUiThread` vor jedem UI-Schreiben erneut prüfen:

```kotlin
if (!fieldMode.acceptsQualityUpdates) return@runOnUiThread
```

Unmittelbar danach und vor dem Setzen des Texts ergänzen:

```kotlin
binding.qualityHint.visibility = View.VISIBLE
```

Der bestehende `finally`-Block muss jeden `ImageProxy` weiterhin schließen.

- [ ] **Step 7: GREEN per Tests und Build verifizieren**

Run:

```bash
./gradlew --offline testDebugUnitTest
./gradlew --offline assembleDebug
```

Expected: beide Befehle `BUILD SUCCESSFUL`; Layout/View Binding kompiliert; keine Testfehler.

- [ ] **Step 8: Android-Smoke-Test ausführen**

Auf verfügbarem Redmi oder `xikey_api35`:

1. APK installieren und Kamera-Berechtigung sicherstellen.
2. App starten; Button zeigt „Analysieren“, Kamera ist verbunden.
3. Auslösen; während der Analyse zeigt der Button „Analysiere …“, danach „Neu“.
4. Standbild und Ergebnis bleiben mindestens drei Sekunden unverändert; keine Qualitätszeile erscheint erneut.
5. „Neu“ drücken; bewegte Vorschau und Qualitätszeile kehren zurück, ohne neue Analyse.
6. Erneut „Analysieren“ drücken; ein neuer Zyklus endet wieder in „Neu“.
7. Logcat auf `FATAL EXCEPTION`, `Inference error`, `Frame error` prüfen.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/res/layout/activity_main.xml \
  app/src/main/res/values/strings.xml \
  app/src/main/java/com/example/mushroomexposed/MainActivity.kt
git commit -m "fix(app): show a stable frozen analysis frame"
```
