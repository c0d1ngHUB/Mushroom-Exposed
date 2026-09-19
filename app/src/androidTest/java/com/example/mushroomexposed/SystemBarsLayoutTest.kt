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
import org.junit.Assert.assertNotNull
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
