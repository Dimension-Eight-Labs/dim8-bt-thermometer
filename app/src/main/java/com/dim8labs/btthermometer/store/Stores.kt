package com.dim8labs.btthermometer.store

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.dim8labs.btthermometer.core.AppSettings
import com.dim8labs.btthermometer.core.CookStateStore
import com.dim8labs.btthermometer.core.CookTimerState
import com.dim8labs.btthermometer.core.Excursion
import com.dim8labs.btthermometer.core.HistorySample
import com.dim8labs.btthermometer.core.HistoryStore
import com.dim8labs.btthermometer.core.MonitoringPolicy
import com.dim8labs.btthermometer.core.ProbeSettings
import com.dim8labs.btthermometer.core.SelectedDevice
import com.dim8labs.btthermometer.core.SettingsStore

/**
 * Android persistence adapters. All methods run on the monitor thread, so
 * synchronous commit() can guarantee settings persist before they take effect
 * without blocking the UI.
 */
private const val TAG = "Dim8Store"

class PrefsSettingsStore(context: Context) : SettingsStore {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    override fun load(): AppSettings {
        val defaults = AppSettings()
        val address = prefs.getString(KEY_ADDRESS, null)
        return AppSettings(
            probes = List(2) { index ->
                ProbeSettings(
                    targetC = prefs.optionalInt("p${index}_target"),
                    lowC = prefs.optionalInt("p${index}_low"),
                    highC = prefs.optionalInt("p${index}_high"),
                    hysteresisC = prefs.getInt("p${index}_hysteresis", defaults.probes[index].hysteresisC),
                    // Installs saved before this setting existed have no key and stay in use.
                    inUse = prefs.getBoolean("p${index}_in_use", defaults.probes[index].inUse),
                )
            },
            displayFahrenheit = prefs.getBoolean(KEY_FAHRENHEIT, defaults.displayFahrenheit),
            colorBandC = prefs.getInt(KEY_COLOR_BAND, defaults.colorBandC),
            signalLossWarning = prefs.getBoolean(KEY_SIGNAL_WARNING, defaults.signalLossWarning),
            selectedDevice = address?.let { SelectedDevice(it, prefs.getString(KEY_NAME, null) ?: "H5182") },
        )
    }

    override fun save(settings: AppSettings) {
        val editor = prefs.edit().putInt(KEY_SCHEMA, SCHEMA_VERSION)
        settings.probes.forEachIndexed { index, probe ->
            editor.putOptionalInt("p${index}_target", probe.targetC)
            editor.putOptionalInt("p${index}_low", probe.lowC)
            editor.putOptionalInt("p${index}_high", probe.highC)
            editor.putInt("p${index}_hysteresis", probe.hysteresisC)
            editor.putBoolean("p${index}_in_use", probe.inUse)
        }
        editor.putBoolean(KEY_FAHRENHEIT, settings.displayFahrenheit)
            .putInt(KEY_COLOR_BAND, settings.colorBandC)
            .putBoolean(KEY_SIGNAL_WARNING, settings.signalLossWarning)
            .putString(KEY_ADDRESS, settings.selectedDevice?.address)
            .putString(KEY_NAME, settings.selectedDevice?.name)
        if (!editor.commit()) Log.e(TAG, "Settings commit failed")
    }

    private fun SharedPreferences.optionalInt(key: String): Int? =
        if (contains(key)) getInt(key, 0) else null

    private fun SharedPreferences.Editor.putOptionalInt(key: String, value: Int?) {
        if (value == null) remove(key) else putInt(key, value)
    }

    private companion object {
        const val SCHEMA_VERSION = 1
        const val KEY_SCHEMA = "schema_version"
        const val KEY_FAHRENHEIT = "display_fahrenheit"
        const val KEY_COLOR_BAND = "color_band"
        const val KEY_SIGNAL_WARNING = "signal_warning"
        const val KEY_ADDRESS = "device_address"
        const val KEY_NAME = "device_name"
    }
}

class PrefsCookStateStore(context: Context) : CookStateStore {
    private val prefs = context.getSharedPreferences("cook", Context.MODE_PRIVATE)

    override fun load() = CookTimerState(
        running = prefs.getBoolean("running", false),
        accumulatedMs = prefs.getLong("accumulated", 0),
        anchorElapsedMs = prefs.getLong("anchor", 0),
        bootCount = prefs.getInt("boot", 0),
    )

    override fun save(state: CookTimerState) {
        val saved = prefs.edit()
            .putBoolean("running", state.running)
            .putLong("accumulated", state.accumulatedMs)
            .putLong("anchor", state.anchorElapsedMs)
            .putInt("boot", state.bootCount)
            .commit()
        if (!saved) Log.e(TAG, "Cook state commit failed")
    }
}

/**
 * Bounded sparkline history. It is optional to monitoring: database failures are
 * logged and swallowed so they can never interrupt readings or alarms.
 */
class SqliteHistoryStore(context: Context) :
    SQLiteOpenHelper(context, "history.db", null, 2), HistoryStore {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE history (probe INTEGER NOT NULL, bucket INTEGER NOT NULL, " +
                "centi_c INTEGER NOT NULL, excursion INTEGER NOT NULL DEFAULT 0, " +
                "PRIMARY KEY (probe, bucket)) WITHOUT ROWID",
        )
    }

    /**
     * Version 2 adds the excursion recorded with each sample. Older rows default
     * to none because the limits in force when they were recorded are unknown.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE history ADD COLUMN excursion INTEGER NOT NULL DEFAULT 0")
    }

    override fun load(oldestBucket: Long, newestBucket: Long): List<HistorySample> = guarded(emptyList()) {
        readableDatabase.rawQuery(
            "SELECT probe, bucket, centi_c, excursion FROM history WHERE bucket BETWEEN ? AND ? LIMIT ?",
            arrayOf(
                oldestBucket.toString(),
                newestBucket.toString(),
                (MonitoringPolicy.HISTORY_BUCKETS * 2).toString(),
            ),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        HistorySample(
                            cursor.getInt(0),
                            cursor.getLong(1),
                            cursor.getInt(2),
                            Excursion.entries.getOrElse(cursor.getInt(3)) { Excursion.NONE },
                        ),
                    )
                }
            }
        }
    }

    /** Pruning both directions keeps the table bounded even after clock changes. */
    override fun append(sample: HistorySample, oldestBucket: Long, newestBucket: Long) = guarded(Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val row = ContentValues(4).apply {
                put("probe", sample.probe)
                put("bucket", sample.bucket)
                put("centi_c", sample.centiC)
                put("excursion", sample.excursion.ordinal)
            }
            db.insertWithOnConflict("history", null, row, SQLiteDatabase.CONFLICT_REPLACE)
            db.delete(
                "history",
                "bucket < ? OR bucket > ?",
                arrayOf(oldestBucket.toString(), newestBucket.toString()),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun clear() = guarded(Unit) {
        writableDatabase.delete("history", null, null)
        Unit
    }

    private inline fun <T> guarded(fallback: T, block: () -> T): T = try {
        block()
    } catch (e: SQLiteException) {
        Log.e(TAG, "History store failed", e)
        fallback
    }
}
