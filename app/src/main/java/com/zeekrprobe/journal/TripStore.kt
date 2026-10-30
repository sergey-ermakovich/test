package com.zeekrprobe.journal

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A finished trip, kept so the range calculator can learn from real driving. */
data class TripRecord(
    val id: Long,
    val timestamp: Long,
    val km: Double,                 // odometer delta if available, else GPS
    val kwh: Double?,               // energy used (from SoC drop × capacity)
    val consumptionPer100: Double?, // kWh / 100 km
    val avgTempC: Double?,          // outside temperature during the trip
    val avgSpeed: Double?,          // dashboard average, km/h
    val socStart: Double?,
    val socEnd: Double?,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("timestamp", timestamp); put("km", km)
        putOpt("kwh", kwh); putOpt("consumptionPer100", consumptionPer100)
        putOpt("avgTempC", avgTempC); putOpt("avgSpeed", avgSpeed)
        putOpt("socStart", socStart); putOpt("socEnd", socEnd)
    }

    companion object {
        fun fromJson(o: JSONObject) = TripRecord(
            id = o.getLong("id"),
            timestamp = o.optLong("timestamp", o.getLong("id")),
            km = o.optDouble("km", 0.0),
            kwh = o.optDoubleOrNull("kwh"),
            consumptionPer100 = o.optDoubleOrNull("consumptionPer100"),
            avgTempC = o.optDoubleOrNull("avgTempC"),
            avgSpeed = o.optDoubleOrNull("avgSpeed"),
            socStart = o.optDoubleOrNull("socStart"),
            socEnd = o.optDoubleOrNull("socEnd"),
        )
    }
}

/** Persists finished trips as JSON in filesDir, exposed as a StateFlow for the UI/calculator. */
object TripStore {

    private lateinit var app: Context
    private lateinit var file: File

    private val _trips = MutableStateFlow<List<TripRecord>>(emptyList())
    val trips: StateFlow<List<TripRecord>> = _trips.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        file = File(File(app.filesDir, "journal").apply { mkdirs() }, "trips.json")
        _trips.value = load()
    }

    @Synchronized
    fun add(rec: TripRecord) {
        if (!::file.isInitialized) return
        // Keep the list bounded; the calculator only needs recent history.
        _trips.value = (_trips.value + rec).sortedBy { it.timestamp }.takeLast(200)
        save()
    }

    private fun load(): List<TripRecord> = try {
        if (!file.exists()) emptyList()
        else {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { TripRecord.fromJson(arr.getJSONObject(it)) }.sortedBy { it.timestamp }
        }
    } catch (_: Throwable) {
        emptyList()
    }

    private fun save() {
        try {
            val arr = JSONArray()
            _trips.value.forEach { arr.put(it.toJson()) }
            file.writeText(arr.toString())
        } catch (_: Throwable) {}
    }
}
