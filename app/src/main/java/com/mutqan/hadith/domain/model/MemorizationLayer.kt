package com.mutqan.hadith.domain.model

/**
 * The layers a hadith is memorized in (التدرج بالطبقات): the chain and its wording are not asked for until the
 * core of the hadith holds. Which layer a hadith is in follows from its stabilities; see
 * [com.mutqan.hadith.domain.scheduler.SpacedRepetitionScheduler.layerStatus].
 *
 * - [LAYER_1_CORE] لبّ الحديث: the matn, its Companion and its takhrij. Where every hadith starts.
 * - [LAYER_2_SANAD_TREE] شبكة السند: the chain of narrators, and the drills on it.
 * - [LAYER_3_TRANSMISSION_TERMS] صيغ الأداء والضبط: the wording of each link (حدّثنا، أخبرنا، عن) and the weakest links.
 */
enum class MemorizationLayer(val number: Int) {
    LAYER_1_CORE(1),
    LAYER_2_SANAD_TREE(2),
    LAYER_3_TRANSMISSION_TERMS(3),
    ;

    /** The chain is studied and the Sanad drills are open. */
    val opensSanad: Boolean get() = this >= LAYER_2_SANAD_TREE

    /** The transmission terms are asked for and the narrators' reliability is shown. */
    val opensTerms: Boolean get() = this >= LAYER_3_TRANSMISSION_TERMS

    /** The layer to reach next, or null at the last one. */
    val next: MemorizationLayer? get() = entries.getOrNull(ordinal + 1)
}

/**
 * Where a hadith stands.
 * @property progress how far the memory is from opening the [MemorizationLayer.next] layer, 0..1; 1 at the last layer.
 */
data class LayerStatus(val layer: MemorizationLayer, val progress: Float) {
    companion object {
        /** A hadith not yet taken into study. */
        val Fresh = LayerStatus(MemorizationLayer.LAYER_1_CORE, 0f)
    }
}
