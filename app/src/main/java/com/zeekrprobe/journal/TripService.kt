package com.zeekrprobe.journal

import android.Manifest
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
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
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service that records a trip (GPS + ECARX speed/odometer) once per second. Being a
 * foreground service, it keeps running when the user switches to the map or music — unlike the
 * old Activity-bound logger. On stop it writes the CSV to Downloads and uploads it to the drop
 * box if one is configured (same prefs the probe used).
 */
class TripService : Service(), LocationListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    @Volatile private var lastLoc: Location? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        CarRepo.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTrip()
            return START_NOT_STICKY
        }
        startTrip()
        return START_STICKY
    }

    private fun startTrip() {
        if (loop != null) return
        startForeground(NOTE_ID, buildNotification(null))
        val startOdo = try { CarRepo.refresh().odometerKm } catch (_: Throwable) { null }
        TripRecorder.begin(startOdo)
        requestGps()
        loop = scope.launch {
            while (scope.isActive) {
                val s = try { CarRepo.refresh() } catch (_: Throwable) { CarState() }
                try { TripRecorder.addRow(s.speedKmh, lastLoc, s.odometerKm, s.socPercent, s.outsideC) } catch (_: Throwable) {}
                notify(buildNotification(TripRecorder.state.value))
                delay(1000)
            }
        }
    }

    private fun stopTrip() {
        loop?.cancel()
        loop = null
        try { locationManager().removeUpdates(this) } catch (_: Throwable) {}
        val csv = TripRecorder.finish()
        // Save + upload off the main thread, then tear the service down.
        Thread {
            if (csv != null) saveAndUpload(csv)
            Handler(Looper.getMainLooper()).post {
                try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) {}
                stopSelf()
            }
        }.start()
    }

    private fun locationManager() = getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private fun requestGps() {
        try {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
            val lm = locationManager()
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
            lastLoc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        } catch (_: Throwable) {}
    }

    override fun onLocationChanged(location: Location) { lastLoc = location }
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    private fun saveAndUpload(text: String) {
        val name = "trip-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        // Public Downloads.
        try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "$name.csv")
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            uri?.let { contentResolver.openOutputStream(it)?.use { os -> os.write(text.toByteArray()) } }
        } catch (_: Throwable) {}
        // Drop box (optional), same prefs keys the probe used.
        try {
            val p = getSharedPreferences("probe", MODE_PRIVATE)
            val url = p.getString("url", "") ?: ""
            if (url.isNotBlank()) {
                val token = p.getString("token", "") ?: ""
                val full = url + (if (url.contains('?')) "&" else "?") +
                    "token=" + URLEncoder.encode(token, "UTF-8") +
                    "&name=" + URLEncoder.encode("$name.csv", "UTF-8")
                val conn = URL(full).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                conn.outputStream.use { it.write(text.toByteArray()) }
                conn.responseCode
            }
        } catch (_: Throwable) {}
    }

    private fun notify(n: android.app.Notification) {
        try { androidx.core.app.NotificationManagerCompat.from(this).notify(NOTE_ID, n) } catch (_: Throwable) {}
    }

    private fun buildNotification(s: TripState?): android.app.Notification {
        val open = PendingIntent.getActivity(
            this, 1,
            Intent(this, JournalActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = if (s != null) {
            "Поездка: %.1f км".format(Locale.US, s.gpsKm)
        } else "Поездка записывается"
        val text = if (s != null) {
            "прибор %.0f / GPS %.0f км/ч".format(Locale.US, s.speedEcarx, s.speedGps)
        } else "жду данные…"
        return NotificationCompat.Builder(this, JournalApp.CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onDestroy() {
        loop?.cancel()
        scope.cancel()
        try { locationManager().removeUpdates(this) } catch (_: Throwable) {}
        super.onDestroy()
    }

    companion object {
        private const val NOTE_ID = 43
        private const val ACTION_STOP = "com.zeekrprobe.journal.STOP_TRIP"

        fun start(context: Context) {
            try { context.startForegroundService(Intent(context, TripService::class.java)) } catch (_: Throwable) {}
        }

        fun stop(context: Context) {
            try { context.startService(Intent(context, TripService::class.java).setAction(ACTION_STOP)) } catch (_: Throwable) {}
        }
    }
}
