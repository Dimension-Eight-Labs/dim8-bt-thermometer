package com.dim8labs.btthermometer.core

/**
 * Pure decoder for the Govee H5182 manufacturer advertisement value.
 *
 * Offsets index the value returned for one Android manufacturer-data entry,
 * after Android has removed the two-byte manufacturer key. See the protocol
 * section of docs/ARCHITECTURE.md. Offsets are source-derived and still require
 * validation against the physical thermometer.
 */
object H5182Decoder {
    const val VALUE_LENGTH = 17

    /** Apple's company ID. Its entries can coexist with Govee data and are unrelated. */
    const val APPLE_MANUFACTURER_ID = 0x004c

    private const val BATTERY_OFFSET = 5
    private const val PROBE_1_TEMPERATURE_OFFSET = 8
    private const val PROBE_1_ALARM_OFFSET = 10
    private const val PROBE_2_TEMPERATURE_OFFSET = 13
    private const val PROBE_2_ALARM_OFFSET = 15
    private const val UNAVAILABLE_WORD = 0xffff
    private const val HIGH_BIT = 0x8000

    /**
     * Selects the one plausible H5182 value. The manufacturer key varies by
     * device, so it cannot identify the model. Multiple 17-byte candidates are
     * rejected instead of guessing which contains temperatures.
     */
    fun selectValue(entries: Map<Int, ByteArray>): SelectResult {
        val proprietary = entries.filterKeys { it != APPLE_MANUFACTURER_ID }
        if (proprietary.isEmpty()) return SelectResult.Failure(DecodeError.NO_MANUFACTURER_DATA)
        val candidates = proprietary.values.filter { it.size == VALUE_LENGTH }
        return when (candidates.size) {
            0 -> SelectResult.Failure(DecodeError.WRONG_LENGTH)
            1 -> SelectResult.Selected(candidates.single())
            else -> SelectResult.Failure(DecodeError.AMBIGUOUS_MANUFACTURER_DATA)
        }
    }

    fun decode(value: ByteArray): DecodeResult {
        if (value.size != VALUE_LENGTH) return DecodeResult.Failure(DecodeError.WRONG_LENGTH)
        return DecodeResult.Success(
            H5182Frame(
                batteryPercent = decodeBattery(value[BATTERY_OFFSET]),
                probe1 = DecodedProbe(
                    temperature = decodeWord(value, PROBE_1_TEMPERATURE_OFFSET),
                    deviceAlarm = decodeWord(value, PROBE_1_ALARM_OFFSET),
                ),
                probe2 = DecodedProbe(
                    temperature = decodeWord(value, PROBE_2_TEMPERATURE_OFFSET),
                    deviceAlarm = decodeWord(value, PROBE_2_ALARM_OFFSET),
                ),
            ),
        )
    }

    /** Bit 7 has unverified meaning, so it is masked. Values above 100 are not a percentage. */
    private fun decodeBattery(raw: Byte): Int? = (raw.toInt() and 0x7f).takeIf { it <= 100 }

    /**
     * Reads a big-endian word as unsigned. Reviewed decoders disagree on words
     * with the high bit set, so those are rejected pending hardware evidence.
     */
    private fun decodeWord(value: ByteArray, offset: Int): DecodedWord {
        val word = ((value[offset].toInt() and 0xff) shl 8) or (value[offset + 1].toInt() and 0xff)
        return when {
            word == UNAVAILABLE_WORD -> DecodedWord.Unavailable
            word and HIGH_BIT != 0 -> DecodedWord.Invalid(word)
            else -> DecodedWord.CentiCelsius(word)
        }
    }
}

enum class DecodeError { NO_MANUFACTURER_DATA, WRONG_LENGTH, AMBIGUOUS_MANUFACTURER_DATA }

sealed interface SelectResult {
    class Selected(val value: ByteArray) : SelectResult
    data class Failure(val error: DecodeError) : SelectResult
}

sealed interface DecodeResult {
    data class Success(val frame: H5182Frame) : DecodeResult
    data class Failure(val error: DecodeError) : DecodeResult
}

sealed interface DecodedWord {
    data class CentiCelsius(val value: Int) : DecodedWord
    data object Unavailable : DecodedWord
    data class Invalid(val raw: Int) : DecodedWord
}

data class DecodedProbe(val temperature: DecodedWord, val deviceAlarm: DecodedWord)

data class H5182Frame(val batteryPercent: Int?, val probe1: DecodedProbe, val probe2: DecodedProbe)
