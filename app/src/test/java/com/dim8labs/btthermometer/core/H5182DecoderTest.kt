package com.dim8labs.btthermometer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host/JVM tests using the public source examples in docs/ARCHITECTURE.md.
 * These are not captures from this project's physical H5182.
 */
class H5182DecoderTest {
    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun frame(hex: String) = (H5182Decoder.decode(bytes(hex)) as DecodeResult.Success).frame

    @Test
    fun `decodes GoveeBTTempLogger example with both probes and alarms`() {
        val decoded = frame("2701000101e4018008341cdc8008341cdc")
        assertEquals(100, decoded.batteryPercent)
        assertEquals(DecodedWord.CentiCelsius(2100), decoded.probe1.temperature)
        assertEquals(DecodedWord.CentiCelsius(7388), decoded.probe1.deviceAlarm)
        assertEquals(DecodedWord.CentiCelsius(2100), decoded.probe2.temperature)
        assertEquals(DecodedWord.CentiCelsius(7388), decoded.probe2.deviceAlarm)
    }

    @Test
    fun `decodes README example with unavailable device alarms`() {
        val decoded = frame("270100010164018007d0ffff860708ffff")
        assertEquals(100, decoded.batteryPercent)
        assertEquals(DecodedWord.CentiCelsius(2000), decoded.probe1.temperature)
        assertEquals(DecodedWord.Unavailable, decoded.probe1.deviceAlarm)
        assertEquals(DecodedWord.CentiCelsius(1800), decoded.probe2.temperature)
        assertEquals(DecodedWord.Unavailable, decoded.probe2.deviceAlarm)
    }

    @Test
    fun `keeps unavailable probes absent instead of zero`() {
        val decoded = frame("6301000101e40101ffff120301ffff0000")
        assertEquals(DecodedWord.Unavailable, decoded.probe1.temperature)
        assertEquals(DecodedWord.CentiCelsius(4611), decoded.probe1.deviceAlarm)
        assertEquals(DecodedWord.Unavailable, decoded.probe2.temperature)
        assertEquals(DecodedWord.CentiCelsius(0), decoded.probe2.deviceAlarm)
    }

    @Test
    fun `decodes anonymized capture from the physical H5182`() {
        val stream = javaClass.classLoader!!.getResourceAsStream("fixtures/h5182-pixel-xl-2026-11-22.txt")
        val rows = stream.bufferedReader().readLines().filterNot { it.startsWith("#") }
        assertTrue(rows.size >= 10)
        val decoded = rows.map { row ->
            val (_, hex, probe1, probe2, battery) = row.split(" ")
            val result = frame(hex)
            assertEquals(DecodedWord.CentiCelsius(probe1.toInt()), result.probe1.temperature)
            assertEquals(DecodedWord.CentiCelsius(probe2.toInt()), result.probe2.temperature)
            assertEquals(battery.toInt(), result.batteryPercent)
            result
        }
        // Owner-observed reference: Probe 1 peaked at 95 F while Probe 2 stayed at 70 F.
        assertEquals(3500, decoded.maxOf { (it.probe1.temperature as DecodedWord.CentiCelsius).value })
        assertTrue(decoded.all { it.probe2.temperature == DecodedWord.CentiCelsius(2100) })
    }

    @Test
    fun `rejects high-bit temperature words`() {
        val decoded = frame("270100010164018080000834" + "0080000834")
        assertTrue(decoded.probe1.temperature is DecodedWord.Invalid)
    }

    @Test
    fun `rejects truncated and oversized values`() {
        for (size in listOf(0, 16, 18)) {
            assertEquals(
                DecodeResult.Failure(DecodeError.WRONG_LENGTH),
                H5182Decoder.decode(ByteArray(size)),
            )
        }
    }

    @Test
    fun `battery above 100 after masking is unavailable`() {
        assertEquals(null, frame("2701000101ff018007d0ffff860708ffff").batteryPercent)
    }

    @Test
    fun `selection ignores Apple entry and rejects ambiguity`() {
        val value = ByteArray(17)
        val selected = H5182Decoder.selectValue(mapOf(0x004c to ByteArray(17), 0x1330 to value))
        assertTrue((selected as SelectResult.Selected).value === value)
        assertEquals(
            SelectResult.Failure(DecodeError.AMBIGUOUS_MANUFACTURER_DATA),
            H5182Decoder.selectValue(mapOf(0x1330 to ByteArray(17), 0x2730 to ByteArray(17))),
        )
        assertEquals(
            SelectResult.Failure(DecodeError.NO_MANUFACTURER_DATA),
            H5182Decoder.selectValue(mapOf(0x004c to ByteArray(17))),
        )
    }
}
