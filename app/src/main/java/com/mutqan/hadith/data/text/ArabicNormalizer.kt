package com.mutqan.hadith.data.text

/** Produces the diacritic-free, orthographically unified form used for Arabic search. */
object ArabicNormalizer {

    private val DIACRITICS = Regex("[\\u0610-\\u061A\\u064B-\\u065F\\u0670\\u06D6-\\u06ED\\u0640]")
    private val WHITESPACE = Regex("\\s+")

    fun normalize(text: String): String =
        text
            .replace(DIACRITICS, "")
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')
            .replace('ٱ', 'ا')
            .replace('ى', 'ي')
            .replace('ة', 'ه')
            .replace(WHITESPACE, " ")
            .trim()
}
