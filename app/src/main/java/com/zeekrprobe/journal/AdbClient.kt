package com.zeekrprobe.journal

import android.content.Context
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.io.IOException

/**
 * In-device ADB client. The head unit runs adbd listening on TCP 5555 (confirmed by the
 * diagnostics: service.adb.tcp.port=5555, init.svc.adbd=running). This connects to it from the
 * app itself — so APKs install and permissions get granted straight from the car, with no
 * laptop, Wi-Fi link, cable or flash drive. First connection shows the "Allow debugging?"
 * prompt on the head unit; tap allow once.
 *
 * Uses the dadb library (dev.mobile:dadb). Our own RSA key is kept in filesDir so the grant
 * sticks; we never rely on ~/.android/adbkey (which does not exist on Android).
 */
object AdbClient {

    private fun keyPair(ctx: Context): AdbKeyPair {
        val dir = File(ctx.filesDir, "adb").apply { mkdirs() }
        val priv = File(dir, "adbkey")
        val pub = File(dir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        return AdbKeyPair.read(priv, pub)
    }

    /** Opens a connection to the local adbd. Blocking — call off the main thread. */
    fun connect(ctx: Context): Dadb {
        val kp = keyPair(ctx)
        var last: Throwable? = null
        for (host in listOf("127.0.0.1", "localhost")) {
            try {
                return Dadb.create(host, 5555, kp, connectTimeout = 8000, socketTimeout = 20000)
            } catch (t: Throwable) {
                last = t
            }
        }
        throw (last ?: IOException("не удалось подключиться к adbd на 5555"))
    }

    fun test(ctx: Context): String = connect(ctx).use { d ->
        val r = d.shell("echo ok; id; getprop ro.build.type")
        "Подключился ✓\n" + r.allOutput.trim()
    }

    fun install(ctx: Context, apk: File): String = connect(ctx).use { d ->
        "Установка: " + d.install(apk).toString()
    }

    fun grantCarPerms(ctx: Context): String = connect(ctx).use { d ->
        val perms = listOf(
            "CAR_SPEED", "CAR_ENERGY", "CAR_MILEAGE", "CAR_INFO",
            "CAR_POWERTRAIN", "CAR_EXTERIOR_ENVIRONMENT", "CAR_ENERGY_PORTS",
        )
        val sb = StringBuilder()
        for (p in perms) {
            val r = d.shell("pm grant ${ctx.packageName} android.car.permission.$p")
            sb.append(p).append(": ")
                .append(if (r.exitCode == 0) "ok" else "— ${r.errorOutput.trim().ifEmpty { "код ${r.exitCode}" }}")
                .append('\n')
        }
        sb.toString().trim()
    }

    fun shell(ctx: Context, cmd: String): String = connect(ctx).use { d ->
        d.shell(cmd).allOutput.trim().ifEmpty { "(пусто)" }
    }
}
