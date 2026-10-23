package com.joasasso.paperlink.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Base de PaperLink. Arranca en la versión 3: las versiones 1 y 2 (app anterior) se descartan
 * sin migrar. De la 3 en adelante cada cambio de esquema lleva una Migration con su test;
 * nunca `fallbackToDestructiveMigration()`.
 */
@Database(
    entities = [Link::class, Attachment::class],
    version = 3,
    exportSchema = true,
)
abstract class PaperLinkDatabase : RoomDatabase() {
    abstract fun linkDao(): LinkDao

    companion object {
        const val NAME = "paperlink.db"

        fun build(context: Context, name: String = NAME): PaperLinkDatabase =
            Room.databaseBuilder(context, PaperLinkDatabase::class.java, name)
                .fallbackToDestructiveMigrationFrom(true, 1, 2)
                .build()
    }
}
