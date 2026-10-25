package com.mutqan.hadith.domain.narrator

/**
 * One person under every name the catalog gives him. [name] is the name the app shows and hints from; [spellings] are
 * all the forms the books and datasets write, [name] among them. A spelling is looked up by [NarratorNormalizer.key],
 * so differences of tashkeel, hamza, «ابن»/«بن», the case of «أبو» and a blessing after the name need no entry here.
 *
 * [isCompanion] groups are only applied to a narrator the catalog files as a Companion: a bare name such as «أنس» or
 * «عمر» is the Companion by the convention of the hadith books, but must not swallow a later narrator who shares it.
 */
data class AliasGroup(val name: String, val spellings: List<String>, val isCompanion: Boolean = true) {
    val key: String = NarratorNormalizer.key(name)
}

/**
 * The curated table of who is who across the Arba'in, 'Umdat al-Ahkam and Sahih al-Bukhari. The catalog writes the
 * same Companion as «ابن عمر», «عبد الله بن عمر» and «أبو عبد الرحمن عبد الله بن عمر بن الخطاب» depending on the book;
 * without this table each spelling is a separate narrator, with its own node in the network, its own tally of misses
 * and its own turn as a distractor.
 *
 * Every merge here rests on one of two grounds, and a name that rests on neither is left alone:
 * - the same kunya, ism or lineage in a longer or shorter form («أنس» / «أنس بن مالك» / «أبو حمزة أنس بن مالك»), or a
 *   bare name that is, by the convention of the hadith books, one Companion («أبو موسى» is al-Ash'ari, «حذيفة» is Ibn
 *   al-Yaman, «عبد الله بن عمرو» is Ibn al-'As: the hadiths of 'Umdat al-Ahkam that carry it are his);
 * - the same position in parallel chains of the same hadith: Sahih al-Bukhari 1 and 54 both run «يحيى بن سعيد ← محمد
 *   بن إبراهيم ← علقمة بن وقاص ← عمر», so the two spellings of each link name one man.
 *
 * Deliberately **not** merged, though they look alike: «عبد الله بن عمر» and «عبد الله بن عمرو» (two Companions);
 * «سفيان» of a Bukhari chain and the Companion «أبو عمرو سفيان بن عبد الله»; the bare «يحيى» and «عبد الله» of the
 * Bukhari chains, which name no one without their book's context; «همام بن منبه» and «همام بن الحارث»; «عقبة بن
 * عامر» and «عقبة بن الحارث».
 */
object NarratorAliases {

    val groups: List<AliasGroup> = listOf(
        AliasGroup("أبو هريرة", listOf("أبو هريرة", "أبو هريرة عبد الرحمن بن صخر")),
        AliasGroup(
            "عبد الله بن عمر",
            listOf("عبد الله بن عمر", "ابن عمر", "أبو عبد الرحمن عبد الله بن عمر بن الخطاب", "عبد الله بن عمر بن الخطاب"),
        ),
        AliasGroup("عمر بن الخطاب", listOf("عمر بن الخطاب", "عمر", "أمير المؤمنين أبي حفص عمر بن الخطاب")),
        AliasGroup("عبد الله بن عباس", listOf("عبد الله بن عباس", "ابن عباس")),
        AliasGroup("أنس بن مالك", listOf("أنس بن مالك", "أنس", "أبو حمزة أنس بن مالك")),
        AliasGroup("عائشة", listOf("عائشة", "أم المؤمنين أم عبد الله عائشة")),
        AliasGroup(
            "جابر بن عبد الله",
            listOf("جابر بن عبد الله", "أبو عبد الله جابر بن عبد الله الأنصاري", "جابر بن عبد الله الأنصاري"),
        ),
        AliasGroup("أبو سعيد الخدري", listOf("أبو سعيد الخدري", "أبو سعيد سعد بن مالك بن سنان الخدري")),
        AliasGroup("عبد الله بن مسعود", listOf("عبد الله بن مسعود", "ابن مسعود", "أبو عبد الرحمن عبد الله بن مسعود")),
        AliasGroup("النعمان بن بشير", listOf("النعمان بن بشير", "أبو عبد الله النعمان بن بشير")),
        AliasGroup(
            "عبد الله بن عمرو بن العاص",
            listOf("عبد الله بن عمرو بن العاصي", "عبد الله بن عمرو بن العاص", "أبو محمد عبد الله بن عمرو بن العاص", "عبد الله بن عمرو"),
        ),
        AliasGroup("أبو مسعود الأنصاري", listOf("أبو مسعود الأنصاري", "أبو مسعود عقبة بن عمرو الأنصاري البدري")),
        AliasGroup("أبو موسى الأشعري", listOf("أبو موسى الأشعري", "أبو موسى", "أبو موسى عبد الله بن قيس")),
        AliasGroup(
            "أبو قتادة الأنصاري",
            listOf("أبو قتادة الأنصاري", "أبو قتادة الحارث بن ربعي الأنصاري", "أبو قتادة بن ربعي الأنصاري"),
        ),
        AliasGroup("أبو ذر الغفاري", listOf("أبو ذر الغفاري", "أبو ذر", "أبو ذر جندب بن جنادة")),
        AliasGroup("عبد الله بن زيد بن عاصم", listOf("عبد الله بن زيد بن عاصم", "عبد الله بن زيد بن عاصم المازني")),
        AliasGroup("أم عطية الأنصارية", listOf("أم عطية الأنصارية", "أم عطية نسيبة الأنصارية", "أم عطية")),
        AliasGroup("سهل بن سعد الساعدي", listOf("سهل بن سعد الساعدي", "أبو العباس سهل بن سعد الساعدي")),
        AliasGroup("أبو ثعلبة الخشني", listOf("أبو ثعلبة الخشني", "أبو ثعلبة الخشني جرثوم بن ناشر")),
        AliasGroup("عبد الله بن مالك ابن بحينة", listOf("عبد الله بن مالك ابن بحينة", "عبد الله ابن بحينة")),
        AliasGroup("حذيفة بن اليمان", listOf("حذيفة بن اليمان", "حذيفة")),
        AliasGroup("سلمة بن الأكوع", listOf("سلمة بن الأكوع", "سلمة ابن الأكوع")),

        // The links of the Bukhari chains that two of its hadiths share.
        AliasGroup("الزهري", listOf("الزهري", "ابن شهاب"), isCompanion = false),
        AliasGroup("يحيى بن سعيد الأنصاري", listOf("يحيى بن سعيد الأنصاري", "يحيى بن سعيد"), isCompanion = false),
        AliasGroup("محمد بن إبراهيم التيمي", listOf("محمد بن إبراهيم التيمي", "محمد بن إبراهيم"), isCompanion = false),
        AliasGroup("علقمة بن وقاص الليثي", listOf("علقمة بن وقاص الليثي", "علقمة بن وقاص"), isCompanion = false),
        AliasGroup(
            "عبيد الله بن عبد الله بن عتبة بن مسعود",
            listOf("عبيد الله بن عبد الله بن عتبة بن مسعود", "عبيد الله بن عبد الله"),
            isCompanion = false,
        ),
    )

    private val byKey: Map<String, AliasGroup> = buildMap {
        for (group in groups) {
            for (spelling in group.spellings + group.name) {
                val key = NarratorNormalizer.key(spelling)
                val earlier = put(key, group)
                require(earlier == null || earlier === group) { "«$spelling» is spelled the same in «${earlier?.name}» and «${group.name}»" }
            }
        }
    }

    /**
     * Given names that name no one by themselves: the bare «سفيان» of a Bukhari chain is al-Thawri or Ibn 'Uyaynah,
     * «يحيى» al-Qattan or Ibn Sa'id, and the book's context is all that says which. The catalog keeps them as the
     * rows it has, so a question that asks "who is this?" has no one answer for them and must not use them.
     */
    private val ambiguousBareNames: Set<String> =
        listOf("سفيان", "يحيى", "عبد الله", "محمد").mapTo(mutableSetOf(), NarratorNormalizer::key)

    /** Whether [raw] is a bare given name that fits several narrators; see [ambiguousBareNames]. */
    fun isAmbiguous(raw: String): Boolean = NarratorNormalizer.key(raw) in ambiguousBareNames

    /** The group [raw] belongs to, whatever its tashkeel, hamza or grammatical case; null for a name nobody merges. */
    fun groupOf(raw: String): AliasGroup? = byKey[NarratorNormalizer.key(raw)]

    /**
     * The one name to show and to hint from for [raw], as it may be written in a narration or a dataset: the table's
     * name when it is a known person, else the name alone, without a «عن», title, blessing or tashkeel.
     */
    fun canonicalName(raw: String): String = groupOf(raw)?.name ?: NarratorNormalizer.displayName(raw)
}
