package com.zeekrprobe.journal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zeekrprobe.Updater
import com.zeekrprobe.journal.AdbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AdbCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var out by remember { mutableStateOf<String?>(null) }
    var cmd by remember { mutableStateOf("") }

    fun run(label: String, block: suspend () -> String) {
        if (busy) return
        busy = true; out = "$label…"
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                try { block() } catch (t: Throwable) { "Ошибка: ${t.message ?: t.javaClass.simpleName}" }
            }
            busy = false; out = r
        }
    }

    SectionCard(title = "ADB из машины") {
        Text(
            "Ставит APK и выдаёт права прямо с ГУ, без ноута и сети — через adbd самой машины. " +
                "Первый раз на экране подтверди «Разрешить отладку».",
            color = TextMid, fontSize = 15.sp,
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { run("Проверяю связь") { AdbClient.test(ctx) } },
                enabled = !busy,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Surface2, contentColor = TextHi),
            ) { Text("Проверить") }
            Button(
                onClick = { run("Ставлю последнюю версию") { val apk = Updater(ctx) {}.downloadLatest(); AdbClient.install(ctx, apk) } },
                enabled = !busy,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Energy, contentColor = Bg),
            ) { Text("Обновить") }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { run("Выдаю права") { AdbClient.grantCarPerms(ctx) } },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Surface2, contentColor = TextHi),
        ) { Text("Выдать права car-service") }

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = cmd,
            onValueChange = { cmd = it },
            label = { Text("Команда shell (напр. pm list packages)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Energy, unfocusedBorderColor = Outline,
                focusedLabelColor = Energy, unfocusedLabelColor = TextMid,
                focusedTextColor = TextHi, unfocusedTextColor = TextHi, cursorColor = Energy,
            ),
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { run("shell") { AdbClient.shell(ctx, cmd) } },
            enabled = !busy && cmd.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = Surface2, contentColor = TextHi),
        ) { Text("Выполнить") }

        out?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = TextHi, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        }
    }
}
