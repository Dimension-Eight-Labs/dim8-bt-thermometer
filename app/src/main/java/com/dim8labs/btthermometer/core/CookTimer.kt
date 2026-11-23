package com.dim8labs.btthermometer.core

/**
 * Persisted timer state. While running, total elapsed time is
 * `accumulatedMs + (now - anchorElapsedMs)` on the boot named by [bootCount].
 */
data class CookTimerState(
    val running: Boolean = false,
    val accumulatedMs: Long = 0,
    val anchorElapsedMs: Long = 0,
    val bootCount: Int = 0,
)

/**
 * Elapsed cook timer using monotonic time, so wall-clock changes cannot alter it.
 *
 * Reboot policy (architecture question 6, pending owner confirmation): a timer
 * running across a reboot stops at its last checkpoint and excludes powered-off
 * time. The user can press Start to resume.
 */
class CookTimer(saved: CookTimerState, nowElapsedMs: Long, private val bootCount: Int) {
    var state: CookTimerState = restore(saved, nowElapsedMs, bootCount)
        private set

    val running: Boolean get() = state.running

    fun elapsedMs(nowElapsedMs: Long): Long =
        if (state.running) {
            state.accumulatedMs + (nowElapsedMs - state.anchorElapsedMs).coerceAtLeast(0)
        } else {
            state.accumulatedMs
        }

    fun start(nowElapsedMs: Long) {
        if (state.running) return
        state = state.copy(running = true, anchorElapsedMs = nowElapsedMs, bootCount = bootCount)
    }

    fun stop(nowElapsedMs: Long) {
        if (!state.running) return
        state = CookTimerState(false, elapsedMs(nowElapsedMs), 0, bootCount)
    }

    fun reset() {
        state = CookTimerState(bootCount = bootCount)
    }

    /** Folds running time into the saved total to limit loss at an unexpected reboot. */
    fun checkpoint(nowElapsedMs: Long) {
        if (!state.running) return
        state = state.copy(accumulatedMs = elapsedMs(nowElapsedMs), anchorElapsedMs = nowElapsedMs)
    }

    private companion object {
        fun restore(saved: CookTimerState, nowElapsedMs: Long, bootCount: Int): CookTimerState {
            val sameBoot = saved.bootCount == bootCount && nowElapsedMs >= saved.anchorElapsedMs
            return if (saved.running && !sameBoot) {
                CookTimerState(false, saved.accumulatedMs, 0, bootCount)
            } else {
                saved
            }
        }
    }
}
