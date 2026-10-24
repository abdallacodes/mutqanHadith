package com.mutqan.hadith.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mutqan.hadith.R

/** Naskh face with tall ascenders/descenders, so full tashkeel is never clipped. */
val AmiriFamily = FontFamily(
    Font(R.font.amiri_regular, FontWeight.Normal),
    Font(R.font.amiri_bold, FontWeight.Bold),
)

/** Hadith text and Quranic quotes: generous line height keeps stacked diacritics apart. */
val MatnTextStyle = TextStyle(
    fontFamily = AmiriFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 24.sp,
    lineHeight = 46.sp,
)

val MatnCompactTextStyle = MatnTextStyle.copy(fontSize = 19.sp, lineHeight = 36.sp)

/** Narrator names in chains. */
val NarratorTextStyle = TextStyle(
    fontFamily = AmiriFamily,
    fontWeight = FontWeight.Bold,
    fontSize = 19.sp,
    lineHeight = 32.sp,
)

// UI chrome stays on the system Arabic face; only titles borrow Amiri for the Islamic feel.
val Typography = Typography().let { base ->
    base.copy(
        headlineMedium = base.headlineMedium.copy(fontFamily = AmiriFamily, fontWeight = FontWeight.Bold, lineHeight = 40.sp),
        headlineSmall = base.headlineSmall.copy(fontFamily = AmiriFamily, fontWeight = FontWeight.Bold, lineHeight = 36.sp),
        titleLarge = base.titleLarge.copy(fontFamily = AmiriFamily, fontWeight = FontWeight.Bold, lineHeight = 34.sp),
        titleMedium = base.titleMedium.copy(lineHeight = 26.sp),
        bodyLarge = base.bodyLarge.copy(lineHeight = 28.sp),
        bodyMedium = base.bodyMedium.copy(lineHeight = 24.sp),
        labelLarge = base.labelLarge.copy(lineHeight = 22.sp),
        labelMedium = base.labelMedium.copy(lineHeight = 20.sp),
    )
}
