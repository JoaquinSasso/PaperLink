package com.joasasso.paperlink.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Base de datos local de PaperLink.
 *
 * Versionado:
 * - v1: schema inicial con una sola tabla `paper_references`.
 *
 * El schema se exporta a `app/schemas/` (configurado en build.gradle.kts vía
 * `ksp.arg("room.schemaLocation", ...)`). Esto permite versionar las migraciones
 * en el repositorio cuando lleguen.
 */
@Database(
    entities = [PaperReference::class],
    version = 1,
    exportSchema = true
)
abstract class PaperReferenceDatabase : RoomDatabase() {

    abstract fun paperReferenceDao(): PaperReferenceDao

    companion object {
        private const val DATABASE_NAME = "paperlink.db"

        /**
         * Construye la instancia única de la base. Se llama desde [AppContainer].
         * No usa singleton estático: la unicidad la garantiza el container.
         */
        fun build(context: Context): PaperReferenceDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                PaperReferenceDatabase::class.java,
                DATABASE_NAME
            ).build()
        }
    }
}
