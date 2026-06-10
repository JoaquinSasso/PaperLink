package com.joasasso.paperlink.di

import android.content.Context
import com.joasasso.paperlink.data.local.PaperLinkDatabase
import com.joasasso.paperlink.data.repository.PaperLinkRepository
import com.joasasso.paperlink.domain.GenerateCodeUseCase

import com.joasasso.paperlink.data.preferences.UserPreferencesRepository

/**
 * Contenedor de dependencias principal de la aplicación.
 */
interface AppContainer {
    val paperLinkRepository: PaperLinkRepository
    val generateCodeUseCase: GenerateCodeUseCase
    val userPreferencesRepository: UserPreferencesRepository
}

/**
 * Implementación por defecto del [AppContainer].
 */
class DefaultAppContainer(private val context: Context) : AppContainer {

    private val database: PaperLinkDatabase by lazy {
        PaperLinkDatabase.build(context)
    }

    override val paperLinkRepository: PaperLinkRepository by lazy {
        PaperLinkRepository(database.paperLinkDao())
    }

    override val generateCodeUseCase: GenerateCodeUseCase by lazy {
        GenerateCodeUseCase(paperLinkRepository)
    }

    override val userPreferencesRepository: UserPreferencesRepository by lazy {
        UserPreferencesRepository(context)
    }
}
