package com.example.mushroomexposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerdictPolicyTest {
    @Test
    fun `poisonous result remains danger even at low confidence`() {
        val decision = VerdictPolicy.decide("giftig", confidence = 0.01f)

        assertEquals("GIFTIG !!", decision.headline)
        assertEquals(VerdictTone.DANGER, decision.tone)
        assertEquals("Nicht verzehren. Im Zweifel Pilzberatung fragen.", decision.warning)
    }

    @Test
    fun `edible result below threshold never grants eating approval`() {
        val decision = VerdictPolicy.decide("essbar", confidence = 0.59f)

        assertEquals("unsicher — nicht essen", decision.headline)
        assertEquals(VerdictTone.CAUTION, decision.tone)
        assertEquals("Zu unsicher für eine Freigabe — Pilzberatung fragen.", decision.warning)
    }

    @Test
    fun `edible result at threshold is cautiously marked edible`() {
        val decision = VerdictPolicy.decide("essbar", confidence = 0.60f)

        assertEquals("essbar", decision.headline)
        assertEquals(VerdictTone.SAFE, decision.tone)
        assertEquals("Nur bei sicherer Bestimmung essen — nie auf App verlassen.", decision.warning)
    }

    @Test
    fun `toxic alternative in the shortlist drops an edible release to caution`() {
        // Messbefund 19.09.2026: das Modell gibt giftige Arten als essbar frei,
        // mit 0,41 bis 1,00 Konfidenz. Die Konfidenz allein erkennt das nicht --
        // aber die giftige Art steht in den Top-3. Der Kopf bleibt "essbar",
        // der Ton warnt.
        val decision = VerdictPolicy.decide(
            "essbar",
            confidence = 0.95f,
            toxicAlternative = "Grüner Knollenblätterpilz",
        )

        assertEquals("essbar", decision.headline)
        assertEquals(VerdictTone.CAUTION, decision.tone)
        assertTrue(decision.warning.startsWith("Nur bei sicherer Bestimmung essen"))
        assertTrue(decision.warning.contains("Grüner Knollenblätterpilz"))
    }

    @Test
    fun `toxic alternative never softens a poisonous verdict`() {
        val decision = VerdictPolicy.decide(
            "giftig",
            confidence = 0.99f,
            toxicAlternative = "Wiesen-Champignon",
        )

        assertEquals("GIFTIG !!", decision.headline)
        assertEquals(VerdictTone.DANGER, decision.tone)
    }

    @Test
    fun `toxic alternative never softens an unknown verdict`() {
        val decision = VerdictPolicy.decide(
            "unbekannt",
            confidence = 0.98f,
            toxicAlternative = "Grüner Knollenblätterpilz",
        )

        assertEquals("nicht bewertet", decision.headline)
        assertEquals(VerdictTone.CAUTION, decision.tone)
    }

    @Test
    fun `weak genus hint drops an edible release to caution`() {
        // Messbefund 19.09.2026: die Top-3 der schlimmsten Fehlfreigaben
        // enthaelt Knollenblaetterpilze -- aber essbare (Amanita_ceciliae,
        // Amanita_excelsa). Das Verdict der Treffer verraet das nicht, die
        // Gattung schon.
        val decision = VerdictPolicy.decide(
            "essbar",
            confidence = 0.95f,
            toxicGenus = "Amanita",
        )

        assertEquals("essbar", decision.headline)
        assertEquals(VerdictTone.CAUTION, decision.tone)
        assertTrue(decision.warning.contains("Amanita"))
    }

    @Test
    fun `genus hint never softens a poisonous verdict`() {
        val decision = VerdictPolicy.decide("giftig", confidence = 0.99f, toxicGenus = "Amanita")

        assertEquals("GIFTIG !!", decision.headline)
        assertEquals(VerdictTone.DANGER, decision.tone)
    }

    @Test
    fun `genus hint never softens an unknown verdict`() {
        val decision = VerdictPolicy.decide("unbekannt", confidence = 0.98f, toxicGenus = "Clitocybe")

        assertEquals("nicht bewertet", decision.headline)
        assertEquals(VerdictTone.CAUTION, decision.tone)
    }

    @Test
    fun `below the release threshold the genus hint adds nothing`() {
        val decision = VerdictPolicy.decide("essbar", confidence = 0.42f, toxicGenus = "Amanita")

        assertEquals("unsicher — nicht essen", decision.headline)
        assertEquals("Zu unsicher für eine Freigabe — Pilzberatung fragen.", decision.warning)
    }

    @Test
    fun `species and genus hint are both stated on the same card`() {
        val decision = VerdictPolicy.decide(
            "essbar",
            confidence = 0.91f,
            toxicAlternative = "Grüner Knollenblätterpilz",
            toxicGenus = "Amanita",
        )

        assertEquals(VerdictTone.CAUTION, decision.tone)
        assertTrue(decision.warning.contains("Grüner Knollenblätterpilz"))
        assertTrue(decision.warning.contains("Amanita"))
    }

    @Test
    fun `toxic alternative below the release threshold changes nothing extra`() {
        // Unterhalb der Schwelle ist der Kopf schon "unsicher" -- die giftige
        // Alternative darf keinen zweiten, widersprechenden Satz anhaengen.
        val decision = VerdictPolicy.decide(
            "essbar",
            confidence = 0.42f,
            toxicAlternative = "Grüner Knollenblätterpilz",
        )

        assertEquals("unsicher — nicht essen", decision.headline)
        assertEquals(VerdictTone.CAUTION, decision.tone)
        assertEquals("Zu unsicher für eine Freigabe — Pilzberatung fragen.", decision.warning)
    }

    @Test
    fun `unknown result remains caution at high confidence`() {
        val decision = VerdictPolicy.decide("unbekannt", confidence = 0.98f)

        assertEquals("nicht bewertet", decision.headline)
        assertEquals(VerdictTone.CAUTION, decision.tone)
        assertEquals("Verzehr-Einschätzung unbekannt — Pilzberatung fragen.", decision.warning)
    }

    @Test
    fun `dangerous lookalike never yields a green edible verdict`() {
        val lookalike = Lookalike(
            kind = LookalikeKind.GEFAEHRLICH,
            key = "Amanita_phalloides",
            name = "Grüner Knollenblätterpilz",
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
            key = "Agaricus_xanthodermus",
            name = "Karbol-Champignon",
            evidence = "",
        )

        val decision = VerdictPolicy.decide("essbar", confidence = 0.80f, lookalike = lookalike)

        assertEquals(VerdictTone.SAFE, decision.tone)
        assertTrue(decision.warning.contains("Karbol-Champignon"))
    }

    @Test
    fun `dangerous lookalike of a poisonous find keeps the danger tone`() {
        val lookalike = Lookalike(
            kind = LookalikeKind.GEFAEHRLICH,
            key = "Amanita_phalloides",
            name = "Grüner Knollenblätterpilz",
            evidence = "tödlich giftig",
        )

        val decision = VerdictPolicy.decide("giftig", confidence = 0.99f, lookalike = lookalike)

        assertEquals("GIFTIG !!", decision.headline)
        assertEquals(VerdictTone.DANGER, decision.tone)
    }

    @Test
    fun `missing lookalike keeps the previous behaviour`() {
        val decision = VerdictPolicy.decide("essbar", confidence = 0.80f, lookalike = null)

        assertEquals(VerdictTone.SAFE, decision.tone)
        assertEquals("Nur bei sicherer Bestimmung essen — nie auf App verlassen.", decision.warning)
    }
}
