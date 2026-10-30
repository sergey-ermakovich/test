package com.zeekrprobe.journal

import android.content.Context
import com.zeekrprobe.EcarxProbe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A single live read of the car via ECARX AdaptAPI. Any field may be null when unavailable. */
data class CarState(
    val connected: Boolean = false,
    val socPercent: Float? = null,
    val odometerKm: Float? = null,
    val rangeKm: Float? = null,
    val speedKmh: Float? = null,
    val outsideC: Float? = null,
    val gear: String? = null,
    val chargeKw: Float? = null,
    val error: String? = null,
    val updatedAt: Long = 0L,
) {
    val charging: Boolean get() = (chargeKw ?: 0f) > 0.1f
}

/**
 * Thin wrapper over [EcarxProbe] that both the dashboard and the sampling service share.
 * [refresh] does one blocking read (call it off the main thread) and publishes to [state].
 */
object CarRepo {

    private lateinit var probe: EcarxProbe
    private val _state = MutableStateFlow(CarState())
    val state: StateFlow<CarState> = _state.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (!::probe.isInitialized) probe = EcarxProbe(context.applicationContext)
    }

    /** Blocking read; returns the new state and also publishes it. Serialised across callers. */
    @Synchronized
    fun refresh(): CarState {
        val ok = try { probe.connect() } catch (_: Throwable) { false }
        val s = if (!ok) {
            CarState(connected = false, error = probe.connectError, updatedAt = System.currentTimeMillis())
        } else {
            CarState(
                connected = true,
                socPercent = safe { probe.socPercent() },
                odometerKm = safe { probe.odometerKm() },
                rangeKm = safe { probe.rangeKm() },
                speedKmh = safe { probe.speedKmh() },
                outsideC = safe { probe.outsideC() },
                gear = safe { probe.gear() },
                chargeKw = safe { probe.chargeKw() },
                updatedAt = System.currentTimeMillis(),
            )
        }
        _state.value = s
        return s
    }

    private fun <T> safe(block: () -> T?): T? = try { block() } catch (_: Throwable) { null }
}
