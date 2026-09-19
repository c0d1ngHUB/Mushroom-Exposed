package com.example.mushroomexposed

import android.Manifest
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
                val headline = root.findViewById<android.view.View>(R.id.resultHeadline)
                val controls = root.findViewById<android.view.View>(R.id.controls)

                assertTrue(
                    "Result headline starts at ${headline.top}, inside the ${systemBars.top}px status bar",
                    headline.top >= systemBars.top,
                )
                assertTrue(
                    "Controls end at ${controls.bottom}, below the ${root.height - systemBars.bottom}px navigation-safe edge",
                    controls.bottom <= root.height - systemBars.bottom,
                )
            }
        }
    }
}
