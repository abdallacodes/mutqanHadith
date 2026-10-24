package com.mutqan.hadith.domain.usecase

import com.mutqan.hadith.data.relation.HadithWithFullSanad
import com.mutqan.hadith.domain.repository.HadithRepository

/** Feeds the "new memorization" flow: Hadiths with no mastery record, in curriculum order. */
class GetUnseenHadithsUseCase(private val hadiths: HadithRepository) {
    suspend operator fun invoke(limit: Int? = null): List<HadithWithFullSanad> {
        val unseen = hadiths.getUnseen()
        return if (limit == null) unseen else unseen.take(limit)
    }
}
