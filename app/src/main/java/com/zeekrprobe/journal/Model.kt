package com.zeekrprobe.journal

import org.json.JSONObject
import java.util.Calendar

/**
 * One charging session in the journal. Every numeric field is optional on purpose:
 * the user only has to enter one of kWh / cost / price and we derive the rest from the
 * network's price (see [priceAt]). Auto-detected sessions come in with [auto] = true and
 * [confirmed] = false until the user looks at them.
 */
data class ChargeSession(
    val id: Long,                 // epoch millis, also the creation time
    val timestamp: Long,          // when the charge happened
    val network: String,          // Network.key
    val odometerKm: Double? = null,
    val kwh: Double? = null,      // energy added
    val pricePerKwh: Double? = null,
    val cost: Double? = null,     // total, rubles
    val socBefore: Double? = null,
    val socAfter: Double? = null,
    val note: String = "",
    val auto: Boolean = false,
    val confirmed: Boolean = true,
) {
    /** Price actually used: explicit price, else the network default for this session's time. */
    fun effectivePrice(): Double? = pricePerKwh ?: Network.byKey(network)?.priceAt(timestamp)

    /** kWh added: explicit, else derived from cost and price. */
    fun effectiveKwh(): Double? {
        kwh?.let { return it }
        val p = effectivePrice()
        return if (cost != null && p != null && p > 0) cost / p else null
    }

    /** Total rubles: explicit, else derived from kWh and price. */
    fun effectiveCost(): Double? {
        cost?.let { return it }
        val p = effectivePrice()
        val e = kwh
        return if (e != null && p != null) e * p else null
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("timestamp", timestamp)
        put("network", network)
        putOpt("odometerKm", odometerKm)
        putOpt("kwh", kwh)
        putOpt("pricePerKwh", pricePerKwh)
        putOpt("cost", cost)
        putOpt("socBefore", socBefore)
        putOpt("socAfter", socAfter)
        put("note", note)
        put("auto", auto)
        put("confirmed", confirmed)
    }

    companion object {
        fun fromJson(o: JSONObject) = ChargeSession(
            id = o.getLong("id"),
            timestamp = o.optLong("timestamp", o.getLong("id")),
            network = o.optString("network", Network.OTHER),
            odometerKm = o.optDoubleOrNull("odometerKm"),
            kwh = o.optDoubleOrNull("kwh"),
            pricePerKwh = o.optDoubleOrNull("pricePerKwh"),
            cost = o.optDoubleOrNull("cost"),
            socBefore = o.optDoubleOrNull("socBefore"),
            socAfter = o.optDoubleOrNull("socAfter"),
            note = o.optString("note", ""),
            auto = o.optBoolean("auto", false),
            confirmed = o.optBoolean("confirmed", true),
        )
    }
}

/** A charging network with its default price. [nightPrice] applies within [nightStart]..[nightEnd). */
data class Network(
    val key: String,
    val title: String,
    val basePrice: Double?,
    val nightPrice: Double? = null,
    val nightStart: Int = 0,
    val nightEnd: Int = 0,
) {
    /** Default price for a given moment, applying the night window if this network has one. */
    fun priceAt(timeMillis: Long): Double? {
        if (nightPrice == null) return basePrice
        val cal = Calendar.getInstance().apply { timeInMillis = timeMillis }
        val h = cal.get(Calendar.HOUR_OF_DAY)
        // Window may wrap past midnight (e.g. 21:00..08:00).
        val inNight = if (nightStart <= nightEnd) h in nightStart until nightEnd
                      else h >= nightStart || h < nightEnd
        return if (inNight) nightPrice else basePrice
    }

    companion object {
        const val OTHER = "other"

        // Prices from the owner (BYN per kWh). Editable per session; the app also remembers
        // the last price used per network and pre-fills that instead when present.
        val ALL: List<Network> = listOf(
            Network("malanka", "Маланка", 0.73),
            Network("batteryfly", "BatteryFly", 0.65),
            Network("evika", "Evika", 0.54),
            Network("zaradka", "Zaradka", 0.73, nightPrice = 0.48, nightStart = 21, nightEnd = 8),
            Network("home", "Дом", 0.25),
            Network(OTHER, "Другое", null),
        )

        fun byKey(key: String): Network? = ALL.firstOrNull { it.key == key }
        fun title(key: String): String = byKey(key)?.title ?: key
    }
}

/** org.json returns a double even for a missing key; this distinguishes "absent" from a value. */
fun JSONObject.optDoubleOrNull(name: String): Double? =
    if (has(name) && !isNull(name)) getDouble(name) else null

fun JSONObject.putOpt(name: String, value: Double?) {
    if (value != null) put(name, value)
}
