package com.joasasso.paperlink.data.files

import com.joasasso.paperlink.data.local.Attachment.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentStoreFunctionsTest {

    @Test
    fun kindForMimeType_mapsKnownFamilies() {
        assertEquals(Kind.IMAGE, kindForMimeType("image/jpeg"))
        assertEquals(Kind.IMAGE, kindForMimeType("image/heic"))
        assertEquals(Kind.VIDEO, kindForMimeType("video/mp4"))
        assertEquals(Kind.AUDIO, kindForMimeType("audio/mpeg"))
        assertEquals(Kind.PDF, kindForMimeType("application/pdf"))
        assertEquals(Kind.IMAGE, kindForMimeType("IMAGE/PNG"))
    }

    @Test
    fun kindForMimeType_fallsBackToFile() {
        assertEquals(Kind.FILE, kindForMimeType(null))
        assertEquals(Kind.FILE, kindForMimeType("application/zip"))
        assertEquals(Kind.FILE, kindForMimeType("text/plain"))
    }

    @Test
    fun sampleSizeFor_keepsSmallImagesAtFullResolution() {
        assertEquals(1, sampleSizeFor(800, 600, 3000))
        assertEquals(1, sampleSizeFor(3000, 2000, 3000))
        assertEquals(1, sampleSizeFor(5999, 4000, 3000))
    }

    @Test
    fun sampleSizeFor_neverDropsBelowMaxSide() {
        assertEquals(2, sampleSizeFor(6000, 4000, 3000))
        assertEquals(2, sampleSizeFor(4000, 8000, 3000))
        assertEquals(4, sampleSizeFor(12000, 9000, 3000))
        for (side in listOf(3001, 4500, 6001, 9000, 12_345, 24_000)) {
            val sample = sampleSizeFor(side, side / 2, 3000)
            assertTrue("side=$side sample=$sample", side / sample >= 3000)
            assertTrue("side=$side sample=$sample could be larger", side / (sample * 2) < 3000)
        }
    }
}
