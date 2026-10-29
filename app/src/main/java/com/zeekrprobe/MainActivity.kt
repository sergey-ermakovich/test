package com.zeekrprobe

import android.app.Activity
import android.app.UiModeManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var live: TextView
    private lateinit var report: TextView
    private lateinit var car: CarProbe
    private lateinit var ecarx: EcarxProbe
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private var lastLocation: Location? = null
    private var satsUsed = 0
    private var satsTotal = 0
    private var accel = floatArrayOf(0f, 0f, 0f)
    private var lastReport = ""

    // Explicit object instead of a SAM lambda: before API 29 the other three methods
    // are abstract, so a lambda would blow up with AbstractMethodError at runtime.
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) { lastLocation = location }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }
    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            satsTotal = status.satelliteCount
            satsUsed = (0 until status.satelliteCount).count { status.usedInFix(it) }
        }
    }
    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            if (e.sensor.type == Sensor.TYPE_ACCELEROMETER) accel = e.values.copyOf(3)
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val liveTick = object : Runnable {
        override fun run() {
            io.execute {
                val text = buildLive()
                ui.post { live.text = text }
            }
            ui.postDelayed(this, 700)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        car = CarProbe(this)
        ecarx = EcarxProbe(this)
        setContentView(buildUi())
        requestMissingPermissions()
        refreshReport()
    }

    override fun onResume() {
        super.onResume()
        startLocation()
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sm.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
        }
        ui.post(liveTick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(liveTick)
        (getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(sensorListener)
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            lm.removeUpdates(locationListener)
            lm.unregisterGnssStatusCallback(gnssCallback)
        } catch (_: Throwable) {
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        startLocation()
        refreshReport()
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        buttons.addView(button("Обновить") { refreshReport() })
        buttons.addView(button("Разрешения") { requestMissingPermissions(force = true) })
        buttons.addView(button("Сохранить") { saveReport() })
        buttons.addView(button("Поделиться") { shareReport() })
        root.addView(buttons)

        live = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(120, 255, 160))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(0, dp(8), 0, dp(8))
            text = "…"
        }
        root.addView(live)

        report = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(220, 220, 220))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextIsSelectable(true)
            text = "Собираю отчёт…"
        }
        val scroll = ScrollView(this).apply {
            addView(report)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(scroll)
        return root
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- live panel

    private fun buildLive(): String {
        val sb = StringBuilder()
        // ECARX AdaptAPI is the path that actually works on Zeekr without permissions.
        if (ecarx.libraryPresent && ecarx.connect()) {
            val kmh = ecarx.speedKmh()
            val odo = ecarx.odometerKm()
            val soc = ecarx.socPercent()
            val range = ecarx.rangeKm()
            val out = ecarx.outsideC()
            val kw = ecarx.chargeKw()
            sb.append("ECARX скорость ").append(kmh?.let { "%.1f км/ч".format(it) } ?: "—")
                .append("   одометр ").append(odo?.let { "%.1f км".format(it) } ?: "—")
                .append("   передача ").append(ecarx.gear() ?: "—").append('\n')
            sb.append("      батарея ").append(soc?.let { "%.1f%%".format(it) } ?: "—")
                .append("   запас ").append(range?.let { "%.0f км".format(it) } ?: "—")
                .append("   улица ").append(out?.let { "%.1f °C".format(it) } ?: "—")
                .append("   зарядка ").append(kw?.let { "%.1f кВт".format(it) } ?: "нет").append('\n')
        } else {
            sb.append("ECARX ").append(if (ecarx.libraryPresent) ecarx.connectError ?: "не подключился" else "AdaptAPI отсутствует").append('\n')
        }
        if (car.libraryPresent) {
            val speedMs = car.readFloat(0x11600207)
            val odoKm = car.readFloat(0x11600204)
            val socWh = car.readFloat(0x11600309)
            val capWh = car.readFloat(0x11600106)
            val rangeM = car.readFloat(0x11600308)
            val rateW = car.readFloat(0x1160030a)
            val gear = car.read(0x11400400)
            val tempC = car.readFloat(0x11600703)
            val pct = if (socWh != null && capWh != null && capWh > 0) "%.1f%%".format(socWh / capWh * 100) else "—"
            sb.append("АВТО  скорость ").append(speedMs?.let { "%.1f км/ч".format(it * 3.6f) } ?: car.read(0x11600207).short())
                .append("   одометр ").append(odoKm?.let { "%.1f км".format(it) } ?: car.read(0x11600204).short()).append('\n')
            sb.append("      батарея ").append(pct).append(socWh?.let { " (%.0f Вт·ч)".format(it) } ?: " " + car.read(0x11600309).short())
                .append("   запас ").append(rangeM?.let { "%.0f км".format(it / 1000) } ?: "—")
                .append("   мощность ").append(rateW?.let { "%.1f кВт".format(it / 1000) } ?: "—").append('\n')
            sb.append("      передача ").append(gear.short()).append("   улица ").append(tempC?.let { "%.1f °C".format(it) } ?: "—").append('\n')
        } else {
            sb.append("АВТО  android.car отсутствует — это не Automotive-система\n")
        }
        val loc = lastLocation
        sb.append("GPS   ")
        if (loc == null) {
            sb.append("нет фикса   спутники ").append(satsUsed).append('/').append(satsTotal)
        } else {
            sb.append("%.1f км/ч  ±%.0f м  %.5f, %.5f  спутники %d/%d  alt %.0f".format(
                loc.speed * 3.6f, loc.accuracy, loc.latitude, loc.longitude, satsUsed, satsTotal, loc.altitude))
        }
        sb.append('\n')
        sb.append("ACC   x %+.2f  y %+.2f  z %+.2f".format(accel[0], accel[1], accel[2]))
        return sb.toString()
    }

    private fun String.short(): String = if (startsWith("ERR")) {
        when {
            contains("SecurityException") -> "нет прав"
            contains("IllegalArgumentException") -> "нет свойства"
            else -> take(28)
        }
    } else this

    // ---------------------------------------------------------------- full report

    private fun refreshReport() {
        report.text = "Собираю отчёт…"
        io.execute {
            val text = buildReport()
            lastReport = text
            ui.post { report.text = text }
        }
    }

    private fun buildReport(): String {
        val sb = StringBuilder()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        sb.append("Zeekr Probe ").append(stamp).append("\n\n")
        section(sb, "УСТРОЙСТВО") { deviceInfo() }
        section(sb, "РАЗРЕШЕНИЯ") { permissionsInfo() }
        section(sb, "ECARX ADAPTAPI (com.ecarx.xui.adaptapi)") { ecarx.dump() }
        section(sb, "CAR SERVICE (android.car)") { car.dumpAll() }
        section(sb, "СИСТЕМНЫЕ БИБЛИОТЕКИ") { sharedLibraries() }
        section(sb, "ПАКЕТЫ ПРОИЗВОДИТЕЛЯ") { oemPackages() }
        section(sb, "СИСТЕМНЫЕ СЕРВИСЫ") { systemServices() }
        section(sb, "ЛОКАЦИЯ") { locationInfo() }
        section(sb, "ДАТЧИКИ") { sensorsInfo() }
        section(sb, "SYSTEM PROPERTIES (getprop, фильтр)") { getProps() }
        return sb.toString()
    }

    private inline fun section(sb: StringBuilder, title: String, body: () -> String) {
        sb.append("=== ").append(title).append(" ===\n")
        sb.append(
            try {
                body()
            } catch (t: Throwable) {
                "СЕКЦИЯ УПАЛА: " + CarProbe.describe(t)
            }
        )
        sb.append("\n\n")
    }

    private fun deviceInfo(): String {
        val dm = resources.displayMetrics
        val uiMode = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        val pm = packageManager
        val b = StringBuilder()
        b.append("Производитель/бренд: ").append(Build.MANUFACTURER).append(" / ").append(Build.BRAND).append('\n')
        b.append("Модель: ").append(Build.MODEL).append("  устройство: ").append(Build.DEVICE).append("  продукт: ").append(Build.PRODUCT).append('\n')
        b.append("Плата: ").append(Build.BOARD).append("  железо: ").append(Build.HARDWARE).append("  SoC: ")
            .append(if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else "n/a").append('\n')
        b.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")  патч ")
            .append(Build.VERSION.SECURITY_PATCH).append('\n')
        b.append("Прошивка: ").append(Build.DISPLAY).append('\n')
        b.append("Fingerprint: ").append(Build.FINGERPRINT).append('\n')
        b.append("ABI: ").append(Build.SUPPORTED_ABIS.joinToString()).append('\n')
        b.append("Экран: ").append(dm.widthPixels).append('x').append(dm.heightPixels).append(" px, density ").append(dm.density)
            .append(" (").append(dm.densityDpi).append(" dpi), ")
            .append(if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "альбом" else "портрет").append('\n')
        b.append("UI mode: ").append(uiMode.currentModeType).append(if (uiMode.currentModeType == Configuration.UI_MODE_TYPE_CAR) " (CAR)" else "").append('\n')
        b.append("Feature automotive: ").append(pm.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)).append('\n')
        b.append("Feature GPS: ").append(pm.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS))
            .append("  touchscreen: ").append(pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN))
            .append("  bluetooth: ").append(pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH))
            .append("  wifi: ").append(pm.hasSystemFeature(PackageManager.FEATURE_WIFI)).append('\n')
        b.append("Класс android.car найден: ").append(car.libraryPresent).append('\n')
        b.append("Locale: ").append(Locale.getDefault()).append("  TZ: ").append(java.util.TimeZone.getDefault().id).append('\n')
        return b.toString()
    }

    private fun permissionsInfo(): String {
        val info = packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        val requested = info.requestedPermissions ?: emptyArray()
        return requested.joinToString("\n") { p ->
            val granted = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
            val level = try {
                val pi = packageManager.getPermissionInfo(p, 0)
                when (pi.protection) {
                    android.content.pm.PermissionInfo.PROTECTION_NORMAL -> "normal"
                    android.content.pm.PermissionInfo.PROTECTION_DANGEROUS -> "dangerous"
                    android.content.pm.PermissionInfo.PROTECTION_SIGNATURE -> "signature" +
                        (if (pi.protectionFlags != 0) "|0x${Integer.toHexString(pi.protectionFlags)}" else "")
                    else -> "lvl=${pi.protection}"
                }
            } catch (_: Throwable) {
                "НЕ ОБЪЯВЛЕНО В СИСТЕМЕ"
            }
            "${if (granted) "[✓]" else "[ ]"} ${p.removePrefix("android.")}  ($level)"
        }
    }

    private fun sharedLibraries(): String {
        val libs = packageManager.getSharedLibraries(0)
        return "Всего: ${libs.size}\n" + libs.joinToString("\n") { "  ${it.name}" }
    }

    private val oemHints = listOf("zeekr", "geely", "ecarx", "lynk", "car", "vehicle", "vhal", "hvac", "flyme", "xui", "adapt")

    private fun oemPackages(): String {
        val b = StringBuilder()
        val all = packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        b.append("Всего пакетов: ").append(all.size).append('\n')
        val hits = all.filter { p -> oemHints.any { p.packageName.lowercase().contains(it) } }
            .sortedBy { it.packageName }
        b.append("Похожих на автомобильные: ").append(hits.size).append('\n')
        for (p in hits) {
            b.append("  ").append(p.packageName)
            if (p.versionName != null) b.append("  v").append(p.versionName)
            b.append('\n')
            // Permissions a package *defines* reveal which private APIs it guards.
            val defined = p.permissions
            if (defined != null && defined.isNotEmpty()) {
                for (perm in defined.take(40)) {
                    b.append("      объявляет: ").append(perm.name).append(" (lvl=").append(perm.protection).append(")\n")
                }
                if (defined.size > 40) b.append("      … ещё ").append(defined.size - 40).append('\n')
            }
        }
        return b.toString()
    }

    private fun systemServices(): String {
        return try {
            val cls = Class.forName("android.os.ServiceManager")
            @Suppress("UNCHECKED_CAST")
            val names = cls.getMethod("listServices").invoke(null) as Array<String>
            val interesting = names.filter { n -> oemHints.any { n.lowercase().contains(it) } }.sorted()
            "Всего сервисов: ${names.size}\nАвтомобильные (${interesting.size}):\n" +
                interesting.joinToString("\n") { "  $it" }
        } catch (t: Throwable) {
            "ServiceManager.listServices недоступен: " + CarProbe.describe(t)
        }
    }

    private fun locationInfo(): String {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val b = StringBuilder()
        b.append("Провайдеры: ").append(lm.allProviders.joinToString()).append('\n')
        for (p in lm.allProviders) b.append("  ").append(p).append(": ").append(if (lm.isProviderEnabled(p)) "включён" else "выключен").append('\n')
        val fine = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        b.append("Разрешение FINE_LOCATION: ").append(fine).append('\n')
        if (fine) {
            for (p in lm.allProviders) {
                val l = try { lm.getLastKnownLocation(p) } catch (_: Throwable) { null }
                b.append("  last ").append(p).append(": ")
                b.append(if (l == null) "нет" else "%.5f, %.5f ±%.0f м, %d с назад".format(
                    l.latitude, l.longitude, l.accuracy, (System.currentTimeMillis() - l.time) / 1000))
                b.append('\n')
            }
        }
        return b.toString()
    }

    private fun sensorsInfo(): String {
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val list = sm.getSensorList(Sensor.TYPE_ALL)
        return "Всего: ${list.size}\n" + list.joinToString("\n") {
            "  [${it.type}] ${it.name} — ${it.vendor}"
        }
    }

    private val propHints = listOf("ro.product", "ro.build", "ro.board", "ro.hardware", "ro.soc", "zeekr", "geely", "ecarx",
        "vehicle", "car", "vhal", "persist.sys", "ro.vendor", "ro.boot.", "ro.debuggable", "ro.secure", "ro.adb", "service.adb")

    private fun getProps(): String {
        val proc = Runtime.getRuntime().exec("getprop")
        val lines = BufferedReader(InputStreamReader(proc.inputStream)).readLines()
        val hits = lines.filter { l -> propHints.any { l.contains(it) } }
        return "Всего строк: ${lines.size}, показано ${hits.size}\n" + hits.joinToString("\n")
    }

    // ---------------------------------------------------------------- actions

    private fun requestMissingPermissions(force: Boolean = false) {
        val info = packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        val missing = (info.requestedPermissions ?: emptyArray())
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            if (force) toast("Все разрешения уже выданы")
            return
        }
        requestPermissions(missing.toTypedArray(), 1)
    }

    private fun startLocation() {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            if (lm.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener)
            }
            @Suppress("DEPRECATION")
            lm.registerGnssStatusCallback(gnssCallback)
        } catch (t: Throwable) {
            toast("GPS: " + CarProbe.describe(t))
        }
    }

    private fun reportFileName() = "zeekr-probe-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"

    private fun saveReport() {
        val text = lastReport.ifEmpty { report.text.toString() }
        val name = reportFileName()
        val saved = ArrayList<String>()
        try {
            val dir = getExternalFilesDir(null) ?: filesDir
            File(dir, name).writeText(text)
            saved += File(dir, name).absolutePath
        } catch (t: Throwable) {
            saved += "app dir: " + CarProbe.describe(t)
        }
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                    saved += "Downloads/$name"
                }
            } catch (t: Throwable) {
                saved += "Downloads: " + CarProbe.describe(t)
            }
        }
        toast("Сохранено:\n" + saved.joinToString("\n"))
    }

    private fun shareReport() {
        val text = lastReport.ifEmpty { report.text.toString() }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, reportFileName())
            putExtra(Intent.EXTRA_TEXT, text)
        }
        try {
            startActivity(Intent.createChooser(intent, "Отправить отчёт"))
        } catch (t: Throwable) {
            toast("Некуда отправить: " + CarProbe.describe(t))
        }
    }

    private fun toast(msg: String) = ui.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
}
