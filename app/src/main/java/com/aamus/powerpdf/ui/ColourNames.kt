package com.aamus.powerpdf.ui

import androidx.annotation.StringRes
import com.aamus.powerpdf.R

/**
 * A plain name for a colour swatch, so a screen reader says "Red" instead of nothing (colour
 * buttons had no label: uiautomator NAF; decisions §43). Pure, so it can be unit-tested.
 */
enum class ColourWord(@StringRes val label: Int) {
    Black(R.string.colour_black), White(R.string.colour_white), Grey(R.string.colour_grey),
    Red(R.string.colour_red), Orange(R.string.colour_orange), Brown(R.string.colour_brown),
    Yellow(R.string.colour_yellow), Green(R.string.colour_green), LightBlue(R.string.colour_light_blue),
    Blue(R.string.colour_blue), Violet(R.string.colour_violet), Pink(R.string.colour_pink);

    companion object {
        /** The nearest everyday name for an ARGB colour (alpha ignored). */
        fun of(argb: Int): ColourWord {
            val r = (argb shr 16 and 255) / 255f
            val g = (argb shr 8 and 255) / 255f
            val b = (argb and 255) / 255f
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val chroma = max - min
            val light = (max + min) / 2
            if (chroma < 0.12f) return when {
                light < 0.22f -> Black
                light > 0.9f -> White
                else -> Grey
            }
            val hue = when (max) {
                r -> 60 * (((g - b) / chroma) % 6)
                g -> 60 * ((b - r) / chroma + 2)
                else -> 60 * ((r - g) / chroma + 4)
            }.let { if (it < 0) it + 360 else it }
            return when {
                hue >= 345 || hue < 15 -> if (light > 0.75f) Pink else Red
                hue < 45 -> if (max < 0.6f) Brown else Orange
                hue < 70 -> Yellow
                hue < 170 -> Green
                hue < 205 -> LightBlue
                hue < 250 -> if (light > 0.7f) LightBlue else Blue
                hue < 290 -> Violet
                else -> Pink
            }
        }
    }
}
