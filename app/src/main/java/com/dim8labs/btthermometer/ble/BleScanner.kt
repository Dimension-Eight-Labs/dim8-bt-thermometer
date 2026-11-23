package com.dim8labs.btthermometer.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import com.dim8labs.btthermometer.BuildConfig
import com.dim8labs.btthermometer.core.Advertisement
import com.dim8labs.btthermometer.core.H5182Decoder
import com.dim8labs.btthermometer.core.RadioFault
import com.dim8labs.btthermometer.core.RecoveryBackoff
import kotlin.random.Random

/**
 * Owns the Android BLE scan, its failures, and bounded recovery. It holds no
 * alarm policy. [service] and scan state run on the monitor thread. Android
 * delivers scan callbacks on the main thread, so they only normalize and hand off.
 */
class BleScanner(
    private val context: Context,
    private val onAdvertisement: (Advertisement) -> Unit,
    private val postToMonitor: (() -> Unit) -> Unit,
) {
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val locationManager = context.getSystemService(LocationManager::class.java)
    private val backoff = RecoveryBackoff()
    private var scanning = false
    private var scanFailed = false
    private var nextAttemptElapsedMs = 0L

    // A random per-process salt lets debug captures correlate packets without
    // logging the thermometer's Bluetooth address.
    private val captureSalt = Random.nextInt()
    private var lastCaptureElapsedMs = 0L

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "BLE scan failed: code=$errorCode")
            postToMonitor {
                scanning = false
                scanFailed = true
            }
        }
    }

    /**
     * Called about once per second while monitoring. It reports environment
     * faults and restarts scanning with backoff. Polling avoids separate
     * receivers and also catches state changes missed while the process slept.
     */
    fun service(signalLost: Boolean): RadioFault? {
        environmentFault()?.let { fault ->
            halt()
            return fault
        }
        val now = SystemClock.elapsedRealtime()
        if (!scanning || signalLost) {
            if (now >= nextAttemptElapsedMs) {
                halt()
                begin()
                nextAttemptElapsedMs = now + backoff.next()
            }
        } else {
            backoff.reset()
        }
        return if (scanFailed) RadioFault.SCAN_ERROR else null
    }

    fun stop() {
        halt()
        scanFailed = false
        nextAttemptElapsedMs = 0
        backoff.reset()
    }

    private fun environmentFault(): RadioFault? {
        val granted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return when {
            !granted -> RadioFault.PERMISSION_REQUIRED
            bluetoothManager?.adapter?.isEnabled != true -> RadioFault.BLUETOOTH_OFF
            // On API 29, scans return no results and no error with location off.
            locationManager?.isLocationEnabled != true -> RadioFault.LOCATION_OFF
            else -> null
        }
    }

    private fun begin() {
        scanFailed = false
        val scanner = bluetoothManager?.adapter?.bluetoothLeScanner
        if (scanner == null) {
            scanFailed = true
            return
        }
        // The non-empty filter is required for delivery with the screen off.
        val filters = listOf(ScanFilter.Builder().setServiceUuid(H5182_SERVICE_UUID).build())
        // BALANCED listens only part of the time. On the Pixel XL it delivered
        // packets at a 5 s median and up to 16 s apart, with slow-looking recovery
        // reported by the owner. LOW_LATENCY listens continuously and costs battery.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
            .build()
        try {
            scanner.startScan(filters, settings, callback)
            scanning = true
        } catch (e: IllegalStateException) {
            Log.w(TAG, "BLE scan could not start: adapter unavailable")
            scanFailed = true
        } catch (e: SecurityException) {
            Log.w(TAG, "BLE scan could not start: permission denied")
            scanFailed = true
        }
    }

    // Lint reads the compile SDK's Android 12+ BLUETOOTH_SCAN requirement. This
    // app targets API 29, where the declared BLUETOOTH_ADMIN permission applies.
    @SuppressLint("MissingPermission")
    private fun halt() {
        if (!scanning) return
        scanning = false
        try {
            bluetoothManager?.adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (e: IllegalStateException) {
            // Bluetooth turned off first, which already ended the scan.
        } catch (e: SecurityException) {
            // Permission was revoked, which already ended the scan.
        }
    }

    private fun handle(result: ScanResult) {
        val record = result.scanRecord ?: return
        // A result without manufacturer data still proves the device is in
        // range. The coordinator counts reception without treating it as a reading.
        val raw = record.manufacturerSpecificData
        val manufacturer = HashMap<Int, ByteArray>(raw?.size() ?: 0)
        if (raw != null) {
            for (index in 0 until raw.size()) manufacturer[raw.keyAt(index)] = raw.valueAt(index)
        }
        val hasUuid = record.serviceUuids?.contains(H5182_SERVICE_UUID) == true
        val name = record.deviceName
        if (BuildConfig.DEBUG) logCapture(result.device.address, name, hasUuid, manufacturer)
        onAdvertisement(Advertisement(result.device.address, name, hasUuid, manufacturer))
    }

    /**
     * Debug-only bring-up evidence for building target-device fixtures. It
     * hashes the address and keeps only the model part of the name. At most two
     * lines per second are written. Remove after H5182 qualification.
     *
     * Physical capture showed that the manufacturer key's high byte and value
     * byte 0 repeat the last two address bytes, so both are zeroed here. Apple
     * entries are unrelated to temperature, so only their length is logged.
     */
    private fun logCapture(address: String, name: String?, hasUuid: Boolean, data: Map<Int, ByteArray>) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastCaptureElapsedMs < CAPTURE_MIN_INTERVAL_MS) return
        lastCaptureElapsedMs = now
        val device = (address.hashCode() xor captureSalt).toUInt().toString(16)
        val model = name?.take(MODEL_NAME_LENGTH) ?: "none"
        val appleLength = data[H5182Decoder.APPLE_MANUFACTURER_ID]?.size ?: 0
        val entries = data.filterKeys { it != H5182Decoder.APPLE_MANUFACTURER_ID }.values
            .joinToString(" ") { value ->
                "0000=" + value.mapIndexed { index, byte ->
                    if (index == 0) "00" else "%02x".format(byte)
                }.joinToString("")
            }
        Log.d(CAPTURE_TAG, "t=$now dev=$device name=$model uuid=$hasUuid apple=$appleLength mfr=$entries")
    }

    private companion object {
        const val TAG = "Dim8Ble"
        const val CAPTURE_TAG = "H5182Capture"
        const val CAPTURE_MIN_INTERVAL_MS = 500L
        const val MODEL_NAME_LENGTH = 7
        val H5182_SERVICE_UUID: ParcelUuid =
            ParcelUuid.fromString("00008251-0000-1000-8000-00805f9b34fb")
    }
}
