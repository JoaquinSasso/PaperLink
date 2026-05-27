package com.joasasso.paperlink.domain

/**
 * Define el alfabeto y las reglas de validación para los códigos de PaperLink.
 * * Alfabeto elegido: A-Z (sin I, O) + 2-9. Total: 32 caracteres.
 * Combinaciones posibles para 4 caracteres: 32^4 = 1.048.576.
 */
object CodeAlphabet {
    const val ALLOWED_CHARACTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    const val CODE_LENGTH = 4

    // Límite de seguridad para evitar loops infinitos en caso de colisiones extremas.
    const val MAX_RETRIES = 10

    /**
     * Valida si un string cumple con el formato exacto de un código PaperLink.
     */
    fun isValid(code: String): Boolean {
        if (code.length != CODE_LENGTH) return false
        return code.all { it in ALLOWED_CHARACTERS }
    }

    /**
     * Normaliza un input de búsqueda ingresado por el usuario.
     */
    fun normalize(input: String): String {
        return input.uppercase().trim()
    }
}