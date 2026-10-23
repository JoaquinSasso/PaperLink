package com.joasasso.paperlink.domain

import kotlin.random.Random

/**
 * Define el alfabeto y las reglas de validación para los códigos de PaperLink.
 *
 * Alfabeto: A-Z + 2-9, sin los caracteres que se confunden al escribir a mano:
 * I/1, O/0, Z/2, S/5 y B/8 (se excluyen ambos miembros de cada par). Total: 26 caracteres.
 * Combinaciones posibles para 4 caracteres: 26^4 = 456.976.
 */
object CodeAlphabet {
    const val ALLOWED_CHARACTERS = "ACDEFGHJKLMNPQRTUVWXY34679"
    const val CODE_LENGTH = 4

    // Límite de seguridad para evitar loops infinitos en caso de colisiones extremas.
    const val MAX_RETRIES = 10

    /**
     * Genera un código al azar. No verifica unicidad: quien lo inserta debe reintentar
     * con otro si choca la clave primaria.
     */
    fun generate(random: Random = Random.Default): String =
        String(CharArray(CODE_LENGTH) { ALLOWED_CHARACTERS[random.nextInt(ALLOWED_CHARACTERS.length)] })

    /**
     * Valida si un string cumple con el formato exacto de un código PaperLink.
     */
    fun isValid(code: String): Boolean {
        if (code.length != CODE_LENGTH) return false
        return code.all { it in ALLOWED_CHARACTERS }
    }

    /**
     * Limpia lo que el usuario escribe o pega en el campo de código: mayúsculas, solo letras y
     * dígitos ASCII, a lo sumo [CODE_LENGTH]. Deja pasar caracteres fuera del alfabeto (O, 1…)
     * para poder avisar del error y sugerir códigos parecidos.
     */
    fun sanitizeInput(raw: String): String =
        raw.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }.take(CODE_LENGTH)

    /**
     * Normaliza un input de búsqueda ingresado por el usuario.
     */
    fun normalize(input: String): String {
        return input.uppercase().trim()
    }
}
