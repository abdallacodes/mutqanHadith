package com.mutqan.hadith.domain.narrator

import com.mutqan.hadith.data.text.ArabicNormalizer

/**
 * Reads a narrator's name the way it is written in a narration and says who it names, so that «عن أبي هريرة رضي الله
 * عنه» and «أبو هريرة» are one name. Three layers, each a plain function of the text:
 *
 * - [parse] takes the name apart: what the narration wraps around it (the «عن» before, the blessing after, a title in
 *   front) is set aside, and the head noun of a kunya is put in the nominative («أبي» -> «أبو»);
 * - [displayName] is the name that is left, for showing;
 * - [key] is the form two spellings of one name share: no tashkeel, one hamza, «ابن» as «بن», «عبدالله» as «عبد الله».
 *
 * Which different names are one person is not decided here: that is the curated [NarratorAliases] table. A key only
 * makes the lookup in it tolerant of spelling and grammar.
 */
object NarratorNormalizer {

    /**
     * A name taken apart. [core] is the name itself, [titles] the honorific titles that stood in front of it («أمير
     * المؤمنين», «أم المؤمنين») and [blessing] the formula of blessing that followed, e.g. «رضي الله عنهما»: kept as
     * display metadata, never part of the name.
     */
    data class ParsedName(val core: String, val titles: List<String>, val blessing: String)

    private val PUNCTUATION = Regex("[()\\[\\]{}«»\"'“”‘’،,؛;:.!؟?*\\-–—_/\\\\]")
    private val WHITESPACE = Regex("\\s+")

    /** Titles that may stand in front of a name, as the words they are made of. */
    private val TITLES = listOf(
        "أمير المؤمنين", "أم المؤمنين", "الصحابي الجليل", "الصحابية الجليلة", "سيدنا", "السيدة", "الإمام", "الحافظ",
    ).map { title -> title.split(" ").map(::fold) to title }

    private val NARRATION_PREFIXES = setOf("عن", "وعن")

    private val BLESSING_VERBS = setOf("رضي")
    private val BLESSING_OBJECTS = setOf("عنه", "عنها", "عنهما", "عنهم", "عنهن")
    private val MERCY_VERBS = setOf("رحمه", "رحمها", "رحمهما", "رحمهم")

    private val KUNYA_NOUNS = mapOf("ابي" to "أبو", "ابا" to "أبو", "اخي" to "أخو", "اخا" to "أخو")
    private val LINEAGE = setOf("بن", "ابن")

    /** The form of a word that ignores tashkeel and the ways one letter can be written. */
    private fun fold(word: String): String = ArabicNormalizer.normalize(word)

    fun parse(raw: String): ParsedName {
        val words = ArabicNormalizer.stripDiacritics(raw).replace(PUNCTUATION, " ")
            .split(WHITESPACE).filter { it.isNotEmpty() }.toMutableList()

        if (words.isNotEmpty() && fold(words.first()) in NARRATION_PREFIXES) words.removeAt(0)

        val titles = mutableListOf<String>()
        while (true) {
            val (folded, title) = TITLES.firstOrNull { (parts, _) -> startsWith(words, parts) } ?: break
            titles += title
            repeat(folded.size) { words.removeAt(0) }
        }

        val blessing = trailingBlessing(words)
        repeat(blessing.size) { words.removeAt(words.lastIndex) }

        return ParsedName(nominativeHead(words).joinToString(" "), titles, blessing.joinToString(" "))
    }

    /** [raw] as the name alone, ready to show: without a narration's «عن», title or blessing, and without tashkeel. */
    fun displayName(raw: String): String = parse(raw).core

    /** What every spelling of one name has in common; blank when the text holds no name. */
    fun key(raw: String): String {
        val words = fold(parse(raw).core).split(" ").filter { it.isNotEmpty() }
        return words.flatMapIndexed { i, word ->
            when {
                word == "ابن" -> listOf("بن")
                word in KUNYA_NOUNS && !isLineageNext(words, i) -> listOf(KUNYA_NOUNS.getValue(word).let(::fold))
                // «عبدالله», «عبدالرحمن»: the servant-of compound written as one word.
                word.length > 5 && word.startsWith("عبدال") -> listOf("عبد", word.removePrefix("عبد"))
                else -> listOf(word)
            }
        }.joinToString(" ")
    }

    private fun startsWith(words: List<String>, parts: List<String>) =
        words.size > parts.size && parts.indices.all { fold(words[it]) == parts[it] }

    /** The words of a blessing that end [words], if any: «رضي الله عنه», «رضي الله تعالى عنهما», «رحمه الله». */
    private fun trailingBlessing(words: List<String>): List<String> {
        val folded = words.map(::fold)
        val n = folded.size
        // A name is never only a blessing: at least one word must stay.
        fun endsWith(vararg slots: Set<String>): Boolean =
            n > slots.size && slots.indices.all { folded[n - slots.size + it] in slots[it] }
        val allah = setOf("الله")
        val most = setOf("تعالى", "تعالي")
        return when {
            endsWith(BLESSING_VERBS, allah, most, BLESSING_OBJECTS) -> words.takeLast(4)
            endsWith(BLESSING_VERBS, allah, BLESSING_OBJECTS) -> words.takeLast(3)
            endsWith(MERCY_VERBS, allah, most) -> words.takeLast(3)
            endsWith(MERCY_VERBS, allah) -> words.takeLast(2)
            else -> emptyList()
        }
    }

    /**
     * A name that begins with the noun «أب» or «أخ» in the genitive or accusative («عن أبي هريرة», «سمعت أبا هريرة»)
     * is the nominative name, «أبو هريرة». «أبي بن كعب» is another matter: there «أبي» is the Companion's own name.
     */
    private fun nominativeHead(words: List<String>): List<String> {
        val head = words.firstOrNull() ?: return words
        val nominative = KUNYA_NOUNS[fold(head)] ?: return words
        if (words.size < 2 || isLineageNext(words.map(::fold), 0)) return words
        return listOf(nominative) + words.drop(1)
    }

    private fun isLineageNext(foldedWords: List<String>, index: Int) = foldedWords.getOrNull(index + 1) in LINEAGE
}
