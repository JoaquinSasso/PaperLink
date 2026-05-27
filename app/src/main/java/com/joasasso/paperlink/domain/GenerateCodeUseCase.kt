package com.joasasso.paperlink.domain

import com.joasasso.paperlink.data.repository.PaperLinkRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Caso de uso responsable de generar un código único de 4 caracteres.
 */
class GenerateCodeUseCase(
    private val repository: PaperLinkRepository
) {
    /**
     * Genera un código aleatorio y verifica contra la base de datos que no exista.
     * Si existe, reintenta hasta [CodeAlphabet.MAX_RETRIES] veces.
     * * @throws IllegalStateException si no logra generar un código único tras los reintentos.
     */
    suspend operator fun invoke(): String = withContext(Dispatchers.Default) {
        var retries = 0
        while (retries < CodeAlphabet.MAX_RETRIES) {
            val candidate = generateRandomCode()

            // La llamada a repository.exists() ya delega internamente a Dispatchers.IO
            if (!repository.exists(candidate)) {
                return@withContext candidate
            }
            retries++
        }

        // Falla de manera ruidosa. Si llegamos acá, estadísticamente hay un problema
        // grave en la base de datos o se agotaron las ~1M de combinaciones.
        throw IllegalStateException("Colisión extrema: no se pudo generar un código único tras ${CodeAlphabet.MAX_RETRIES} intentos.")
    }

    private fun generateRandomCode(): String {
        return (1..CodeAlphabet.CODE_LENGTH)
            .map { CodeAlphabet.ALLOWED_CHARACTERS.random() }
            .joinToString("")
    }
}