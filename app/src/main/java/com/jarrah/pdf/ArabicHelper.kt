package com.jarrah.pdf

import com.ibm.icu.text.ArabicShaping
import com.ibm.icu.text.Bidi
import java.io.File

object ArabicHelper {

    private val FONT_PATHS = listOf(
        "/system/fonts/NotoNaskhArabic-Regular.ttf",
        "/system/fonts/NotoNaskhArabicUI-Regular.ttf",
        "/system/fonts/DroidNaskh-Regular.ttf",
        "/system/fonts/DroidSansArabic.ttf",
        "/system/fonts/NotoSerifArabic-Regular.ttf"
    )

    fun findArabicFont(): File? =
        FONT_PATHS.map { File(it) }.firstOrNull { it.exists() && it.canRead() }

    fun prepareForPdf(text: String): String {
        if (text.isBlank()) return text
        if (text.none { it in '\u0600'..'\u06FF' }) return text
        val shaped = try {
            ArabicShaping(ArabicShaping.LETTERS_SHAPE).shape(text)
        } catch (_: Exception) { text }
        return try {
            val bidi = Bidi(shaped, Bidi.DIRECTION_DEFAULT_RIGHT_TO_LEFT)
            bidi.setReorderingMode(Bidi.REORDER_DEFAULT)
            bidi.writeReordered(0)
        } catch (_: Exception) { shaped }
    }
}
