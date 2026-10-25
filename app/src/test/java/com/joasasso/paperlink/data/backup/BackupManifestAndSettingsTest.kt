package com.joasasso.paperlink.data.backup

import com.joasasso.paperlink.data.local.Attachment
import com.joasasso.paperlink.data.preferences.BackupSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupManifestAndSettingsTest {

    private val validLink = BackupLink(
        code = "HWQ3",
        label = "Física",
        createdAt = 1_000L,
        updatedAt = 2_000L,
    )

    private val validFileAttachment = BackupAttachment(
        id = "att-1",
        linkCode = "HWQ3",
        kind = Attachment.Kind.IMAGE,
        relativePath = "media/11111111-2222-3333-4444-555555555555.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 1024L,
        sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        position = 0,
        createdAt = 1_000L,
    )

    @Test
    fun validManifest_passesValidation() {
        val manifest = BackupManifest(
            createdAt = 3_000L,
            links = listOf(validLink),
            attachments = listOf(
                validFileAttachment,
                BackupAttachment(
                    id = "att-2",
                    linkCode = "HWQ3",
                    kind = Attachment.Kind.URL,
                    url = "https://example.com",
                    position = 1,
                    createdAt = 1_500L,
                ),
                BackupAttachment(
                    id = "att-3",
                    linkCode = "HWQ3",
                    kind = Attachment.Kind.NOTE,
                    text = "Repasar capítulo 4",
                    position = 2,
                    createdAt = 1_800L,
                ),
            ),
        )
        manifest.validate()
    }

    @Test(expected = BackupException.InvalidArchive::class)
    fun unsupportedFormatVersion_isRejected() {
        BackupManifest(
            formatVersion = 99,
            createdAt = 1_000L,
            links = listOf(validLink),
            attachments = listOf(validFileAttachment),
        ).validate()
    }

    @Test(expected = BackupException.InvalidArchive::class)
    fun invalidCodeInManifest_isRejected() {
        BackupManifest(
            createdAt = 1_000L,
            links = listOf(validLink.copy(code = "AB01")), // '0' y '1' están fuera del alfabeto
            attachments = emptyList(),
        ).validate()
    }

    @Test(expected = BackupException.InvalidArchive::class)
    fun pathTraversalInMediaPath_isRejected() {
        BackupManifest(
            createdAt = 1_000L,
            links = listOf(validLink),
            attachments = listOf(validFileAttachment.copy(relativePath = "media/../secret.db")),
        ).validate()
    }

    @Test(expected = BackupException.InvalidArchive::class)
    fun missingSha256OnFileAttachment_isRejected() {
        BackupManifest(
            createdAt = 1_000L,
            links = listOf(validLink),
            attachments = listOf(validFileAttachment.copy(sha256 = null)),
        ).validate()
    }

    @Test(expected = BackupException.InvalidArchive::class)
    fun attachmentReferencingUnknownCode_isRejected() {
        BackupManifest(
            createdAt = 1_000L,
            links = listOf(validLink),
            attachments = listOf(validFileAttachment.copy(linkCode = "ACDE")),
        ).validate()
    }

    @Test
    fun backupSettings_staleDetectionAfterSevenDays() {
        val dayMs = 24L * 60 * 60 * 1000
        val baseTime = 100L * dayMs
        val recent = BackupSettings(driveEnabled = true, lastBackupAt = baseTime - 6 * dayMs)
        val stale = BackupSettings(driveEnabled = true, lastBackupAt = baseTime - 8 * dayMs)
        val disabled = BackupSettings(driveEnabled = false, lastBackupAt = baseTime - 30 * dayMs)

        assertFalse(recent.isStale(baseTime))
        assertTrue(stale.isStale(baseTime))
        assertFalse(disabled.isStale(baseTime))
    }

    @Test
    fun backupSettings_suggestsOnlyAfterFiveLinksWhenNotEnabledAndNotDismissed() {
        val fresh = BackupSettings(driveEnabled = false, suggestionDismissed = false)
        assertFalse(fresh.shouldSuggestBackup(0))
        assertFalse(fresh.shouldSuggestBackup(4))
        assertTrue(fresh.shouldSuggestBackup(5))
        assertTrue(fresh.shouldSuggestBackup(12))

        assertFalse(fresh.copy(suggestionDismissed = true).shouldSuggestBackup(5))
        assertFalse(fresh.copy(driveEnabled = true).shouldSuggestBackup(5))
    }
}
