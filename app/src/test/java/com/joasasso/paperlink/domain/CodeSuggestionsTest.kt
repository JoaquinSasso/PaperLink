package com.joasasso.paperlink.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeSuggestionsTest {

    private val existing = listOf("HWQ3", "HWQ4", "HWG3", "ACDE", "UCDE")

    @Test
    fun confusablePairs_areSymmetric() {
        assertTrue(areConfusable('U', 'V'))
        assertTrue(areConfusable('V', 'U'))
        assertTrue(areConfusable('6', 'G'))
        assertFalse(areConfusable('A', 'C'))
    }

    @Test
    fun confusableSubstitution_ranksFirst() {
        // 9↔Q es confundible (HWQ3); 9→G no lo es (HWG3).
        assertEquals(listOf("HWQ3", "HWG3"), suggestCodes("HW93", existing))
        // V↔U es confundible (UCDE); V→A no lo es (ACDE).
        assertEquals(listOf("UCDE", "ACDE"), suggestCodes("VCDE", existing))
    }

    @Test
    fun sameRank_isAlphabetical() {
        assertEquals(listOf("HWQ3", "HWQ4"), suggestCodes("HWQ7", existing))
    }

    @Test
    fun invalidCharacters_actAsWildcards() {
        // La O no está en el alfabeto: esa posición acepta cualquier carácter.
        assertEquals(listOf("HWG3", "HWQ3"), suggestCodes("HWO3", existing))
        // También en minúsculas y con 0.
        assertEquals(listOf("HWG3", "HWQ3"), suggestCodes("hw03", existing))
    }

    @Test
    fun wildcardPlusMismatch_isTooFar() {
        assertEquals(emptyList<String>(), suggestCodes("HXO3", existing))
    }

    @Test
    fun noSuggestions_forWrongLengthOrDistantCodes() {
        assertEquals(emptyList<String>(), suggestCodes("HWQ", existing))
        assertEquals(emptyList<String>(), suggestCodes("PPPP", existing))
    }

    @Test
    fun typedCode_isNotSuggestedToItself() {
        assertFalse("HWQ3" in suggestCodes("HWQ3", existing))
    }

    @Test
    fun results_areLimited() {
        val many = CodeAlphabet.ALLOWED_CHARACTERS.map { "ACD$it" }
        assertEquals(5, suggestCodes("ACDP", many).size)
    }
}
