package com.joasasso.paperlink.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Paleta fija de colores para la categorización de materias.
 * Al ser una app offline-first, es mucho más eficiente tener los colores
 * predefinidos localmente que usar un ColorPicker dinámico completo.
 */
object SubjectColors {
    val palette = listOf(
        "#EF5350", // Rojo
        "#EC407A", // Rosa
        "#AB47BC", // Morado
        "#7E57C2", // Púrpura profundo
        "#5C6BC0", // Índigo
        "#42A5F5", // Azul
        "#29B6F6", // Azul claro
        "#26C6DA", // Cian
        "#26A69A", // Teal
        "#66BB6A", // Verde
        "#9CCC65", // Verde claro
        "#D4E157", // Lima
        "#FFEE58", // Amarillo
        "#FFA726", // Naranja
        "#FF7043", // Naranja profundo
        "#8D6E63", // Marrón
        "#BDBDBD", // Gris
        "#78909C"  // Azul grisáceo
    )
}

val SubjectColorPalette = SubjectColors.palette
val DEFAULT_SUBJECT_COLOR = SubjectColorPalette.first()

/**
 * Helper para convertir el String hexadecimal de la base de datos
 * a un objeto [Color] nativo de Jetpack Compose de forma segura.
 */
fun parseSubjectColor(colorHex: String): Color {
    return try {
        Color(android.graphics.Color.parseColor(colorHex))
    } catch (e: Exception) {
        // Fallback seguro: si por algún error en base de datos llega un color mal formado,
        // devolvemos gris en lugar de crashear la aplicación.
        Color.Gray
    }
}