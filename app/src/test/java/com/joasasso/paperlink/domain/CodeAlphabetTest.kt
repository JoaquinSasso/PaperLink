package com.joasasso.paperlink.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeAlphabetTest {

    @Test
    fun alphabet_has26UniqueCharacters() {
        val chars = CodeAlphabet.ALLOWED_CHARACTERS
        assertEquals(26, chars.length)
        assertEquals(chars.length, chars.toSet().size)
    }

    @Test
    fun alphabet_excludesHandwritingConfusables() {
        for (c in "IO01ZS2B58") {
            assertFalse("'$c' no debería estar en el alfabeto", c in CodeAlphabet.ALLOWED_CHARACTERS)
        }
    }

    @Test
    fun alphabet_isUppercaseLettersAndDigitsOnly() {
        assertTrue(CodeAlphabet.ALLOWED_CHARACTERS.all { it in 'A'..'Z' || it in '2'..'9' })
    }

    @Test
    fun isValid_acceptsWellFormedCodes() {
        assertTrue(CodeAlphabet.isValid("HWQ3"))
        assertTrue(CodeAlphabet.isValid("ACDE"))
        assertTrue(CodeAlphabet.isValid("3467"))
    }

    @Test
    fun isValid_rejectsWrongLength() {
        assertFalse(CodeAlphabet.isValid(""))
        assertFalse(CodeAlphabet.isValid("ACD"))
        assertFalse(CodeAlphabet.isValid("ACDEF"))
    }

    @Test
    fun isValid_rejectsCharactersOutsideAlphabet() {
        assertFalse(CodeAlphabet.isValid("ACDI"))
        assertFalse(CodeAlphabet.isValid("ACDO"))
        assertFalse(CodeAlphabet.isValid("AC01"))
        assertFalse(CodeAlphabet.isValid("HWQ2"))
        assertFalse(CodeAlphabet.isValid("ZSB8"))
        assertFalse(CodeAlphabet.isValid("AC-D"))
        assertFalse(CodeAlphabet.isValid("AC D"))
    }

    @Test
    fun isValid_rejectsLowercase() {
        assertFalse(CodeAlphabet.isValid("hwq3"))
    }

    @Test
    fun normalize_uppercasesAndTrims() {
        assertEquals("HWQ3", CodeAlphabet.normalize("  hwq3 "))
        assertEquals("HWQ3", CodeAlphabet.normalize("HWQ3"))
    }

    @Test
    fun normalize_thenIsValid_acceptsLowercaseInput() {
        assertTrue(CodeAlphabet.isValid(CodeAlphabet.normalize("hwq3")))
    }
}
