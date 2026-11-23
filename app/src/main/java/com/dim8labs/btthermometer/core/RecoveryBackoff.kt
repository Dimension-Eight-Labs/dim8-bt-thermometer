package com.dim8labs.btthermometer.core

/**
 * Bounded exponential scan-restart delay. The 10 s floor stays clear of
 * Android's limit of about five scan starts per 30 s, beyond which scans are
 * silently suppressed. Delays are PROVISIONAL until recovery is physically tested.
 */
class RecoveryBackoff(private val baseMs: Long = 10_000, private val maxMs: Long = 120_000) {
    private var nextMs = baseMs

    fun next(): Long {
        val current = nextMs
        nextMs = (nextMs * 2).coerceAtMost(maxMs)
        return current
    }

    fun reset() {
        nextMs = baseMs
    }
}
