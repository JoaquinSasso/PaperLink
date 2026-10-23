package com.joasasso.paperlink.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.joasasso.paperlink.TestDir
import com.joasasso.paperlink.data.files.AttachmentStore
import com.joasasso.paperlink.data.files.ImportException
import com.joasasso.paperlink.data.local.Attachment.Kind
import com.joasasso.paperlink.data.local.PaperLinkDatabase
import com.joasasso.paperlink.domain.CodeAlphabet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class LinkRepositoryTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: TestDir
    private lateinit var db: PaperLinkDatabase
    private lateinit var store: AttachmentStore
    private lateinit var scope: CoroutineScope
    private var now = 1_000_000L

    @Before
    fun setUp() {
        dir = TestDir()
        db = Room.inMemoryDatabaseBuilder(context, PaperLinkDatabase::class.java).build()
        store = AttachmentStore(context, dir.base)
        scope = CoroutineScope(Job())
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        dir.delete()
    }

    private fun repository(random: Random = Random(0)) =
        LinkRepository(db.linkDao(), store, scope, clock = { now }, random = random)

    private fun file(name: String, size: Int = 1_000) =
        AttachmentSource.Content(dir.source(name, Random(name.hashCode()).nextBytes(size)))

    // --- Crear ---

    @Test
    fun createLink_storesAllAttachmentsInOrder() = runBlocking {
        val repo = repository()

        val code = repo.createLink(
            listOf(file("a.pdf"), AttachmentSource.WebUrl("  https://youtu.be/x  "), AttachmentSource.Note("hola")),
            label = "  Física 1 ",
        )

        assertTrue(CodeAlphabet.isValid(code))
        val link = repo.getLink(code)!!
        assertEquals("Física 1", link.link.label)
        assertEquals(now, link.link.createdAt)
        assertEquals(listOf(Kind.PDF, Kind.URL, Kind.NOTE), link.attachments.map { it.kind })
        assertEquals(listOf(0, 1, 2), link.attachments.map { it.position })
        assertEquals("https://youtu.be/x", link.attachments[1].url)
        assertEquals("hola", link.attachments[2].text)
        val pdf = link.attachments[0]
        assertTrue(File(dir.base, pdf.relativePath!!).isFile)
        assertNotNull(pdf.sha256)
    }

    @Test
    fun createLink_lookupIsCaseInsensitive() = runBlocking {
        val repo = repository()
        val code = repo.createLink(listOf(AttachmentSource.Note("x")))

        assertNotNull(repo.getLink(code.lowercase()))
    }

    @Test
    fun createLink_blankLabelBecomesNull() = runBlocking {
        val repo = repository()
        val code = repo.createLink(listOf(AttachmentSource.Note("x")), label = "   ")

        assertNull(repo.getLink(code)!!.link.label)
    }

    @Test
    fun createLink_retriesWithAnotherCodeOnCollision() = runBlocking {
        // Dos repositorios con la misma semilla proponen el mismo primer código.
        val first = repository(Random(7)).createLink(listOf(AttachmentSource.Note("a")))
        val second = repository(Random(7)).createLink(listOf(AttachmentSource.Note("b")))

        assertNotEquals(first, second)
        assertEquals("a", repository().getLink(first)!!.attachments.single().text)
        assertEquals("b", repository().getLink(second)!!.attachments.single().text)
    }

    @Test
    fun createLink_failedImportLeavesNothingBehind() = runBlocking {
        val repo = repository()
        try {
            repo.createLink(listOf(file("ok.pdf"), AttachmentSource.Content(dir.missingSource("gone.jpg"))))
            fail("Expected SourceUnreadable")
        } catch (_: ImportException.SourceUnreadable) {
            // esperado
        }
        assertEquals(0, repo.observeCount().first())
        assertTrue(dir.mediaFiles().isEmpty())
    }

    @Test
    fun createLink_rejectsBlankInputsBeforeCopying() = runBlocking {
        val repo = repository()
        try {
            repo.createLink(listOf(file("ok.pdf"), AttachmentSource.WebUrl(" ")))
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // esperado
        }
        assertTrue(dir.mediaFiles().isEmpty())
    }

    @Test
    fun storedFiles_areLinkedWithoutCopyingAgainAndSurviveFailures() = runBlocking {
        val repo = repository()
        val stored = store.import(dir.source("shared.pdf", byteArrayOf(1, 2, 3)))

        // Falla (código inexistente): el archivo ya copiado no se borra, para reintentar.
        try {
            repo.addAttachments("ACDE", listOf(AttachmentSource.Stored(stored)))
            fail("Expected LinkNotFoundException")
        } catch (_: LinkNotFoundException) {
            // esperado
        }
        assertEquals(1, dir.mediaFiles().size)

        val code = repo.createLink(listOf(AttachmentSource.Stored(stored)))
        assertEquals(stored.relativePath, repo.getLink(code)!!.attachments.single().relativePath)
        assertEquals(1, dir.mediaFiles().size)
    }

    // --- Agregar y etiquetar ---

    @Test
    fun addAttachments_appendsAfterExistingOnes() = runBlocking {
        val repo = repository()
        val code = repo.createLink(listOf(AttachmentSource.Note("1")))
        now += 500

        repo.addAttachments(code, listOf(AttachmentSource.Note("2"), file("b.pdf")))

        val link = repo.getLink(code)!!
        assertEquals(listOf("1", "2", null), link.attachments.map { it.text })
        assertEquals(listOf(0, 1, 2), link.attachments.map { it.position })
        assertEquals(now, link.link.updatedAt)
    }

    @Test
    fun addAttachments_toMissingCodeFailsAndCleansFiles() = runBlocking {
        val repo = repository()
        try {
            repo.addAttachments("ACDE", listOf(file("c.pdf")))
            fail("Expected LinkNotFoundException")
        } catch (_: LinkNotFoundException) {
            // esperado
        }
        assertTrue(dir.mediaFiles().isEmpty())
    }

    @Test
    fun setLabel_updatesAndFailsForMissingCode() = runBlocking {
        val repo = repository()
        val code = repo.createLink(listOf(AttachmentSource.Note("x")))

        repo.setLabel(code, "Álgebra")
        assertEquals("Álgebra", repo.getLink(code)!!.link.label)

        try {
            repo.setLabel("ACDE", "x")
            fail("Expected LinkNotFoundException")
        } catch (_: LinkNotFoundException) {
            // esperado
        }
    }

    // --- Búsqueda y recientes ---

    @Test
    fun searchByLabel_matchesSubstringsAndEscapesWildcards() = runBlocking {
        val repo = repository()
        val discount = repo.createLink(listOf(AttachmentSource.Note("x")), label = "50% off")
        repo.createLink(listOf(AttachmentSource.Note("x")), label = "500 off")
        val physics = repo.createLink(listOf(AttachmentSource.Note("x")), label = "Física II")

        assertEquals(listOf(discount), repo.searchByLabel("0%").first().map { it.link.code })
        assertEquals(listOf(physics), repo.searchByLabel("física").first().map { it.link.code })
    }

    @Test
    fun observeRecent_ordersByLastUpdate() = runBlocking {
        val repo = repository()
        val older = repo.createLink(listOf(AttachmentSource.Note("1")))
        now += 1
        val newer = repo.createLink(listOf(AttachmentSource.Note("2")))
        assertEquals(listOf(newer, older), repo.observeRecent().first().map { it.link.code })

        now += 1
        repo.addAttachments(older, listOf(AttachmentSource.Note("3")))
        assertEquals(listOf(older, newer), repo.observeRecent().first().map { it.link.code })
    }

    // --- Borrado ---

    @Test
    fun delete_hidesLinkAndUndoRestoresIt() = runBlocking {
        val repo = repository()
        val code = repo.createLink(listOf(file("d.pdf")))

        repo.delete(code)
        assertNull(repo.getLink(code))
        assertEquals(0, repo.observeCount().first())
        assertEquals(1, dir.mediaFiles().size)

        assertTrue(repo.undoDelete(code))
        assertNotNull(repo.getLink(code))
    }

    @Test
    fun purgeExpired_removesRowsAndFilesOnlyAfterUndoWindow() = runBlocking {
        val repo = repository()
        val code = repo.createLink(listOf(file("e.pdf")))
        repo.delete(code)

        now += LinkRepository.UNDO_WINDOW_MS - 1
        repo.purgeExpired()
        assertEquals(1, dir.mediaFiles().size)

        now += 1
        repo.purgeExpired()
        assertTrue(dir.mediaFiles().isEmpty())
        assertTrue(db.linkDao().allFilePaths().isEmpty())
        assertFalse(repo.undoDelete(code))
    }

    @Test
    fun deleteAttachment_hidesItAndUndoRestoresIt() = runBlocking {
        val repo = repository()
        val code = repo.createLink(listOf(file("a.pdf"), AttachmentSource.Note("n")))
        val pdf = repo.getLink(code)!!.attachments.first()

        assertTrue(repo.deleteAttachment(pdf.id))
        assertEquals(listOf(Kind.NOTE), repo.getLink(code)!!.attachments.map { it.kind })
        assertFalse(repo.deleteAttachment(pdf.id))

        assertTrue(repo.undoDeleteAttachment(pdf.id))
        assertEquals(listOf(Kind.PDF, Kind.NOTE), repo.getLink(code)!!.attachments.map { it.kind })
    }

    @Test
    fun purgeExpired_removesDeletedAttachmentAndItsFile() = runBlocking {
        val repo = repository()
        val code = repo.createLink(listOf(file("a.pdf"), file("b.pdf")))
        val first = repo.getLink(code)!!.attachments.first()
        repo.deleteAttachment(first.id)

        now += LinkRepository.UNDO_WINDOW_MS
        repo.purgeExpired()

        assertEquals(1, dir.mediaFiles().size)
        assertFalse(File(dir.base, first.relativePath!!).exists())
        assertFalse(repo.undoDeleteAttachment(first.id))
        assertEquals(1, repo.getLink(code)!!.attachments.size)
    }

    @Test
    fun linkContent_flagsFilesMissingOnDisk() = runBlocking {
        val repo = repository()
        val code = repo.createLink(listOf(file("a.pdf"), AttachmentSource.Note("n")))
        val pdf = repo.getLink(code)!!.attachments.first()
        File(dir.base, pdf.relativePath!!).delete()

        val content = repo.observeLinkContent(code).first()!!

        assertEquals(setOf(pdf.id), content.unavailableIds)
        assertEquals(2, content.link.attachments.size)
    }

    @Test
    fun searchByLabel_ignoresAccents() = runBlocking {
        val repo = repository()
        val physics = repo.createLink(listOf(AttachmentSource.Note("x")), label = "Física II")
        repo.createLink(listOf(AttachmentSource.Note("x")), label = "Química")

        assertEquals(listOf(physics), repo.searchByLabel("fisica").first().map { it.link.code })
        assertEquals(listOf(physics), repo.searchByLabel("  FÍSICA ").first().map { it.link.code })
    }

    @Test
    fun allCodes_excludesDeletedCodes() = runBlocking {
        val repo = repository()
        val kept = repo.createLink(listOf(AttachmentSource.Note("a")))
        val deleted = repo.createLink(listOf(AttachmentSource.Note("b")))
        repo.delete(deleted)

        assertEquals(listOf(kept), repo.allCodes())
    }

    @Test
    fun delete_missingCodeFails() = runBlocking {
        try {
            repository().delete("ACDE")
            fail("Expected LinkNotFoundException")
        } catch (_: LinkNotFoundException) {
            // esperado
        }
    }

    @Test
    fun runMaintenance_purgesAndSweepsOrphans() = runBlocking {
        val repo = repository()
        val kept = repo.createLink(listOf(file("kept.pdf")))
        val deleted = repo.createLink(listOf(file("deleted.pdf")))
        repo.delete(deleted)
        val keptPath = repo.getLink(kept)!!.attachments.single().relativePath!!
        val orphan = File(dir.media, "orphan.jpg").apply {
            writeBytes(byteArrayOf(1))
            setLastModified(System.currentTimeMillis() - AttachmentStore.SWEEP_MIN_AGE_MS - 1)
        }
        // El barrido compara con el reloj del repositorio.
        now = System.currentTimeMillis() + LinkRepository.UNDO_WINDOW_MS

        repo.runMaintenance()

        assertEquals(listOf(File(dir.base, keptPath).name), dir.mediaFiles().map { it.name })
        assertFalse(orphan.exists())
    }
}
