package com.joasasso.paperlink.di

import android.content.Context
import com.joasasso.paperlink.data.local.PaperLinkDatabase
import com.joasasso.paperlink.data.repository.PaperLinkRepository
import com.joasasso.paperlink.domain.GenerateCodeUseCase

/**
 * Contenedor de dependencias principal de la aplicación.
 * Utilizamos inyección manual (Service Locator pattern) para mantener el MVP
 * simple, ligero y libre de la sobrecarga de configuración de librerías como Hilt.
 */
interface AppContainer {
    // Repositorio central para acceder a los vínculos multimedia guardados.
    val paperLinkRepository: PaperLinkRepository

    // Caso de uso que encapsula la lógica matemática para generar y validar códigos únicos.
    val generateCodeUseCase: GenerateCodeUseCase
}

/**
 * Implementación por defecto del [AppContainer].
 * Las dependencias se inicializan de forma perezosa (lazy) para no bloquear
 * el hilo principal durante el arranque de la aplicación.
 */
class DefaultAppContainer(private val context: Context) : AppContainer {

    /**
     * Instancia única de la base de datos Room. Se crea de forma perezosa.
     */
    private val database: PaperLinkDatabase by lazy {
        PaperLinkDatabase.build(context)
    }

    /**
     * Proveedor del repositorio, inyectando el DAO generado por Room.
     */
    override val paperLinkRepository: PaperLinkRepository by lazy {
        PaperLinkRepository(database.paperLinkDao())
    }

    /**
     * Proveedor del caso de uso, inyectando el repositorio necesario para validar unicidad.
     */
    override val generateCodeUseCase: GenerateCodeUseCase by lazy {
        GenerateCodeUseCase(paperLinkRepository)
    }
}