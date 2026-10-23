package com.joasasso.paperlink.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeAlphabetTest {

    @Test
    fun alphabet_has32UniqueCharacters() {
        val chars = CodeAlphabet.ALLOWED_CHARACTERS
        assertEquals(32, chars.length)
        assertEquals(chars.length, chars.toSet().size)
    }

    @Test
    fun alphabet_excludesAmbiguousCharacters() {
        for (c in "IO01") {
            assertFalse("'$c' no debería estar en el alfabeto", c in CodeAlphabet.ALLOWED_CHARACTERS)
        }
    }

    @Test
    fun isValid_acceptsWellFormedCodes() {
        assertTrue(CodeAlphabet.isValid("HWQ2"))
        assertTrue(CodeAlphabet.isValid("ABCD"))
        assertTrue(CodeAlphabet.isValid("2345"))
    }

    @Test
    fun isValid_rejectsWrongLength() {
        assertFalse(CodeAlphabet.isValid(""))
        assertFalse(CodeAlphabet.isValid("ABC"))
        assertFalse(CodeAlphabet.isValid("ABCDE"))
    }

    @Test
    fun isValid_rejectsCharactersOutsideAlphabet() {
        assertFalse(CodeAlphabet.isValid("ABCI"))
        assertFalse(CodeAlphabet.isValid("ABCO"))
        assertFalse(CodeAlphabet.isValid("AB01"))
        assertFalse(CodeAlphabet.isValid("AB-D"))
        assertFalse(CodeAlphabet.isValid("AB D"))
    }

    @Test
    fun isValid_rejectsLowercase() {
        assertFalse(CodeAlphabet.isValid("hwq2"))
    }

    @Test
    fun normalize_uppercasesAndTrims() {
        assertEquals("HWQ2", CodeAlphabet.normalize("  hwq2 "))
        assertEquals("HWQ2", CodeAlphabet.normalize("HWQ2"))
    }

    @Test
    fun normalize_thenIsValid_acceptsLowercaseInput() {
        assertTrue(CodeAlphabet.isValid(CodeAlphabet.normalize("hwq2")))
    }
}
