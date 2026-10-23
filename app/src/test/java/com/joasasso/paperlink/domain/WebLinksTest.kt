package com.joasasso.paperlink.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebLinksTest {

    @Test
    fun extractUrls_findsLinksInsideText() {
        assertEquals(
            listOf("https://youtu.be/dQw4w9WgXcQ"),
            extractUrls("Mirá este video: https://youtu.be/dQw4w9WgXcQ"),
        )
    }

    @Test
    fun extractUrls_dropsTrailingPunctuationAndDuplicates() {
        assertEquals(
            listOf("https://a.com/x", "http://b.org/y?z=1"),
            extractUrls("Ver https://a.com/x, también (http://b.org/y?z=1). Repito: https://a.com/x."),
        )
    }

    @Test
    fun extractUrls_returnsEmptyForPlainText() {
        assertEquals(emptyList<String>(), extractUrls("Solo una nota sin links"))
        assertEquals(emptyList<String>(), extractUrls("https://"))
    }

    @Test
    fun normalizeUrl_addsSchemeWhenMissing() {
        assertEquals("https://example.com", normalizeUrl("example.com"))
        assertEquals("https://www.youtube.com/watch?v=1", normalizeUrl("  www.youtube.com/watch?v=1 "))
        assertEquals("http://example.com", normalizeUrl("http://example.com"))
    }

    @Test
    fun normalizeUrl_rejectsNonLinks() {
        assertNull(normalizeUrl(""))
        assertNull(normalizeUrl("hola mundo"))
        assertNull(normalizeUrl("palabra"))
        assertNull(normalizeUrl("ftp://example.com"))
        assertNull(normalizeUrl("https://.com"))
    }
}
