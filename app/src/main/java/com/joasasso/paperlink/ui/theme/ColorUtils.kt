package com.joasasso.paperlink.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.absoluteValue

object ColorUtils {
    // Paleta de colores estéticos para los degradados (Material 3 Soft Tones)
    private val aestheticPalette = listOf(
        Color(0xFF6750A4), // Purple
        Color(0xFF984061), // Raspberry
        Color(0xFF3B6470), // Blue-Grey
        Color(0xFF006A6A), // Teal
        Color(0xFF4B6043), // Forest
        Color(0xFF914D00), // Orange/Brown
        Color(0xFF7D5260), // Mauve
        Color(0xFF005AC1), // Blue
        Color(0xFF625B71), // Deep Purple Grey
        Color(0xFF7E5260)  // Rose
    )

    /**
     * Genera dos colores estéticos basados en un código de 4 caracteres.
     * Es determinista: el mismo código siempre devuelve los mismos colores.
     */
    fun getAestheticColors(code: String): List<Color> {
        val hash = code.hashCode()
        val index1 = (hash.absoluteValue % aestheticPalette.size)
        // Usamos un offset para el segundo color
        val index2 = ((hash.absoluteValue + 31) % aestheticPalette.size)
        
        // Si por casualidad son iguales, forzamos un offset diferente
        val finalIndex2 = if (index1 == index2) (index2 + 1) % aestheticPalette.size else index2
        
        return listOf(aestheticPalette[index1], aestheticPalette[finalIndex2])
    }
}
