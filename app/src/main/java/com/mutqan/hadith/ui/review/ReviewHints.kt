package com.mutqan.hadith.ui.review

import com.mutqan.hadith.data.text.ArabicNormalizer

/** What a hint gives away about a layer 1 hadith, in the order the hints are given. */
enum class HintKind {
    /** The first letter of the Companion's name. */
    COMPANION,

    /** The takhrij: which collections hold the hadith. */
    TAKHRIJ,
}

/** The hints this card has, in order: the Companion if it names one, then the takhrij if it has one. */
val ReviewCard.hintKinds: List<HintKind>
    get() = buildList {
        if (sahabi.isNotBlank()) add(HintKind.COMPANION)
        if (takhrij.isNotBlank()) add(HintKind.TAKHRIJ)
    }

private const val NON_JOINING_LETTERS = "اأإآدذرزوؤةىء"
private const val TATWEEL = "ـ"
private const val ELLIPSIS = "…"

/**
 * The start of a Companion's name, enough to jog the memory and no more: its first letter, joined to the dots that
 * stand for the rest, as «عـ…» for «عمر بن الخطاب». A letter that does not join to the next is left bare.
 */
fun companionHint(name: String): String {
    val first = ArabicNormalizer.stripDiacritics(name).firstOrNull { it.isLetter() } ?: return ""
    val joins = first in '؀'..'ۿ' && first !in NON_JOINING_LETTERS
    return first + (if (joins) TATWEEL else "") + ELLIPSIS
}
