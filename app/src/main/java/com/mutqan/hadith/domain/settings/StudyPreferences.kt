package com.mutqan.hadith.domain.settings

import com.mutqan.hadith.domain.model.StartingPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The learner's own choices about how they study, kept from one launch to the next. */
interface StudyPreferences {
    /**
     * "Advanced student": the Sanad tree and the transmission terms are open on every hadith in study, whatever
     * its stabilities say. Off by default, so the layers open one after the other as the memory holds.
     */
    val unlockAllLayers: StateFlow<Boolean>

    fun setUnlockAllLayers(enabled: Boolean)

    /** What the learner said about where they start in the Forty Hadith; null until they have been asked. */
    val startingPoint: StateFlow<StartingPoint?>

    fun setStartingPoint(point: StartingPoint)

    /** How many new hadiths were taken into study on the local calendar day [epochDay]. */
    fun newStartsOn(epochDay: Long): Int

    fun recordNewStart(epochDay: Long)
}

/** [StudyPreferences] that live as long as the process; the default for code that is not given the stored ones. */
class InMemoryStudyPreferences(unlockAllLayers: Boolean = false, startingPoint: StartingPoint? = null) : StudyPreferences {
    private val unlockAll = MutableStateFlow(unlockAllLayers)
    private val point = MutableStateFlow(startingPoint)
    private val starts = mutableMapOf<Long, Int>()

    override val unlockAllLayers: StateFlow<Boolean> = unlockAll.asStateFlow()

    override fun setUnlockAllLayers(enabled: Boolean) {
        unlockAll.value = enabled
    }

    override val startingPoint: StateFlow<StartingPoint?> = point.asStateFlow()

    override fun setStartingPoint(point: StartingPoint) {
        this.point.value = point
    }

    override fun newStartsOn(epochDay: Long): Int = starts[epochDay] ?: 0

    override fun recordNewStart(epochDay: Long) {
        starts[epochDay] = newStartsOn(epochDay) + 1
    }
}
