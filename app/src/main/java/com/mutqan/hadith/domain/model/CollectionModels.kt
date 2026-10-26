package com.mutqan.hadith.domain.model

/** A collection as the lists show it: what it is called and how many hadiths it holds. */
data class CollectionSummary(
    val id: Long,
    val title: String,
    val accent: Int,
    val createdAt: Long,
    val hadithCount: Int,
)

/** Why a collection's name was not accepted. */
enum class TitleProblem {
    /** Nothing but whitespace, or nothing at all. */
    EMPTY,

    /** Longer than [CollectionTitle.MAX_LENGTH]. */
    TOO_LONG,

    /** Another collection already has this name. */
    DUPLICATE,
}

/** How a collection's name is kept: trimmed, and with every run of spaces (of any kind) made a single space. */
object CollectionTitle {
    const val MAX_LENGTH = 40

    private val spaces = Regex("[\\p{Z}\\s]+")

    fun clean(raw: String): String = raw.replace(spaces, " ").trim()

    /** Whether a [cleaned] name shows anything: one made of zero-width marks or control characters would look empty. */
    fun isVisible(cleaned: String): Boolean =
        cleaned.any { !it.isWhitespace() && Character.getType(it) != Character.FORMAT.toInt() && !it.isISOControl() }
}

/** Which of the [COUNT] accent tones a collection is given; the screens map an index to a color. */
object CollectionAccent {
    const val COUNT = 6

    /** [index] if it names a tone, else the first one. */
    fun coerce(index: Int): Int = if (index in 0 until COUNT) index else 0
}

/** What came of creating or renaming a collection. */
sealed interface CollectionSaved {
    data class Saved(val id: Long) : CollectionSaved

    data class Rejected(val problem: TitleProblem) : CollectionSaved
}
