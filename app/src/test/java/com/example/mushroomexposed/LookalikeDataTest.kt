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
