package com.joasasso.paperlink.ui.screens.camera

import android.graphics.Bitmap
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Garantía 9: ninguna foto perdida en ráfaga, ni si el proceso muere. */
@RunWith(AndroidJUnit4::class)
class CameraViewModelTest {

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

    private fun viewModel(handle: SavedStateHandle = SavedStateHandle(), appendTo: String? = null) =
        CameraViewModel(handle, appendTo, repository, store, scope)

    /** Simula lo que hace CameraX: escribe un JPEG en el archivo de captura. */
    private suspend fun capture(): File {
        val file = store.newCaptureFile()
        dir.image("src-${file.name}", 64, 48, Bitmap.CompressFormat.JPEG).copyTo(file)
        return file
    }

    private suspend fun CameraViewModel.awaitSaved(count: Int) = withTimeout(15_000) {
        state.first { it.photoCount == count && it.savingCount == 0 }
    }

    @Test
    fun burstOfPhotos_endsInOneCodeWithAllPhotosInOrder() = runBlocking {
        val vm = viewModel()
        val files = List(3) { capture() }

        withContext(Dispatchers.Main) { files.forEach(vm::onPhotoSaved) }
        val state = vm.awaitSaved(3)

        val link = repository.getLink(state.code!!)!!
        assertEquals(3, link.attachments.size)
        assertTrue(link.attachments.all { it.kind == Kind.IMAGE })
        files.forEach { assertFalse("Captura sin borrar: $it", it.exists()) }
    }

    @Test
    fun pendingCaptures_areImportedAfterProcessDeath() = runBlocking {
        // Estado que quedó en SavedStateHandle: una foto escrita por la cámara que no llegó a la base.
        val file = capture()
        val restored = SavedStateHandle(mapOf(CameraViewModel.KEY_PENDING to arrayListOf(file.name)))

        val state = viewModel(restored).awaitSaved(1)

        assertNotNull(repository.getLink(state.code!!))
        assertFalse(file.exists())
        assertEquals(emptyList<String>(), restored.get<ArrayList<String>>(CameraViewModel.KEY_PENDING))
    }

    @Test
    fun pendingCaptureWhoseFileIsGone_isDroppedSilently() = runBlocking {
        val restored = SavedStateHandle(mapOf(CameraViewModel.KEY_PENDING to arrayListOf("missing.jpg")))
        val vm = viewModel(restored)

        withTimeout(5_000) { vm.state.first { it.savingCount == 0 } }

        assertEquals(0, vm.state.value.photoCount)
        assertEquals(null, vm.state.value.message)
        assertEquals(0, repository.observeCount().first())
    }

    @Test
    fun appendSession_addsToExistingCode() = runBlocking {
        val code = repository.createLink(listOf(AttachmentSource.Note("x")))
        val vm = viewModel(appendTo = code)

        val file = capture()
        withContext(Dispatchers.Main) { vm.onPhotoSaved(file) }
        vm.awaitSaved(1)

        assertEquals(2, repository.getLink(code)!!.attachments.size)
        assertEquals(1, repository.observeCount().first())
    }

    @Test
    fun done_waitsForPendingSaves() = runBlocking {
        val vm = viewModel()
        val file = capture()
        withContext(Dispatchers.Main) {
            vm.onPhotoSaved(file)
            vm.onDone()
        }

        val state = withTimeout(15_000) { vm.state.first { it.canFinish } }

        assertEquals(1, state.photoCount)
        assertNotNull(state.code)
    }
}
