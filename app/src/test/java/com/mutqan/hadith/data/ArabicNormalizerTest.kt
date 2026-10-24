package com.mutqan.hadith.data

import com.mutqan.hadith.data.text.ArabicNormalizer
import org.junit.Assert.assertEquals
import org.junit.Test

class ArabicNormalizerTest {
    @Test
    fun stripsTashkeelAndUnifiesLetters() {
        assertEquals("انما الاعمال بالنيات", ArabicNormalizer.normalize("إِنَّمَا الْأَعْمَالُ بِالنِّيَّاتِ"))
        assertEquals("الحلال بين", ArabicNormalizer.normalize("  الْحَلاَلُ   بَيِّنٌ "))
        assertEquals("مستوي", ArabicNormalizer.normalize("مُسْتَوَى"))
        assertEquals("صلاه", ArabicNormalizer.normalize("صَلَاة"))
    }
}
