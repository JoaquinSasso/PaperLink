package com.joasasso.paperlink.domain

/**
 * Pares del alfabeto que se confunden al escribir a mano. Los más confundibles (I/1, O/0, Z/2,
 * S/5, B/8) ya están fuera del alfabeto.
 */
private val CONFUSABLE_PAIRS: Set<Set<Char>> = listOf("UV", "MN", "G6", "Q9", "T7", "A4")
    .map { it.toSet() }
    .toSet()

fun areConfusable(a: Char, b: Char): Boolean = setOf(a, b) in CONFUSABLE_PAIRS

/**
 * Códigos existentes parecidos a [input] (que no existe), para "¿quisiste decir…?".
 *
 * Un código es candidato si difiere de [input] en a lo sumo un carácter. Las posiciones de
 * [input] con caracteres fuera del alfabeto (por ejemplo una O o un 1) aceptan cualquier carácter.
 * Orden: primero los que solo difieren en comodines o en un par confundible, después el resto.
 */
fun suggestCodes(input: String, existing: Collection<String>, limit: Int = 5): List<String> {
    val typed = CodeAlphabet.normalize(input)
    if (typed.length != CodeAlphabet.CODE_LENGTH) return emptyList()
    val wildcards = typed.indices.filter { typed[it] !in CodeAlphabet.ALLOWED_CHARACTERS }
    if (wildcards.size > 2) return emptyList()

    return existing.asSequence()
        .filter { it.length == typed.length && it != typed }
        .mapNotNull { code ->
            val mismatches = typed.indices.filter { it !in wildcards && typed[it] != code[it] }
            when {
                mismatches.isEmpty() -> code to 0
                mismatches.size == 1 && wildcards.isEmpty() -> {
                    val i = mismatches.single()
                    code to if (areConfusable(typed[i], code[i])) 0 else 1
                }
                else -> null
            }
        }
        .sortedWith(compareBy<Pair<String, Int>> { it.second }.thenBy { it.first })
        .map { it.first }
        .take(limit)
        .toList()
}
