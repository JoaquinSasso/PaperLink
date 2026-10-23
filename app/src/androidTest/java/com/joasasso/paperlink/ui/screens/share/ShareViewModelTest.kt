package com.joasasso.paperlink.ui.screens.share

import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.joasasso.paperlink.TestDir
import com.joasasso.paperlink.data.files.AttachmentStore
import com.joasasso.paperlink.data.local.Attachment.Kind
import com.joasasso.paperlink.data.local.PaperLinkDatabase
import com.joasasso.paperlink.data.repository.AttachmentSource
import com.joasasso.paperlink.data.repository.LinkRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/** Garantía 10: compartir sin URIs vencidas; si la copia falla, no se crea nada. */
@RunWith(AndroidJUnit4::class)
class ShareViewModelTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: TestDir
    private lateinit var db: PaperLinkDatabase
    private lateinit var store: AttachmentStore
    private lateinit var repository: LinkRepository
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        dir = TestDir()
        db = Room.inMemoryDatabaseBuilder(context, PaperLinkDatabase::class.java).build()
        store = AttachmentStore(context, dir.base)
        scope = CoroutineScope(SupervisorJob())
        repository = LinkRepository(db.linkDao(), store, scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        dir.delete()
    }

    private fun file(name: String) = dir.source(name, Random(name.hashCode()).nextBytes(2_000))

    private fun viewModel(uris: List<android.net.Uri>, text: String?, handle: SavedStateHandle = SavedStateHandle()) =
        ShareViewModel(handle, uris, text, store, repository)

    private suspend fun ShareViewModel.awaitPhase(predicate: (ShareViewModel.Phase) -> Boolean) =
        withTimeout(15_000) { state.first { predicate(it.phase) } }

    @Test
    fun filesAreCopiedBeforeChoosingDestination() = runBlocking {
        val vm = viewModel(listOf(file("a.pdf"), file("b.pdf")), text = null)

        vm.awaitPhase { it == ShareViewModel.Phase.Ready }

        // Ya copiados, aunque todavía no hay código.
        assertEquals(2, dir.mediaFiles().size)
        assertEquals(0, repository.observeCount().first())
    }

    @Test
    fun newCode_containsFilesAndSharedLink() = runBlocking {
        val vm = viewModel(listOf(file("a.pdf")), text = "Mirá https://youtu.be/abc")
        vm.awaitPhase { it == ShareViewModel.Phase.Ready }

        vm.createNew()
        val state = withTimeout(15_000) { vm.state.first { it.savedCode != null } }

        val link = repository.getLink(state.savedCode!!)!!
        assertEquals(listOf(Kind.PDF, Kind.URL), link.attachments.map { it.kind })
        assertEquals("https://youtu.be/abc", link.attachments[1].url)
        assertTrue(state.createdNew)
    }

    @Test
    fun plainText_isSavedAsNote() = runBlocking {
        val vm = viewModel(emptyList(), text = "Página 12, ejercicio 3")
        vm.createNew()

        val state = withTimeout(15_000) { vm.state.first { it.savedCode != null } }

        val note = repository.getLink(state.savedCode!!)!!.attachments.single()
        assertEquals(Kind.NOTE, note.kind)
        assertEquals("Página 12, ejercicio 3", note.text)
    }

    @Test
    fun addToMissingCode_keepsCopiesForRetry() = runBlocking {
        val existing = repository.createLink(listOf(AttachmentSource.Note("x")))
        val vm = viewModel(listOf(file("a.pdf")), text = null)
        vm.awaitPhase { it == ShareViewModel.Phase.Ready }

        vm.addToExisting("ACDE")
        withTimeout(15_000) { vm.state.first { it.message != null } }
        assertEquals(1, dir.mediaFiles().size)

        vm.addToExisting(existing.lowercase())
        val state = withTimeout(15_000) { vm.state.first { it.savedCode != null } }
        assertEquals(existing, state.savedCode)
        assertEquals(2, repository.getLink(existing)!!.attachments.size)
    }

    @Test
    fun failedCopy_createsNothingAndCleansUp() = runBlocking {
        val vm = viewModel(listOf(file("a.pdf"), dir.missingSource("revoked.jpg")), text = "https://x.com")

        vm.awaitPhase { it is ShareViewModel.Phase.Failed }

        assertTrue(dir.mediaFiles().isEmpty())
        assertEquals(0, repository.observeCount().first())
    }

    @Test
    fun cancel_deletesCopies() = runBlocking {
        val vm = viewModel(listOf(file("a.pdf")), text = null)
        vm.awaitPhase { it == ShareViewModel.Phase.Ready }

        vm.cancel()
        withTimeout(15_000) { vm.state.first { it.closed } }

        assertTrue(dir.mediaFiles().isEmpty())
    }

    @Test
    fun cancelDuringCopy_stopsCopyAndLeavesNothing() = runBlocking {
        val big = dir.source("video.mp4", ByteArray(200 * 1024 * 1024))
        val vm = viewModel(listOf(file("a.pdf"), big), text = null)
        // Esperar a que esté copiando el archivo grande.
        vm.awaitPhase { it is ShareViewModel.Phase.Copying && it.fileIndex == 1 && it.progress != null }

        withContext(Dispatchers.Main) { vm.cancel() }
        withTimeout(15_000) { vm.state.first { it.closed } }

        assertTrue("Quedaron archivos: ${dir.mediaFiles()}", dir.mediaFiles().isEmpty())
        assertEquals(0, repository.observeCount().first())
    }

    @Test
    fun recreatedViewModel_doesNotCopyAgain() = runBlocking {
        val handle = SavedStateHandle()
        val uris = listOf(file("a.pdf"))
        viewModel(uris, null, handle).awaitPhase { it == ShareViewModel.Phase.Ready }

        // Tras la muerte del proceso el ViewModel se recrea con el mismo estado guardado.
        viewModel(uris, null, handle).awaitPhase { it == ShareViewModel.Phase.Ready }

        assertEquals(1, dir.mediaFiles().size)
    }
}
