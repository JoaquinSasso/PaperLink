package com.joasasso.paperlink.data.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.joasasso.paperlink.TestDir
import com.joasasso.paperlink.data.local.Attachment.Kind
import com.joasasso.paperlink.sha256
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class AttachmentStoreTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: TestDir
    private lateinit var store: AttachmentStore

    @Before
    fun setUp() {
        dir = TestDir()
        store = AttachmentStore(context, dir.base)
    }

    @After
    fun tearDown() {
        dir.delete()
    }

    private fun stored(result: AttachmentStore.ImportResult): File = File(dir.base, result.relativePath)

    // --- Imágenes ---

    @Test
    fun largeImage_isReencodedToWebpWithinMaxSide() = runBlocking {
        val source = dir.image("big.png", 4000, 2000, Bitmap.CompressFormat.PNG)

        val result = store.import(Uri.fromFile(source))

        assertEquals(Kind.IMAGE, result.kind)
        assertEquals("image/webp", result.mimeType)
        assertTrue(result.relativePath.matches(Regex("media/[0-9a-f-]{36}\\.webp")))
        val bounds = decodeBounds(stored(result))
        assertEquals(AttachmentStore.IMAGE_MAX_SIDE_PX, bounds.outWidth)
        assertEquals(AttachmentStore.IMAGE_MAX_SIDE_PX / 2, bounds.outHeight)
        assertEquals("image/webp", bounds.outMimeType)
    }

    @Test
    fun webpSource_isReencodedWithoutClobberingItself() = runBlocking {
        // El original y el re-codificado no pueden compartir el nombre del .part (bug de la fase 2 con JPEG).
        val source = dir.image("photo.webp", 300, 200, Bitmap.CompressFormat.WEBP_LOSSY)

        val result = store.import(Uri.fromFile(source))

        assertEquals("image/webp", result.mimeType)
        assertEquals(300, decodeBounds(stored(result)).outWidth)
        assertEquals(listOf(stored(result).name), dir.mediaFiles().map { it.name })
    }

    @Test
    fun exifRotation_isAppliedToPixels() = runBlocking {
        val source = dir.image("rotated.jpg", 400, 200, Bitmap.CompressFormat.JPEG)
        ExifInterface(source).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }

        val result = store.import(Uri.fromFile(source))

        // Rotada 90° en sentido horario: queda vertical, con el rojo (izquierda) arriba.
        val bitmap = BitmapFactory.decodeFile(stored(result).path)
        assertEquals(200, bitmap.width)
        assertEquals(400, bitmap.height)
        assertColorNear(Color.RED, bitmap.getPixel(100, 50))
        assertColorNear(Color.BLUE, bitmap.getPixel(100, 350))
        // La imagen resultante ya no lleva rotación pendiente.
        assertEquals(0, ExifInterface(stored(result)).rotationDegrees)
    }

    @Test
    fun largeRotatedImage_isScaledAndRotatedKeepingAspect() = runBlocking {
        // Como una foto de cámara: más grande que el máximo y con rotación EXIF.
        val source = dir.image("camera.jpg", 4000, 2000, Bitmap.CompressFormat.JPEG)
        ExifInterface(source).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }

        val result = store.import(Uri.fromFile(source))

        val bitmap = BitmapFactory.decodeFile(stored(result).path)
        assertEquals(AttachmentStore.IMAGE_MAX_SIDE_PX / 2, bitmap.width)
        assertEquals(AttachmentStore.IMAGE_MAX_SIDE_PX, bitmap.height)
        assertColorNear(Color.RED, bitmap.getPixel(bitmap.width / 2, bitmap.height / 8))
        assertColorNear(Color.BLUE, bitmap.getPixel(bitmap.width / 2, bitmap.height * 7 / 8))
    }

    @Test
    fun transparentImage_isFlattenedOnWhite() = runBlocking {
        val source = dir.image("alpha.png", 200, 100, Bitmap.CompressFormat.PNG, alpha = true)

        val result = store.import(Uri.fromFile(source))

        val bitmap = BitmapFactory.decodeFile(stored(result).path)
        assertColorNear(Color.RED, bitmap.getPixel(50, 50))
        assertColorNear(Color.WHITE, bitmap.getPixel(150, 50))
    }

    @Test
    fun undecodableImage_isStoredAsOriginal() = runBlocking {
        val bytes = Random(3).nextBytes(10_000)
        val uri = dir.source("broken.jpg", bytes)

        val result = store.import(uri)

        assertEquals(Kind.IMAGE, result.kind)
        assertEquals("image/jpeg", result.mimeType)
        assertArrayEquals(bytes, stored(result).readBytes())
        assertEquals(1, dir.mediaFiles().size)
    }

    // --- Otros archivos ---

    @Test
    fun genericFile_isCopiedByteForByteWithHashAndProgress() = runBlocking {
        val bytes = Random(4).nextBytes(300_000)
        val uri = dir.source("document.pdf", bytes)
        var lastProgress = 0L

        val result = store.import(uri) { copied, _ -> lastProgress = copied }

        val file = stored(result)
        assertEquals(Kind.PDF, result.kind)
        assertEquals("application/pdf", result.mimeType)
        assertTrue(result.relativePath.endsWith(".pdf"))
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(bytes.size.toLong(), result.sizeBytes)
        assertEquals(bytes.size.toLong(), lastProgress)
        assertEquals(file.sha256(), result.sha256)
        assertEquals(listOf(file.name), dir.mediaFiles().map { it.name })
    }

    @Test
    fun unknownType_isStoredAsBinFile() = runBlocking {
        val result = store.import(dir.source("data.weird", byteArrayOf(1, 2, 3)))

        assertEquals(Kind.FILE, result.kind)
        assertEquals("application/octet-stream", result.mimeType)
        assertTrue(result.relativePath.endsWith(".bin"))
    }

    // --- Fallas: nunca quedan archivos a medias ---

    @Test
    fun notEnoughSpace_failsBeforeWriting() = runBlocking {
        val lowSpace = AttachmentStore(context, dir.base, usableSpace = { 10L * 1024 * 1024 })
        try {
            lowSpace.import(dir.source("video.mp4", ByteArray(1024)))
            fail("Expected InsufficientSpace")
        } catch (e: ImportException.InsufficientSpace) {
            assertTrue(e.requiredBytes > e.availableBytes)
        }
        assertTrue(dir.mediaFiles().isEmpty())
    }

    @Test
    fun missingSource_failsAsUnreadable() = runBlocking {
        try {
            store.import(dir.missingSource("gone.jpg"))
            fail("Expected SourceUnreadable")
        } catch (_: ImportException.SourceUnreadable) {
            // esperado
        }
        assertTrue(dir.mediaFiles().isEmpty())
    }

    @Test
    fun cancelledImport_leavesNoFiles() = runBlocking {
        val uri = dir.source("large.mp4", ByteArray(20 * 1024 * 1024))

        val job = launch(Dispatchers.IO) {
            store.import(uri) { copied, _ -> if (copied >= 1024 * 1024) cancel() }
        }
        job.join()

        assertTrue(job.isCancelled)
        assertTrue("Quedaron archivos: ${dir.mediaFiles()}", dir.mediaFiles().isEmpty())
    }

    // --- Borrado, rutas y barrido ---

    @Test
    fun delete_removesFileAndIsIdempotent() = runBlocking {
        val result = store.import(dir.source("a.pdf", byteArrayOf(1)))

        assertTrue(store.delete(result.relativePath))
        assertFalse(stored(result).exists())
        assertFalse(store.exists(result.relativePath))
        assertTrue(store.delete(result.relativePath))
    }

    @Test
    fun fileFor_rejectsPathsOutsideMedia() {
        for (path in listOf("../secret", "media/../paperlink.db", "other/x.jpg", "media/sub/x.jpg")) {
            try {
                store.fileFor(path)
                fail("Expected rejection of $path")
            } catch (_: IllegalArgumentException) {
                // esperado
            }
        }
    }

    @Test
    fun sweep_removesOldOrphansAndPartsOnly() = runBlocking {
        val now = System.currentTimeMillis()
        val old = now - AttachmentStore.SWEEP_MIN_AGE_MS - 1
        dir.media.mkdirs()
        fun media(name: String, modified: Long) =
            File(dir.media, name).apply { writeBytes(byteArrayOf(1)); setLastModified(modified) }
        val referenced = media("kept.jpg", old)
        val orphan = media("orphan.jpg", old)
        val oldPart = media("x.jpg.part", old)
        val recentOrphan = media("recent.jpg", now)
        val recentPart = media("y.jpg.part", now)

        val removed = store.sweep(setOf("media/kept.jpg"), now)

        assertEquals(2, removed)
        assertTrue(referenced.exists())
        assertFalse(orphan.exists())
        assertFalse(oldPart.exists())
        assertTrue(recentOrphan.exists())
        assertTrue(recentPart.exists())
    }

    @Test
    fun sweep_keepsRecentCapturesAndRemovesAbandonedOnes() = runBlocking {
        val now = System.currentTimeMillis()
        val recent = store.newCaptureFile().apply { writeBytes(byteArrayOf(1)) }
        val abandoned = store.newCaptureFile().apply {
            writeBytes(byteArrayOf(1))
            setLastModified(now - AttachmentStore.CAPTURE_MAX_AGE_MS - 1)
        }

        store.sweep(emptySet(), now)

        assertTrue(recent.exists())
        assertFalse(abandoned.exists())
        assertEquals(recent, store.captureFile(recent.name))
    }

    // --- Helpers ---

    private fun decodeBounds(file: File) =
        BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(file.path, it) }

    private fun assertColorNear(expected: Int, actual: Int) {
        val channels = listOf<(Int) -> Int>({ Color.red(it) }, { Color.green(it) }, { Color.blue(it) })
        val ok = channels.all { channel -> kotlin.math.abs(channel(expected) - channel(actual)) <= 40 }
        assertTrue("Expected #%08x, got #%08x".format(expected, actual), ok)
    }
}
