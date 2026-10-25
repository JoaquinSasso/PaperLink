package com.joasasso.paperlink.data.backup

import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.joasasso.paperlink.TestDir
import com.joasasso.paperlink.data.files.AttachmentStore
import com.joasasso.paperlink.data.local.PaperLinkDatabase
import com.joasasso.paperlink.data.preferences.UserPreferencesRepository
import com.joasasso.paperlink.data.repository.AttachmentSource
import com.joasasso.paperlink.data.repository.LinkRepository
import com.joasasso.paperlink.sha256
import com.joasasso.paperlink.ui.screens.home.HomeViewModel
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Pruebas instrumentadas del respaldo ZIP manual y de Google Drive (garantías 12, 13, 14 y 15).
 */
@RunWith(AndroidJUnit4::class)
class BackupTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private class TestNode(context: android.content.Context) {
        val dir = TestDir()
        val db: PaperLinkDatabase = Room.inMemoryDatabaseBuilder(context, PaperLinkDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob())
        val store = AttachmentStore(context, dir.base)
        val repository = LinkRepository(db.linkDao(), store, scope)
        val preferences = UserPreferencesRepository(
            PreferenceDataStoreFactory.create(scope = scope) {
                File(dir.root, "prefs-${UUID.randomUUID()}.preferences_pb")
            }
        )
        val zipBackup = ZipBackupManager(store, repository)

        fun close() {
            scope.cancel()
            db.close()
            dir.delete()
        }
    }

    private lateinit var sourceNode: TestNode
    private lateinit var targetNode: TestNode

    @Before
    fun setUp() {
        sourceNode = TestNode(context)
        targetNode = TestNode(context)
    }

    @After
    fun tearDown() {
        sourceNode.close()
        targetNode.close()
    }

    @Test
    fun zipExportAndRestoreIntoCleanInstall_restoresAllActiveLinksAndFiles() = runBlocking {
        val file1 = sourceNode.dir.source("notes.pdf", "PDF content 123".encodeToByteArray())
        val code1 = sourceNode.repository.createLink(
            sources = listOf(
                AttachmentSource.Content(file1),
                AttachmentSource.WebUrl("https://example.org/paper"),
                AttachmentSource.Note("Importante para el parcial"),
            ),
            label = "Álgebra",
        )
        val deletedCode = sourceNode.repository.createLink(
            sources = listOf(AttachmentSource.Note("Borrado")),
            label = "No exportar",
        )
        sourceNode.repository.delete(deletedCode)

        val zipFile = File(sourceNode.dir.root, "backup.zip")
        val exported = sourceNode.zipBackup.exportZip(Uri.fromFile(zipFile))
        assertEquals(1, exported.links.size)
        assertEquals(3, exported.attachments.size)

        val mergeCounts = targetNode.zipBackup.importZip(Uri.fromFile(zipFile))
        assertEquals(1, mergeCounts.addedLinks)
        assertEquals(3, mergeCounts.addedAttachments)

        assertNull(targetNode.repository.getLink(deletedCode))
        val restored = targetNode.repository.getLink(code1)
        assertNotNull(restored)
        assertEquals("Álgebra", restored!!.link.label)
        assertEquals(3, restored.attachments.size)

        val restoredFile = targetNode.repository.fileFor(restored.attachments[0])!!
        assertTrue(restoredFile.isFile)
        assertEquals("PDF content 123", restoredFile.readBytes().decodeToString())
        assertEquals(restored.attachments[0].sha256, restoredFile.sha256())
        assertEquals("https://example.org/paper", restored.attachments[1].url)
        assertEquals("Importante para el parcial", restored.attachments[2].text)
    }

    @Test
    fun restoreOverExistingData_mergesByAttachmentIdWithoutOverwriting() = runBlocking {
        // Creamos un código en común exportándolo y luego modificamos origen y destino de forma distinta.
        val initialUri = sourceNode.dir.source("first.bin", "first-file".encodeToByteArray())
        val sharedCode = sourceNode.repository.createLink(
            sources = listOf(AttachmentSource.Content(initialUri)),
            label = "Etiqueta original",
        )
        val initialZip = File(sourceNode.dir.root, "initial.zip")
        sourceNode.zipBackup.exportZip(Uri.fromFile(initialZip))

        // Importamos el estado base en el destino y le cambiamos la etiqueta + sumamos un elemento local.
        targetNode.zipBackup.importZip(Uri.fromFile(initialZip))
        targetNode.repository.setLabel(sharedCode, "Etiqueta local del teléfono")
        targetNode.repository.addAttachments(sharedCode, listOf(AttachmentSource.Note("Nota local exclusiva")))

        // En el origen agregamos otro archivo a sharedCode y además creamos un segundo código nuevo.
        val secondUri = sourceNode.dir.source("second.bin", "second-file".encodeToByteArray())
        sourceNode.repository.addAttachments(sharedCode, listOf(AttachmentSource.Content(secondUri)))
        val newCode = sourceNode.repository.createLink(
            sources = listOf(AttachmentSource.Note("Código nuevo del respaldo")),
            label = "Química",
        )
        val secondZip = File(sourceNode.dir.root, "second.zip")
        sourceNode.zipBackup.exportZip(Uri.fromFile(secondZip))

        // Al importar secondZip sobre targetNode:
        // - sharedCode conserva "Etiqueta local del teléfono" y su "Nota local exclusiva"
        // - no duplica el primer archivo y agrega el segundo al final
        // - newCode se agrega completo
        val counts = targetNode.zipBackup.importZip(Uri.fromFile(secondZip))
        assertEquals(1, counts.addedLinks)
        assertEquals(2, counts.addedAttachments) // 1 nuevo en sharedCode + 1 en newCode

        val mergedShared = targetNode.repository.getLink(sharedCode)!!
        assertEquals("Etiqueta local del teléfono", mergedShared.link.label)
        assertEquals(3, mergedShared.attachments.size)
        assertEquals("Nota local exclusiva", mergedShared.attachments[1].text)
        val secondRestoredFile = targetNode.repository.fileFor(mergedShared.attachments[2])!!
        assertEquals("second-file", secondRestoredFile.readBytes().decodeToString())

        val mergedNew = targetNode.repository.getLink(newCode)
        assertNotNull(mergedNew)
        assertEquals("Química", mergedNew!!.link.label)
    }

    @Test
    fun corruptFileInZip_isRejectedAndLeavesNothingOnDiskOrDatabase() = runBlocking {
        val uri = sourceNode.dir.source("data.bin", "contenido-intacto".encodeToByteArray())
        sourceNode.repository.createLink(listOf(AttachmentSource.Content(uri)))

        val validZip = File(sourceNode.dir.root, "valid.zip")
        sourceNode.zipBackup.exportZip(Uri.fromFile(validZip))

        // Adulteramos los bytes del archivo dentro del ZIP sin cambiar el manifest.json.
        val corruptZip = File(sourceNode.dir.root, "corrupt.zip")
        ZipInputStream(validZip.inputStream()).use { zipIn ->
            ZipOutputStream(corruptZip.outputStream()).use { zipOut ->
                while (true) {
                    val entry = zipIn.nextEntry ?: break
                    zipOut.putNextEntry(ZipEntry(entry.name))
                    val bytes = zipIn.readBytes()
                    if (entry.name.startsWith("media/")) {
                        bytes[0] = (bytes[0].toInt() xor 0xFF).toByte()
                    }
                    zipOut.write(bytes)
                    zipOut.closeEntry()
                    zipIn.closeEntry()
                }
            }
        }

        try {
            targetNode.zipBackup.importZip(Uri.fromFile(corruptZip))
            fail("Expected CorruptBackup exception")
        } catch (_: BackupException.CorruptBackup) {
            // Esperado: falla la verificación SHA-256 (garantía 12)
        }

        assertEquals(0, targetNode.repository.allCodes().size)
        assertEquals(emptyList<File>(), targetNode.dir.mediaFiles())
    }

    @Test
    fun driveIncrementalBackup_uploadsMediaFirstAndManifestLast_andRecoversIfInterrupted() = runBlocking {
        val fakeRemote = FakeDriveRemoteStore()
        val fakeToken = FakeTokenProvider()
        val fakeScheduler = FakeBackupScheduler()

        val sourceDrive = DriveBackupManager(
            store = sourceNode.store,
            repository = sourceNode.repository,
            preferences = sourceNode.preferences,
            tokenProvider = fakeToken,
            remote = fakeRemote,
            scheduler = fakeScheduler,
            clock = { 10_000L },
        )

        val file1 = sourceNode.dir.source("one.bin", "file-one-bytes".encodeToByteArray())
        val code1 = sourceNode.repository.createLink(listOf(AttachmentSource.Content(file1)))

        val summary1 = sourceDrive.backupNow()
        assertEquals(1, summary1.uploadedFiles)
        assertEquals(1, summary1.totalLinks)
        // Garantía 12: primero se sube media/* y al final manifest.json
        assertEquals(2, fakeRemote.uploadOrder.size)
        assertTrue(fakeRemote.uploadOrder[0].startsWith("media/"))
        assertEquals(BackupManifest.MANIFEST_FILE_NAME, fakeRemote.uploadOrder[1])

        // Segunda corrida incremental con un archivo nuevo: no vuelve a subir el primero.
        fakeRemote.uploadOrder.clear()
        val file2 = sourceNode.dir.source("two.bin", "file-two-bytes".encodeToByteArray())
        val code2 = sourceNode.repository.createLink(listOf(AttachmentSource.Content(file2)))

        val summary2 = sourceDrive.backupNow()
        assertEquals(1, summary2.uploadedFiles)
        assertEquals(2, summary2.totalLinks)
        assertEquals(2, fakeRemote.uploadOrder.size)
        assertTrue(fakeRemote.uploadOrder[0].startsWith("media/"))
        assertEquals(BackupManifest.MANIFEST_FILE_NAME, fakeRemote.uploadOrder[1])

        // Si un tercer respaldo se corta por red antes de subir manifest.json, el respaldo anterior sigue intacto.
        val file3 = sourceNode.dir.source("three.bin", "file-three-bytes".encodeToByteArray())
        val code3 = sourceNode.repository.createLink(listOf(AttachmentSource.Content(file3)))
        fakeRemote.failOnUploadName = BackupManifest.MANIFEST_FILE_NAME
        try {
            sourceDrive.backupNow()
            fail("Expected NetworkError")
        } catch (_: BackupException.NetworkError) {
            // Esperado
        }
        fakeRemote.failOnUploadName = null

        val targetDrive = DriveBackupManager(
            store = targetNode.store,
            repository = targetNode.repository,
            preferences = targetNode.preferences,
            tokenProvider = fakeToken,
            remote = fakeRemote,
            scheduler = fakeScheduler,
        )
        val restored = targetDrive.restoreFromDrive()
        assertEquals(2, restored.addedLinks)
        assertNotNull(targetNode.repository.getLink(code1))
        assertNotNull(targetNode.repository.getLink(code2))
        assertNull(targetNode.repository.getLink(code3))
    }

    @Test
    fun homeBanners_suggestAtFiveLinks_andWarnOnRevokedDriveAccess() = runBlocking {
        repeat(5) { i ->
            sourceNode.repository.createLink(listOf(AttachmentSource.Note("Nota $i")))
        }

        val vm = withContext(Dispatchers.Main) {
            HomeViewModel(
                handle = SavedStateHandle(),
                repository = sourceNode.repository,
                preferences = sourceNode.preferences,
            )
        }

        // Garantía 15: al llegar a 5 códigos sin respaldo activo sugiere activarlo.
        val banner1 = withTimeout(5_000) {
            vm.backupBanner.first { it != null }
        }
        assertEquals(HomeViewModel.BackupBanner.SuggestEnable, banner1)

        // Al descartar la sugerencia desaparece y no vuelve a molestar.
        withContext(Dispatchers.Main) { vm.dismissBackupSuggestion() }
        withTimeout(5_000) {
            vm.backupBanner.first { it == null }
        }

        // Garantía 13: si el respaldo está activo y se revoca el acceso a Drive, aparece NeedsReauth.
        val fakeRemote = FakeDriveRemoteStore()
        val revokedTokenProvider = FakeTokenProvider(shouldFailAuth = true)
        val driveManager = DriveBackupManager(
            store = sourceNode.store,
            repository = sourceNode.repository,
            preferences = sourceNode.preferences,
            tokenProvider = revokedTokenProvider,
            remote = fakeRemote,
            scheduler = FakeBackupScheduler(),
        )
        sourceNode.preferences.setDriveBackupEnabled(true)
        try {
            driveManager.backupNow()
            fail("Expected AuthRequired")
        } catch (_: BackupException.AuthRequired) {
            // Esperado
        }

        val reauthBanner = withTimeout(5_000) {
            vm.backupBanner.first { it is HomeViewModel.BackupBanner.NeedsReauth }
        }
        assertEquals(HomeViewModel.BackupBanner.NeedsReauth, reauthBanner)
    }

    private class FakeTokenProvider(var shouldFailAuth: Boolean = false) : DriveTokenProvider {
        override suspend fun getAccessToken(): String {
            if (shouldFailAuth) throw BackupException.AuthRequired()
            return "fake-access-token"
        }

        override suspend fun invalidateToken(token: String) = Unit
    }

    private class FakeBackupScheduler : BackupScheduler {
        var scheduled = false
        var lastChargingOnly = false

        override fun scheduleDaily(chargingOnly: Boolean) {
            scheduled = true
            lastChargingOnly = chargingOnly
        }

        override fun cancel() {
            scheduled = false
        }
    }

    private class FakeDriveRemoteStore : DriveRemoteStore {
        private data class StoredEntry(
            val file: RemoteDriveFile,
            val bytes: ByteArray,
            val order: Int,
        )

        private val entries = LinkedHashMap<String, StoredEntry>()
        private var counter = 0
        val uploadOrder = mutableListOf<String>()
        var failOnUploadName: String? = null

        override suspend fun listFiles(accessToken: String): List<RemoteDriveFile> =
            entries.values.sortedByDescending { it.order }.map { it.file }

        override suspend fun uploadStream(
            accessToken: String,
            name: String,
            mimeType: String,
            contentStream: InputStream,
        ): RemoteDriveFile {
            if (failOnUploadName == name) {
                throw BackupException.NetworkError("Simulated network failure on $name")
            }
            val bytes = contentStream.readBytes()
            val id = "drive-${++counter}"
            val remoteFile = RemoteDriveFile(id = id, name = name, size = bytes.size.toLong())
            entries[id] = StoredEntry(remoteFile, bytes, counter)
            uploadOrder += name
            return remoteFile
        }

        override suspend fun downloadFile(
            accessToken: String,
            fileId: String,
            consumer: suspend (InputStream) -> Unit,
        ) {
            val stored = entries[fileId] ?: throw BackupException.NetworkError("File not found: $fileId")
            ByteArrayInputStream(stored.bytes).use { consumer(it) }
        }

        override suspend fun deleteFile(accessToken: String, fileId: String) {
            entries.remove(fileId)
        }
    }
}
