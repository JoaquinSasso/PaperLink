package com.joasasso.paperlink.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Paleta fija de colores para materias. Se guarda el hex en `Subject.colorHex`.
 * Una paleta cerrada (en vez de un picker HSV libre) mantiene consistencia visual
 * y es más rápida de usar — alineado con "velocidad sobre estética".
 */
val SubjectColorPalette: List<String> = listOf(
    "#E53935", // rojo
    "#D81B60", // rosa
    "#8E24AA", // púrpura
    "#5E35B1", // violeta
    "#3949AB", // índigo
    "#1E88E5", // azul
    "#039BE5", // celeste
    "#00ACC1", // cian
    "#00897B", // teal
    "#43A047", // verde
    "#7CB342", // lima
    "#FDD835", // amarillo
    "#FB8C00", // naranja
    "#6D4C41", // marrón
    "#546E7A"  // gris azulado
)

const val DEFAULT_SUBJECT_COLOR = "#1E88E5"

/**
 * Convierte un hex ("#RRGGBB" o "#AARRGGBB") a [Color] de Compose.
 * Si el string es inválido, cae al color por defecto en lugar de crashear.
 */
fun parseSubjectColor(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (e: IllegalArgumentException) {
    Color(android.graphics.Color.parseColor(DEFAULT_SUBJECT_COLOR))
}
