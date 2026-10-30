package com.zeekrprobe.journal

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.zeekrprobe.JournalApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that samples the car every few seconds and detects charging on its own,
 * so a forgotten "I charged" entry still lands in the journal.
 *
 * Primary detection: the ECARX charge-power signal. While power > 0 we integrate kW·h and
 * remember the SoC/odometer at the start; when power drops back to 0 we create a *pending*
 * (auto, unconfirmed) session for the user to price up. A startup SoC-jump check catches a
 * charge that finished while the head unit was asleep.
 */
class SamplingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        CarRepo.init(this)
        Store.init(this)
        startForeground(NOTE_ID, buildNotification(CarState()))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            prefs().edit().putBoolean("serviceEnabled", false).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        if (loop == null) loop = scope.launch { run() }
        return START_STICKY
    }

    private suspend fun run() {
        checkStartupJump()
        while (scope.isActive) {
            val s = try { CarRepo.refresh() } catch (_: Throwable) { CarState() }
            try { handleSample(s) } catch (_: Throwable) {}
            notify(buildNotification(s))
            delay(if (s.charging) CHARGING_INTERVAL else IDLE_INTERVAL)
        }
    }

    // ---------------------------------------------------------------- detection

    private fun handleSample(s: CarState) {
        val p = prefs()
        val now = System.currentTimeMillis()

        if (s.charging) {
            if (!p.getBoolean("inCharge", false)) {
                // Start of a charge.
                p.edit()
                    .putBoolean("inCharge", true)
                    .putFloat("chgSocStart", s.socPercent ?: -1f)
                    .putFloat("chgOdoStart", s.odometerKm ?: -1f)
                    .putFloat("chgEnergy", 0f)
                    .putLong("chgStart", now)
                    .putLong("chgLast", now)
                    .apply()
            } else {
                val last = p.getLong("chgLast", now)
                val dtH = (now - last).coerceAtLeast(0) / 3_600_000.0
                val add = (s.chargeKw ?: 0f) * dtH
                p.edit()
                    .putFloat("chgEnergy", p.getFloat("chgEnergy", 0f) + add.toFloat())
                    .putLong("chgLast", now)
                    .apply()
            }
        } else {
            if (p.getBoolean("inCharge", false)) finalizeCharge(s.socPercent, now)
            // Remember a quiet (not charging) reading for the startup-jump check.
            if (s.socPercent != null && s.odometerKm != null) {
                p.edit()
                    .putFloat("lastSoc", s.socPercent)
                    .putFloat("lastOdo", s.odometerKm)
                    .apply()
            }
        }
    }

    private fun finalizeCharge(socAfter: Float?, now: Long) {
        val p = prefs()
        val socStart = p.getFloat("chgSocStart", -1f).takeIf { it >= 0 }?.toDouble()
        val odoStart = p.getFloat("chgOdoStart", -1f).takeIf { it >= 0 }?.toDouble()
        val energy = p.getFloat("chgEnergy", 0f).toDouble()
        p.edit().putBoolean("inCharge", false).apply()

        val socDelta = if (socStart != null && socAfter != null) socAfter - socStart else null
        val kwh = when {
            energy > 0.3 -> energy
            socDelta != null && socDelta >= 2 -> socDelta / 100.0 * Store.batteryKwh
            else -> null
        }
        // Nothing meaningful happened (brief plug-in, noise) — don't litter the journal.
        if (kwh == null && (socDelta == null || socDelta < 2)) return

        Store.add(
            ChargeSession(
                id = now,
                timestamp = p.getLong("chgStart", now),
                network = Network.OTHER,
                odometerKm = odoStart,
                kwh = kwh?.let { kRound(it) },
                socBefore = socStart,
                socAfter = socAfter?.toDouble(),
                auto = true,
                confirmed = false,
            )
        )
    }

    /** Catches a charge that completed while this service was not running. */
    private fun checkStartupJump() {
        val p = prefs()
        if (p.getBoolean("inCharge", false)) return
        val prevSoc = p.getFloat("lastSoc", -1f)
        val prevOdo = p.getFloat("lastOdo", -1f)
        if (prevSoc < 0 || prevOdo < 0) return
        val s = try { CarRepo.refresh() } catch (_: Throwable) { return }
        val soc = s.socPercent ?: return
        val odo = s.odometerKm ?: return
        val socDelta = soc - prevSoc
        if (socDelta >= 5 && kotlin.math.abs(odo - prevOdo) < 2 && !s.charging) {
            Store.add(
                ChargeSession(
                    id = System.currentTimeMillis(),
                    timestamp = System.currentTimeMillis(),
                    network = Network.OTHER,
                    odometerKm = odo.toDouble(),
                    kwh = kRound(socDelta / 100.0 * Store.batteryKwh),
                    socBefore = prevSoc.toDouble(),
                    socAfter = soc.toDouble(),
                    auto = true,
                    confirmed = false,
                    note = "определено по приросту заряда",
                )
            )
        }
        p.edit().putFloat("lastSoc", soc).putFloat("lastOdo", odo).apply()
    }

    private fun kRound(v: Double): Double = Math.round(v * 10.0) / 10.0

    // ---------------------------------------------------------------- notification

    private fun notify(n: android.app.Notification) {
        try {
            androidx.core.app.NotificationManagerCompat.from(this).notify(NOTE_ID, n)
        } catch (_: Throwable) {}
    }

    private fun buildNotification(s: CarState): android.app.Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, JournalActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = when {
            s.charging -> "Идёт зарядка" + (s.chargeKw?.let { " · %.1f кВт".format(it) } ?: "")
            s.socPercent != null -> "Заряд %.0f%%".format(s.socPercent) +
                (s.rangeKm?.let { " · %.0f км".format(it) } ?: "")
            else -> "Бортжурнал активен"
        }
        return NotificationCompat.Builder(this, JournalApp.CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("Слежу за зарядкой и расходом")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onDestroy() {
        loop?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun prefs(): SharedPreferences = getSharedPreferences("journal", Context.MODE_PRIVATE)

    companion object {
        private const val NOTE_ID = 42
        private const val ACTION_STOP = "com.zeekrprobe.journal.STOP"
        private const val IDLE_INTERVAL = 15_000L
        private const val CHARGING_INTERVAL = 10_000L

        fun start(context: Context) {
            val i = Intent(context, SamplingService::class.java)
            try { context.startForegroundService(i) } catch (_: Throwable) {}
        }

        fun stop(context: Context) {
            val i = Intent(context, SamplingService::class.java).setAction(ACTION_STOP)
            try { context.startService(i) } catch (_: Throwable) {}
        }
    }
}
