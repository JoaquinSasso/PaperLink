package com.joasasso.paperlink.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Migraciones reales desde la versión 3 (las versiones 1 y 2 se descartan, ver DatabaseUpgradeTest). */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val name = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PaperLinkDatabase::class.java,
    )

    @Test
    fun migrate3To4_keepsDataAndAddsAttachmentDeletedAt() {
        helper.createDatabase(name, 3).use { db ->
            db.execSQL("INSERT INTO links (code, label, createdAt, updatedAt, deletedAt) VALUES ('HWQ3', 'Física', 1, 2, NULL)")
            db.execSQL(
                "INSERT INTO attachments (id, linkCode, kind, relativePath, url, text, mimeType, sizeBytes, sha256, position, createdAt) " +
                    "VALUES ('a1', 'HWQ3', 'IMAGE', 'media/a1.jpg', NULL, NULL, 'image/jpeg', 10, 'abc', 0, 1)"
            )
        }

        helper.runMigrationsAndValidate(name, 4, true, MIGRATION_3_4).use { db ->
            db.query("SELECT label FROM links WHERE code = 'HWQ3'").use {
                assertTrue(it.moveToFirst())
                assertEquals("Física", it.getString(0))
            }
            db.query("SELECT relativePath, deletedAt FROM attachments WHERE id = 'a1'").use {
                assertTrue(it.moveToFirst())
                assertEquals("media/a1.jpg", it.getString(0))
                assertTrue(it.isNull(1))
            }
        }
    }
}
