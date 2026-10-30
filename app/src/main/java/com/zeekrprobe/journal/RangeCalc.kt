package com.zeekrprobe.journal

/** Output of the range estimator. */
data class RangeEstimate(
    val usableKwh: Double,
    val mixedConsumption: Double,   // kWh/100km for the "mixed" number, current conditions
    val mixedKm: Int,
    val cityKm: Int,
    val highwayKm: Int,
    val reserve10Km: Int,           // km until a 10% buffer
    val tempC: Double?,
    val windMs: Double?,
    val basis: String,
)

/**
 * Personalised, condition-aware range estimate — an honest alternative to the built-in GOM.
 * It learns a base consumption from recent trips (normalised to 20 °C), then re-applies the
 * current temperature and wind. City/highway numbers come from trips bucketed by average speed,
 * or sensible ratios until there is enough data.
 */
object RangeCalc {

    /** Consumption multiplier for ambient temperature (1.0 at ~20 °C, higher when cold/hot). */
    fun tempFactor(t: Double): Double {
        val f = if (t >= 20.0) 1.0 + maxOf(0.0, t - 30.0) * 0.010
        else 1.0 + (20.0 - t) * 0.012 + maxOf(0.0, -t) * 0.013
        return f.coerceIn(0.8, 2.2)
    }

    /** Small penalty for wind (direction unknown, so a mild average effect). */
    fun windFactor(w: Double?): Double =
        if (w == null) 1.0 else (1.0 + maxOf(0.0, w - 3.0) * 0.004).coerceIn(1.0, 1.2)

    fun estimate(
        socPercent: Float?,
        capacityKwh: Double,
        trips: List<TripRecord>,
        segmentConsumption: Double?,
        carConsumption: Float?,
        weather: WeatherData?,
        ecarxTempC: Float?,
    ): RangeEstimate? {
        val soc = socPercent?.toDouble() ?: return null
        val usable = soc / 100.0 * capacityKwh
        val tempNow = weather?.tempC ?: ecarxTempC?.toDouble()
        val tf = tempNow?.let { tempFactor(it) } ?: 1.0
        val wf = windFactor(weather?.windMs)

        val recent = trips.filter { (it.consumptionPer100 ?: 0.0) > 3.0 && it.km >= 1.0 }.takeLast(12)

        val baseAt20: Double
        val basis: String
        when {
            recent.isNotEmpty() -> {
                var wsum = 0.0; var vsum = 0.0
                recent.forEachIndexed { i, t ->
                    val weight = (i + 1).toDouble() * t.km.coerceAtMost(100.0)
                    val norm = t.consumptionPer100!! / (t.avgTempC?.let { tempFactor(it) } ?: 1.0)
                    vsum += norm * weight; wsum += weight
                }
                baseAt20 = if (wsum > 0) vsum / wsum else 18.0
                basis = "по ${recent.size} поездкам"
            }
            (segmentConsumption ?: 0.0) > 3.0 -> {
                baseAt20 = segmentConsumption!! / tf
                basis = "по зарядкам"
            }
            (carConsumption ?: 0f) > 3f -> {
                baseAt20 = carConsumption!!.toDouble() / tf
                basis = "по данным машины"
            }
            else -> {
                baseAt20 = 18.0
                basis = "оценка по умолчанию"
            }
        }

        val cityTrips = recent.filter { (it.avgSpeed ?: 0.0) in 1.0..45.0 }
        val hwTrips = recent.filter { (it.avgSpeed ?: 0.0) >= 75.0 }
        val cityBase = if (cityTrips.isNotEmpty())
            cityTrips.map { it.consumptionPer100!! / (it.avgTempC?.let { t -> tempFactor(t) } ?: 1.0) }.average()
        else baseAt20 * 0.90
        val hwBase = if (hwTrips.isNotEmpty())
            hwTrips.map { it.consumptionPer100!! / (it.avgTempC?.let { t -> tempFactor(t) } ?: 1.0) }.average()
        else baseAt20 * 1.28

        val mixed = baseAt20 * tf * wf
        val city = cityBase * tf * wf
        val highway = hwBase * tf * wf

        fun km(cons: Double) = if (cons > 0) (usable / cons * 100).toInt() else 0
        val reserve = if (soc > 10 && mixed > 0) ((soc - 10) / 100.0 * capacityKwh) / mixed * 100 else 0.0

        return RangeEstimate(
            usableKwh = usable,
            mixedConsumption = mixed,
            mixedKm = km(mixed),
            cityKm = km(city),
            highwayKm = km(highway),
            reserve10Km = reserve.toInt(),
            tempC = tempNow,
            windMs = weather?.windMs,
            basis = basis,
        )
    }
}
