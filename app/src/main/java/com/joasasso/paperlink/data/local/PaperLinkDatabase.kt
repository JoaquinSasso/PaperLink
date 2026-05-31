package com.joasasso.paperlink.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Base de datos radical de PaperLink.
 * PIVOTE: Se elimina la tabla de Subjects para un diseño de Fricción Cero.
 */
@Database(
    entities = [PaperLink::class],
    version = 2, // Incrementamos para forzar la migración destructiva
    exportSchema = true
)
@TypeConverters(ContentTypeConverter::class)
abstract class PaperLinkDatabase : RoomDatabase() {
    abstract fun paperLinkDao(): PaperLinkDao

    companion object {
        @Volatile
        private var INSTANCE: PaperLinkDatabase? = null
        fun build(context: Context): PaperLinkDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context,
                    PaperLinkDatabase::class.java,
                    "paperlink.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
