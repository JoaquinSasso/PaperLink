package com.joasasso.paperlink.domain

/** Cuántas etiquetas sugerir como máximo. */
const val LABEL_SUGGESTION_LIMIT = 8

/**
 * Etiquetas ya usadas para sugerir mientras se escribe [query] (al buscar o al etiquetar un código).
 *
 * - [labels]: etiquetas de los códigos vigentes, de la más reciente a la más vieja (con repetidas).
 * - Las variantes que solo cambian en tildes, mayúsculas o espacios ("Física" / "fisica ") cuentan
 *   como una sola; se muestra la grafía más reciente.
 * - Con [query] vacía se sugieren las más usadas. Si no, las que la contienen (sin tildes ni
 *   mayúsculas): primero las que empiezan con lo escrito, después las más usadas y las más recientes.
 * - Nunca se sugiere lo que ya está escrito tal cual.
 */
fun suggestLabels(labels: List<String>, query: String, limit: Int = LABEL_SUGGESTION_LIMIT): List<String> {
    val normalizedQuery = normalizeForSearch(query)
    val groups = LinkedHashMap<String, LabelGroup>()
    labels.forEachIndexed { index, label ->
        val key = normalizeForSearch(label)
        if (key.isEmpty()) return@forEachIndexed
        val group = groups.getOrPut(key) { LabelGroup(label.trim(), firstIndex = index) }
        group.count++
    }
    return groups.entries
        .filter { (key, _) -> key != normalizedQuery && normalizedQuery in key }
        .sortedWith(
            compareByDescending<Map.Entry<String, LabelGroup>> { (key, _) -> key.startsWith(normalizedQuery) }
                .thenByDescending { it.value.count }
                .thenBy { it.value.firstIndex }
        )
        .take(limit)
        .map { it.value.display }
}

private class LabelGroup(val display: String, val firstIndex: Int) {
    var count = 0
}
