package com.mutqan.hadith.ui.study

import com.mutqan.hadith.domain.pacing.DailyPacing
import com.mutqan.hadith.domain.usecase.StartMemorizingUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * "Take this hadith into my memorization", with the day's limit in front of it. Every screen that can start a hadith
 * does it through one of these, so the limit cannot be passed by coming in through another door: when enough new
 * hadiths were started today the request waits for the user's word ([asking]); otherwise it goes straight ahead.
 */
class PacedStart(
    private val hadithId: Long,
    private val startMemorizing: StartMemorizingUseCase,
    private val pacing: DailyPacing,
    private val scope: CoroutineScope,
) {
    enum class Status { IDLE, STARTING, FAILED }

    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _asking = MutableStateFlow(false)

    /** The day's new hadiths are already enough, and the user is being asked whether to go on. */
    val asking: StateFlow<Boolean> = _asking.asStateFlow()

    /** Asks to start the hadith: at once when the day has room, else after the user's answer (see [confirm]). */
    fun request(onStarted: (Long) -> Unit) {
        if (_status.value == Status.STARTING) return
        if (pacing.isOverLimit()) {
            _asking.value = true
            return
        }
        begin(onStarted)
    }

    /** The user chose to go on in spite of the day's limit. */
    fun confirm(onStarted: (Long) -> Unit) {
        _asking.value = false
        begin(onStarted)
    }

    /** The user left the hadith for another day. */
    fun dismiss() {
        _asking.value = false
    }

    /**
     * Takes the hadith into the user's memorization, then calls [onStarted] so the screen can open the learning
     * flow. A request already under way is not repeated; a failure is shown and can be retried.
     */
    private fun begin(onStarted: (Long) -> Unit) {
        if (_status.value == Status.STARTING) return
        _status.value = Status.STARTING
        scope.launch {
            try {
                startMemorizing(hadithId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _status.value = Status.FAILED
                return@launch
            }
            _status.value = Status.IDLE
            onStarted(hadithId)
        }
    }
}
