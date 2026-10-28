package com.joasasso.paperlink.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class LabelSuggestionsTest {

    @Test
    fun emptyQuery_suggestsMostUsedThenMostRecent() {
        val labels = listOf("Química", "Física", "Historia", "Física", "Historia", "Física")
        assertEquals(listOf("Física", "Historia", "Química"), suggestLabels(labels, ""))
    }

    @Test
    fun variantsInAccentsOrCaseCountAsOne_showingMostRecentSpelling() {
        val labels = listOf("Física", "fisica", "FISICA ", "Química")
        assertEquals(listOf("Física", "Química"), suggestLabels(labels, "  "))
    }

    @Test
    fun query_filtersIgnoringAccents_andPrefersPrefixMatches() {
        val labels = listOf("Bioquímica", "Química", "Química", "Física")
        // "Bioquímica" es más reciente, pero "Química" empieza con lo escrito.
        assertEquals(listOf("Química", "Bioquímica"), suggestLabels(labels, "quim"))
    }

    @Test
    fun exactMatchIsNotSuggested() {
        val labels = listOf("Física", "Física II")
        assertEquals(listOf("Física II"), suggestLabels(labels, "fisica"))
    }

    @Test
    fun blankLabelsAreIgnored_andLimitIsApplied() {
        val labels = listOf(" ", "A1", "A2", "A3")
        assertEquals(listOf("A1", "A2"), suggestLabels(labels, "a", limit = 2))
    }

    @Test
    fun noMatches_returnsEmpty() {
        assertEquals(emptyList<String>(), suggestLabels(listOf("Física"), "historia"))
        assertEquals(emptyList<String>(), suggestLabels(emptyList(), ""))
    }
}
