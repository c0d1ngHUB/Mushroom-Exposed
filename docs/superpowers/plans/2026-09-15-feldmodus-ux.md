# Feldmodus-UX Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Die Pilze-App bekommt einen ruhigen Feldmodus: Auslöser statt Dauererkennung, Kameraführung, klare Ergebnisanzeige, lokalen Verlauf und Verwechslungswarnungen.

**Architecture:** Alle neue Fachlogik (Verlauf, Bildqualität, Doppelgänger-Daten, Formatierung, Verzehrlogik) liegt in reinen Kotlin-Klassen ohne Android-Abhängigkeit und wird per JUnit getestet. `MainActivity` enthält nur noch Kameravorschau, Zustandsmaschine LIVE/FROZEN und UI-Verdrahtung. Die Doppelgänger-Daten werden im Trainings-Repo aus den vorhandenen Wikipedia-Cache-Texten extrahiert und als Asset `lookalikes.txt` in die App gestaged.

**Tech Stack:** Kotlin/Android (AGP 8.7.3, Kotlin 2.1.21, minSdk 26), CameraX 1.3.4, TensorFlow Lite 2.15.0, JUnit 4, Python 3 mit `unittest`, adb.

**Spec:** `docs/superpowers/specs/2026-09-15-feldmodus-ux-design.md`

## Global Constraints

- `VerdictPolicy.CONFIDENCE_THRESHOLD` bleibt `0.40f`; das Schwellenverhalten wird nicht gelockert.
- Das Modell-Asset (`model.tflite`, `labels.txt`) und alles im Trainings-Repo außer der Lookalike-Extraktion bleiben unverändert.
- Keine neuen Gradle-Abhängigkeiten: kein Room, kein Gson, kein Moshi, kein Kotlinx-Serialization. JSONL wird mit reinem Kotlin geschrieben/gelesen.
- Reine Logikklassen (`History.kt`, `FrameQuality.kt`, `LookalikeData.kt`, `ResultFormatter.kt`) importieren **kein** `android.*` und **kein** `org.json`; sie müssen im JVM-Unit-Test laufen. Android-Zugriffe (Assets, Activity, Dateisystem-Pfade) sitzen ausschließlich in `MainActivity`/`HistoryActivity`.
- Ein Lookalike-Paar mit `gefaehrlich` darf niemals zu einem grünen Kopf führen.
- Zusätzliche Sicherheitsregel gegenüber der Spec (bewusste Verschärfung): `achtung`-Einträge werden nur erzeugt, wenn die Zielart in `verdict_final.json` **nicht** als `essbar` geführt wird; essbare Ähnlichkeiten erzeugen keinen Eintrag.
- Verlauf speichert niemals Bilddaten oder Standortdaten; Cap 200 Einträge; Datei `filesDir/history.jsonl`.
- Sichtbare Texte liegen in `res/values/strings.xml`.
- Version: `versionCode = 7`, `versionName = "0.6.0"`.
- Arbeit auf `feat/feldmodus-ux` in `Mushroom-Exposed` und `feat/lookalikes` in `Mushroom-Exposed-training`; Conventional Commits; Merge nach `main` erst nach bestandenem Smoke-Test.

---

## File Structure

App (`/home/m3kky/projects/Mushroom-Exposed`):

- Create: `app/src/main/java/com/example/mushroomexposed/History.kt` — `HistoryEntry`, JSONL-Serialisierung, `HistoryStore` (Cap, Append, Lesen, Löschen).
- Create: `app/src/main/java/com/example/mushroomexposed/FrameQuality.kt` — Graustufen-Analyse, `FrameQuality`, `QualityHint`, `FrameQualityPolicy`.
- Create: `app/src/main/java/com/example/mushroomexposed/LookalikeData.kt` — `Lookalike`, `LookalikeKind`, Parser, Asset-Loader.
- Create: `app/src/main/java/com/example/mushroomexposed/ResultFormatter.kt` — `RankedSpecies`, `ResultView`, Formatierung.
- Modify: `app/src/main/java/com/example/mushroomexposed/VerdictPolicy.kt` — Lookalike-Parameter.
- Modify: `app/src/main/java/com/example/mushroomexposed/MainActivity.kt` — Zustandsmaschine, Kamera, UI.
- Create: `app/src/main/java/com/example/mushroomexposed/HistoryActivity.kt` — Verlaufsliste.
- Modify: `app/src/main/res/layout/activity_main.xml`, Create: `app/src/main/res/layout/activity_history.xml`.
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/AndroidManifest.xml` (HistoryActivity, Hochformat).
- Create: `app/src/main/assets/lookalikes.txt` (gestaged).
- Create tests: `app/src/test/java/com/example/mushroomexposed/{HistoryStoreTest,FrameQualityTest,LookalikeDataTest,ResultFormatterTest}.kt`; Modify: `VerdictPolicyTest.kt`.
- Modify: `app/build.gradle.kts` (Version).

Training (`/home/m3kky/projects/Mushroom-Exposed-training`):

- Create: `src/extract_lookalikes.py`, `src/lookalikes_curated.json`, `tests/test_extract_lookalikes.py`.
- Modify: `src/verify_and_stage.py` (Lookalikes staging), `README.md`.

---

### Task 1: Verlaufsdatei (JSONL)

**Files:**
- Create: `app/src/main/java/com/example/mushroomexposed/History.kt`
- Create: `app/src/test/java/com/example/mushroomexposed/HistoryStoreTest.kt`

**Interfaces:**
- Produces `data class HistoryEntry(val timestamp: String, val scientific: String, val german: String, val confidence: Float, val verdict: String, val lookalike: Boolean)`.
- Produces `class HistoryStore(directory: java.io.File, cap: Int = 200)` mit `fun append(entry: HistoryEntry)`, `fun readNewestFirst(): List<HistoryEntry>`, `fun clear()`; Datei ist `directory/history.jsonl`.
- Produces intern `fun encode(entry: HistoryEntry): String` und `fun decode(line: String): HistoryEntry?` (Top-Level-Funktionen im selben File, für Tests sichtbar).

- [ ] **Step 1: Failing test schreiben**

```kotlin
package com.example.mushroomexposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HistoryStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun entry(i: Int, verdict: String = "essbar") = HistoryEntry(
        timestamp = "2026-09-15T12:0$i:00Z",
        scientific = "Agaricus_essettei",
        german = "Dünnfleischiger Anis-Champignon",
        confidence = 0.5f + i / 100f,
        verdict = verdict,
        lookalike = true,
    )

    @Test
    fun `append and read back keeps newest first`() {
        val store = HistoryStore(tmp.root)
        store.append(entry(1))
        store.append(entry(2))

        val read = store.readNewestFirst()

        assertEquals(2, read.size)
        assertEquals("2026-09-15T12:02:00Z", read[0].timestamp)
        assertEquals("Dünnfleischiger Anis-Champignon", read[0].german)
        assertEquals("essbar", read[0].verdict)
        assertEquals(true, read[0].lookalike)
        assertEquals("2026-09-15T12:01:00Z", read[1].timestamp)
    }

    @Test
    fun `store keeps at most cap entries`() {
        val store = HistoryStore(tmp.root, cap = 3)
        repeat(5) { store.append(entry(it)) }

        val read = store.readNewestFirst()

        assertEquals(3, read.size)
        assertEquals("2026-09-15T12:04:00Z", read[0].timestamp)
        assertEquals("2026-09-15T12:02:00Z", read[2].timestamp)
    }

    @Test
    fun `clear removes the file`() {
        val store = HistoryStore(tmp.root)
        store.append(entry(1))

        store.clear()

        assertTrue(store.readNewestFirst().isEmpty())
    }

    @Test
    fun `corrupt lines are skipped and quotes are escaped`() {
        val file = tmp.newFile("history.jsonl")
        file.writeText("{\"ts\":\"broken\"\n\n" + encode(entry(1)).let { it.replace("Dünnfleischiger", "Dünn\"fleischiger") })

        val read = HistoryStore(tmp.root).readNewestFirst()

        assertEquals(1, read.size)
        assertEquals("Dünn\"fleischiger Anis-Champignon", read[0].german)
    }

    @Test
    fun `an unreadable directory does not throw on read`() {
        val store = HistoryStore(java.io.File(tmp.root, "does-not-exist"))
        assertTrue(store.readNewestFirst().isEmpty())
    }
}
```

- [ ] **Step 2: Test laufen lassen, RED bestätigen**

Run: `./gradlew testDebugUnitTest --tests "com.example.mushroomexposed.HistoryStoreTest"`
Expected: Kompilierfehler `Unresolved reference: HistoryStore` / `HistoryEntry` / `encode`.

- [ ] **Step 3: Implementieren**

```kotlin
package com.example.mushroomexposed

import java.io.File

data class HistoryEntry(
    val timestamp: String,
    val scientific: String,
    val german: String,
    val confidence: Float,
    val verdict: String,
    val lookalike: Boolean,
)

private fun escape(value: String): String {
    val sb = StringBuilder(value.length + 8)
    for (c in value) when (c) {
        '"' -> sb.append("\\\"")
        '\\' -> sb.append("\\\\")
        '\n' -> sb.append("\\n")
        '\r' -> sb.append("\\r")
        '\t' -> sb.append("\\t")
        else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
    }
    return sb.toString()
}

private fun unescape(value: String): String {
    val sb = StringBuilder(value.length)
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c != '\\' || i + 1 >= value.length) { sb.append(c); i++; continue }
        when (val n = value[i + 1]) {
            '"', '\\', '/' -> { sb.append(n); i += 2 }
            'n' -> { sb.append('\n'); i += 2 }
            'r' -> { sb.append('\r'); i += 2 }
            't' -> { sb.append('\t'); i += 2 }
            'u' -> {
                if (i + 6 > value.length) { sb.append(c); i++; continue }
                sb.append(value.substring(i + 2, i + 6).toInt(16).toChar()); i += 6
            }
            else -> { sb.append(c); i++ }
        }
    }
    return sb.toString()
}

/** One JSON object per line, fixed key order: ts, sci, de, conf, verdict, lookalike. */
fun encode(entry: HistoryEntry): String = buildString {
    append("{\"ts\":\"").append(escape(entry.timestamp)).append('"')
    append(",\"sci\":\"").append(escape(entry.scientific)).append('"')
    append(",\"de\":\"").append(escape(entry.german)).append('"')
    append(",\"conf\":").append(entry.confidence)
    append(",\"verdict\":\"").append(escape(entry.verdict)).append('"')
    append(",\"lookalike\":").append(entry.lookalike)
    append('}')
}

private val FIELD = Regex("\"(ts|sci|de|conf|verdict|lookalike)\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|true|false|-?[0-9.]+)")

fun decode(line: String): HistoryEntry? {
    val trimmed = line.trim()
    if (trimmed.isEmpty() || !trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
    val found = FIELD.findAll(trimmed).associate { it.groupValues[1] to it.groupValues[2] }
    val ts = found["ts"] ?: return null
    val sci = found["sci"] ?: return null
    return try {
        HistoryEntry(
            timestamp = unescape(ts.trim('"')),
            scientific = unescape(sci.trim('"')),
            german = unescape((found["de"] ?: "\"\"").trim('"')),
            confidence = (found["conf"] ?: return null).toFloat(),
            verdict = unescape((found["verdict"] ?: "\"\"").trim('"')),
            lookalike = found["lookalike"] == "true",
        )
    } catch (e: NumberFormatException) {
        null
    }
}

class HistoryStore(private val directory: File, private val cap: Int = 200) {

    private val file get() = File(directory, FILE_NAME)

    @Synchronized
    fun append(entry: HistoryEntry) {
        directory.mkdirs()
        val existing = file.takeIf { it.isFile }?.readLines().orEmpty().filter { it.isNotBlank() }
        val lines = (existing + encode(entry)).takeLast(cap)
        file.writeText(lines.joinToString("\n", postfix = "\n"))
    }

    fun readNewestFirst(): List<HistoryEntry> = try {
        if (!file.isFile) emptyList()
        else file.readLines().mapNotNull { decode(it) }.asReversed()
    } catch (e: java.io.IOException) {
        emptyList()
    }

    @Synchronized
    fun clear() {
        if (file.isFile) file.delete()
    }

    companion object {
        const val FILE_NAME = "history.jsonl"
        const val DEFAULT_CAP = 200
    }
}
```

- [ ] **Step 4: Test laufen lassen, GREEN bestätigen**

Run: `./gradlew testDebugUnitTest --tests "com.example.mushroomexposed.HistoryStoreTest"`
Expected: 5 Tests, alle grün.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/mushroomexposed/History.kt app/src/test/java/com/example/mushroomexposed/HistoryStoreTest.kt
git commit -m "feat(app): add JSONL history store"
```

### Task 2: Doppelgänger-Daten in der App

**Files:**
- Create: `app/src/main/java/com/example/mushroomexposed/LookalikeData.kt`
- Create: `app/src/test/java/com/example/mushroomexposed/LookalikeDataTest.kt`

**Interfaces:**
- Produces `enum class LookalikeKind { GEFAEHRLICH, ACHTUNG }`.
- Produces `data class Lookalike(val kind: LookalikeKind, val targetKey: String, val targetName: String, val evidence: String)`.
- Produces `object LookalikeParser { fun parse(lines: Sequence<String>, namesByKey: Map<String, String>): Map<String, List<Lookalike>> }`.
- Produces `object LookalikeData { fun load(assets: android.content.res.AssetManager, namesByKey: Map<String, String>): Map<String, List<Lookalike>> }` — liest `lookalikes.txt`, gibt bei jedem Fehler eine leere Map zurück.
- Consumes das Asset-Format aus Task 6: `keyInApp|gefaehrlich:ZielKey|achtung:ZielKey|Belegsatz`.

- [ ] **Step 1: Failing test schreiben**

```kotlin
package com.example.mushroomexposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LookalikeDataTest {
    private val names = mapOf(
        "Amanita_phalloides" to "Grüner Knollenblätterpilz",
        "Agaricus_xanthodermus" to "Karbol-Champignon",
    )

    @Test
    fun `parses pairs and keeps the evidence sentence`() {
        val map = LookalikeParser.parse(
            listOf(
                "# kommentar",
                "Agaricus_essettei|gefaehrlich:Amanita_phalloides|Junge Fruchtkörper ähneln tödlich giftigen Knollenblätterpilzen.",
            ).asSequence(),
            names,
        )

        val entry = map.getValue("Agaricus_essettei").single()
        assertEquals(LookalikeKind.GEFAEHRLICH, entry.kind)
        assertEquals("Amanita_phalloides", entry.targetKey)
        assertEquals("Grüner Knollenblätterpilz", entry.targetName)
        assertEquals("Junge Fruchtkörper ähneln tödlich giftigen Knollenblätterpilzen.", entry.evidence)
    }

    @Test
    fun `unknown target key falls back to a readable name and unknown kinds are ignored`() {
        val map = LookalikeParser.parse(
            listOf("Boletus_edulis|harmlos:Boletus_luridus|achtung:Boletus_calopus").asSequence(),
            names,
        )

        val entry = map.getValue("Boletus_edulis").single()
        assertEquals(LookalikeKind.ACHTUNG, entry.kind)
        assertEquals("Boletus calopus", entry.targetName)
        assertEquals("", entry.evidence)
    }

    @Test
    fun `malformed lines are skipped without throwing`() {
        val map = LookalikeParser.parse(
            listOf("", "   ", "nurkey", "|gefaehrlich:Amanita_phalloides", "Key|gefaehrlich").asSequence(),
            names,
        )

        assertTrue(map.isEmpty())
    }

    @Test
    fun `duplicate pairs are collapsed`() {
        val map = LookalikeParser.parse(
            listOf("Agaricus_essettei|gefaehrlich:Amanita_phalloides|x|gefaehrlich:Amanita_phalloides|Beleg").asSequence(),
            names,
        )

        assertEquals(1, map.getValue("Agaricus_essettei").size)
    }
}
```

- [ ] **Step 2: Test laufen lassen, RED bestätigen**

Run: `./gradlew testDebugUnitTest --tests "com.example.mushroomexposed.LookalikeDataTest"`
Expected: `Unresolved reference: LookalikeParser`.

- [ ] **Step 3: Implementieren**

```kotlin
package com.example.mushroomexposed

import android.content.res.AssetManager

enum class LookalikeKind { GEFAEHRLICH, ACHTUNG }

data class Lookalike(
    val kind: LookalikeKind,
    val targetKey: String,
    val targetName: String,
    val evidence: String,
) {
    val isDangerous get() = kind == LookalikeKind.GEFAEHRLICH
}

object LookalikeParser {

    private val PAIR = Regex("^(gefaehrlich|achtung):(.+)$", RegexOption.IGNORE_CASE)

    fun parse(lines: Sequence<String>, namesByKey: Map<String, String>): Map<String, List<Lookalike>> {
        val result = LinkedHashMap<String, MutableList<Lookalike>>()
        val seen = HashSet<String>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split("|").map { it.trim() }
            val key = parts.firstOrNull().orEmpty()
            if (key.isEmpty() || parts.size < 2) continue

            val evidence = parts.drop(1).lastOrNull { !PAIR.matches(it) } ?: ""
            val pairs = parts.drop(1).mapNotNull { part ->
                val m = PAIR.matchEntire(part) ?: return@mapNotNull null
                val kind = if (m.groupValues[1].equals("gefaehrlich", ignoreCase = true))
                    LookalikeKind.GEFAEHRLICH else LookalikeKind.ACHTUNG
                val target = m.groupValues[2].trim()
                if (target.isEmpty()) null else Lookalike(
                    kind = kind,
                    targetKey = target,
                    targetName = namesByKey[target] ?: target.replace('_', ' '),
                    evidence = evidence,
                )
            }
            for (pair in pairs) {
                if (seen.add("$key|${pair.kind}|${pair.targetKey}")) {
                    result.getOrPut(key) { mutableListOf() }.add(pair)
                }
            }
        }
        return result
    }
}

object LookalikeData {
    const val ASSET_NAME = "lookalikes.txt"

    fun load(assets: AssetManager, namesByKey: Map<String, String>): Map<String, List<Lookalike>> = try {
        assets.open(ASSET_NAME).bufferedReader().useLines { lines ->
            LookalikeParser.parse(lines, namesByKey)
        }
    } catch (e: Exception) {
        emptyMap()
    }
}
```

- [ ] **Step 4: Test laufen lassen, GREEN bestätigen**

Run: `./gradlew testDebugUnitTest --tests "com.example.mushroomexposed.LookalikeDataTest"`
Expected: 4 Tests grün.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/mushroomexposed/LookalikeData.kt app/src/test/java/com/example/mushroomexposed/LookalikeDataTest.kt
git commit -m "feat(app): parse lookalike species data"
```

### Task 3: Verzehrlogik mit Doppelgänger

**Files:**
- Modify: `app/src/main/java/com/example/mushroomexposed/VerdictPolicy.kt`
- Modify: `app/src/test/java/com/example/mushroomexposed/VerdictPolicyTest.kt`

**Interfaces:**
- Consumes `Lookalike`, `LookalikeKind` aus Task 2.
- Produces `VerdictPolicy.decide(verdict: String?, confidence: Float, lookalike: Lookalike? = null): VerdictDecision` (Rückgabetyp unverändert; der Default-Parameter hält alle bisherigen Tests gültig).
- Produces `VerdictPolicy.lookalikeSentence(lookalike: Lookalike): String`.

- [ ] **Step 1: Failing test ergänzen**

An `VerdictPolicyTest` anhängen:

```kotlin
    @Test
    fun `dangerous lookalike never yields a green edible verdict`() {
        val lookalike = Lookalike(
            kind = LookalikeKind.GEFAEHRLICH,
            targetKey = "Amanita_phalloides",
            targetName = "Grüner Knollenblätterpilz",
            evidence = "Junge Fruchtkörper ähneln tödlich giftigen Knollenblätterpilzen.",
        )

        val decision = VerdictPolicy.decide("essbar", confidence = 0.95f, lookalike = lookalike)

        assertEquals(VerdictTone.CAUTION, decision.tone)
        assertTrue(decision.warning.startsWith("Nur bei sicherer Bestimmung essen"))
        assertTrue(decision.warning.contains("Grüner Knollenblätterpilz"))
        assertTrue(decision.warning.contains("Knollenblätterpilzen"))
    }

    @Test
    fun `attention lookalike keeps the tone and appends the hint`() {
        val lookalike = Lookalike(
            kind = LookalikeKind.ACHTUNG,
            targetKey = "Agaricus_xanthodermus",
            targetName = "Karbol-Champignon",
            evidence = "",
        )

        val decision = VerdictPolicy.decide("essbar", confidence = 0.80f, lookalike = lookalike)

        assertEquals(VerdictTone.SAFE, decision.tone)
        assertTrue(decision.warning.contains("Karbol-Champignon"))
    }

    @Test
    fun `missing lookalike keeps the previous behaviour`() {
        val decision = VerdictPolicy.decide("essbar", confidence = 0.80f, lookalike = null)
        assertEquals(VerdictTone.SAFE, decision.tone)
        assertEquals("Nur bei sicherer Bestimmung essen — nie auf App verlassen.", decision.warning)
    }
```

Zusätzlich oben ergänzen: `import org.junit.Assert.assertTrue`.

- [ ] **Step 2: Test laufen lassen, RED bestätigen**

Run: `./gradlew testDebugUnitTest --tests "com.example.mushroomexposed.VerdictPolicyTest"`
Expected: `Too many arguments for decide` — Kompilierfehler.

- [ ] **Step 3: Implementieren**

In `VerdictPolicy.kt` die fünf `VerdictDecision(...)`-Zweige in eine private Funktion `baseDecision(verdict, confidence)` auslagern (Wortlaut unverändert) und `decide` erweitern:

```kotlin
object VerdictPolicy {
    const val CONFIDENCE_THRESHOLD = 0.40f

    fun lookalikeSentence(lookalike: Lookalike): String {
        val body = if (lookalike.evidence.isBlank()) {
            "Achtung Verwechslung: sieht aus wie ${lookalike.targetName}."
        } else {
            "Achtung Verwechslung mit ${lookalike.targetName}: ${lookalike.evidence}"
        }
        return body
    }

    fun decide(verdict: String?, confidence: Float, lookalike: Lookalike? = null): VerdictDecision {
        val base = baseDecision(verdict, confidence)
        if (lookalike == null) return base
        val tone = if (lookalike.isDangerous && base.tone == VerdictTone.SAFE) VerdictTone.CAUTION else base.tone
        return base.copy(tone = tone, warning = "${base.warning} ${lookalikeSentence(lookalike)}")
    }

    private fun baseDecision(verdict: String?, confidence: Float): VerdictDecision = when {
        // ... unveränderte Zweige wie bisher ...
    }
}
```

- [ ] **Step 4: Test laufen lassen, GREEN bestätigen**

Run: `./gradlew testDebugUnitTest --tests "com.example.mushroomexposed.VerdictPolicyTest"`
Expected: 7 Tests grün (4 alte + 3 neue).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/mushroomexposed/VerdictPolicy.kt app/src/test/java/com/example/mushroomexposed/VerdictPolicyTest.kt
git commit -m "feat(app): let dangerous lookalikes override the edible verdict"
```

### Task 4: Ergebnisformatierung

**Files:**
- Create: `app/src/main/java/com/example/mushroomexposed/ResultFormatter.kt`
- Create: `app/src/test/java/com/example/mushroomexposed/ResultFormatterTest.kt`

**Interfaces:**
- Produces `data class RankedSpecies(val germanName: String, val scientific: String, val verdict: String, val probability: Float)`.
- Produces `data class ResultView(val headline: String, val subline: String, val tone: VerdictTone, val topLines: List<String>, val warning: String, val emergency: String?)`.
- Produces `object ResultFormatter { fun format(ranked: List<RankedSpecies>, decision: VerdictDecision): ResultView }`.
- `emergency` ist `null` bei `VerdictTone.SAFE` und sonst der Text aus `R.string` `emergency_text`; `ResultFormatter` liefert dafür die Konstante `ResultFormatter.EMERGENCY_TEXT` (identisch zum String-Ressourcenwert, per Test festgehalten).

- [ ] **Step 1: Failing test schreiben**

```kotlin
package com.example.mushroomexposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResultFormatterTest {
    private val ranked = listOf(
        RankedSpecies("Steinpilz", "Boletus_edulis", "essbar", 0.71f),
        RankedSpecies("Schönfuß-Röhrling", "Boletus_calopus", "giftig", 0.12f),
        RankedSpecies("Fahler Röhrling", "Boletus_pallidus", "unbekannt", 0.008f),
    )

    @Test
    fun `headline carries the name and the subline the confidence`() {
        val view = ResultFormatter.format(ranked, VerdictPolicy.decide("essbar", 0.71f))

        assertEquals("essbar — Steinpilz", view.headline)
        assertEquals("Boletus_edulis · 71 %", view.subline)
        assertEquals(VerdictTone.SAFE, view.tone)
        assertNull(view.emergency)
    }

    @Test
    fun `top lines mark the verdict and format small confidences with one decimal`() {
        val view = ResultFormatter.format(ranked, VerdictPolicy.decide("essbar", 0.71f))

        assertEquals(
            listOf(
                "1. Steinpilz — 71 % ✓",
                "2. Schönfuß-Röhrling — 12 % ☠",
                "3. Fahler Röhrling — 0.8 %",
            ),
            view.topLines,
        )
    }

    @Test
    fun `caution and danger show the emergency block`() {
        val danger = ResultFormatter.format(ranked, VerdictPolicy.decide("giftig", 0.12f))
        val caution = ResultFormatter.format(ranked, VerdictPolicy.decide("unbekannt", 0.90f))

        assertEquals(VerdictTone.DANGER, danger.tone)
        assertEquals(ResultFormatter.EMERGENCY_TEXT, danger.emergency)
        assertEquals(ResultFormatter.EMERGENCY_TEXT, caution.emergency)
        assertEquals(
            "Bei Verdacht auf Pilzvergiftung: Vergiftungsinformationszentrale 01 406 43 43 (24 h) " +
                "oder Notruf 144. Restpilz und Erbrochenes aufbewahren.",
            ResultFormatter.EMERGENCY_TEXT,
        )
    }

    @Test
    fun `empty ranking falls back to the policy headline`() {
        val view = ResultFormatter.format(emptyList(), VerdictPolicy.decide(null, 0f))

        assertEquals("unsicher", view.headline)
        assertEquals("", view.subline)
        assertEquals(emptyList<String>(), view.topLines)
    }
}
```

- [ ] **Step 2: Test laufen lassen, RED bestätigen**

Run: `./gradlew testDebugUnitTest --tests "com.example.mushroomexposed.ResultFormatterTest"`
Expected: `Unresolved reference: ResultFormatter`.

- [ ] **Step 3: Implementieren**

```kotlin
package com.example.mushroomexposed

import java.util.Locale

data class RankedSpecies(
    val germanName: String,
    val scientific: String,
    val verdict: String,
    val probability: Float,
)

data class ResultView(
    val headline: String,
    val subline: String,
    val tone: VerdictTone,
    val topLines: List<String>,
    val warning: String,
    val emergency: String?,
)

object ResultFormatter {

    const val EMERGENCY_TEXT =
        "Bei Verdacht auf Pilzvergiftung: Vergiftungsinformationszentrale 01 406 43 43 (24 h) " +
            "oder Notruf 144. Restpilz und Erbrochenes aufbewahren."

    /** "71 %" from 0.71; below one percent one decimal ("0.8 %") so rare hits stay readable. */
    fun percent(probability: Float): String {
        val p = probability * 100
        return if (p >= 1.0) "${p.toInt()} %"
        else String.format(Locale.GERMANY, "%.1f %%", p)
    }

    fun format(ranked: List<RankedSpecies>, decision: VerdictDecision): ResultView {
        val best = ranked.firstOrNull()
        val topLines = ranked.mapIndexed { index, species ->
            val mark = when (species.verdict) {
                "giftig" -> " ☠"
                "essbar" -> " ✓"
                else -> ""
            }
            "${index + 1}. ${species.germanName} — ${percent(species.probability)}$mark"
        }
        return ResultView(
            headline = if (best == null) decision.headline else "${decision.headline} — ${best.germanName}",
            subline = if (best == null) "" else "${best.scientific} · ${percent(best.probability)}",
            tone = decision.tone,
            topLines = topLines,
            warning = decision.warning,
            emergency = if (decision.tone == VerdictTone.SAFE) null else EMERGENCY_TEXT,
        )
    }
}
```

- [ ] **Step 4: Test laufen lassen, GREEN bestätigen**

Run: `./gradlew testDebugUnitTest --tests "com.example.mushroomexposed.ResultFormatterTest"`
Expected: 4 Tests grün.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/mushroomexposed/ResultFormatter.kt app/src/test/java/com/example/mushroomexposed/ResultFormatterTest.kt
git commit -m "feat(app): format the field-mode result card"
```

### Task 5: Bildqualität

**Files:**
- Create: `app/src/main/java/com/example/mushroomexposed/FrameQuality.kt`
- Create: `app/src/test/java/com/example/mushroomexposed/FrameQualityTest.kt`

**Interfaces:**
- Produces `data class FrameQuality(val meanLuminance: Float, val laplacianVariance: Float, val overexposedFraction: Float)`.
- Produces `enum class QualityHint { DARK, BRIGHT, BLURRY, OK }`.
- Produces `object FrameQualityPolicy { const val MIN_LUMINANCE, MAX_LUMINANCE, MAX_OVEREXPOSED, MIN_SHARPNESS; fun hint(quality: FrameQuality): QualityHint }` mit Startwerten `0.18f`, `0.82f`, `0.30f`, `60f`.
- Produces `object FrameQualityAnalyzer { fun analyze(gray: IntArray, width: Int, height: Int): FrameQuality }` (Graustufen 0–255).
- Consumes in `MainActivity`: `android.graphics.Bitmap` → `IntArray` → `analyze`.

- [ ] **Step 1: Failing test schreiben**

```kotlin
package com.example.mushroomexposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameQualityTest {

    private fun uniform(value: Int, size: Int = 64) = IntArray(size * size) { value }

    /** Checkerboard: high local contrast, so a high Laplacian variance. */
    private fun checkerboard(size: Int = 64) =
        IntArray(size * size) { i -> if ((i / size + i % size) % 2 == 0) 20 else 235 }

    @Test
    fun `dark frame reports DARK`() {
        val quality = FrameQualityAnalyzer.analyze(uniform(20), 64, 64)
        assertTrue(quality.meanLuminance < 0.18f)
        assertEquals(QualityHint.DARK, FrameQualityPolicy.hint(quality))
    }

    @Test
    fun `bright frame reports BRIGHT`() {
        val quality = FrameQualityAnalyzer.analyze(uniform(230), 64, 64)
        assertTrue(quality.meanLuminance > 0.82f)
        assertEquals(QualityHint.BRIGHT, FrameQualityPolicy.hint(quality))
    }

    @Test
    fun `flat mid grey frame is BLURRY`() {
        val quality = FrameQualityAnalyzer.analyze(uniform(128), 64, 64)
        assertEquals(0f, quality.laplacianVariance, 0.0001f)
        assertEquals(QualityHint.BLURRY, FrameQualityPolicy.hint(quality))
    }

    @Test
    fun `contrasty mid grey frame is OK`() {
        val quality = FrameQualityAnalyzer.analyze(checkerboard(), 64, 64)
        assertTrue(quality.laplacianVariance > FrameQualityPolicy.MIN_SHARPNESS)
        assertEquals(QualityHint.OK, FrameQualityPolicy.hint(quality))
    }

    @Test
    fun `overexposed corners report BRIGHT even at moderate mean`() {
        val size = 64
        val gray = IntArray(size * size) { 120 }
        for (y in 0 until size / 2) for (x in 0 until size / 2) gray[y * size + x] = 255

        val quality = FrameQualityAnalyzer.analyze(gray, size, size)

        assertTrue(quality.overexposedFraction > 0.30f)
        assertEquals(QualityHint.BRIGHT, FrameQualityPolicy.hint(quality))
    }
}
```

- [ ] **Step 2: Test laufen lassen, RED bestätigen**

Run: `./gradlew testDebugUnitTest --tests "com.example.mushroomexposed.FrameQualityTest"`
Expected: `Unresolved reference: FrameQualityAnalyzer`.

- [ ] **Step 3: Implementieren**

```kotlin
package com.example.mushroomexposed

import kotlin.math.abs

data class FrameQuality(
    val meanLuminance: Float,
    val laplacianVariance: Float,
    val overexposedFraction: Float,
)

enum class QualityHint { DARK, BRIGHT, BLURRY, OK }

object FrameQualityPolicy {
    // Startwerte aus der Spec; im Geräte-Smoke-Test an echten Pilzfotos nachgezogen.
    const val MIN_LUMINANCE = 0.18f
    const val MAX_LUMINANCE = 0.82f
    const val MAX_OVEREXPOSED = 0.30f
    const val MIN_SHARPNESS = 60f

    fun hint(quality: FrameQuality): QualityHint = when {
        quality.meanLuminance < MIN_LUMINANCE -> QualityHint.DARK
        quality.meanLuminance > MAX_LUMINANCE -> QualityHint.BRIGHT
        quality.overexposedFraction > MAX_OVEREXPOSED -> QualityHint.BRIGHT
        quality.laplacianVariance < MIN_SHARPNESS -> QualityHint.BLURRY
        else -> QualityHint.OK
    }
}

object FrameQualityAnalyzer {

    /** 4-neighbour Laplacian magnitude; the variance over the frame measures sharpness. */
    fun analyze(gray: IntArray, width: Int, height: Int): FrameQuality {
        require(width > 2 && height > 2) { "frame too small: ${width}x$height" }
        require(gray.size >= width * height) { "pixel buffer too small" }

        var sum = 0L
        var overexposed = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = gray[y * width + x]
                sum += v
                if (v >= 250) overexposed++
            }
        }
        val mean = sum.toDouble() / (width * height)

        var lapSum = 0.0
        var lapSumSq = 0.0
        var count = 0
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val c = gray[y * width + x]
                val lap = abs(
                    4 * c - gray[(y - 1) * width + x] - gray[(y + 1) * width + x] -
                        gray[y * width + x - 1] - gray[y * width + x + 1],
                ).toDouble()
                lapSum += lap
                lapSumSq += lap * lap
                count++
            }
        }
        val lapMean = if (count == 0) 0.0 else lapSum / count
        val variance = if (count == 0) 0.0 else lapSumSq / count - lapMean * lapMean

        return FrameQuality(
            meanLuminance = (mean / 255.0).toFloat(),
            laplacianVariance = variance.toFloat(),
            overexposedFraction = overexposed.toFloat() / (width * height),
        )
    }
}
```

- [ ] **Step 4: Test laufen lassen, GREEN bestätigen**

Run: `./gradlew testDebugUnitTest --tests "com.example.mushroomexposed.FrameQualityTest"`
Expected: 5 Tests grün.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/mushroomexposed/FrameQuality.kt app/src/test/java/com/example/mushroomexposed/FrameQualityTest.kt
git commit -m "feat(app): add frame quality hints"
```

### Task 6: Lookalike-Extraktion im Trainings-Repo

**Files:**
- Create: `/home/m3kky/projects/Mushroom-Exposed-training/src/extract_lookalikes.py`
- Create: `/home/m3kky/projects/Mushroom-Exposed-training/src/lookalikes_curated.json`
- Create: `/home/m3kky/projects/Mushroom-Exposed-training/tests/test_extract_lookalikes.py`

**Interfaces:**
- Produces `parse_sections(text: str) -> list[tuple[str, str]]`.
- Produces `name_index(classes: dict) -> dict[str, str]` — Anzeigename (deutsch) → Klassenschlüssel.
- Produces `find_pairs(text: str, own_key: str, names: dict[str, str], verdicts: dict[str, str]) -> list[dict]` mit `{"target": str, "kind": "gefaehrlich"|"achtung", "evidence": str}`.
- Produces `build(classes, verdicts, cache_dir: Path, curated: dict) -> dict[str, list[dict]]`.
- Produces `write_app_file(entries: dict, destination: Path) -> dict[str, int]` (Format `key|kind:target|...|evidence`, Rückgabe `{"classes": n, "gefaehrlich": n, "achtung": n}`).
- Consumes `out/wiki_cache/*.txt`, `out/classes_v2.json`, `out/verdict_final.json`.

- [ ] **Step 1: Failing test schreiben**

```python
import unittest

from src.extract_lookalikes import (
    build,
    find_pairs,
    name_index,
    parse_sections,
    write_app_file,
)

TEXT = """Der Dünnfleischige Anis-Egerling (Agaricus essettei) ist eine Pilzart.

== Artabgrenzung ==
Besonders die jungen Fruchtkörper ähneln stark denen tödlich giftiger Knollenblätterpilze (Grüner Knollenblätterpilz, Kegelhütiger Knollenblätterpilz).
Der Pilz hat große Ähnlichkeit zu verwandten Arten wie dem Wiesen-Champignon (Agaricus campestris).

== Weblinks ==
Commons: Agaricus essettei – Sammlung von Bildern.
"""

CLASSES = {
    "Agaricus_essettei": {"display": "Agaricus essettei", "de_rest": "Dünnfleischiger Anis-Champignon"},
    "Amanita_phalloides": {"display": "Amanita phalloides", "de_rest": "Grüner Knollenblätterpilz"},
    "Agaricus_campestris": {"display": "Agaricus campestris", "de_rest": "Wiesen-Champignon"},
}
VERDICTS = {"Amanita_phalloides": "giftig", "Agaricus_campestris": "essbar"}


class ExtractLookalikesTest(unittest.TestCase):
    def test_parse_sections_splits_headings(self):
        heads = [h for h, _ in parse_sections(TEXT)]
        self.assertIn("Artabgrenzung", heads)
        self.assertIn("Weblinks", heads)

    def test_name_index_maps_german_names_to_keys(self):
        names = name_index(CLASSES)
        self.assertEqual(names["Grüner Knollenblätterpilz"], "Amanita_phalloides")

    def test_poisonous_lookalike_is_found_with_evidence(self):
        pairs = find_pairs(TEXT, "Agaricus_essettei", name_index(CLASSES), VERDICTS)

        poison = [p for p in pairs if p["target"] == "Amanita_phalloides"]
        self.assertEqual(len(poison), 1)
        self.assertEqual(poison[0]["kind"], "gefaehrlich")
        self.assertIn("Knollenblätterpilze", poison[0]["evidence"])

    def test_edible_relative_is_not_reported(self):
        pairs = find_pairs(TEXT, "Agaricus_essettei", name_index(CLASSES), VERDICTS)
        self.assertNotIn("Agaricus_campestris", [p["target"] for p in pairs])

    def test_unnamed_sentence_yields_nothing(self):
        pairs = find_pairs("== Artabgrenzung ==\nÄhnlich wie viele andere Arten.\n", "x", {}, {})
        self.assertEqual(pairs, [])

    def test_curated_entries_win_and_are_written_in_app_format(self):
        import json
        import tempfile
        from pathlib import Path

        with tempfile.TemporaryDirectory() as tmp:
            cache = Path(tmp)
            (cache / "agerling.txt").write_text(TEXT, encoding="utf-8")
            curated = {
                "Agaricus_essettei": [
                    {
                        "target": "Amanita_virosa",
                        "kind": "gefaehrlich",
                        "evidence": "Kuratierter Beleg.",
                    }
                ]
            }

            entries = build(CLASSES, VERDICTS, cache, curated)
            out = Path(tmp) / "lookalikes.txt"
            stats = write_app_file(entries, out)

            line = [l for l in out.read_text(encoding="utf-8").splitlines() if l.startswith("Agaricus_essettei|")][0]
            self.assertIn("gefaehrlich:Amanita_virosa", line)
            self.assertIn("gefaehrlich:Amanita_phalloides", line)
            self.assertIn("Kuratierter Beleg.", line)
            self.assertEqual(stats["gefaehrlich"], 2)
```

- [ ] **Step 2: Test laufen lassen, RED bestätigen**

Run: `cd /home/m3kky/projects/Mushroom-Exposed-training && ./venv/bin/python -m unittest tests.test_extract_lookalikes -v`
Expected: `ModuleNotFoundError: No module named 'src.extract_lookalikes'`.

- [ ] **Step 3: Implementieren**

`src/extract_lookalikes.py`:

```python
#!/usr/bin/env python3
"""Extract dangerous lookalike pairs from the cached German Wikipedia texts.

Only pairs whose target resolves to a class of our own label space are kept,
and only when the target is not cleared as edible: a false "essbar" hint is the
dangerous error, a missing hint is not. Every entry carries the evidence
sentence so a warning can be traced back to its source.
"""
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "out"
APP_ASSETS = Path("/home/m3kky/projects/Mushroom-Exposed/app/src/main/assets")

SECTION = re.compile(r"^\s*={2,}\s*(.+?)\s*={2,}\s*$", re.M)
RELEVANT = re.compile(r"^(Artabgrenzung|Verwechslung\w*|Ähnlich\w*|Giftigkeit|Toxikolog\w*)$", re.I)
LOOKALIKE = re.compile(r"ähnlich|verwechsl|gleicht|sieht\s+\S+\s+aus", re.I)
POISON = re.compile(r"tödlich\s+giftig|stark\s+giftig|sehr\s+giftig|giftig|giftverdächtig", re.I)
SENTENCE = re.compile(r"(?<=[.:;])\s+")


def parse_sections(text: str) -> list[tuple[str, str]]:
    """Split a Wikipedia plaintext article into (heading, body) pairs."""
    parts, marks = [], list(SECTION.finditer(text))
    for i, m in enumerate(marks):
        end = marks[i + 1].start() if i + 1 < len(marks) else len(text)
        parts.append((m.group(1).strip(), text[m.end():end].strip()))
    return parts


def name_index(classes: dict) -> dict[str, str]:
    """German display name -> class key, plus the scientific binomial."""
    names = {}
    for key, entry in classes.items():
        rest = (entry.get("de_rest") or "").strip()
        for synonym in rest.split(","):
            synonym = synonym.strip()
            if synonym:
                names.setdefault(synonym, key)
        display = (entry.get("display") or "").strip()
        if display and display.replace(" ", "_") != key:
            names.setdefault(display, key)
        names.setdefault(key.replace("_", " "), key)
    return names


def _resolve(sentence: str, names: dict[str, str], own_key: str) -> str | None:
    for name, key in names.items():
        if key == own_key or len(name) < 5:
            continue
        if re.search(r"(?<!\w)" + re.escape(name) + r"(?!\w)", sentence):
            return key
    return None


def find_pairs(text: str, own_key: str, names: dict[str, str], verdicts: dict[str, str]) -> list[dict]:
    pairs = []
    for heading, body in parse_sections(text):
        if not RELEVANT.match(heading):
            continue
        for sentence in SENTENCE.split(body):
            if not LOOKALIKE.search(sentence):
                continue
            target = _resolve(sentence, names, own_key)
            if target is None:
                continue
            if verdicts.get(target) == "essbar":
                continue
            kind = "gefaehrlich" if verdicts.get(target) == "giftig" or POISON.search(sentence) else "achtung"
            pairs.append({"target": target, "kind": kind, "evidence": " ".join(sentence.split())})
    return pairs


def build(classes: dict, verdicts: dict, cache_dir: Path, curated: dict) -> dict[str, list[dict]]:
    names = name_index(classes)
    found: dict[str, list[dict]] = {}
    for path in sorted(Path(cache_dir).glob("*.txt")):
        text = path.read_text(encoding="utf-8", errors="ignore")
        for key in classes:
            german = (classes[key].get("de_rest") or "").split(",")[0].strip()
            if german and german not in text and key.replace("_", " ") not in text:
                continue
            for pair in find_pairs(text, key, names, verdicts):
                found.setdefault(key, []).append(pair)

    entries: dict[str, list[dict]] = {}
    for key in sorted(set(found) | set(curated)):
        merged, seen = [], set()
        for pair in list(curated.get(key, [])) + list(found.get(key, [])):
            target = pair.get("target")
            if not target or target == key or target not in classes:
                continue
            marker = (pair.get("kind"), target)
            if marker in seen:
                continue
            seen.add(marker)
            merged.append({
                "target": target,
                "kind": "gefaehrlich" if pair.get("kind") == "gefaehrlich" else "achtung",
                "evidence": pair.get("evidence", ""),
            })
        if merged:
            entries[key] = merged
    return entries


def write_app_file(entries: dict, destination: Path) -> dict[str, int]:
    stats = {"classes": 0, "gefaehrlich": 0, "achtung": 0}
    lines = ["# automatisch erzeugt von src/extract_lookalikes.py — nicht von Hand editieren"]
    for key in sorted(entries):
        pairs = entries[key]
        evidence = next((p["evidence"] for p in pairs if p.get("evidence")), "")
        parts = [key] + [f"{p['kind']}:{p['target']}" for p in pairs]
        if evidence:
            parts.append(evidence)
        lines.append("|".join(parts))
        stats["classes"] += 1
        for p in pairs:
            stats[p["kind"]] = stats.get(p["kind"], 0) + 1
    destination.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return stats


def main() -> int:
    classes = json.load(open(OUT / "classes_v2.json"))
    verdicts = json.load(open(OUT / "verdict_final.json"))
    curated_path = ROOT / "src" / "lookalikes_curated.json"
    curated = json.load(open(curated_path)) if curated_path.exists() else {}
    entries = build(classes, verdicts, OUT / "wiki_cache", curated)
    stats = write_app_file(entries, OUT / "lookalikes.txt")
    (OUT / "lookalikes_report.json").write_text(
        json.dumps({"stats": stats, "entries": entries}, ensure_ascii=False, indent=1),
        encoding="utf-8",
    )
    print(f"classes={stats['classes']} gefaehrlich={stats['gefaehrlich']} achtung={stats['achtung']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
```

`src/lookalikes_curated.json` (kuratierte Klassiker, Zielarten existieren im eigenen Labelraum — Schlüssel vor dem Commit mit `out/labels_app.txt` abgleichen):

```json
{
  "Agaricus_augustus": [
    {"target": "Amanita_phalloides", "kind": "gefaehrlich", "evidence": "Champignons und Knollenblätterpilze sind jung leicht zu verwechseln; weiße Lamellen und Stielscheide sind Warngrenzen."}
  ],
  "Agaricus_campestris": [
    {"target": "Amanita_phalloides", "kind": "gefaehrlich", "evidence": "Weiße Champignons können mit weißen Knollenblätterpilzen verwechselt werden."}
  ],
  "Agaricus_sylvicola": [
    {"target": "Amanita_phalloides", "kind": "gefaehrlich", "evidence": "Junge Fruchtkörper sind von tödlich giftigen Knollenblätterpilzen kaum zu unterscheiden."}
  ],
  "Agaricus_essettei": [
    {"target": "Amanita_phalloides", "kind": "gefaehrlich", "evidence": "Junge Fruchtkörper ähneln stark tödlich giftigen Knollenblätterpilzen."}
  ]
}
```

- [ ] **Step 4: Test laufen lassen, GREEN bestätigen**

Run: `cd /home/m3kky/projects/Mushroom-Exposed-training && ./venv/bin/python -m unittest tests.test_extract_lookalikes tests.test_export_labels_app -v`
Expected: alle Tests grün.

- [ ] **Step 5: Echte Extraktion laufen lassen und Ergebnis prüfen**

```bash
cd /home/m3kky/projects/Mushroom-Exposed-training
./venv/bin/python src/extract_lookalikes.py
head -20 out/lookalikes.txt
column -s'|' -t out/lookalikes.txt | head -30
```

Prüfen: `gefaehrlich`-Zeilen vorhanden, alle Zielschlüssel existieren in `out/labels_app.txt`, keine Selbstverweise, Belegsatz in jeder Zeile. Zielgröße 20–40 Arten; deutlich mehr ist ein Zeichen für zu lockere Auflösung und muss durch strengere Namenslänge/Satzauswahl nachgezogen werden.

- [ ] **Step 6: Commit**

```bash
git checkout -b feat/lookalikes
git add src/extract_lookalikes.py src/lookalikes_curated.json tests/test_extract_lookalikes.py
git commit -m "feat(train): extract dangerous lookalike pairs"
```

### Task 7: Lookalikes im Parity-Gate stagen

**Files:**
- Modify: `/home/m3kky/projects/Mushroom-Exposed-training/src/verify_and_stage.py`
- Modify: `/home/m3kky/projects/Mushroom-Exposed-training/README.md`

**Interfaces:**
- Consumes `out/lookalikes.txt` aus Task 6.
- Produces gestagte Datei `/home/m3kky/projects/Mushroom-Exposed/app/src/main/assets/lookalikes.txt`.

- [ ] **Step 1: Staging ergänzen**

In `verify_and_stage.py` nach dem Asset-Staging:

```python
    lookalikes = OUT / "lookalikes.txt"
    if lookalikes.exists():
        shutil.copy2(lookalikes, APP / "lookalikes.txt")
        lines = [l for l in lookalikes.read_text(encoding="utf-8").splitlines()
                 if l.strip() and not l.startswith("#")]
        print(f"staged lookalikes.txt ({len(lines)} Arten) -> {APP}")
    else:
        print("no out/lookalikes.txt — app ships without lookalike warnings")
```

- [ ] **Step 2: Lauf und Staging verifizieren**

```bash
cd /home/m3kky/projects/Mushroom-Exposed-training
./venv/bin/python src/verify_and_stage.py
cmp -s out/lookalikes.txt /home/m3kky/projects/Mushroom-Exposed/app/src/main/assets/lookalikes.txt && echo "LOOKALIKES STAGED OK"
git -C /home/m3kky/projects/Mushroom-Exposed status --short app/src/main/assets
```

Expected: `PARITY OK` (Modell unverändert), `LOOKALIKES STAGED OK`, Status zeigt nur `lookalikes.txt` als neu.

- [ ] **Step 3: README ergänzen**

Abschnitt „Verwechslungspaare" im Trainings-README: Quelle (`out/wiki_cache`), Aufruf (`./venv/bin/python src/extract_lookalikes.py`), Ausgabeformat (eine Zeile je Art, Belegsatz am Ende), Regel (nur nicht-essbare Ziele, kuratierte Einträge gewinnen).

- [ ] **Step 4: Commit**

```bash
git add src/verify_and_stage.py README.md
git commit -m "chore(train): stage lookalike data into the app"
```

### Task 8: Feldmodus in der App

**Files:**
- Modify: `app/src/main/java/com/example/mushroomexposed/MainActivity.kt`
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes `HistoryStore`/`HistoryEntry` (Task 1), `LookalikeData`/`Lookalike` (Task 2), `VerdictPolicy.decide(..., lookalike)` (Task 3), `ResultFormatter`/`RankedSpecies`/`ResultView` (Task 4), `FrameQualityAnalyzer`/`FrameQualityPolicy`/`QualityHint` (Task 5).
- Produces Verlaufs-Eintrag pro Analyse über `HistoryStore.append(...)`, Schreibfehler nur als Toast.

- [ ] **Step 1: Layout umbauen**

`activity_main.xml` (ConstraintLayout, Hochformat) mit: `PreviewView` (flächendeckend), `ImageView frameOverlay` (Zielrahmen-Quadrat zentriert, 80 % der kurzen Kante), `TextView hintText` (Live-Hinweis über dem Auslöser, `#CC000000` Hintergrund), `ImageButton shutterButton` (72 dp, unten mittig), `ImageButton torchButton` (oben rechts), `ImageButton historyButton` (oben links), `MaterialCardView resultCard` (über dem Auslöser, initial `visibility="gone"`) mit `resultHeadline`, `resultSubline`, `resultWarning`, `resultTop3`, `resultEmergency`, `ImageButton closeButton` („Neu"). Alle Texte über `@string/...`.

Neue Strings in `strings.xml`:

```xml
<string name="hint_dark">Mehr Licht</string>
<string name="hint_bright">Zu hell — abschatten</string>
<string name="hint_blurry">Näher heran / ruhig halten</string>
<string name="hint_ready">Pilz in den Rahmen, dann auslösen</string>
<string name="frame_caption">Pilz in den Rahmen</string>
<string name="shutter">Auslösen</string>
<string name="torch">Taschenlampe</string>
<string name="history">Verlauf</string>
<string name="new_shot">Neu</string>
<string name="history_title">Verlauf</string>
<string name="history_empty">Noch keine Erkennungen</string>
<string name="history_clear">Verlauf löschen</string>
<string name="history_clear_confirm">Alle Einträge löschen?</string>
<string name="history_unavailable">Verlauf nicht verfügbar</string>
<string name="emergency_text">Bei Verdacht auf Pilzvergiftung: Vergiftungsinformationszentrale 01 406 43 43 (24 h) oder Notruf 144. Restpilz und Erbrochenes aufbewahren.</string>
```

Manifest: `android:screenOrientation="portrait"` und `android:configChanges="orientation|screenSize"` für `MainActivity`, plus `<activity android:name=".HistoryActivity" android:exported="false" android:screenOrientation="portrait" />`.

- [ ] **Step 2: MainActivity implementieren**

Aufbau (Details in dieser Reihenfolge umsetzen):

1. Zustand: `private enum class Mode { LIVE, FROZEN }`, Felder `mode`, `analyzing`, `frozenBitmap: Bitmap?`, `lookalikes: Map<String, List<Lookalike>>`, `historyStore: HistoryStore`.
2. `onCreate`: Labels laden, Modell laden (wie bisher), `namesByKey = scientific -> germanName` bauen, `lookalikes = LookalikeData.load(assets, namesByKey)`, `historyStore = HistoryStore(filesDir)`, `EMERGENCY_TEXT`-String aus Ressourcen in `ResultFormatter.EMERGENCY_TEXT` prüfen (Test aus Task 4 hält den Wortlaut), Kamera starten, `shutterButton.setOnClickListener { onShutter() }`.
3. Analyzer: **immer** gesetzt, aber `analyzeImage` klassifiziert nie. Er macht nur:
   - Bitmap erzeugen (`imageProxyToBitmap`, unverändert),
   - bei `mode == LIVE`: auf 224×224 skalieren, Graustufen-`IntArray` bauen, `FrameQualityAnalyzer.analyze`, `FrameQualityPolicy.hint`, `hintText` und Rahmenfarbe setzen (nur wenn sich der Hinweis ändert, um UI-Flackern zu sparen),
   - bei `mode == FROZEN`: nichts weiter,
   - im `finally` immer `imageProxy.close()`.
4. `onShutter()`: wenn `analyzing` → return. Der Auslöser friert den zuletzt gesehenen Frame ein: Feld `latestBitmap` wird im Analyzer bei `LIVE` aktualisiert (Kopie, damit der ImageProxy-Buffer nicht geteilt wird). Ist `latestBitmap == null` → Toast „Noch kein Bild". Dann `mode = FROZEN`, `analyzing = true`, `previewView.visibility = INVISIBLE` und `frozenView` (ImageView) mit dem Standbild sichtbar, `resultCard` einblenden mit „Analysiere…". Danach `inferenceExecutor.execute { classify(frozen) }`.
5. `classify(bitmap)`: quadratischer Mittelausschnitt über `FrameQualityPolicy`-unabhängiger Hilfsfunktion `centerSquare(bitmap)` (80 % der kurzen Kante), Skalierung auf `inputW/inputH`, Float-Puffer wie bisher, `interpreter.run`, `softmax`, Top-3, `RankedSpecies`-Liste mit `labels.getOrNull(idx)` (Name, scientific, verdict, probability), beste Art, `lookalike = lookalikes[bestKey]?.maxByOrNull { if (it.isDangerous) 1 else 0 }`, `decision = VerdictPolicy.decide(best?.verdict, bestP, lookalike)`, `view = ResultFormatter.format(ranked, decision)`. Dann `runOnUiThread { render(view) }` und `HistoryStore.append(HistoryEntry(Instant/UTC-String, best.scientific, best.name, bestP, best.verdict, lookalike != null))` — Zeitstempel über `java.time.Instant.now().toString()` (minSdk 26 erlaubt `java.time`), Schreibfehler in `try/catch` mit Toast.
6. `render(view)`: Headline mit Tonfarbe (`DANGER` rot, `SAFE` grün, `CAUTION` orange — wie bisherige Farben), Subline, Warnung, Top-3 als `joinToString("\n")`, Notfallblock über `EMERGENCY_TEXT`-Ressource, `analyzing = false`.
7. „Neu": `mode = LIVE`, `frozenBitmap = null`, `resultCard` ausblenden, `previewView` sichtbar, Hint zurücksetzen.
8. Taschenlampe: `cameraProvider.getCameraInfo(DEFAULT_BACK_CAMERA).hasFlashUnit()` nach dem Bind prüfen, Button nur dann sichtbar; Klick toggelt `camera?.cameraControl?.enableTorch(on)`.
9. Tap-to-Focus: `previewView.setOnTouchListener` → `ScaleGestureDetector`-freie Variante: `previewView.meteringPointFactory.createPoint(x, y)` + `camera.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).build())`.
10. Verlauf-Button: `startActivity(Intent(this, HistoryActivity::class.java))`.
11. `latestBitmap` und `frozenBitmap` in `onDestroy` recyclen, falls noch im FROZEN-Modus.

- [ ] **Step 3: HistoryActivity implementieren**

`HistoryActivity`: lädt `HistoryStore(filesDir).readNewestFirst()` in `Dispatchers.IO` (oder einfacher: in einem `Thread` + `runOnUiThread`), zeigt Einträge in einer `ListView`/`RecyclerView`-Liste (`activity_history.xml`, einfache `LinearLayout`-Zeilen reichen: Zeit, deutscher Name, wissenschaftlicher Name, Konfidenz, Ampelpunkt), „Verlauf löschen"-Button mit `AlertDialog`-Bestätigung, danach `clear()` und Liste neu laden. Leerer Zustand zeigt `history_empty`.

- [ ] **Step 4: Bauen und Unit-Tests laufen lassen**

```bash
cd /home/m3kky/projects/Mushroom-Exposed
./gradlew testDebugUnitTest assembleDebug
```

Expected: Build erfolgreich, alle Tests aus Tasks 1–5 grün.

- [ ] **Step 5: Commit**

```bash
git add -A app/src/main
git commit -m "feat(app): add field mode with shutter, camera guidance and result card"
```

### Task 9: Version, Geräte-Smoke-Test und Auslieferung

**Files:**
- Modify: `app/build.gradle.kts`
- Generated: `/home/m3kky/projects/DM_APK_Android/mushroom-exposed-0.6.0-debug-<merge-commit>.apk`

**Interfaces:**
- Consumes den Build aus Task 8.

- [ ] **Step 1: Version hochziehen**

`versionCode = 7`, `versionName = "0.6.0"`, dann:

```bash
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.example.mushroomexposed
adb shell am start -n com.example.mushroomexposed/.MainActivity
sleep 4
adb shell pidof com.example.mushroomexposed
adb shell dumpsys media.camera | grep -i mushroomexposed
adb logcat -d | grep -E "Failed to load model|Inference error|Frame error|FATAL"
```

Expected: Installation `Success`, Prozess-ID vorhanden, Kamera-Client vorhanden, keine Treffer in der Logcat-Suche.

- [ ] **Step 2: Manuelle Prüfung am Gerät**

Der Agent kann diese Punkte nicht selbst durchführen; sie sind als Liste an Markus zu übergeben, sobald die App installiert ist:

- Auslöser → Ergebnis bleibt stehen, Vorschau ist eingefroren.
- „Neu" → zurück zum Sucher.
- Taschenlampe an/aus (Button nur wenn Gerät Blitz hat).
- Tippen in die Vorschau → Fokuspunkt, Bild wird scharf.
- Zu dunkel/unscharf → Hinweiszeile erscheint.
- Verlauf öffnen: Einträge mit Zeit, Art, Konfidenz, Ampel; löschen funktioniert.
- Flugmodus: Erkennung und Verlauf funktionieren weiter.

- [ ] **Step 3: Nachziehen der Qualitätsschwellen**

Aus den Beobachtungen in Step 2 `FrameQualityPolicy`-Konstanten anpassen (nur wenn ein Hinweis nachweislich zu früh oder zu spät kommt), Tests grün halten, `git commit -m "fix(app): calibrate frame quality thresholds"`.

- [ ] **Step 4: Merge und Push**

```bash
git checkout main
git merge --no-ff feat/feldmodus-ux -m "Merge branch 'feat/feldmodus-ux'"
git push origin main
git -C /home/m3kky/projects/Mushroom-Exposed-training checkout main
git merge --no-ff feat/lookalikes -m "Merge branch 'feat/lookalikes'"
git -C /home/m3kky/projects/Mushroom-Exposed-training push origin main
```

- [ ] **Step 5: APK-Handoff**

```bash
cd /home/m3kky/projects/Mushroom-Exposed
./gradlew assembleDebug
MERGE=$(git rev-parse --short HEAD)
cp app/build/outputs/apk/debug/app-debug.apk \
   /home/m3kky/projects/DM_APK_Android/mushroom-exposed-0.6.0-debug-$MERGE.apk
cmp -s app/build/outputs/apk/debug/app-debug.apk \
   /home/m3kky/projects/DM_APK_Android/mushroom-exposed-0.6.0-debug-$MERGE.apk && echo "APK HANDOFF OK"
ls -la /home/m3kky/projects/DM_APK_Android/mushroom-exposed-0.6.0-debug-$MERGE.apk
```

## Plan self-review

- **Spec coverage:** Hybrid-Zustandsmaschine und Auslöser (Task 8), Kameraführung inkl. Taschenlampe/Tap-to-Focus/Zielrahmen (Task 8), Bildqualitätshinweise (Task 5 + 8), Ergebnisanzeige und Notfallblock (Task 4 + 8), JSONL-Verlauf mit Cap und Löschen (Task 1 + 8), Lookalike-Extraktion mit Belegsatz und kuratierter Ergänzung (Task 6), Staging (Task 7), Verzehrlogik-Verschärfung (Task 3), Version/Build/Smoke-Test/Handoff (Task 9).
- **Bekannte Abweichung:** `achtung`-Einträge werden nur für Ziele erzeugt, die nicht `essbar` sind (Global Constraints) — bewusste Verschärfung gegenüber der Spec, um Rauschen zu vermeiden.
- **Placeholder-Scan:** keine TODO/TBD; alle Schwellen, Versionen und Kommandos sind konkret.
- **Typkonsistenz:** `HistoryEntry`, `HistoryStore`, `Lookalike`, `LookalikeKind`, `LookalikeParser.parse`, `VerdictPolicy.decide(verdict, confidence, lookalike)`, `RankedSpecies`, `ResultView`, `ResultFormatter.format`, `FrameQuality`, `QualityHint`, `FrameQualityPolicy.hint`, `FrameQualityAnalyzer.analyze` werden über Tasks hinweg identisch benannt und verwendet.
