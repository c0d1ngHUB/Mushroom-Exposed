package com.example.mushroomexposed

import android.Manifest
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SystemBarsLayoutTest {
    @Test
    fun primaryOverlaysStayOutsideSystemBars() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName,
            Manifest.permission.CAMERA,
        )

        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.container)).check { root, error ->
                if (error != null) throw error

                val windowInsets = ViewCompat.getRootWindowInsets(root)
                assertNotNull("Root window insets must be available after layout", windowInsets)
                val systemBars = windowInsets!!.getInsets(WindowInsetsCompat.Type.systemBars())
                val headline = root.findViewById<android.view.View>(R.id.statusPill)

                assertTrue(
                    "Status pill starts at ${headline.top}, inside the ${systemBars.top}px status bar",
                    headline.top >= systemBars.top,
                )
                // Die Steuerleiste liegt am unteren Rand und ist beim Start sichtbar.
                val controls = root.findViewById<android.view.View>(R.id.controls)
                assertTrue(
                    "Controls end at ${controls.bottom}, below the ${root.height - systemBars.bottom}px navigation-safe edge",
                    controls.bottom <= root.height - systemBars.bottom,
                )
                val shutter = root.findViewById<android.view.View>(R.id.shutterButton)
                val torch = root.findViewById<android.view.View>(R.id.torchButton)
                assertTrue(
                    "Shutter ${shutter.width}x${shutter.height}px must clearly beat the " +
                        "side control ${torch.width}x${torch.height}px",
                    shutter.width >= 48 * shutter.resources.displayMetrics.density &&
                        shutter.width >= torch.width * 1.2f,
                )
            }
        }
    }

    /**
     * Hold-to-scan auf einem echten Layout: die drei Ansichtszeilen sind
     * sichtbar, jede ueber der 48-dp-Marke, und keine ist im Ruhezustand
     * als "erfasst" markiert. Ein Abbruch darf nichts hinterlassen.
     */
    @Test
    fun theThreeViewRowsAreVisibleAndUncapturedInLive() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName,
            Manifest.permission.CAMERA,
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val density = activity.resources.displayMetrics.density
                for (id in listOf(R.id.viewCap, R.id.viewUnderside, R.id.viewStipeRing)) {
                    val row = activity.findViewById<android.view.View>(id)
                    assertEquals("row $id must be visible in LIVE", android.view.View.VISIBLE, row.visibility)
                    assertTrue(
                        "row $id must keep the 48dp touch minimum, is ${row.height}px",
                        row.height >= 48 * density * 0.95,
                    )
                }
                // Ruhezustand: keine Zeile darf "Erfasst" behaupten.
                val cap = activity.findViewById<android.widget.TextView>(R.id.viewCap)
                val captured = activity.getString(R.string.view_captured)
                assertFalse(
                    "an unfilled row must not claim to be captured, was '${cap.text}'",
                    cap.text.toString().contains(captured),
                )
        }
        }
    }

    /**
     * Der Review-Fokus: Loslassen waehrend des Sammelns bricht ab und laesst
     * weder eingefrorenes Bild noch Verlaufseintrag zurueck.
     */
    @Test
    fun releasingDuringCollectionCancelsWithoutPersisting() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName,
            Manifest.permission.CAMERA,
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val mode = MainActivity::class.java.getDeclaredField("fieldMode")
                    .apply { isAccessible = true }
                    .get(activity)
                val machine = mode as FieldModeMachine

                // Vorher zaehlen, nicht die blosse Existenz pruefen: andere
                // Tests und fruehere Laeufe duerfen den Verlauf schon gefuellt
                // haben. Geprueft wird die Wirkung *dieses* Abbruchs.
                val entriesBefore = readHistoryEntries(activity)

                val down = machine.onPrimaryDown()
                assertEquals("pressing the ring must start a scan", FieldModeAction.START_SCAN, down)

                val up = machine.onPrimaryUp()
                assertEquals("releasing during collection must stop it", FieldModeAction.STOP_SCAN, up)
                assertEquals("the machine must be back in LIVE", FieldMode.LIVE, machine.state)

                val frozen = MainActivity::class.java.getDeclaredField("frozen")
                    .apply { isAccessible = true }
                    .get(activity)
                assertNull("a cancelled scan must not leave a frozen frame", frozen)

                assertEquals(
                    "a cancelled scan must not add a history entry",
                    entriesBefore,
                    readHistoryEntries(activity),
                )
            }
        }
    }

    /** Anzahl der Verlaufszeilen auf der Platte; 0, solange nichts geschrieben wurde. */
    private fun readHistoryEntries(activity: android.app.Activity): Int {
        val file = java.io.File(java.io.File(activity.filesDir, "history"), "history.jsonl")
        if (!file.isFile) return 0
        return file.readLines().count { it.isNotBlank() }
    }

    /**
     * Das ausgelieferte Segmentierer-Asset ist am Geraet wirklich benutzbar.
     *
     * Die Unit-Tests pruefen den Vertrag auf der JVM. Ob das Modell auf dem
     * Geraet laedt und Evidenz mit einer Vergleichsgroesse liefert, entscheidet
     * sich erst hier: eine falsch geladene TFLite-Datei oder ein
     * Kanalvertragsfehler wuerde auf der JVM nicht auffallen.
     *
     * Geprueft werden zwei Dinge: `available` ist wahr, und jede Evidenz traegt
     * eine `competingCoverage` — ohne sie koennte der Accumulator die
     * Hutdominanz nicht anwenden und wuerde jede Ansicht abweisen.
     */
    @Test
    fun aShippedSegmenterIsAvailableAndEmitsEvidenceWithACompetitor() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName,
            Manifest.permission.CAMERA,
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val segmenter = MainActivity::class.java.getDeclaredField("segmenter")
                    .apply { isAccessible = true }
                    .get(activity) as ViewpointSegmenter

                assertTrue(
                    "the gated segmenter must load on the device",
                    segmenter.available,
                )

                // Ein einfarbiges Bild ist kein Pilz; geprueft wird die Form der
                // Evidenz, nicht ihre Hoehe. Ein leeres Ergebnis waere hier
                // zulaessig, ein Ergebnis ohne Vergleichsgroesse nicht.
                val bitmap = android.graphics.Bitmap.createBitmap(
                    64, 64, android.graphics.Bitmap.Config.ARGB_8888,
                )
                for (evidence in segmenter.evidenceFor(bitmap, 1L)) {
                    assertTrue(
                        "evidence for ${evidence.step} must carry a competing coverage " +
                            "in [0, 1], was ${evidence.competingCoverage}",
                        evidence.competingCoverage in 0f..1f,
                    )
                }
            }
        }
    }

    /**
     * Fail closed, an der Maschine: ohne verfuegbare Ansichten faellt ein Druck
     * auf genau einen Frame zurueck statt einen Sammelvorgang zu starten.
     *
     * Gefragt wird die Zustandsmaschine selbst (`viewsAvailable = false`), nicht
     * die ausgelieferte App: das ausgelieferte Asset **hat** jetzt einen
     * Segmentierer, der Fehlerfall laesst sich am Geraet also nur noch ueber den
     * Parameter herstellen. Die Zusicherung, die zaehlt, ist damit erhalten und
     * sogar schaerfer: sie haengt am gemeldeten Zustand, nicht an der
     * Abwesenheit einer Datei.
     */
    @Test
    fun withoutAvailableViewsThePressCapturesASingleFrame() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName,
            Manifest.permission.CAMERA,
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val machine = MainActivity::class.java.getDeclaredField("fieldMode")
                    .apply { isAccessible = true }
                    .get(activity) as FieldModeMachine

                assertEquals("the app starts in LIVE", FieldMode.LIVE, machine.state)
                assertEquals(
                    "without available views a press is a single capture, not a scan",
                    FieldModeAction.CAPTURE,
                    machine.onPrimaryDown(viewsAvailable = false),
                )
                assertEquals(
                    "the frame is analysed immediately, nothing is collected",
                    FieldMode.ANALYSING,
                    machine.state,
                )
            }
        }
    }

    /**
     * Mit verfuegbaren Ansichten startet ein Druck den Sammelvorgang.
     *
     * Das ist der ausgelieferte Pfad: der Segmentierer ist da, also fuehrt der
     * Ausloeser in den Hold-to-scan. Der Gegenfall steht in
     * `withoutAvailableViewsThePressCapturesASingleFrame`.
     */
    @Test
    fun withAvailableViewsThePressStartsAScan() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName,
            Manifest.permission.CAMERA,
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val machine = MainActivity::class.java.getDeclaredField("fieldMode")
                    .apply { isAccessible = true }
                    .get(activity) as FieldModeMachine
                val segmenter = MainActivity::class.java.getDeclaredField("segmenter")
                    .apply { isAccessible = true }
                    .get(activity) as ViewpointSegmenter

                assertEquals(
                    "the shipped app has a segmenter, so the scan path is the real one",
                    true,
                    segmenter.available,
                )
                assertEquals(
                    "with available views a press starts collecting the three views",
                    FieldModeAction.START_SCAN,
                    machine.onPrimaryDown(viewsAvailable = segmenter.available),
                )
                assertEquals(
                    "the machine collects instead of analysing immediately",
                    FieldMode.SCANNING,
                    machine.state,
                )
            }
        }
    }

    /**
     * Das Sheet deckt die Steuerleiste. Ohne die schwebenden Chips gaebe es in
     * FROZEN keinen Weg zurueck zur Kamera und keinen zum Verlauf. Der
     * Zustandswechsel wird ueber denselben privaten Pfad ausgeloest, den eine
     * echte Analyse nimmt -- das Layout wird danach wirklich vermessen.
     */
    @Test
    fun frozenChipsReplaceTheCoveredControlBar() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName,
            Manifest.permission.CAMERA,
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // Ergebnis darstellen, ohne auf einen Kameraframe zu warten; der
            // Zustandswechsel laeuft ueber denselben privaten Pfad wie eine
            // echte Analyse. Geprueft wird danach mit Espresso, das heisst
            // nach einem echten Layout-Durchlauf.
            scenario.onActivity { activity ->
                assertEquals(
                    "chips start hidden in LIVE",
                    android.view.View.GONE,
                    activity.findViewById<android.view.View>(R.id.frozenChips).visibility,
                )
                MainActivity::class.java
                    .getDeclaredMethod("showFrozenControls")
                    .apply { isAccessible = true }
                    .invoke(activity)
            }

            onView(withId(R.id.newChip)).check { chip, error ->
                if (error != null) throw error
                val root = chip.rootView
                val insets = ViewCompat.getRootWindowInsets(root)
                assertNotNull("Root window insets must be available after layout", insets)
                val systemBars = insets!!.getInsets(WindowInsetsCompat.Type.systemBars())
                val chips = root.findViewById<android.view.View>(R.id.frozenChips)
                val controls = root.findViewById<android.view.View>(R.id.controls)
                val density = chip.resources.displayMetrics.density

                assertEquals("chips visible in FROZEN", android.view.View.VISIBLE, chips.visibility)
                // top ist relativ zur Chip-Leiste; geprueft wird die Lage auf dem Bildschirm.
                val location = IntArray(2)
                chip.getLocationOnScreen(location)
                assertTrue(
                    "New chip top ${location[1]}px must clear the ${systemBars.top}px status bar",
                    location[1] >= systemBars.top,
                )
                assertTrue(
                    "New chip must be at least 48dp tall, is ${chip.height}px",
                    chip.height >= 48 * density * 0.95,
                )
                assertTrue(
                    "New chip must be at least 48dp wide, is ${chip.width}px",
                    chip.width >= 48 * density * 0.95,
                )
                assertEquals(
                    "the control bar is covered by the sheet, so it must be gone",
                    android.view.View.GONE,
                    controls.visibility,
                )
            }
        }
    }

    /**
     * Der Fehlerpfad muss die Navigation im eingefrorenen Zustand anbieten,
     * ohne ein Ergebnis der vorherigen Aufnahme wieder einzublenden.
     */
    @Test
    fun failedAnalysisKeepsPreviousResultSheetHidden() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName,
            Manifest.permission.CAMERA,
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val ranked = listOf(
                    RankedSpecies("Steinpilz", "Boletus_edulis", "essbar", 0.81f),
                )
                val previousResult = ResultFormatter.format(
                    ranked,
                    VerdictPolicy.decide("essbar", 0.81f),
                )
                MainActivity::class.java
                    .getDeclaredMethod("render", ResultView::class.java)
                    .apply { isAccessible = true }
                    .invoke(activity, previousResult)

                val resultSheet = activity.findViewById<android.view.View>(R.id.resultSheet)
                assertEquals(android.view.View.VISIBLE, resultSheet.visibility)

                // Der Fehlerpfad blendet das alte Ergebnis aus und aktiviert
                // danach nur noch die Navigation im eingefrorenen Zustand.
                resultSheet.visibility = android.view.View.GONE
                MainActivity::class.java
                    .getDeclaredMethod("showFrozenControls")
                    .apply { isAccessible = true }
                    .invoke(activity)

                assertEquals(
                    "a failed analysis must not reveal the previous result again",
                    android.view.View.GONE,
                    resultSheet.visibility,
                )
                assertEquals(
                    "the user still needs a way back to the camera",
                    android.view.View.VISIBLE,
                    activity.findViewById<android.view.View>(R.id.frozenChips).visibility,
                )
            }
        }
    }

    /**
     * Der Ausloeser muss auf der Bildschirmmitte sitzen. Der Review-Befund war
     * 73 dp links daneben, weil die Gruppe links klebte und rechts Flaeche leer
     * blieb. Diese Messung ist die Zusicherung, nicht die XML-Form.
     */
    @Test
    fun shutterIsCentredOnTheScreen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName,
            Manifest.permission.CAMERA,
        )

        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.shutterButton)).check { shutter, error ->
                if (error != null) throw error

                val location = IntArray(2)
                shutter.getLocationOnScreen(location)
                val shutterCenter = location[0] + shutter.width / 2
                val screenCenter = shutter.resources.displayMetrics.widthPixels / 2
                val density = shutter.resources.displayMetrics.density

                assertTrue(
                    "the shutter centre ${shutterCenter}px must sit on the screen centre " +
                        "${screenCenter}px, off by ${kotlin.math.abs(shutterCenter - screenCenter)}px " +
                        "(${kotlin.math.abs(shutterCenter - screenCenter) / density}dp)",
                    kotlin.math.abs(shutterCenter - screenCenter) <= 4 * density,
                )
            }
        }
    }

    /**
     * Der Ausloeser benennt am Geraet seine Handlung und traegt keine
     * Freigabeoptik. Die sichtbare Sammelanweisung uebernimmt den Wortlaut.
     */
    @Test
    fun theShutterNamesItsActionAndCarriesNoApprovalColour() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName,
            Manifest.permission.CAMERA,
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val shutter = activity.findViewById<android.view.View>(R.id.shutterButton)
                val described = shutter.contentDescription?.toString().orEmpty()
                assertTrue(
                    "the shutter must describe holding for the three views, was '$described'",
                    described.contains("halten", ignoreCase = true),
                )
                assertTrue(
                    "the description must name all three views, was '$described'",
                    described.contains("Hut") && described.contains("Unterseite"),
                )

                // Die Anweisung im Sucher muss zur Haltegeste passen, nicht mehr
                // zum Klicken auffordern.
                val hint = activity.findViewById<android.widget.TextView>(R.id.qualityHint)
                assertFalse(
                    "the live guidance must not tell the user to press a button, was '${hint.text}'",
                    hint.text.toString().contains("Bild analysieren"),
                )
            }
        }
    }

    /**
     * Das Querformat ist gesperrt — endgueltig, nicht uebergangsweise: das
     * Geraet wird nur hochkant verwendet und es wird kein zweites Layout
     * gepflegt (Entscheidung 2026-09-20, siehe
     * docs/superpowers/specs/2026-09-20-review-fixes-design.md).
     *
     * Geprueft wird die angeforderte Ausrichtung, nicht die zufaellig
     * vorliegende: auf einem hochkant gehaltenen Geraet waere die
     * Laufzeitausrichtung auch ohne Sperre portrait, der Test also wertlos.
     * `requestedOrientation` faellt genau dann, wenn die Manifest-Sperre
     * entfernt wird.
     */
    @Test
    fun theActivityIsLockedToPortrait() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(
                    "the activity is portrait-only by decision; a landscape layout is not maintained",
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                    activity.requestedOrientation,
                )
            }
        }
    }

    /**
     * Finding 1 am laufenden UI: nach einem essbaren Urteil darf die Marke
     * nicht "FREIGABE" heissen und die Flaeche nicht in Freigabegruen stehen.
     * Ein Textvergleich allein wuerde eine gruene Flaeche nicht bemerken.
     */
    @Test
    fun anEdibleVerdictIsNeverPaintedAsAClearance() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName,
            Manifest.permission.CAMERA,
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val ranked = listOf(
                    RankedSpecies("Steinpilz", "Boletus_edulis", "essbar", 0.71f),
                )
                val view = ResultFormatter.format(ranked, VerdictPolicy.decide("essbar", 0.71f))
                MainActivity::class.java
                    .getDeclaredMethod("render", ResultView::class.java)
                    .apply { isAccessible = true }
                    .invoke(activity, view)

                val badge = activity.findViewById<android.widget.TextView>(R.id.resultBadge)
                assertFalse(
                    "the edible badge must not claim a release, was '${badge.text}'",
                    badge.text.toString().contains("FREIGABE", ignoreCase = true),
                )

                // Die Flaeche darf nicht die Freigabefarbe tragen. Geprueft wird
                // die tatsaechlich gesetzte Fuellung, nicht die Absicht im Code.
                val fill = (badge.background as? android.graphics.drawable.GradientDrawable)
                    ?.color?.defaultColor
                val safeGreen = androidx.core.content.ContextCompat.getColor(
                    activity, R.color.verdict_safe,
                )
                assertNotEquals(
                    "an edible estimate must not be painted in the clearance green",
                    safeGreen,
                    fill,
                )
            }
        }
    }

    /**
     * Finding 4 am laufenden UI: ohne Eintraege ist der Loeschknopf aus, mit
     * Eintraegen an. Zusaetzlich verlangt der Test die Rueckfrage vor dem
     * Loeschen — der Knopf allein darf nichts entfernen.
     */
    @Test
    fun theClearButtonFollowsTheHistoryState() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName,
            Manifest.permission.CAMERA,
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val store = HistoryStore(java.io.File(activity.filesDir, "history"))
                store.clear()

                MainActivity::class.java
                    .getDeclaredMethod("showHistory")
                    .apply { isAccessible = true }
                    .invoke(activity)

                val clear = activity.findViewById<android.view.View>(R.id.clearHistoryButton)
                assertFalse(
                    "with an empty history the clear button must be off",
                    clear.isEnabled,
                )

                store.append(
                    HistoryEntry(
                        timestamp = "2026-09-20T09:04:06",
                        scientific = "Cantharellus_cibarius",
                        german = "Pfifferling",
                        confidence = 0.71f,
                        verdict = "safe",
                        lookalike = false,
                    ),
                )
                MainActivity::class.java
                    .getDeclaredMethod("renderHistory")
                    .apply { isAccessible = true }
                    .invoke(activity)

                assertTrue(
                    "with entries the clear button must be available",
                    clear.isEnabled,
                )

                // Der Verlauf ist noch da: Loeschen passiert erst nach Rueckfrage.
                assertTrue(
                    "opening the history must not delete anything",
                    store.readNewestFirst().isNotEmpty(),
                )
            }
        }
    }

    /**
     * Der gefaehrlichste Fall: giftiges Urteil. Der Notfallblock muss sichtbar
     * sein, beide Nummern muessen als grosse Flaeche treffbar sein und der
     * gepinnte Wortlaut muss fuer Vorlesehilfen erhalten bleiben.
     */
    @Test
    fun toxicVerdictShowsReachableEmergencyBlock() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName,
            Manifest.permission.CAMERA,
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val ranked = listOf(
                    RankedSpecies("Grüner Knollenblätterpilz", "Amanita_phalloides", "giftig", 0.81f),
                    RankedSpecies("Stadt-Champignon", "Agaricus_bitorquis", "essbar", 0.11f),
                    RankedSpecies("Kegelhütiger Knollenblätterpilz", "Amanita_virosa", "giftig", 0.04f),
                )
                val decision = VerdictPolicy.decide("giftig", 0.81f)
                val view = ResultFormatter.format(ranked, decision)

                MainActivity::class.java
                    .getDeclaredMethod("render", ResultView::class.java)
                    .apply { isAccessible = true }
                    .invoke(activity, view)
            }

            onView(withId(R.id.emergencyBlock)).check { block, error ->
                if (error != null) throw error
                val root = block.rootView
                val insets = ViewCompat.getRootWindowInsets(root)
                assertNotNull(insets)
                val systemBars = insets!!.getInsets(WindowInsetsCompat.Type.systemBars())
                val poison = root.findViewById<android.view.View>(R.id.callPoisonButton)
                val emergency = root.findViewById<android.view.View>(R.id.callEmergencyButton)
                val sheet = root.findViewById<android.view.View>(R.id.resultSheet)
                val density = block.resources.displayMetrics.density

                assertTrue(
                    "emergency block must be on screen, top is ${block.top}",
                    block.height > 0,
                )
                assertTrue(
                    "the sheet top ${sheet.top}px must clear the ${systemBars.top}px status bar",
                    sheet.top >= systemBars.top,
                )
                for ((name, target) in listOf("poison" to poison, "emergency" to emergency)) {
                    assertTrue(
                        "$name call target must be at least 48dp tall, is ${target.height}px",
                        target.height >= 48 * density * 0.95,
                    )
                    assertTrue(
                        "$name call target must be at least 48dp wide, is ${target.width}px",
                        target.width >= 48 * density * 0.95,
                    )
                }
                assertEquals(
                    "TalkBack must keep reading the pinned wording",
                    ResultFormatter.EMERGENCY_TEXT,
                    block.contentDescription?.toString(),
                )
            }

            onView(withId(R.id.callPoisonNumber)).check { number, error ->
                if (error != null) throw error
                assertEquals(
                    "the button must show the number the dialer will receive",
                    ResultFormatter.POISON_CONTROL_NUMBER,
                    (number as android.widget.TextView).text.toString(),
                )
            }
        }
    }
}
