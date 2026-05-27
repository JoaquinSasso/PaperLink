package com.joasasso.paperlink.di

import android.content.Context
import com.joasasso.paperlink.data.local.PaperLinkDatabase
import com.joasasso.paperlink.data.repository.PaperLinkRepository

/**
 * Contenedor de dependencias de la aplicación.
 *
 * Esta interfaz declara las dependencias que cualquier capa superior (ViewModels)
 * puede pedir. Mantener una interfaz y no exponer la implementación directamente
 * facilita:
 *   - Reemplazar implementaciones en tests (fake repository, in-memory database).
 *   - Migrar a Hilt en el futuro sin romper consumidores.
 *
 * Por ahora solo expone `paperLinkRepository`. Cuando lleguen use cases en Fase 3
 * se agregan acá.
 */
interface AppContainer {
    val paperLinkRepository: PaperLinkRepository
}

/**
 * Implementación por defecto del contenedor. Instancia lazy las dependencias
 * de larga vida: la base se crea solo cuando alguien pide el repositorio por
 * primera vez, y el repositorio cuando alguien lo pide.
 */
class DefaultAppContainer(context: Context) : AppContainer {

    private val database: PaperLinkDatabase by lazy {
        PaperLinkDatabase.build(context)
    }

    override val paperLinkRepository: PaperLinkRepository by lazy {
        PaperLinkRepository(database.paperLinkDao())
    }
}
