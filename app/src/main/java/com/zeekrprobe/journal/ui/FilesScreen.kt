package com.zeekrprobe.journal.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zeekrprobe.journal.Exchange
import com.zeekrprobe.journal.RemoteFile
import com.zeekrprobe.journal.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private fun size(b: Long): String {
    if (b <= 0) return "—"
    val u = arrayOf("Б", "КБ", "МБ", "ГБ"); var v = b.toDouble(); var i = 0
    while (v >= 1024 && i < 3) { v /= 1024; i++ }
    return (if (i == 0) v.toInt().toString() else String.format(Locale.US, "%.1f", v)) + " " + u[i]
}

@Composable
fun FilesScreen() {
    val context = LocalContextRef()
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf(Store.exchangeUrl) }
    var token by remember { mutableStateOf(Store.exchangeToken) }
    var files by remember { mutableStateOf<List<RemoteFile>>(emptyList()) }
    var local by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun reloadLocal() {
        scope.launch {
            val m = withContext(Dispatchers.IO) { Exchange.localIndex(context) }
            local = m
        }
    }

    fun refresh() {
        Store.exchangeUrl = url
        Store.exchangeToken = token
        if (url.isBlank()) { status = "Укажи адрес exchange.php"; return }
        busy = true; status = "Обновляю список…"
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                try { Result.success(Exchange.list()) } catch (t: Throwable) { Result.failure(t) }
            }
            busy = false
            r.onSuccess { files = it; status = if (it.isEmpty()) "На сервере пусто" else null; reloadLocal() }
                .onFailure { status = "Ошибка: ${it.message}" }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val name = queryName(context, uri)
            busy = true; status = "Загружаю $name…"
            scope.launch {
                val r = withContext(Dispatchers.IO) {
                    try { Result.success(Exchange.upload(context, uri, name)) } catch (t: Throwable) { Result.failure(t) }
                }
                busy = false
                r.onSuccess { status = it; refresh() }.onFailure { status = "Ошибка: ${it.message}" }
            }
        }
    }

    fun download(name: String) {
        busy = true; status = "Скачиваю $name…"
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                try {
                    Result.success(Exchange.download(context, name) { p ->
                        status = if (p >= 0) "Скачиваю $name… $p%" else "Скачиваю $name…"
                    })
                } catch (t: Throwable) { Result.failure(t) }
            }
            busy = false
            r.onSuccess { status = "Сохранено: $it"; reloadLocal() }.onFailure { status = "Ошибка: ${it.message}" }
        }
    }

    LaunchedEffect(Unit) { reloadLocal(); if (url.isNotBlank()) refresh() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Spacer(Modifier.height(8.dp))
        SectionCard(title = "Обменник (через хостинг)") {
            Text(
                "Файлы с твоего сервера. Тап «Скачать» — файл ложится в «Загрузки» машины (APKPure и файловый менеджер его увидят). «Отправить» — кидает файл с этого устройства на сервер.",
                color = TextMid, fontSize = 15.sp,
            )
            Spacer(Modifier.height(12.dp))
            Field("Адрес exchange.php", url) { url = it }
            Spacer(Modifier.height(8.dp))
            Field("Токен", token) { token = it }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { refresh() },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = Surface2, contentColor = TextHi),
                ) { Text("Обновить") }
                Button(
                    onClick = { picker.launch("*/*") },
                    enabled = !busy && url.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Energy, contentColor = Bg),
                ) {
                    Icon(Icons.Rounded.Upload, null, modifier = Modifier.size(18.dp))
                    Text("  Отправить файл")
                }
            }
            status?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = TextMid, fontSize = 15.sp)
            }
        }

        Spacer(Modifier.height(14.dp))
        files.forEach { f ->
            val have = local[f.name]
            val downloaded = have != null && have == f.size
            SectionCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.name, color = TextHi, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                        Row {
                            Text(size(f.size), color = TextMid, fontSize = 14.sp)
                            if (downloaded) Text("  · в Загрузках", color = Good, fontSize = 14.sp)
                        }
                    }
                    TextButton(onClick = { download(f.name) }, enabled = !busy) {
                        val tint = if (downloaded) TextMid else Energy
                        Icon(Icons.Rounded.Download, null, tint = tint, modifier = Modifier.size(18.dp))
                        Text(if (downloaded) "  Заново" else "  Скачать", color = tint)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(90.dp))
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Energy, unfocusedBorderColor = Outline,
            focusedLabelColor = Energy, unfocusedLabelColor = TextMid,
            focusedTextColor = TextHi, unfocusedTextColor = TextHi, cursorColor = Energy,
        ),
    )
}

@Composable
private fun LocalContextRef(): Context = androidx.compose.ui.platform.LocalContext.current

private fun queryName(context: Context, uri: Uri): String {
    var name = "file"
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx)?.let { name = it }
        }
    } catch (_: Throwable) {}
    return name
}
