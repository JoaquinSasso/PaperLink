package com.joasasso.paperlink.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTextTest {

    @Test
    fun normalize_removesAccentsCaseAndExtraSpaces() {
        assertEquals("fisica ii", normalizeForSearch("  Física   II "))
        assertEquals("nandu", normalizeForSearch("Ñandú"))
    }

    @Test
    fun labelMatches_ignoresAccentsAndCase() {
        assertTrue(labelMatches("Física II", "fisica"))
        assertTrue(labelMatches("Analisis", "ANÁLISIS"))
        assertTrue(labelMatches("50% off", "0%"))
    }

    @Test
    fun labelMatches_falseForMissingLabelOrBlankQuery() {
        assertFalse(labelMatches(null, "x"))
        assertFalse(labelMatches("Física", "   "))
        assertFalse(labelMatches("Física", "química"))
    }
}
