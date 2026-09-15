package com.example.mushroomexposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LookalikeDataTest {
    private val names = mapOf(
        "Amanita_phalloides" to "Grüner Knollenblätterpilz",
        "Rubroboletus_satanas" to "Satansröhrling",
        "Agaricus_campestris" to "Wiesen-Champignon",
    )

    private val file = """
        # automatisch erzeugt — Zeilen mit # sind Kommentare
        Agaricus_campestris|gefaehrlich:Amanita_phalloides:Grüner Knollenblätterpilz:junge Champignons bleiben weiss|achtung:Rubroboletus_satanas::Junger Röhrling mit Netz
        Boletus_edulis|achtung:Amanita_phalloides:Grüner Knollenblätterpilz
    """.trimIndent()

    @Test
    fun `parses one entry per species with kinds, targets and evidence`() {
        val byKey = LookalikeData.parse(file.lines().asSequence(), names)

        val entries = byKey["Agaricus_campestris"]!!
        assertEquals(2, entries.size)
        assertEquals(LookalikeKind.GEFAEHRLICH, entries[0].kind)
        assertEquals("Amanita_phalloides", entries[0].key)
        assertEquals("Grüner Knollenblätterpilz", entries[0].name)
        assertEquals("junge Champignons bleiben weiss", entries[0].evidence)
        assertEquals(LookalikeKind.ACHTUNG, entries[1].kind)
        assertEquals("Junger Röhrling mit Netz", entries[1].evidence)
    }

    @Test
    fun `comment lines and empty lines are ignored`() {
        val byKey = LookalikeData.parse(
            listOf("# nur ein Kommentar", "", "Boletus_edulis|achtung:Amanita_phalloides").asSequence(),
            names,
        )

        assertEquals(1, byKey.size)
        assertEquals(LookalikeKind.ACHTUNG, byKey["Boletus_edulis"]!![0].kind)
        assertEquals("Grüner Knollenblätterpilz", byKey["Boletus_edulis"]!![0].name)
        assertEquals("", byKey["Boletus_edulis"]!![0].evidence)
    }

    @Test
    fun `pairs are deduplicated per target keeping the more dangerous kind`() {
        val byKey = LookalikeData.parse(
            listOf("Agaricus_campestris|achtung:Amanita_phalloides|gefaehrlich:Amanita_phalloides").asSequence(),
            names,
        )

        assertEquals(1, byKey["Agaricus_campestris"]!!.size)
        assertEquals(LookalikeKind.GEFAEHRLICH, byKey["Agaricus_campestris"]!![0].kind)
    }

    @Test
    fun `escaped pipe stays inside the evidence text`() {
        val byKey = LookalikeData.parse(
            listOf("Boletus_edulis|gefaehrlich:Amanita_phalloides::Lamellen \\| Stiel weiss").asSequence(),
            names,
        )

        assertEquals("Lamellen | Stiel weiss", byKey["Boletus_edulis"]!![0].evidence)
    }

    @Test
    fun `unknown target key falls back to a readable name and unknown kinds are ignored`() {
        val byKey = LookalikeData.parse(
            listOf("Boletus_edulis|harmlos:Boletus_luridus|achtung:Amanita_phalloides").asSequence(),
            names,
        )

        assertEquals(1, byKey["Boletus_edulis"]!!.size)
        assertEquals("Amanita_phalloides", byKey["Boletus_edulis"]!![0].key)
    }

    @Test
    fun `explicit name in the data wins over the label list`() {
        val byKey = LookalikeData.parse(
            listOf("Boletus_edulis|achtung:Amanita_phalloides:Knolli").asSequence(),
            names,
        )

        assertEquals("Knolli", byKey["Boletus_edulis"]!![0].name)
    }

    @Test
    fun `malformed lines are skipped without throwing`() {
        val byKey = LookalikeData.parse(
            listOf("nurQuatsch", "|", "Boletus_edulis|").asSequence(),
            names,
        )

        assertNull(byKey["Boletus_edulis"])
        assertEquals(0, byKey.size)
    }
}
