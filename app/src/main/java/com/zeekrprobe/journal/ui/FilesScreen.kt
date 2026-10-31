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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf(Store.exchangeUrl) }
    var token by remember { mutableStateOf(Store.exchangeToken) }
    var files by remember { mutableStateOf<List<RemoteFile>>(emptyList()) }
    var local by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(0) } // 0 = по дате, 1 = по имени, 2 = по размеру
    var pendingDelete by remember { mutableStateOf<RemoteFile?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

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

    fun deleteServer(name: String) {
        busy = true; status = "Удаляю с сервера $name…"
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                try { Result.success(Exchange.delete(name)) } catch (t: Throwable) { Result.failure(t) }
            }
            busy = false
            r.onSuccess { ok -> status = if (ok) "Удалено с сервера: $name" else "Не удалось удалить"; refresh() }
                .onFailure { status = "Ошибка: ${it.message}" }
        }
    }

    fun deleteLocalCopy(name: String) {
        busy = true; status = "Удаляю из «Загрузок» $name…"
        scope.launch {
            val ok = withContext(Dispatchers.IO) { Exchange.deleteLocal(context, name) }
            busy = false
            status = if (ok) "Удалено из «Загрузок»: $name" else "Не нашёл в «Загрузках» (или не моя копия)"
            reloadLocal()
        }
    }

    fun clearServer() {
        busy = true; status = "Чищу сервер…"
        scope.launch {
            val n = withContext(Dispatchers.IO) {
                var c = 0
                for (f in files) { try { if (Exchange.delete(f.name)) c++ } catch (_: Throwable) {} }
                c
            }
            busy = false; status = "Удалено с сервера: $n"; refresh()
        }
    }

    LaunchedEffect(Unit) { reloadLocal(); if (url.isNotBlank()) refresh() }

    val shown = remember(files, query, sort) {
        files.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
            .let { list ->
                when (sort) {
                    1 -> list.sortedBy { it.name.lowercase() }
                    2 -> list.sortedByDescending { it.size }
                    else -> list.sortedByDescending { it.ts }
                }
            }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Spacer(Modifier.height(8.dp))
        SectionCard(title = "Обменник (через хостинг)") {
            Text(
                "Файлы с твоего сервера. «Скачать» — в «Загрузки» машины; «Отправить» — с этого устройства на сервер; корзина — удалить.",
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
            if (files.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { confirmClear = true }, enabled = !busy) {
                    Icon(Icons.Rounded.Delete, null, tint = Bad, modifier = Modifier.size(18.dp))
                    Text("  Очистить сервер", color = Bad)
                }
            }
            status?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = TextMid, fontSize = 15.sp)
            }
        }

        if (files.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Field("Поиск по имени", query) { query = it }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SortChip("Дата", sort == 0) { sort = 0 }
                SortChip("Имя", sort == 1) { sort = 1 }
                SortChip("Размер", sort == 2) { sort = 2 }
            }
        }

        Spacer(Modifier.height(14.dp))
        shown.forEach { f ->
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
                    IconButton(onClick = { pendingDelete = f }, enabled = !busy) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Удалить", tint = Bad, modifier = Modifier.size(20.dp))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(90.dp))
    }

    // Per-file delete options.
    pendingDelete?.let { f ->
        val have = local[f.name]
        val downloaded = have != null && have == f.size
        Dialog(onDismissRequest = { pendingDelete = null }) {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = Surface1),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text(f.name, color = TextHi, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    Spacer(Modifier.height(14.dp))
                    TextButton(onClick = { val n = f.name; pendingDelete = null; deleteServer(n) }) {
                        Text("Удалить с сервера", color = Bad)
                    }
                    if (downloaded) {
                        TextButton(onClick = { val n = f.name; pendingDelete = null; deleteLocalCopy(n) }) {
                            Text("Удалить из «Загрузок»", color = Bad)
                        }
                        TextButton(onClick = {
                            val n = f.name; pendingDelete = null; deleteServer(n); deleteLocalCopy(n)
                        }) { Text("И там, и там", color = Bad) }
                    }
                    TextButton(onClick = { pendingDelete = null }) { Text("Отмена", color = TextMid) }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; clearServer() }) { Text("Удалить всё", color = Bad) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Отмена", color = TextMid) }
            },
            title = { Text("Очистить сервер?") },
            text = { Text("Удалит все ${files.size} файл(ов) на сервере. Локальные копии в «Загрузках» останутся.") },
            containerColor = Surface1,
        )
    }
}

@Composable
private fun SortChip(label: String, active: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = if (active) EnergyDim else Surface2),
    ) {
        Text(
            label,
            color = if (active) Energy else TextMid,
            fontSize = 14.sp,
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
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
