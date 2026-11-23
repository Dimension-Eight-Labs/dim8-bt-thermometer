package com.dim8labs.btthermometer.core

/**
 * Fixed-size history for one probe. Each time bucket keeps its first accepted
 * sample, so advertisement bursts cannot grow storage or overweight a period.
 * The exception is a later excursion in a normal bucket: it replaces that sample,
 * so a brief threshold crossing inside a 10-second bucket is not lost.
 * Slots are tagged with their bucket, so old or future data after a wall-clock
 * change is excluded rather than drawn as current.
 */
class HistoryBuffer(private val size: Int = MonitoringPolicy.HISTORY_BUCKETS) {
    private val buckets = LongArray(size) { EMPTY }
    private val values = IntArray(size)
    private val excursions = Array(size) { Excursion.NONE }

    /** Returns true when the stored sample for [bucket] was created or replaced. */
    fun add(bucket: Long, centiC: Int, excursion: Excursion = Excursion.NONE): Boolean {
        val slot = Math.floorMod(bucket, size.toLong()).toInt()
        val isRepeat = buckets[slot] == bucket
        if (isRepeat && (excursions[slot] != Excursion.NONE || excursion == Excursion.NONE)) return false
        buckets[slot] = bucket
        values[slot] = centiC
        excursions[slot] = excursion
        return true
    }

    /** Parallel to [window]. Empty slots report [Excursion.NONE]. */
    fun excursionWindow(newestBucket: Long): List<Excursion> = List(size) { index ->
        val bucket = newestBucket - (size - 1) + index
        val slot = Math.floorMod(bucket, size.toLong()).toInt()
        if (buckets[slot] == bucket) excursions[slot] else Excursion.NONE
    }

    /** Returns the window ending at [newestBucket], oldest first, with `null` gaps. */
    fun window(newestBucket: Long): List<Int?> = List(size) { index ->
        val bucket = newestBucket - (size - 1) + index
        val slot = Math.floorMod(bucket, size.toLong()).toInt()
        if (buckets[slot] == bucket) values[slot] else null
    }

    fun clear() = buckets.fill(EMPTY)

    private companion object {
        const val EMPTY = Long.MIN_VALUE
    }
}
