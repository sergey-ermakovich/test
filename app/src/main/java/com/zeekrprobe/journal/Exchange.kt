package com.zeekrprobe.journal

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class RemoteFile(val name: String, val size: Long, val ts: Long)

/**
 * Talks to exchange.php over plain HTTP — the one transport that works on this head unit
 * (the browser can't download, USB/ADB/flash all failed, Bluetooth is painful). Lists files,
 * downloads them straight into the public Downloads folder via MediaStore (which APKPure and
 * the file manager can see), and uploads a local file with a raw POST.
 */
object Exchange {

    private fun base(): String = Store.exchangeUrl
    private fun tokenParam(): String = "token=" + URLEncoder.encode(Store.exchangeToken, "UTF-8")

    private fun withParam(extra: String): String {
        val u = base()
        val sep = if (u.contains('?')) "&" else "?"
        return u + sep + tokenParam() + extra
    }

    /** GET ?json — the file list. Throws on network/parse error. */
    fun list(): List<RemoteFile> {
        val conn = (URL(withParam("&json=1")).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 20000
        }
        val code = conn.responseCode
        if (code == 403) throw java.io.IOException("токен не подходит (403)")
        if (code >= 400) throw java.io.IOException("сервер вернул $code")
        val body = conn.inputStream.bufferedReader().readText()
        val arr = JSONArray(body)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            RemoteFile(o.getString("name"), o.optLong("size"), o.optLong("ts"))
        }
    }

    /** Downloads [name] into public Downloads, reporting percent. Returns the visible path. */
    fun download(context: Context, name: String, onProgress: (Int) -> Unit = {}): String {
        val url = withParam("&dl=" + URLEncoder.encode(name, "UTF-8"))
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 120000
        }
        val code = conn.responseCode
        if (code >= 400) throw java.io.IOException("сервер вернул $code")
        val total = try { conn.contentLengthLong } catch (_: Throwable) { -1L }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, guessMime(name))
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw java.io.IOException("не удалось создать файл в «Загрузки»")
        val os = resolver.openOutputStream(uri)
            ?: throw java.io.IOException("не удалось записать в «Загрузки»")
        os.use { out ->
            conn.inputStream.use { input ->
                val buf = ByteArray(64 * 1024)
                var read = 0L
                var lastPct = -1
                var lastKb = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    read += n
                    if (total > 0) {
                        val p = (read * 100 / total).toInt()
                        if (p != lastPct) { lastPct = p; onProgress(p) }
                    } else {
                        val kb = read / 1024
                        if (kb - lastKb >= 512) { lastKb = kb; onProgress(-1) }
                    }
                }
            }
        }
        return "Загрузки/$name"
    }

    /** Uploads the content behind [uri] to the server under [name]. Returns a short status. */
    fun upload(context: Context, uri: Uri, name: String): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw java.io.IOException("не удалось прочитать файл")
        val url = withParam("&put=" + URLEncoder.encode(name, "UTF-8"))
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15000; readTimeout = 120000
            setRequestProperty("Content-Type", "application/octet-stream")
        }
        conn.outputStream.use { it.write(bytes) }
        val code = conn.responseCode
        if (code >= 400) throw java.io.IOException("сервер вернул $code")
        return "Загружено: $name"
    }

    /** Names (→ size) already present in the public Downloads folder that this app can see. */
    fun localIndex(context: Context): Map<String, Long> {
        val map = HashMap<String, Long>()
        try {
            val proj = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE)
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, proj, null, null, null,
            )?.use { c ->
                val ni = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val si = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                while (c.moveToNext()) {
                    val nm = c.getString(ni) ?: continue
                    map[nm] = c.getLong(si)
                }
            }
        } catch (_: Throwable) {}
        return map
    }

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "apk" -> "application/vnd.android.package-archive"
        "csv" -> "text/csv"
        "txt", "log" -> "text/plain"
        "json" -> "application/json"
        "zip" -> "application/zip"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "pdf" -> "application/pdf"
        "mp3" -> "audio/mpeg"
        "mp4" -> "video/mp4"
        else -> "application/octet-stream"
    }
}
