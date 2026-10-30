package com.zeekrprobe.journal

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/**
 * Single source of truth for the journal: the list of charge sessions plus a few settings.
 * Persisted as JSON in filesDir (robust, no Room/DB build surface) and exposed as a StateFlow
 * the Compose UI collects. Safe to touch from the UI thread and the sampling service.
 */
object Store {

    private lateinit var app: Context
    private lateinit var file: File

    private val _charges = MutableStateFlow<List<ChargeSession>>(emptyList())
    val charges: StateFlow<List<ChargeSession>> = _charges.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        file = File(File(app.filesDir, "journal").apply { mkdirs() }, "charges.json")
        _charges.value = load()
    }

    private fun prefs() = app.getSharedPreferences("journal", Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- sessions

    @Synchronized
    fun add(session: ChargeSession) {
        _charges.value = (_charges.value + session).sortedBy { it.timestamp }
        save()
        session.pricePerKwh?.let { setLastPrice(session.network, it) }
    }

    @Synchronized
    fun update(session: ChargeSession) {
        _charges.value = _charges.value.map { if (it.id == session.id) session else it }
            .sortedBy { it.timestamp }
        save()
        session.pricePerKwh?.let { setLastPrice(session.network, it) }
    }

    @Synchronized
    fun delete(id: Long) {
        _charges.value = _charges.value.filterNot { it.id == id }
        save()
    }

    fun byId(id: Long): ChargeSession? = _charges.value.firstOrNull { it.id == id }

    /** Count of auto-detected sessions still waiting for the user to confirm. */
    fun pendingCount(): Int = _charges.value.count { it.auto && !it.confirmed }

    private fun load(): List<ChargeSession> = try {
        if (!file.exists()) emptyList()
        else {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { ChargeSession.fromJson(arr.getJSONObject(it)) }
                .sortedBy { it.timestamp }
        }
    } catch (_: Throwable) {
        emptyList()
    }

    private fun save() {
        try {
            val arr = JSONArray()
            _charges.value.forEach { arr.put(it.toJson()) }
            file.writeText(arr.toString())
        } catch (_: Throwable) {
        }
    }

    // ---------------------------------------------------------------- settings

    // Zeekr 7X AWD pack (owner: 103 kWh); used only to estimate kWh for an auto-detected
    // charge when the user hasn't typed the numbers in. Editable in settings.
    var batteryKwh: Double
        get() = prefs().getFloat("batteryKwh", 103f).toDouble()
        set(v) { prefs().edit().putFloat("batteryKwh", v.toFloat()).apply() }

    fun lastPrice(network: String): Double? {
        val v = prefs().getFloat("lastPrice_$network", -1f)
        return if (v > 0) v.toDouble() else null
    }

    fun setLastPrice(network: String, price: Double) {
        if (price > 0) prefs().edit().putFloat("lastPrice_$network", price.toFloat()).apply()
    }

    /** Price to pre-fill when the user picks [network] at [timeMillis]: last used, else default. */
    fun suggestedPrice(network: String, timeMillis: Long): Double? =
        lastPrice(network) ?: Network.byKey(network)?.priceAt(timeMillis)

    /** Battery capacity implied by a session's SoC rise and energy added, if both known. */
    fun impliedCapacity(s: ChargeSession): Double? {
        val e = s.effectiveKwh() ?: return null
        val b = s.socBefore ?: return null
        val a = s.socAfter ?: return null
        val d = (a - b) / 100.0
        return if (d > 0.05) e / d else null
    }

    // ---------------------------------------------------------------- stats

    fun stats(): Stats {
        val confirmed = _charges.value.filter { it.confirmed }
        val withOdo = confirmed.filter { it.odometerKm != null }.sortedBy { it.odometerKm }

        val segments = ArrayList<Segment>()
        for (i in 1 until withOdo.size) {
            val a = withOdo[i - 1]
            val b = withOdo[i]
            val km = (b.odometerKm!! - a.odometerKm!!)
            if (km <= 0) continue
            // Energy added at the LATER charge refills what the preceding drive used.
            segments.add(
                Segment(
                    from = a, to = b,
                    km = km,
                    kwh = b.effectiveKwh(),
                    cost = b.effectiveCost(),
                )
            )
        }

        val distance = segments.sumOf { it.km }
        val energyForDistance = segments.mapNotNull { it.kwh }.sum()
        val costForDistance = segments.mapNotNull { it.cost }.sum()

        val now = Calendar.getInstance()
        val spentMonth = confirmed.filter { sameMonth(it.timestamp, now) }
            .sumOf { it.effectiveCost() ?: 0.0 }
        val spentTotal = confirmed.sumOf { it.effectiveCost() ?: 0.0 }
        val energyTotal = confirmed.sumOf { it.effectiveKwh() ?: 0.0 }

        return Stats(
            sessions = confirmed.size,
            distanceKm = distance,
            consumptionPer100 = if (distance > 0 && energyForDistance > 0) energyForDistance / distance * 100 else null,
            costPerKm = if (distance > 0 && costForDistance > 0) costForDistance / distance else null,
            spentMonth = spentMonth,
            spentTotal = spentTotal,
            energyTotal = energyTotal,
            segments = segments.asReversed(),
        )
    }

    private fun sameMonth(ts: Long, now: Calendar): Boolean {
        val c = Calendar.getInstance().apply { timeInMillis = ts }
        return c.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
            c.get(Calendar.MONTH) == now.get(Calendar.MONTH)
    }
}

/** One drive between two consecutive charges (the heart of "at which odometer did I spend what"). */
data class Segment(
    val from: ChargeSession,
    val to: ChargeSession,
    val km: Double,
    val kwh: Double?,
    val cost: Double?,
) {
    val consumptionPer100: Double? get() = if (km > 0 && kwh != null) kwh / km * 100 else null
    val costPerKm: Double? get() = if (km > 0 && cost != null) cost / km else null
}

data class Stats(
    val sessions: Int,
    val distanceKm: Double,
    val consumptionPer100: Double?,
    val costPerKm: Double?,
    val spentMonth: Double,
    val spentTotal: Double,
    val energyTotal: Double,
    val segments: List<Segment>,
)
