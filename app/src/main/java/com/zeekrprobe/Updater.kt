package com.zeekrprobe

import android.content.Context
import dadb.AdbKeyPair
import dadb.Dadb
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update over the head unit's local ADB. adbd listens on tcp 5555, so the app
 * connects to 127.0.0.1:5555 and runs the package manager as the shell user, which
 * avoids the "allow installs from this source" dialog the car settings can't open.
 *
 * Releases come from this repository's GitHub Releases; the build number is the CI
 * run number, baked in as versionCode.
 */
class Updater(private val context: Context, private val log: (String) -> Unit) {

    class Release(val build: Int, val tag: String, val apkUrl: String)

    private val api = "https://api.github.com/repos/sergey-ermakovich/test/releases/latest"

    /** Finds the newest release and the APK asset matching this package. */
    fun latest(): Release? {
        val conn = URL(api).openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        val body = conn.inputStream.bufferedReader().readText()
        val json = JSONObject(body)
        val tag = json.getString("tag_name")
        val build = tag.substringAfterLast('-').toIntOrNull() ?: -1
        val wantSys = context.packageName.endsWith(".sys")
        val assets = json.getJSONArray("assets")
        var url: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.getString("name")
            if (!name.endsWith(".apk")) continue
            val isSys = name.contains("-platform")
            if (isSys == wantSys) { url = a.getString("browser_download_url"); break }
        }
        return url?.let { Release(build, tag, it) }
    }

    private fun currentBuild(): Int = try {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
    } catch (_: Throwable) {
        -1
    }

    /** Downloads the APK to the app cache and returns the file. */
    private fun download(rel: Release): File {
        val out = File(context.cacheDir, "update.apk")
        log("Качаю ${rel.tag}…")
        val conn = URL(rel.apkUrl).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15000
        conn.readTimeout = 60000
        conn.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
        log("Скачано ${out.length() / 1024} КБ")
        return out
    }

    /** Installs [apk] through local ADB (pm install as shell). Returns the device banner. */
    private fun installViaAdb(apk: File): String {
        // A persistent key so the car only ever has to authorise this app once.
        val dir = File(context.filesDir, "adb").apply { mkdirs() }
        val priv = File(dir, "adbkey")
        val pub = File(dir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) {
            log("Создаю ADB-ключ…")
            AdbKeyPair.generate(priv, pub)
        }
        val keyPair = AdbKeyPair.read(priv, pub)
        log("Подключаюсь к adb 127.0.0.1:5555…")
        Dadb.create("127.0.0.1", 5555, keyPair).use { dadb ->
            log("Ставлю APK через pm install…")
            dadb.install(apk)
        }
        return "установлено"
    }

    /** Full flow used by the button. Safe to call off the UI thread. */
    fun updateNow(force: Boolean): String {
        val rel = latest() ?: return "В релизе нет APK для этого пакета"
        val cur = currentBuild()
        if (!force && rel.build in 0..cur) return "Уже последняя версия (build $cur)"
        val apk = download(rel)
        return try {
            installViaAdb(apk)
            "Готово: ${rel.tag} — приложение сейчас перезапустится"
        } catch (t: Throwable) {
            "ADB-установка не удалась: " + CarProbe.describe(t) +
                "\nПроверь, что на экране машины подтверждён запрос ADB-отладки."
        } finally {
            apk.delete()
        }
    }
}
