package com.joasasso.paperlink.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.joasasso.paperlink.TestDir
import com.joasasso.paperlink.data.files.AttachmentStore
import com.joasasso.paperlink.data.local.PaperLinkDatabase
import com.joasasso.paperlink.data.preferences.UserPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random

/** Cargas de varios elementos: código tras el primero, resto en segundo plano y en orden. */
@RunWith(AndroidJUnit4::class)
class BatchImporterTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: TestDir
    private lateinit var db: PaperLinkDatabase
    private lateinit var store: AttachmentStore
    private lateinit var repository: LinkRepository
    private lateinit var preferences: UserPreferencesRepository
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        dir = TestDir()
        db = Room.inMemoryDatabaseBuilder(context, PaperLinkDatabase::class.java).build()
        scope = CoroutineScope(SupervisorJob())
        store = AttachmentStore(context, dir.base)
        repository = LinkRepository(db.linkDao(), store, scope)
        preferences = UserPreferencesRepository(
            PreferenceDataStoreFactory.create(scope = scope) { File(dir.root, "prefs.preferences_pb") }
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        dir.delete()
    }

    private fun importer(parallelism: Int = 3) = BatchImporter(repository, store, preferences, scope, parallelism)

    private fun bytes(i: Int, size: Int = 50_000) = Random(i).nextBytes(size)

    private fun file(i: Int, size: Int = 50_000) = AttachmentSource.Content(dir.source("doc$i.pdf", bytes(i, size)))

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Espera a que la carga termine (sin estado = terminó sin fallas; o terminó con fallas). */
    private suspend fun BatchImporter.awaitFinished(code: String) =
        withTimeout(30_000) { status(code).first { it == null || !it.running } }

    @Test
    fun newCode_isReturnedAfterFirstItem_andTheRestArriveInOrder() = runBlocking {
        val importer = importer()

        val code = importer.start((0 until 8).map { file(it) })

        assertNotNull("El código existe apenas se devuelve", repository.getLink(code))
        assertNull(importer.awaitFinished(code))
        val stored = repository.getLink(code)!!.attachments
        assertEquals((0 until 8).map { sha(bytes(it)) }, stored.map { it.sha256 })
        assertEquals((0 until 8).toList(), stored.map { it.position })
    }

    @Test
    fun failedItem_isSkippedAndReported_withoutBreakingOrder() = runBlocking {
        val importer = importer()

        val code = importer.start(listOf(file(0), AttachmentSource.Content(dir.missingSource("gone.pdf")), file(2)))

        val status = importer.awaitFinished(code)!!
        assertEquals(1, status.failed)
        assertEquals(2, status.saved)
        assertEquals(listOf(sha(bytes(0)), sha(bytes(2))), repository.getLink(code)!!.attachments.map { it.sha256 })

        importer.acknowledge(code)
        assertNull(importer.statuses.value[code])
    }

    @Test
    fun firstItemFailing_letsTheNextOneCreateTheCode() = runBlocking {
        val importer = importer()

        val code = importer.start(listOf(AttachmentSource.Content(dir.missingSource("gone.pdf")), file(1), file(2)))

        importer.awaitFinished(code)
        assertEquals(listOf(sha(bytes(1)), sha(bytes(2))), repository.getLink(code)!!.attachments.map { it.sha256 })
    }

    @Test
    fun nothingImportable_leavesTheCodeEmpty_andReportsEveryFailure() = runBlocking {
        val importer = importer()

        val code = importer.start(listOf(AttachmentSource.Content(dir.missingSource("a.pdf")), AttachmentSource.Content(dir.missingSource("b.pdf"))))

        // El código ya estaba anotado: queda, vacío, y se informa que no se guardó nada.
        val status = importer.awaitFinished(code)!!
        assertEquals(0, status.saved)
        assertEquals(2, status.failed)
        assertEquals(listOf(code), repository.allCodes())
        assertTrue(repository.getLink(code)!!.attachments.isEmpty())
        assertTrue(dir.mediaFiles().isEmpty())
    }

    @Test
    fun newCode_isReturnedBeforeItsItemsArrive() = runBlocking {
        val importer = importer(parallelism = 1)

        val code = importer.start((0 until 4).map { file(it, size = 6_000_000) })

        // El código existe enseguida, aunque sus elementos sigan llegando.
        val link = repository.getLink(code)
        assertNotNull(link)
        assertTrue("Todavía no llegaron todos", link!!.attachments.size < 4)
        importer.awaitFinished(code)
        assertEquals(4, repository.getLink(code)!!.attachments.size)
    }

    @Test
    fun appendMode_returnsAtOnce_andAddsAtTheEnd() = runBlocking {
        val code = repository.createLink(listOf(AttachmentSource.Note("primero")))
        val importer = importer()

        assertEquals(code, importer.start(listOf(file(1), file(2)), appendTo = code))

        importer.awaitFinished(code)
        val attachments = repository.getLink(code)!!.attachments
        assertEquals(listOf(null, sha(bytes(1)), sha(bytes(2))), attachments.map { it.sha256 })
    }

    @Test
    fun cancel_keepsWhatWasSaved_andLeavesNoOrphanFiles() = runBlocking {
        // De a uno y archivos grandes, para cancelar a mitad.
        val importer = importer(parallelism = 1)
        val code = importer.start((0 until 8).map { file(it, size = 6_000_000) })

        // Mientras corre, la carga queda anotada (para avisar si el proceso muere).
        withTimeout(10_000) { preferences.pendingImports.first { list -> list.any { it.code == code } } }
        importer.cancel(code)
        importer.awaitFinished(code)

        val saved = repository.getLink(code)!!.attachments
        assertTrue("Se cancela antes de terminar", saved.size < 8)
        // Toda fila tiene su archivo.
        saved.forEach { assertTrue(File(dir.base, it.relativePath!!).isFile) }
        // Si la cancelación llegó justo cuando un archivo ya se había escrito, queda sin fila: lo borra
        // el barrido de huérfanos del próximo inicio (simulado con el reloj adelantado).
        store.sweep(saved.mapNotNull { it.relativePath }.toSet(), System.currentTimeMillis() + 2 * AttachmentStore.SWEEP_MIN_AGE_MS)
        assertEquals(saved.map { File(it.relativePath!!).name }.sorted(), dir.mediaFiles().map { it.name }.sorted())
        assertEquals(emptyList<Any>(), preferences.pendingImports.first())
    }
}
