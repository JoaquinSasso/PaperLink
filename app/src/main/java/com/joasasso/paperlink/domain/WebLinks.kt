package com.joasasso.paperlink.domain

private val URL_PATTERN = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
private val TRAILING_PUNCTUATION = ".,;:!?)]}".toSet()

/**
 * Links http(s) dentro de un texto compartido (por ejemplo, "Mirá esto https://youtu.be/x").
 * Se descarta la puntuación final que suele quedar pegada al link.
 */
fun extractUrls(text: String): List<String> =
    URL_PATTERN.findAll(text)
        .map { it.value.trimEnd { c -> c in TRAILING_PUNCTUATION } }
        .filter { it.length > "https://".length }
        .distinct()
        .toList()

/**
 * Normaliza un link escrito a mano: agrega `https://` si falta.
 * Devuelve null si no parece un link (sin punto o con espacios).
 */
fun normalizeUrl(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
    val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
    if (!withScheme.startsWith("http://", ignoreCase = true) && !withScheme.startsWith("https://", ignoreCase = true)) return null
    val host = withScheme.substringAfter("://").substringBefore('/')
    return if ('.' in host && !host.startsWith('.') && !host.endsWith('.')) withScheme else null
}
