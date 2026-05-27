package com.joasasso.paperlink.data.repository

import com.joasasso.paperlink.data.local.PaperLink
import com.joasasso.paperlink.data.local.PaperLinkDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Capa de abstracción sobre [PaperLinkDao].
 *
 * Responsabilidades:
 * - Normalizar el `code` a mayúsculas en todas las operaciones.
 * - Exponer una API agnóstica de la implementación de persistencia.
 * - Forzar `Dispatchers.IO` en las suspend functions. Room ya cambia de dispatcher
 *   internamente, pero envolver explícitamente aísla al resto del código de
 *   detalles de implementación de Room.
 *
 * Las funciones que devuelven `Flow` no usan `withContext`: el consumidor decide
 * en qué dispatcher coleccionar (típicamente `viewModelScope` en main-immediate,
 * y Room emite ya desde IO).
 */
class PaperLinkRepository(
    private val dao: PaperLinkDao
) {

    /**
     * Inserta un nuevo PaperLink. Lanza excepción si el código ya existe
     * (por la estrategia ABORT del DAO).
     */
    suspend fun insert(link: PaperLink) = withContext(Dispatchers.IO) {
        dao.insert(link.copy(code = link.code.uppercase()))
    }

    /**
     * Borra un PaperLink por su código. Devuelve true si se borró algo.
     */
    suspend fun deleteByCode(code: String): Boolean = withContext(Dispatchers.IO) {
        dao.deleteByCode(code.uppercase()) > 0
    }

    /**
     * Busca un PaperLink por código. Devuelve null si no existe.
     */
    suspend fun getByCode(code: String): PaperLink? = withContext(Dispatchers.IO) {
        dao.getByCode(code.uppercase())
    }

    /**
     * Observa un PaperLink por código en forma reactiva.
     */
    fun observeByCode(code: String): Flow<PaperLink?> {
        return dao.observeByCode(code.uppercase())
    }

    /**
     * Verifica si existe un PaperLink con el código dado.
     * Lo usa GenerateCodeUseCase en Fase 3 para detectar colisiones.
     */
    suspend fun exists(code: String): Boolean = withContext(Dispatchers.IO) {
        dao.existsByCode(code.uppercase())
    }

    /**
     * Lista los últimos [limit] PaperLinks ordenados por fecha de creación descendente.
     * Pensado para alimentar la lista de "recientes" en HomeScreen.
     */
    fun getRecent(limit: Int = DEFAULT_RECENT_LIMIT): Flow<List<PaperLink>> {
        return dao.getRecent(limit)
    }

    /**
     * Cantidad total de PaperLinks guardados.
     */
    suspend fun count(): Int = withContext(Dispatchers.IO) {
        dao.count()
    }

    companion object {
        const val DEFAULT_RECENT_LIMIT = 10
    }
}
