package com.example.mushroomexposed

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class FrozenPreviewLayoutTest {
    @Test
    fun `preview uses texture backed compatible mode so frozen overlay can cover it`() {
        val layout = File("src/main/res/layout/activity_main.xml").readText()
        val previewStart = layout.indexOf("<androidx.camera.view.PreviewView")
        val previewEnd = layout.indexOf("/>", previewStart)
        val previewElement = layout.substring(previewStart, previewEnd)

        assertTrue(
            "PreviewView must use compatible mode; SurfaceView can render above the frozen ImageView",
            "app:implementationMode=\"compatible\"" in previewElement,
        )
    }
}
