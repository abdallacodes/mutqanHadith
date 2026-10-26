package com.mutqan.hadith.data.relation

/** Where a hadith is filed: just enough of it to count a group's hadiths and to roll them up through its sections. */
data class HadithPlacement(
    val hadithId: Long,
    /** The group the hadith is filed under (`hadith.groupId`); null for one the catalog has not placed. */
    val groupId: Long?,
)
