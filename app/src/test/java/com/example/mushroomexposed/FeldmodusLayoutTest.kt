package com.example.mushroomexposed

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Der Ausloeser war das Hauptproblem im Feld: drei gleich grosse
 * Material-Buttons, der mittlere zu klein fuer Handschuhe. Diese Tests halten
 * die Layout-Zusicherungen fest, die den Umbau tragen.
 */
class FeldmodusLayoutTest {

    private val layout = File("src/main/res/layout/activity_main.xml").readText()
    private val dimens = File("src/main/res/values/dimens.xml").readText()

    private fun blockOf(id: String, padBefore: Int = 0, padAfter: Int = 600): String {
        val at = layout.indexOf("android:id=\"@+id/$id\"")
        if (at < 0) return ""
        return layout.substring(maxOf(0, at - padBefore), minOf(layout.length, at + padAfter))
    }

    @Test
    fun `preview uses texture backed compatible mode so frozen overlay can cover it`() {
        val previewStart = layout.indexOf("<androidx.camera.view.PreviewView")
        val previewEnd = layout.indexOf("/>", previewStart)
        val previewElement = layout.substring(previewStart, previewEnd)

        assertTrue(
            "PreviewView must use compatible mode; SurfaceView can render above the frozen ImageView",
            "app:implementationMode=\"compatible\"" in previewElement,
        )
    }

    @Test
    fun `the shutter is the largest control and sits between the two side controls`() {
        val shutterIndex = layout.indexOf("android:id=\"@+id/shutterButton\"")
        val torchIndex = layout.indexOf("android:id=\"@+id/torchButton\"")
        val historyIndex = layout.indexOf("android:id=\"@+id/historyButton\"")

        assertTrue("all three controls must exist", shutterIndex > 0 && torchIndex > 0 && historyIndex > 0)
        assertTrue(
            "the shutter must sit between torch and history so the thumb finds it mid-bottom",
            torchIndex < shutterIndex && shutterIndex < historyIndex,
        )
        assertTrue(
            "the shutter must be bigger than the side controls",
            "<dimen name=\"shutter_size\">88dp</dimen>" in dimens &&
                "<dimen name=\"control_size\">54dp</dimen>" in dimens,
        )
        assertTrue(
            "the shutter needs its own ringed drawable, not a Material button",
            "android:background=\"@drawable/bg_shutter\"" in blockOf("shutterButton"),
        )
    }

    @Test
    fun `the shutter stays a plain clickable view without a text label inside`() {
        // Text in einem 88dp-Kreis war im ersten Mockup nicht lesbar; die
        // Beschriftung sitzt jetzt nicht mehr im Knopf.
        val block = layout.substring(
            layout.indexOf("android:id=\"@+id/shutterButton\""),
            layout.indexOf("/>", layout.indexOf("android:id=\"@+id/shutterButton\"")),
        )
        assertTrue("the shutter must be clickable", "android:clickable=\"true\"" in block)
        assertTrue("the shutter must carry a content description", "android:contentDescription=" in block)
        assertTrue("no text inside the shutter", "android:text=" !in block)
    }

    @Test
    fun `every touch target keeps at least the 48dp minimum`() {
        assertTrue("<dimen name=\"min_touch_target\">48dp</dimen>" in dimens)
        assertTrue(
            "the side controls must not fall below the touch minimum",
            "<dimen name=\"control_size\">54dp</dimen>" in dimens,
        )
    }

    @Test
    fun `the result sheet owns the bottom edge and the emergency block ends there`() {
        val sheetIndex = layout.indexOf("android:id=\"@+id/resultSheet\"")
        val emergencyIndex = layout.indexOf("android:id=\"@+id/emergencyBlock\"")

        assertTrue("the sheet must exist", sheetIndex > 0)
        assertTrue("the emergency block must live inside the sheet", emergencyIndex > sheetIndex)
        assertTrue(
            "the sheet must be pinned to the bottom of the screen",
            "app:layout_constraintBottom_toBottomOf=\"parent\"" in
                layout.substring(sheetIndex - 300, sheetIndex + 600),
        )
        assertTrue(
            "the emergency block must come after the top-3 list",
            layout.indexOf("android:id=\"@+id/resultTops\"") < emergencyIndex,
        )
    }

    @Test
    fun `the frozen chips replace the covered control bar`() {
        val chipsIndex = layout.indexOf("android:id=\"@+id/frozenChips\"")
        assertTrue("frozen chips must exist", chipsIndex > 0)
        assertTrue(
            "the new chip must be present so the state machine can be driven in FROZEN",
            "android:id=\"@+id/newChip\"" in layout,
        )
        assertTrue(
            "the history chip must be present as well",
            "android:id=\"@+id/historyChip\"" in layout,
        )
    }

    @Test
    fun `the status pill anchors the overlays below the system bars`() {
        val pillIndex = layout.indexOf("android:id=\"@+id/statusPill\"")
        assertTrue("status pill must exist", pillIndex > 0)
        val block = layout.substring(pillIndex - 200, pillIndex + 600)
        assertTrue(
            "the pill must sit under the status bar inset",
            "app:layout_constraintTop_toTopOf=\"parent\"" in block,
        )
        assertTrue("the root must respect the system insets", "android:fitsSystemWindows=\"true\"" in layout)
    }
}
