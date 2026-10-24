package com.mutqan.hadith.domain.model

import com.mutqan.hadith.data.entity.HadithEntity

/**
 * The three stages of the curriculum. [value] is what the database stores in `hadith.level`.
 *
 * - [FOUNDATION]: الأصول والأسس, the Forty Hadith of al-Nawawi: the matn, its Companion and its core takhrij.
 * - [AHKAM]: أحاديث الأحكام, Umdat al-Ahkam.
 * - [MUSNAD]: دواوين السنّة المسندة, Sahih al-Bukhari and Sahih Muslim, studied with their full chains.
 */
enum class HadithLevel(val value: Int) {
    FOUNDATION(1),
    AHKAM(2),
    MUSNAD(3),
    ;

    companion object {
        /** The level stored as [value], or null for a number that is not a level. */
        fun of(value: Int): HadithLevel? = entries.firstOrNull { it.value == value }
    }
}

/**
 * The order the curriculum is taught in: level by level, then by the number in the book, then by id.
 * The database queries that promise "curriculum order" sort the same way.
 */
val CurriculumOrder: Comparator<HadithEntity> = compareBy<HadithEntity> { it.level }.thenBy { it.hadithNumber }.thenBy { it.id }
