package com.joasasso.paperlink.ui.screens.home

import androidx.lifecycle.SavedStateHandle
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.joasasso.paperlink.TestDir
import com.joasasso.paperlink.data.files.AttachmentStore
import com.joasasso.paperlink.data.local.PaperLinkDatabase
import com.joasasso.paperlink.data.preferences.UserPreferencesRepository
import com.joasasso.paperlink.data.repository.AttachmentSource
import com.joasasso.paperlink.data.repository.BatchImporter
import com.joasasso.paperlink.data.repository.LinkRepository
import com.joasasso.paperlink.domain.CodeAlphabet
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
import java.io.File

/** Apertura instantánea al cuarto carácter y sugerencias cuando el código no existe. */
@RunWith(AndroidJUnit4::class)
class HomeViewModelTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: TestDir
    private lateinit var db: PaperLinkDatabase
    private lateinit var repository: LinkRepository
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        dir = TestDir()
        db = Room.inMemoryDatabaseBuilder(context, PaperLinkDatabase::class.java).build()
        scope = CoroutineScope(SupervisorJob())
        repository = LinkRepository(db.linkDao(), AttachmentStore(context, dir.base), scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        dir.delete()
    }

    private suspend fun viewModel() = withContext(Dispatchers.Main) {
        val store = AttachmentStore(context, dir.base)
        val preferences = UserPreferencesRepository(
            PreferenceDataStoreFactory.create(scope = scope) { File(dir.root, "prefs.preferences_pb") }
        )
        HomeViewModel(SavedStateHandle(), repository, BatchImporter(repository, store, preferences, scope, 3))
    }

    private suspend fun HomeViewModel.type(text: String) = withContext(Dispatchers.Main) { onCodeChange(text) }

    @Test
    fun fourthCharacterOfExistingCode_opensIt() = runBlocking {
        val code = repository.createLink(listOf(AttachmentSource.Note("x")))
        val vm = viewModel()

        vm.type(code.take(3).lowercase())
        assertEquals(null, vm.events.value.open)
        vm.type(code.lowercase())

        assertEquals(code, withTimeout(5_000) { vm.events.first { it.open != null } }.open)
        assertEquals("", vm.code.value)
    }

    @Test
    fun missingCode_showsErrorWithSuggestions() = runBlocking {
        val code = repository.createLink(listOf(AttachmentSource.Note("x")))
        val vm = viewModel()
        // Mismo código con el primer carácter cambiado por otro del alfabeto.
        val other = CodeAlphabet.ALLOWED_CHARACTERS.first { it != code[0] }
        val typo = other + code.drop(1)

        vm.type(typo)
        val lookup = withTimeout(5_000) { vm.lookup.first { it is HomeViewModel.Lookup.NotFound } } as HomeViewModel.Lookup.NotFound

        assertEquals(listOf(code), lookup.suggestions)
        assertEquals(false, lookup.hasInvalidChars)
        assertEquals(null, vm.events.value.open)
    }

    @Test
    fun invalidCharacters_areFlagged() = runBlocking {
        val vm = viewModel()

        vm.type("AB0O")
        val lookup = withTimeout(5_000) { vm.lookup.first { it is HomeViewModel.Lookup.NotFound } } as HomeViewModel.Lookup.NotFound

        assertTrue(lookup.hasInvalidChars)
    }

    @Test
    fun editingAfterError_clearsIt() = runBlocking {
        val vm = viewModel()
        vm.type("ACDE")
        withTimeout(5_000) { vm.lookup.first { it is HomeViewModel.Lookup.NotFound } }

        vm.type("ACD")

        assertEquals(HomeViewModel.Lookup.Idle, vm.lookup.value)
    }
}
