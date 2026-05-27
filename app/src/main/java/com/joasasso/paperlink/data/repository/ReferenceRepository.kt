package com.joasasso.paperlink.data.repository

import com.joasasso.paperlink.data.local.PaperReference
import com.joasasso.paperlink.data.local.PaperReferenceDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Capa de abstracción sobre [PaperReferenceDao].
 *
 * Responsabilidades:
 * - Normalizar el `code` a mayúsculas en todas las operaciones (escritura y lectura).
 * - Exponer una API agnóstica de la implementación de persistencia.
 * - Forzar `Dispatchers.IO` en las suspend functions. Room ya cambia de dispatcher
 *   internamente, pero envolver explícitamente aísla al resto del código de
 *   detalles de implementación de Room.
 *
 * Las funciones que devuelven `Flow` no usan `withContext`: el consumidor decide
 * en qué dispatcher coleccionar (típicamente `viewModelScope` en main-immediate,
 * y Room emite ya desde IO).
 */
class ReferenceRepository(
    private val dao: PaperReferenceDao
) {

    /**
     * Inserta una nueva referencia. Lanza excepción si el código ya existe
     * (por la estrategia ABORT del DAO).
     */
    suspend fun insert(reference: PaperReference) = withContext(Dispatchers.IO) {
        dao.insert(reference.copy(code = reference.code.uppercase()))
    }

    /**
     * Borra una referencia por su código. Devuelve true si se borró algo.
     */
    suspend fun deleteByCode(code: String): Boolean = withContext(Dispatchers.IO) {
        dao.deleteByCode(code.uppercase()) > 0
    }

    /**
     * Busca una referencia por código. Devuelve null si no existe.
     */
    suspend fun getByCode(code: String): PaperReference? = withContext(Dispatchers.IO) {
        dao.getByCode(code.uppercase())
    }

    /**
     * Observa una referencia por código en forma reactiva.
     */
    fun observeByCode(code: String): Flow<PaperReference?> {
        return dao.observeByCode(code.uppercase())
    }

    /**
     * Verifica si existe una referencia con el código dado.
     * Lo usa [GenerateCodeUseCase] en Fase 3 para detectar colisiones.
     */
    suspend fun exists(code: String): Boolean = withContext(Dispatchers.IO) {
        dao.existsByCode(code.uppercase())
    }

    /**
     * Lista las últimas [limit] referencias ordenadas por fecha de creación descendente.
     * Pensado para alimentar la lista de "recientes" en HomeScreen.
     */
    fun getRecent(limit: Int = DEFAULT_RECENT_LIMIT): Flow<List<PaperReference>> {
        return dao.getRecent(limit)
    }

    /**
     * Cantidad total de referencias guardadas.
     */
    suspend fun count(): Int = withContext(Dispatchers.IO) {
        dao.count()
    }

    companion object {
        const val DEFAULT_RECENT_LIMIT = 10
    }
}
