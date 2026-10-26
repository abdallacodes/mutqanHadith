package com.mutqan.hadith.domain.model

import com.mutqan.hadith.data.entity.HadithEntity

/**
 * The order the catalog is taught in: by the position the catalog gives each hadith, which runs book by book and section
 * by section; the number in the book and then the id only break a tie, for a hadith the catalog has not numbered.
 * The database queries that promise "curriculum order" sort the same way.
 */
val CurriculumOrder: Comparator<HadithEntity> = compareBy<HadithEntity> { it.position }.thenBy { it.hadithNumber }.thenBy { it.id }
