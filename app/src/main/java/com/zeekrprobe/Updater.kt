package com.zeekrprobe

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update: download the latest release APK and hand it to the system installer
 * (or APKPure) via an install intent. The head unit's adbd only serves USB, so local
 * ADB (127.0.0.1:5555) times out — hence the intent path, which is what already works
 * for sideloading on this car.
 *
 * Releases come from this repository's GitHub Releases; the build number is the CI
 * run number, baked in as versionCode.
 */
class Updater(private val context: Context, private val log: (String) -> Unit) {

    // apiAssetUrl is the asset's API URL (works for private repos with a token); its
    // Accept: application/octet-stream redirects to the signed download.
    class Release(val build: Int, val tag: String, val apiAssetUrl: String)

    private val api = "https://api.github.com/repos/sergey-ermakovich/test/releases/latest"

    /** GitHub token for a private repo, pasted by the user; empty for a public repo. */
    private val token: String
        get() = context.getSharedPreferences("probe", Context.MODE_PRIVATE).getString("gh_token", "") ?: ""

    private fun HttpURLConnection.auth() {
        setRequestProperty("Accept", "application/vnd.github+json")
        if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
    }

    /** Finds the newest release and the APK asset matching this package. */
    fun latest(): Release? {
        val conn = URL(api).openConnection() as HttpURLConnection
        conn.auth()
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        val code = conn.responseCode
        if (code == 404) throw java.io.IOException(
            if (token.isBlank())
                "репозиторий приватный — вставь токен GitHub (удерживай «Обновить APK»), либо сделай репу публичной"
            else "404 — токен не видит репу (нужен доступ repo/contents:read к sergey-ermakovich/test)")
        if (code == 401) throw java.io.IOException("токен недействителен (401) — проверь его")
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
            if (!name.endsWith(".apk") || name.startsWith("NetInfo")) continue
            val isSys = name.contains("-platform")
            if (isSys == wantSys) { url = a.getString("url"); break }
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
        // Shared with FileProvider (see file_paths.xml → "updates" → files/updates).
        val dir = File(context.filesDir, "updates").apply { mkdirs() }
        val out = File(dir, "update.apk")
        log("Качаю ${rel.tag}…")
        // Asset API URL + octet-stream → GitHub 302-redirects to a signed CDN URL that
        // must be fetched WITHOUT the Authorization header, so follow the redirect by hand.
        val first = (URL(rel.apiAssetUrl).openConnection() as HttpURLConnection).apply {
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/octet-stream")
            instanceFollowRedirects = false
            connectTimeout = 15000
            readTimeout = 60000
        }
        val conn = when (first.responseCode) {
            in 300..399 -> {
                val loc = first.getHeaderField("Location")
                first.disconnect()
                (URL(loc).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000; readTimeout = 120000
                }
            }
            else -> first
        }
        val total = try { conn.contentLengthLong } catch (_: Throwable) { -1L }
        var read = 0L
        var lastPct = -1
        var lastKb = 0L
        val buf = ByteArray(32 * 1024)
        conn.inputStream.use { input ->
            out.outputStream().use { os ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    os.write(buf, 0, n)
                    read += n
                    if (total > 0) {
                        val pct = (read * 100 / total).toInt()
                        if (pct != lastPct) { lastPct = pct; log("Скачиваю… $pct%") }
                    } else {
                        val kb = read / 1024
                        if (kb - lastKb >= 256) { lastKb = kb; log("Скачиваю… $kb КБ") }
                    }
                }
            }
        }
        log("Скачано ${out.length() / 1024} КБ")
        return out
    }

    /**
     * Copies [apk] into the public Downloads folder under [name].apk. This ROM has no
     * unknown-sources settings screen, so our app can't be the installer; instead the user
     * opens the downloaded file in APKPure (which already holds the install permission).
     * Returns the visible path, or null on failure.
     */
    private fun saveToDownloads(apk: File, name: String): String? {
        return try {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, "$name.apk")
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            resolver.openOutputStream(uri)?.use { out -> apk.inputStream().use { it.copyTo(out) } }
            "Загрузки/$name.apk"
        } catch (t: Throwable) {
            log("Не удалось в Загрузки: " + CarProbe.describe(t))
            null
        }
    }

    /** Also tries to open the installer directly, in case some source on this device handles it. */
    private fun tryOpenInstaller(apk: File) {
        try {
            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Throwable) {
            // No handler / blocked — the Downloads + APKPure path is the fallback.
        }
    }

    /** Full flow used by the button. Safe to call off the UI thread. */
    fun updateNow(force: Boolean): String {
        val rel = latest() ?: return "В релизе нет APK для этого пакета"
        val cur = currentBuild()
        if (!force && rel.build in 0..cur) return "Уже последняя версия (build $cur)"
        val apk = download(rel)
        val name = "ZeekrProbe-${rel.build}"
        val where = saveToDownloads(apk, name)
        tryOpenInstaller(apk)
        return if (where != null)
            "Скачано: $where\nОткрой APKPure → установка из файла → Загрузки → $name.apk"
        else
            "Скачал, но не смог положить в Загрузки — поставь вручную"
    }
}
