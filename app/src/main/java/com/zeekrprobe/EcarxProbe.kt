package com.zeekrprobe

import android.content.Context
import java.lang.reflect.Method

/**
 * Zeekr/Geely head units bridge CAN data to Android through the ECARX "AdaptAPI"
 * (`com.ecarx.xui.adaptapi.car.Car`), which lives on the boot classpath and, unlike
 * the standard android.car properties, is readable by ordinary apps without any
 * permission. Everything here is reflection so the app still runs where it's absent.
 *
 * Signal ids and semantics come from two open-source projects that mapped them on
 * real cars: dts88/zeekr-shortcut-car (7X) and maxim-saplin/zee-power-toys (001/007).
 */
class EcarxProbe(private val context: Context) {

    enum class Kind { SENSOR_VALUE, SENSOR_EVENT, FUNCTION, FUNCTION_ZONE, CUSTOM_FLOAT }

    class Sig(val name: String, val kind: Kind, val id: Int, val zone: Int = 0, val unit: String = "")

    /** Signals confirmed on real cars by the community; the live panel reads a subset. */
    val known: List<Sig> = listOf(
        Sig("SPEED", Kind.SENSOR_VALUE, 0x00100100, unit = "m/s"),
        Sig("ODOMETER", Kind.SENSOR_VALUE, 0x00100700, unit = "km"),
        Sig("RANGE", Kind.SENSOR_VALUE, 0x00100800, unit = "km"),
        Sig("BATTERY_SOC", Kind.SENSOR_VALUE, 0x00404000, unit = "%"),
        Sig("BATTERY_LEVEL_RAW", Kind.SENSOR_VALUE, 0x00100A00, unit = "%"),
        Sig("BATTERY_TEMP", Kind.SENSOR_VALUE, 0x00102A00, unit = "°C"),
        Sig("TEMP_OUTSIDE", Kind.SENSOR_VALUE, 0x00100B00, unit = "°C"),
        Sig("TEMP_INSIDE", Kind.SENSOR_VALUE, 0x00100C00, unit = "°C"),
        Sig("ENERGY_CONS_TRIP", Kind.SENSOR_VALUE, 0x00103100, unit = "kWh/100km"),
        Sig("BRAKE_DEPTH", Kind.SENSOR_VALUE, 0x00101300),
        Sig("THROTTLE_DEPTH", Kind.SENSOR_VALUE, 0x00101400, unit = "%"),
        Sig("STEERING", Kind.SENSOR_VALUE, 0x00101000, unit = "rad"),
        Sig("ACCEL_LATERAL", Kind.SENSOR_VALUE, 0x00102E00),
        Sig("ACCEL_LONGITUDINAL", Kind.SENSOR_VALUE, 0x00102D00),
        Sig("GEAR", Kind.SENSOR_EVENT, 0x00200200),
        Sig("GEAR_ALT", Kind.SENSOR_EVENT, 0x00200500),
        Sig("BATTERY_STATE", Kind.SENSOR_EVENT, 0x00201500),
        Sig("DAY_NIGHT", Kind.SENSOR_EVENT, 0x00201000),
        Sig("BELT_DRIVER", Kind.SENSOR_EVENT, 0x00201200),
        Sig("SEAT_DRIVER", Kind.SENSOR_EVENT, 0x00203300),
        Sig("SEAT_PASSENGER", Kind.SENSOR_EVENT, 0x00203400),
        Sig("IGNITION", Kind.FUNCTION, 0x20259000),
        Sig("DRIVE_MODE", Kind.FUNCTION, 0x22010100),
        Sig("POWER_FLOW", Kind.FUNCTION, 0x24010100),
        Sig("PLUG_STATE", Kind.FUNCTION, 0x24130200),
        Sig("CHARGE_TARGET_SOC", Kind.FUNCTION, 0x24100200, unit = "%"),
        Sig("CHARGE_EST_MINUTES", Kind.FUNCTION, 0x24120300, unit = "min"),
        Sig("CHARGE_VOLTAGE", Kind.CUSTOM_FLOAT, 0x24140100, ZONE_GLOBAL, "V"),
        Sig("CHARGE_CURRENT", Kind.CUSTOM_FLOAT, 0x24140200, ZONE_GLOBAL, "A"),
        Sig("CHARGE_POWER", Kind.CUSTOM_FLOAT, 0x2420C000, ZONE_GLOBAL, "kW"),
        Sig("BRAKE_PEDAL", Kind.FUNCTION, 0x20317A00),
        Sig("AUTO_HOLD", Kind.FUNCTION, 0x20060400),
        Sig("AUTO_HOLD_ACTIVE", Kind.FUNCTION, 0x20320600),
        Sig("INDICATOR", Kind.FUNCTION, 0x2A091500),
        Sig("TURN_LEFT", Kind.FUNCTION, 0x21051100),
        Sig("TURN_RIGHT", Kind.FUNCTION, 0x21051200),
        Sig("LOW_BEAM", Kind.FUNCTION, 0x21050100),
        Sig("HIGH_BEAM", Kind.FUNCTION, 0x21050200),
        Sig("DRL", Kind.FUNCTION, 0x21050900),
        Sig("REAR_FOG", Kind.FUNCTION, 0x21050500),
        Sig("LIGHT_SWITCH", Kind.FUNCTION, 0x20040E00),
        Sig("REVERSE_LAMP", Kind.FUNCTION, 0x21050E00),
        Sig("STOP_LAMP", Kind.FUNCTION, 0x21050D00),
        Sig("DOOR_ZONE_1", Kind.FUNCTION_ZONE, 0x21020100, 0x1),
        Sig("DOOR_ZONE_4", Kind.FUNCTION_ZONE, 0x21020100, 0x4),
        Sig("DOOR_REAR_LEFT", Kind.FUNCTION_ZONE, 0x21020100, 0x10),
        Sig("DOOR_REAR_RIGHT", Kind.FUNCTION_ZONE, 0x21020100, 0x40),
        Sig("DOOR_FRUNK", Kind.FUNCTION_ZONE, 0x21020100, 0x10000000),
        Sig("DOOR_TRUNK", Kind.FUNCTION_ZONE, 0x21020100, 0x20000000),
        Sig("CHARGE_PORT", Kind.FUNCTION, 0x21020500),
        Sig("SENTRY_MODE", Kind.FUNCTION, 0x20240100),
        Sig("STOCK_360_SHOWN", Kind.FUNCTION, 0x2031FE00),
        Sig("PARK_ASSIST", Kind.FUNCTION, 0x23030100),
        Sig("AEB", Kind.FUNCTION, 0x20070E00),
        Sig("LKA", Kind.FUNCTION, 0x20070100),
        Sig("FCW_LEVEL", Kind.FUNCTION, 0x200E0200),
    )

    val libraryPresent: Boolean = try {
        Class.forName(CAR_CLASS); true
    } catch (_: Throwable) {
        false
    }

    private var car: Any? = null
    private var sensor: Any? = null
    private var function: Any? = null
    private var carInfo: Any? = null
    private var mSensorValue: Method? = null
    private var mSensorEvent: Method? = null
    private var mFunction: Method? = null
    private var mFunctionZoned: Method? = null
    private var mCustomFloat: Method? = null
    private var mCarInfoInt: Method? = null
    var connectError: String? = null
        private set
    var connectMillis: Long = -1
        private set

    @Synchronized
    fun connect(): Boolean {
        if (car != null) return true
        if (!libraryPresent) {
            connectError = "класс $CAR_CLASS не найден — ECARX AdaptAPI на этой системе нет"
            return false
        }
        val t0 = System.currentTimeMillis()
        try {
            val carClass = Class.forName(CAR_CLASS)
            val create = carClass.methods.firstOrNull { it.name == "create" && it.parameterTypes.size == 1 }
                ?: run { connectError = "у Car нет create(Context)"; return false }
            val c = create.invoke(null, context)
            if (c == null) {
                connectError = "Car.create(context) вернул null — сервис ещё не поднялся или недоступен"
                return false
            }
            car = c
            function = callOrNull(c, "getICarFunction")
            sensor = callOrNull(c, "getSensorManager")
            carInfo = callOrNull(c, "getCarInfoManager")
            sensor?.let {
                mSensorValue = find(it, "getSensorLatestValue", 1)
                mSensorEvent = find(it, "getSensorEvent", 1)
            }
            function?.let {
                mFunction = find(it, "getFunctionValue", 1)
                mFunctionZoned = find(it, "getFunctionValue", 2)
                mCustomFloat = find(it, "getCustomizeFunctionValue", 2)
            }
            carInfo?.let { mCarInfoInt = find(it, "getCarInfoInt", 1) }
            connectMillis = System.currentTimeMillis() - t0
            if (mSensorValue == null && mFunction == null) {
                connectError = "подключился, но нет читающих методов (sensor=${sensor != null}, function=${function != null})"
                return false
            }
            return true
        } catch (t: Throwable) {
            connectError = CarProbe.describe(t)
            return false
        }
    }

    // ---------------------------------------------------------------- reads

    fun sensorValue(id: Int): Float? = numeric(mSensorValue, sensor, id)?.toFloat()
    fun sensorEvent(id: Int): Int? = numeric(mSensorEvent, sensor, id)?.toInt()
    fun functionValue(id: Int): Int? = numeric(mFunction, function, id)?.toInt()
    fun functionValue(id: Int, zone: Int): Int? = numeric(mFunctionZoned, function, id, zone)?.toInt()
    fun customFloat(id: Int, zone: Int): Float? = numeric(mCustomFloat, function, id, zone)?.toFloat()
    fun carInfoInt(id: Int): Int? = numeric(mCarInfoInt, carInfo, id)?.toInt()

    /** Human-readable value of a signal, with the community's sentinel conventions applied. */
    fun read(s: Sig): String {
        if (!connect()) return "нет связи"
        return try {
            when (s.kind) {
                Kind.SENSOR_VALUE -> fmtFloat(sensorValue(s.id), s.unit)
                Kind.SENSOR_EVENT -> fmtInt(sensorEvent(s.id), s.id)
                Kind.FUNCTION -> fmtInt(functionValue(s.id), s.id)
                Kind.FUNCTION_ZONE -> fmtInt(functionValue(s.id, s.zone), s.id)
                Kind.CUSTOM_FLOAT -> fmtFloat(customFloat(s.id, s.zone), s.unit)
            }
        } catch (t: Throwable) {
            CarProbe.describe(t)
        }
    }

    /** Speed in km/h or null if the sensor isn't giving real data. */
    fun speedKmh(): Float? = sensorValue(0x00100100)?.takeIf { isRealFloat(it) }?.times(3.6f)
    fun odometerKm(): Float? = sensorValue(0x00100700)?.takeIf { isRealFloat(it) }
    fun socPercent(): Float? = sensorValue(0x00404000)?.takeIf { isRealFloat(it) }
        ?: sensorValue(0x00100A00)?.takeIf { isRealFloat(it) }
    fun rangeKm(): Float? = sensorValue(0x00100800)?.takeIf { isRealFloat(it) }
    fun outsideC(): Float? = sensorValue(0x00100B00)?.takeIf { isRealFloat(it) }
    fun gear(): String? = sensorEvent(0x00200200)?.let { gearName(it) }
    fun chargeKw(): Float? = customFloat(0x2420C000, ZONE_GLOBAL)?.takeIf { isRealFloat(it) && it != 0f }

    // ---------------------------------------------------------------- report

    fun dump(): String {
        val sb = StringBuilder()
        if (!connect()) {
            sb.append("ECARX AdaptAPI: НЕ ПОДКЛЮЧИЛСЯ\n  ").append(connectError).append('\n')
            sb.append(altSdkPresence())
            return sb.toString()
        }
        sb.append("ECARX AdaptAPI: OK, Car.create за ").append(connectMillis).append(" мс\n")
        sb.append("  car=").append(car?.javaClass?.name).append('\n')
        sb.append("  sensor=").append(sensor?.javaClass?.name).append("  function=").append(function?.javaClass?.name)
            .append("  carInfo=").append(carInfo?.javaClass?.name).append('\n')
        sb.append("  методы: sensorValue=").append(mSensorValue != null).append(" sensorEvent=").append(mSensorEvent != null)
            .append(" function=").append(mFunction != null).append(" zoned=").append(mFunctionZoned != null)
            .append(" customFloat=").append(mCustomFloat != null).append(" carInfoInt=").append(mCarInfoInt != null).append('\n')

        sb.append("\nИзвестные сигналы (из открытых проектов для 7X / 001):\n")
        for (s in known) {
            sb.append("  ").append(s.name).append(" 0x%08x".format(s.id))
            if (s.zone != 0) sb.append(" z=0x").append(Integer.toHexString(s.zone))
            sb.append(" = ").append(read(s)).append('\n')
        }

        sb.append("\nВсе числовые константы из классов AdaptAPI (только те, что отдали реальное значение):\n")
        sb.append(sweepConstants())
        sb.append(altSdkPresence())
        return sb.toString()
    }

    /**
     * Walks every int constant declared by AdaptAPI interfaces on the boot classpath and
     * tries to read it as a sensor / function / car-info id. Values that are sentinels
     * (255, -1, Float.MIN_VALUE…) are counted but not listed, so the output stays readable.
     */
    private fun sweepConstants(): String {
        val sb = StringBuilder()
        val jars = (System.getProperty("java.boot.class.path") ?: "").split(':')
            .filter { p -> listOf("ecarx", "adaptapi", "zeekr", "geely").any { p.lowercase().contains(it) } }
        sb.append("  jar'ы в boot classpath: ").append(if (jars.isEmpty()) "не нашёл по имени" else jars.joinToString()).append('\n')

        val classNames = LinkedHashSet<String>()
        for (jar in jars) {
            try {
                @Suppress("DEPRECATION")
                val dex = dalvik.system.DexFile(jar)
                val entries = dex.entries()
                while (entries.hasMoreElements()) classNames += entries.nextElement()
                dex.close()
            } catch (t: Throwable) {
                sb.append("  DexFile(").append(jar).append("): ").append(CarProbe.describe(t)).append('\n')
            }
        }
        // Always include the interfaces we know by name, in case the jar scan was blocked.
        classNames += listOf(
            "com.ecarx.xui.adaptapi.car.sensor.ISensor",
            "com.ecarx.xui.adaptapi.car.base.ICarFunction",
            "com.ecarx.xui.adaptapi.car.base.ICarInfo",
            "com.ecarx.xui.adaptapi.car.vehicle.IVehicle",
            "com.ecarx.xui.adaptapi.car.bcm.IBCM",
            "com.ecarx.xui.adaptapi.car.hvac.IHvac",
            "com.ecarx.xui.adaptapi.car.electricity.IElectricity",
            "com.ecarx.xui.adaptapi.car.driveMode.IDriveMode",
            "com.ecarx.xui.adaptapi.car.setting.ISetting",
            "com.ecarx.xui.adaptapi.car.adas.IAdas",
            "com.ecarx.xui.adaptapi.car.base.ICarZone",
            "com.ecarx.xui.adaptapi.car.base.VehicleZone",
        )
        val apiClasses = classNames.filter { it.startsWith("com.ecarx.xui.adaptapi") || it.startsWith("com.zeekr") }
        sb.append("  классов адаптера найдено: ").append(apiClasses.size).append('\n')

        var constants = 0
        var real = 0
        val seen = HashSet<Int>()
        for (cn in apiClasses) {
            val cls = try { Class.forName(cn) } catch (_: Throwable) { continue }
            val fields = try { cls.fields } catch (_: Throwable) { continue }
            val lines = ArrayList<String>()
            for (f in fields) {
                if (f.type != Int::class.javaPrimitiveType) continue
                val id = try { f.getInt(null) } catch (_: Throwable) { continue }
                if (id == 0 || !seen.add(id)) continue
                constants++
                val value = probeId(id) ?: continue
                real++
                lines += "    ${f.name} 0x%08x = $value".format(id)
            }
            if (lines.isNotEmpty()) {
                sb.append("  ").append(cls.simpleName).append(":\n")
                lines.forEach { sb.append(it).append('\n') }
            }
        }
        sb.append("  всего констант опрошено: ").append(constants).append(", с реальным значением: ").append(real).append('\n')
        return sb.toString()
    }

    /** Reads an arbitrary id as whatever it looks like; null when every path is a sentinel. */
    private fun probeId(id: Int): String? {
        val hi = id ushr 24
        return try {
            if (hi == 0x00) {
                // 0x00xxxxxx: sensors and car-info share the range
                sensorValue(id)?.takeIf { isRealFloat(it) }?.let { return "sensor %.3f".format(it) }
                sensorEvent(id)?.takeIf { isRealInt(it, id) }?.let { return "event 0x%08x".format(it) }
                carInfoInt(id)?.takeIf { isRealInt(it, id) }?.let { return "info $it" }
                null
            } else {
                functionValue(id)?.takeIf { isRealInt(it, id) }?.let { v ->
                    // Many enum-style functions answer with id+offset; show both.
                    return if (v ushr 8 == id ushr 8) "func 0x%08x (+%d)".format(v, v - id) else "func $v"
                }
                customFloat(id, ZONE_GLOBAL)?.takeIf { isRealFloat(it) && it != 0f }?.let { return "float %.3f".format(it) }
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun altSdkPresence(): String {
        val sb = StringBuilder("\nДругие пути к данным (есть ли класс):\n")
        for (cn in listOf(
            "com.zeekr.sdk.car.impl.CarAPI",
            "com.zeekr.sdk.vehicle.base.observer.IFunctionValueObserver",
            "com.ecarx.xui.adaptapi.diminteraction.DimInteraction",
            "com.ecarx.xui.adaptapi.car.sensor.ISensor\$ISensorListener",
            "com.ecarx.xui.adaptapi.car.base.ICarFunction\$IFunctionValueWatcher",
            "com.zeekrlife.adaptapi.car.impl.ISensorImpl",
        )) {
            val present = try { Class.forName(cn); true } catch (_: Throwable) { false }
            sb.append("  ").append(if (present) "[✓] " else "[ ] ").append(cn).append('\n')
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- helpers

    private fun numeric(m: Method?, target: Any?, vararg args: Any): Number? {
        if (m == null || target == null) return null
        return m.invoke(target, *args) as? Number
    }

    private fun callOrNull(target: Any, name: String): Any? = try {
        target.javaClass.getMethod(name).invoke(target)
    } catch (_: Throwable) {
        null
    }

    private fun find(target: Any, name: String, arity: Int): Method? =
        target.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.size == arity }

    private fun fmtFloat(v: Float?, unit: String): String = when {
        v == null -> "null"
        !isRealFloat(v) -> "нет данных (%s)".format(if (v == Float.MIN_VALUE) "MIN_VALUE" else v.toString())
        unit == "m/s" -> "%.2f м/с = %.1f км/ч".format(v, v * 3.6f)
        else -> "%.3f %s".format(v, unit).trim()
    }

    private fun fmtInt(v: Int?, id: Int): String = when {
        v == null -> "null"
        v == 255 || v == 254 || v == 253 || v == -1 || v == -65535 -> "нет данных ($v)"
        v ushr 8 == id ushr 8 -> "0x%08x (+%d)".format(v, v - id)
        else -> v.toString()
    }

    private fun isRealFloat(v: Float) = v != Float.MIN_VALUE && !v.isNaN() && !v.isInfinite()
    private fun isRealInt(v: Int, id: Int) = v != 255 && v != 254 && v != 253 && v != -1 && v != -65535 && v != id

    private fun gearName(e: Int): String = when (e) {
        0x00200230 -> "P"
        0x00200240 -> "R"
        0x00200210 -> "N"
        0x00200220 -> "D"
        255, -1 -> "—"
        else -> "0x%08x".format(e)
    }

    companion object {
        const val CAR_CLASS = "com.ecarx.xui.adaptapi.car.Car"
        const val ZONE_GLOBAL = 0x80000000.toInt()
    }
}
