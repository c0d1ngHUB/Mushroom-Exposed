package com.example.mushroomexposed

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReferenceImageStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `write creates a jpeg image and discard removes exactly that image`() {
        val images = ReferenceImageStore(File(tmp.root, "images"))

        val name = images.write(byteArrayOf(1, 2, 3))
        val file = File(File(tmp.root, "images"), name)

        assertTrue(name.endsWith(".jpg"))
        assertTrue(file.isFile)
        assertTrue(file.readBytes().contentEquals(byteArrayOf(1, 2, 3)))
        images.discard(name)
        assertFalse(file.exists())
    }

    @Test
    fun `clear removes every locally retained image`() {
        val directory = File(tmp.root, "images")
        val images = ReferenceImageStore(directory)
        images.write(byteArrayOf(1))
        images.write(byteArrayOf(2))

        images.clear()

        assertFalse(directory.exists())
    }

    @Test
    fun `legacy history line without image remains decodable`() {
        val decoded = decode("{\"ts\":\"2026-09-24T10:00:00\",\"sci\":\"Boletus_edulis\",\"de\":\"Steinpilz\",\"conf\":0.8,\"verdict\":\"caution\",\"lookalike\":false}")

        assertNotNull(decoded)
        assertTrue(decoded?.image == null)
    }
}
