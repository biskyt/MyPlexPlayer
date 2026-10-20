package com.biskyt.mytvplayer.core.ui

/**
 * Everything the UI needs derived from one user-chosen accent colour (ARGB ints), computed once when it changes.
 * - [tint]: translucent accent for selected/focused backgrounds.
 * - [soft]: a lighter version for small text on dark surfaces.
 * - [onAccent]: black or white, whichever reads better on the accent.
 */
data class AccentPalette(val accent: Int, val tint: Int, val soft: Int, val onAccent: Int) {
    companion object {
        const val DEFAULT = 0xFF2BC4B0.toInt()
        private const val DARK_TEXT = 0xFF0F1114.toInt()
        private const val LIGHT_TEXT = 0xFFFFFFFF.toInt()
        private const val DARK_TEXT_LUMINANCE = 0.0056

        val presets: List<Pair<String, Int>> = listOf(
            "Mint" to DEFAULT,
            "Amber" to 0xFFF2A93B.toInt(),
            "Violet" to 0xFFA78BFA.toInt(),
            "Sky" to 0xFF5AA9F2.toInt(),
            "Coral" to 0xFFF2766B.toInt(),
            "Lime" to 0xFFA3D44A.toInt(),
            "Pink" to 0xFFF27BB8.toInt(),
            "Silver" to 0xFFC7CCD4.toInt()
        )

        fun from(argb: Int): AccentPalette {
            val accent = argb or (0xFF shl 24)
            val r = (accent shr 16) and 0xFF
            val g = (accent shr 8) and 0xFF
            val b = accent and 0xFF
            val tint = (0x29 shl 24) or (accent and 0xFFFFFF) // ~16% alpha
            fun lift(c: Int) = c + ((255 - c) * 0.35).toInt()
            val soft = (0xFF shl 24) or (lift(r) shl 16) or (lift(g) shl 8) or lift(b)
            // Pick whichever text colour has the higher WCAG contrast against the accent.
            val lum = relativeLuminance(r, g, b)
            val onAccent = if ((lum + 0.05) / (DARK_TEXT_LUMINANCE + 0.05) >= 1.05 / (lum + 0.05)) DARK_TEXT else LIGHT_TEXT
            return AccentPalette(accent, tint, soft, onAccent)
        }

        private fun relativeLuminance(r: Int, g: Int, b: Int): Double {
            fun lin(c: Int): Double = (c / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
            return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
        }
    }
}
