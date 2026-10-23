package com.joasasso.paperlink.data.local

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La base de la app anterior (versiones 1 y 2) se descarta: al actualizar, la app abre
 * sin crashear y con la base vacía en la versión 3.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseUpgradeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "upgrade-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(name)
    }

    @Test
    fun version1_isDiscarded() = assertDiscarded(oldVersion = 1)

    @Test
    fun version2_isDiscarded() = assertDiscarded(oldVersion = 2)

    private fun assertDiscarded(oldVersion: Int) = runBlocking {
        createOldDatabase(oldVersion)

        val db = PaperLinkDatabase.build(context, name)
        try {
            assertEquals(0, db.linkDao().observeCount().first())
            db.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE name = 'paper_links'").use {
                assertEquals("La tabla vieja debería haberse borrado", 0, it.count)
            }
            assertEquals(3, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
        }
    }

    /** Reproduce el esquema de la app anterior, con un vínculo cargado. */
    private fun createOldDatabase(version: Int) {
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL(
                "CREATE TABLE paper_links (code TEXT NOT NULL PRIMARY KEY, content_type TEXT NOT NULL, " +
                    "content_uri TEXT NOT NULL, created_at INTEGER NOT NULL)"
            )
            old.execSQL("INSERT INTO paper_links VALUES ('HWQ2', 'IMAGE', 'content://old/camera/x.jpg', 1)")
            old.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            old.execSQL("INSERT INTO room_master_table VALUES (42, 'old-hash')")
            old.version = version
        }
    }
}
