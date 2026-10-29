package com.zeekrprobe

import android.content.Context

/**
 * Talks to Android Automotive's car-service purely through reflection, so the app
 * runs unchanged on a phone (no android.car at all) and on a head unit where the
 * library exists but most properties are locked behind signature permissions.
 *
 * Every failure is reported as text instead of thrown: the whole point of the probe
 * is to see *which* calls fail and *how*.
 */
class CarProbe(private val context: Context) {

    /** Well-known VHAL property ids, used when VehiclePropertyIds can't be reflected. */
    private val knownIds: Map<Int, String> = mapOf(
        0x11100100 to "INFO_VIN",
        0x11100101 to "INFO_MAKE",
        0x11100102 to "INFO_MODEL",
        0x11400103 to "INFO_MODEL_YEAR",
        0x11600106 to "INFO_EV_BATTERY_CAPACITY",
        0x11410107 to "INFO_EV_CONNECTOR_TYPE",
        0x11400109 to "INFO_EV_PORT_LOCATION",
        0x11600204 to "PERF_ODOMETER",
        0x11600207 to "PERF_VEHICLE_SPEED",
        0x11600208 to "PERF_VEHICLE_SPEED_DISPLAY",
        0x11600209 to "PERF_STEERING_ANGLE",
        0x11600305 to "ENGINE_RPM",
        0x11600307 to "FUEL_LEVEL",
        0x11600308 to "RANGE_REMAINING",
        0x11600309 to "EV_BATTERY_LEVEL",
        0x1160030a to "EV_BATTERY_INSTANTANEOUS_CHARGE_RATE",
        0x11200301 to "EV_CHARGE_PORT_OPEN",
        0x11200302 to "EV_CHARGE_PORT_CONNECTED",
        0x11400400 to "GEAR_SELECTION",
        0x11400401 to "CURRENT_GEAR",
        0x11200402 to "PARKING_BRAKE_ON",
        0x11200407 to "NIGHT_MODE",
        0x11400409 to "IGNITION_STATE",
        0x1120040a to "ABS_ACTIVE",
        0x1120040b to "TRACTION_CONTROL_ACTIVE",
        0x15400500 to "HVAC_FAN_SPEED",
        0x15600503 to "HVAC_TEMPERATURE_SET",
        0x15200505 to "HVAC_AC_ON",
        0x11600703 to "ENV_OUTSIDE_TEMPERATURE",
        0x16400b00 to "DOOR_POS",
        0x16200b02 to "DOOR_LOCK",
        0x21600a01 to "TIRE_PRESSURE",
        0x11400a0a to "EV_CHARGE_STATE",
        0x11600a0b to "EV_CHARGE_TIME_REMAINING",
        0x11200a0c to "EV_REGENERATIVE_BRAKING_STATE",
    )

    /** Properties worth polling live while driving. */
    val liveIds = listOf(
        0x11600207, // PERF_VEHICLE_SPEED
        0x11600204, // PERF_ODOMETER
        0x11600309, // EV_BATTERY_LEVEL
        0x11600106, // INFO_EV_BATTERY_CAPACITY
        0x11600308, // RANGE_REMAINING
        0x1160030a, // EV_BATTERY_INSTANTANEOUS_CHARGE_RATE
        0x11400400, // GEAR_SELECTION
        0x11600703, // ENV_OUTSIDE_TEMPERATURE
    )

    val libraryPresent: Boolean = try {
        Class.forName("android.car.Car"); true
    } catch (_: Throwable) {
        false
    }

    private var car: Any? = null
    private var propertyManager: Any? = null
    var connectError: String? = null
        private set

    private val idNames: Map<Int, String> by lazy { reflectPropertyNames() + knownIds }

    fun nameOf(id: Int): String = idNames[id] ?: "0x%08x".format(id)

    /** Connects (once) and returns the CarPropertyManager, or null with [connectError] set. */
    @Synchronized
    fun manager(): Any? {
        if (propertyManager != null) return propertyManager
        if (!libraryPresent) {
            connectError = "класс android.car.Car не найден — это не Android Automotive"
            return null
        }
        try {
            val carClass = Class.forName("android.car.Car")
            // Car.createCar(Context) exists since API 29 and connects synchronously.
            val create = carClass.getMethod("createCar", Context::class.java)
            car = create.invoke(null, context)
            if (car == null) {
                connectError = "Car.createCar(context) вернул null — car-service не запущен или недоступен"
                return null
            }
            val getManager = carClass.getMethod("getCarManager", String::class.java)
            propertyManager = getManager.invoke(car, "property")
            if (propertyManager == null) connectError = "getCarManager(\"property\") вернул null"
            return propertyManager
        } catch (t: Throwable) {
            connectError = describe(t)
            return null
        }
    }

    /** One line per property: name, type, access, live value or the error from reading it. */
    fun dumpAll(): String {
        val sb = StringBuilder()
        val pm = manager()
        if (pm == null) {
            sb.append("Подключение к car-service: НЕ УДАЛОСЬ\n  ").append(connectError).append('\n')
            return sb.toString()
        }
        sb.append("Подключение к car-service: OK (").append(pm.javaClass.name).append(")\n")

        val configs: List<Any> = try {
            @Suppress("UNCHECKED_CAST")
            pm.javaClass.getMethod("getPropertyList").invoke(pm) as List<Any>
        } catch (t: Throwable) {
            sb.append("getPropertyList(): ").append(describe(t)).append('\n')
            emptyList()
        }
        sb.append("Свойств в списке: ").append(configs.size).append('\n')

        val seen = HashSet<Int>()
        for (cfg in configs) {
            try {
                val id = cfg.javaClass.getMethod("getPropertyId").invoke(cfg) as Int
                seen += id
                val typeClass = cfg.javaClass.getMethod("getPropertyType").invoke(cfg) as Class<*>
                val access = cfg.javaClass.getMethod("getAccess").invoke(cfg)
                val areas = (cfg.javaClass.getMethod("getAreaIds").invoke(cfg) as IntArray)
                sb.append("  ").append(nameOf(id)).append(" [").append(typeClass.simpleName).append(", access=").append(access).append("]")
                val areaList = if (areas.isEmpty()) intArrayOf(0) else areas
                for (area in areaList.take(4)) {
                    sb.append("\n      area 0x").append(Integer.toHexString(area)).append(" = ").append(read(id, area, typeClass))
                }
                sb.append('\n')
            } catch (t: Throwable) {
                sb.append("  <config error: ").append(describe(t)).append(">\n")
            }
        }

        // The list is usually filtered by the caller's permissions, so also poke the
        // well-known ids directly: a SecurityException proves the property exists.
        sb.append("\nПрямой опрос известных свойств (не из списка):\n")
        for ((id, name) in knownIds) {
            if (id in seen) continue
            sb.append("  ").append(name).append(" = ").append(read(id, 0)).append('\n')
        }
        return sb.toString()
    }

    /** Reads a property as a human string; errors come back as short text, never thrown. */
    fun read(id: Int, area: Int = 0, type: Class<*>? = null): String {
        val pm = manager() ?: return "нет car-service"
        return try {
            val value = getPropertyValue(pm, id, area, type) ?: return "null"
            val raw = value.javaClass.getMethod("getValue").invoke(value)
            val status = try {
                value.javaClass.getMethod("getStatus").invoke(value)
            } catch (_: Throwable) {
                null
            }
            val text = when (raw) {
                is FloatArray -> raw.joinToString(prefix = "[", postfix = "]")
                is IntArray -> raw.joinToString(prefix = "[", postfix = "]")
                is LongArray -> raw.joinToString(prefix = "[", postfix = "]")
                is Array<*> -> raw.joinToString(prefix = "[", postfix = "]")
                else -> raw.toString()
            }
            if (status != null && status != 0) "$text (status=$status)" else text
        } catch (t: Throwable) {
            describe(t)
        }
    }

    fun readFloat(id: Int, area: Int = 0): Float? = read(id, area).toFloatOrNull()

    /**
     * getProperty(int, int) is public since API 30. Older builds only have the typed
     * getProperty(Class, int, int), which insists on the exact boxed class, so we try
     * the config's type first and then the usual suspects.
     */
    private fun getPropertyValue(pm: Any, id: Int, area: Int, type: Class<*>?): Any? {
        val intT = Int::class.javaPrimitiveType
        try {
            val untyped = pm.javaClass.getMethod("getProperty", intT, intT)
            return untyped.invoke(pm, id, area)
        } catch (_: NoSuchMethodException) {
            // fall through to the typed variant
        }
        val typed = pm.javaClass.getMethod("getProperty", Class::class.java, intT, intT)
        val candidates = listOfNotNull(type) + listOf(
            java.lang.Float::class.java, java.lang.Integer::class.java, java.lang.Boolean::class.java,
            java.lang.String::class.java, java.lang.Long::class.java, FloatArray::class.java, IntArray::class.java
        )
        var last: Throwable? = null
        for (cls in candidates.distinct()) {
            try {
                return typed.invoke(pm, cls, id, area)
            } catch (t: java.lang.reflect.InvocationTargetException) {
                val cause = t.cause
                if (cause is IllegalArgumentException) { last = t; continue }
                throw t
            }
        }
        throw last ?: IllegalStateException("no typed getProperty matched")
    }

    private fun reflectPropertyNames(): Map<Int, String> = try {
        val cls = Class.forName("android.car.VehiclePropertyIds")
        cls.fields
            .filter { it.type == Int::class.javaPrimitiveType }
            .associate { (it.get(null) as Int) to it.name }
    } catch (_: Throwable) {
        emptyMap()
    }

    companion object {
        /** Unwraps reflection wrappers so the real cause (usually SecurityException) is visible. */
        fun describe(t: Throwable): String {
            var cause: Throwable = t
            while (cause is java.lang.reflect.InvocationTargetException && cause.cause != null) {
                cause = cause.cause!!
            }
            val msg = cause.message?.lineSequence()?.firstOrNull()?.take(160)
            return "ERR ${cause.javaClass.simpleName}" + (if (msg.isNullOrBlank()) "" else ": $msg")
        }
    }
}
