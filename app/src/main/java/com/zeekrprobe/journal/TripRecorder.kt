package com.zeekrprobe.journal

import android.location.Location
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Live view of an in-progress trip, collected by the UI. */
data class TripState(
    val seconds: Long,
    val odoDeltaKm: Double?,
    val gpsKm: Double,
    val speedEcarx: Float,
    val speedGps: Float,
)

/**
 * Holds the state and CSV buffer of a trip recording. The [TripService] feeds it samples from
 * a background thread, so it survives the app being minimised. The CSV format matches the old
 * probe's trip logger exactly (dashboard vs GPS speed, odometer vs GPS distance).
 */
object TripRecorder {

    private val _state = MutableStateFlow<TripState?>(null)
    val state: StateFlow<TripState?> = _state.asStateFlow()

    /** Short human summary shown once after a trip stops; null when nothing to show. */
    private val _summary = MutableStateFlow<String?>(null)
    val summary: StateFlow<String?> = _summary.asStateFlow()

    val running: Boolean get() = _state.value != null

    private val rows = StringBuilder()
    private var startOdo: Float? = null
    private var lastOdo: Float? = null
    private var distM = 0.0
    private var lastLoc: Location? = null
    private var startMs = 0L
    private var sumE = 0.0
    private var sumG = 0.0
    private var samples = 0
    private var maxE = 0f
    private var maxG = 0f

    @Synchronized
    fun begin(startOdoKm: Float?) {
        rows.setLength(0)
        rows.append("t_s,ecarx_kmh,gps_kmh,odo_km,gps_dist_km,lat,lon,gps_acc_m\n")
        startOdo = startOdoKm
        lastOdo = startOdoKm
        distM = 0.0
        lastLoc = null
        startMs = System.currentTimeMillis()
        sumE = 0.0; sumG = 0.0; samples = 0; maxE = 0f; maxG = 0f
        _state.value = TripState(0L, startOdoKm?.let { 0.0 }, 0.0, 0f, 0f)
    }

    @Synchronized
    fun addRow(speedEcarx: Float?, loc: Location?, odoKm: Float?) {
        if (_state.value == null) return
        val e = speedEcarx ?: 0f
        val g = loc?.let { it.speed * 3.6f } ?: 0f
        if (odoKm != null) lastOdo = odoKm
        if (loc != null && loc.accuracy <= 15f) {
            lastLoc?.let { prev -> if (prev != loc) distM += prev.distanceTo(loc) }
            lastLoc = loc
        }
        sumE += e; sumG += g; samples++
        if (e > maxE) maxE = e
        if (g > maxG) maxG = g
        val t = (System.currentTimeMillis() - startMs) / 1000.0
        rows.append(
            "%.1f,%.1f,%.1f,%s,%.3f,%.6f,%.6f,%.0f\n".format(
                Locale.US, t, e, g,
                odoKm?.let { "%.1f".format(Locale.US, it) } ?: "",
                distM / 1000.0,
                loc?.latitude ?: 0.0, loc?.longitude ?: 0.0, loc?.accuracy ?: -1f,
            )
        )
        val odoDelta = startOdo?.let { s -> lastOdo?.let { en -> (en - s).toDouble() } }
        _state.value = TripState(
            seconds = (System.currentTimeMillis() - startMs) / 1000,
            odoDeltaKm = odoDelta,
            gpsKm = distM / 1000.0,
            speedEcarx = e,
            speedGps = g,
        )
    }

    /** Ends recording and returns the full CSV (summary header + rows), or null if not running. */
    @Synchronized
    fun finish(): String? {
        if (_state.value == null) return null
        val mins = (System.currentTimeMillis() - startMs) / 60000.0
        val odoDelta = startOdo?.let { s -> lastOdo?.let { e -> e - s } }
        val gpsKm = distM / 1000.0
        val avgE = if (samples > 0) sumE / samples else 0.0
        val avgG = if (samples > 0) sumG / samples else 0.0
        val sb = StringBuilder()
        sb.append("# Zeekr trip ").append(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())).append('\n')
        sb.append("# длительность, мин: %.1f\n".format(Locale.US, mins))
        sb.append("# средняя скорость: приборка %.1f, GPS %.1f км/ч\n".format(Locale.US, avgE, avgG))
        sb.append("# макс скорость: приборка %.1f, GPS %.1f км/ч\n".format(Locale.US, maxE, maxG))
        if (avgG > 1) sb.append("# приборка/GPS в среднем: %.3f (приборка выше на %.1f%%)\n".format(Locale.US, avgE / avgG, (avgE / avgG - 1) * 100))
        sb.append("# одометр: начало %s, конец %s, прирост %s км\n".format(
            Locale.US,
            startOdo?.let { "%.1f".format(Locale.US, it) } ?: "—",
            lastOdo?.let { "%.1f".format(Locale.US, it) } ?: "—",
            odoDelta?.let { "%.1f".format(Locale.US, it) } ?: "—",
        ))
        sb.append("# путь по GPS: %.2f км\n".format(Locale.US, gpsKm))
        if (odoDelta != null && gpsKm > 0.3) {
            sb.append("# одометр/GPS: %.3f (одометр считает на %.1f%% больше)\n".format(Locale.US, odoDelta / gpsKm, (odoDelta / gpsKm - 1) * 100))
        }
        sb.append('\n').append(rows)

        _summary.value = buildString {
            append("Поездка записана.\n")
            append("%.1f мин · одометр %s км · GPS %.2f км\n".format(
                Locale.US, mins, odoDelta?.let { "%.1f".format(Locale.US, it) } ?: "—", gpsKm))
            if (odoDelta != null && gpsKm > 0.3) {
                append("одометр/GPS: +%.1f%%\n".format(Locale.US, (odoDelta / gpsKm - 1) * 100))
            }
            if (avgG > 1) append("приборка выше GPS на %.1f%%\n".format(Locale.US, (avgE / avgG - 1) * 100))
            append("Файл в «Загрузки» (и выгружен на хостинг, если задан).")
        }
        _state.value = null
        return sb.toString()
    }

    fun clearSummary() { _summary.value = null }
}
