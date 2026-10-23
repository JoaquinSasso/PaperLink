package com.joasasso.paperlink.domain

import java.text.Normalizer

private val COMBINING_MARKS = Regex("\\p{Mn}+")

/** Texto para comparar en búsquedas: sin tildes, en minúsculas y sin espacios extra. */
fun normalizeForSearch(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(COMBINING_MARKS, "")
        .lowercase()
        .trim()
        .replace(Regex("\\s+"), " ")

/** true si [label] contiene [query], ignorando tildes y mayúsculas. Una consulta vacía no coincide. */
fun labelMatches(label: String?, query: String): Boolean {
    val normalizedQuery = normalizeForSearch(query)
    if (label == null || normalizedQuery.isEmpty()) return false
    return normalizedQuery in normalizeForSearch(label)
}
