package com.dim8labs.btthermometer.core

/** Applies model identity and physical-range rules to decoded fields. */
object ReadingValidator {
    private const val MODEL_NAME_PREFIX = "GVH5182"

    /**
     * The 0x8251 service UUID or GVH5182 name identifies the model. A present
     * name for another model wins, because H5184 shares the 17-byte shape.
     */
    fun isH5182(name: String?, hasServiceUuid: Boolean): Boolean =
        if (name.isNullOrEmpty()) hasServiceUuid else name.startsWith(MODEL_NAME_PREFIX)

    fun validate(word: DecodedWord): ProbeSample = when (word) {
        DecodedWord.Unavailable -> ProbeSample.Absent
        is DecodedWord.Invalid -> ProbeSample.Rejected
        is DecodedWord.CentiCelsius ->
            if (word.value in MonitoringPolicy.MIN_VALID_CENTI_C..MonitoringPolicy.MAX_VALID_CENTI_C) {
                ProbeSample.Valid(word.value)
            } else {
                ProbeSample.Rejected
            }
    }
}

sealed interface ProbeSample {
    data class Valid(val centiC: Int) : ProbeSample
    data object Absent : ProbeSample
    data object Rejected : ProbeSample
}
