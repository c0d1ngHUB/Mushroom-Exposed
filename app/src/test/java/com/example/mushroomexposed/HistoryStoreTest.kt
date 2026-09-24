package com.example.mushroomexposed

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `cap eviction removes the matching reference jpeg`() {
        val store = HistoryStore(tmp.root, cap = 2)
        store.append(entry(0), byteArrayOf(0))
        store.append(entry(1), byteArrayOf(1))
        store.append(entry(2), byteArrayOf(2))

        val retained = store.readNewestFirst()
        val images = File(tmp.root, "images")

        assertEquals(2, retained.size)
        assertTrue(retained.all { it.image != null && File(images, it.image!!).isFile })
        assertEquals(2, images.listFiles()?.size)
    }

    @Test
    fun `clear removes history entries and their reference images`() {
        val store = HistoryStore(tmp.root)
        store.append(entry(1), byteArrayOf(1))

        store.clear()

        assertTrue(store.readNewestFirst().isEmpty())
        assertFalse(File(tmp.root, "images").exists())
    }

    @Test
    fun `corrupt lines are skipped and quotes are escaped`() {
        val file = tmp.newFile("history.jsonl")
        file.writeText("{\"ts\":\"broken\"\n\n" + encode(entry(1)).replace("Dünnfleischiger", "Dünn\\\"fleischiger"))

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
