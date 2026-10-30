package com.zeekrprobe.journal.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zeekrprobe.journal.CarState
import com.zeekrprobe.journal.RangeCalc
import com.zeekrprobe.journal.Store
import com.zeekrprobe.journal.TripRecord
import com.zeekrprobe.journal.Weather
import com.zeekrprobe.journal.WeatherData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private fun f0(v: Double?): String = if (v == null) "—" else String.format(Locale.US, "%.0f", v)
private fun f1(v: Double?): String = if (v == null) "—" else String.format(Locale.US, "%.1f", v)

@Composable
fun RangeScreen(
    car: CarState,
    trips: List<TripRecord>,
    segmentConsumption: Double?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var weather by remember { mutableStateOf<WeatherData?>(null) }
    var weatherTried by remember { mutableStateOf(false) }
    var dest by remember { mutableStateOf("") }

    fun refreshWeather() {
        scope.launch {
            val w = withContext(Dispatchers.IO) {
                val loc = lastKnownLatLon(context) ?: return@withContext null
                Weather.fetch(loc.first, loc.second)
            }
            weatherTried = true
            if (w != null) weather = w
        }
    }
    LaunchedEffect(Unit) { refreshWeather() }

    val est = remember(car.socPercent, trips, weather, segmentConsumption, car.consTripKwh100, car.outsideC) {
        RangeCalc.estimate(
            socPercent = car.socPercent,
            capacityKwh = Store.batteryKwh,
            trips = trips,
            segmentConsumption = segmentConsumption,
            carConsumption = car.consTripKwh100,
            weather = weather,
            ecarxTempC = car.outsideC,
        )
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        if (est == null) {
            SectionCard(title = "Запас хода") {
                Text(
                    "Нет заряда с машины. Открой на заведённом авто — тогда посчитаю запас по твоим поездкам и погоде.",
                    color = TextMid, fontSize = 14.sp,
                )
            }
            return@Column
        }

        // Hero: mixed estimate.
        SectionCard {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("смешанный цикл", color = TextMid, fontSize = 13.sp)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("≈ ${est.mixedKm}", color = Energy, fontWeight = FontWeight.Bold, fontSize = 56.sp)
                    Text(" км", color = TextMid, fontSize = 20.sp, modifier = Modifier.padding(bottom = 10.dp))
                }
                Text(
                    "при расходе ~${f1(est.mixedConsumption)} кВт·ч/100 · ${f0(est.usableKwh)} кВт·ч в батарее",
                    color = TextMid, fontSize = 13.sp,
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ModeTile("Город", est.cityKm, Violet, Modifier.weight(1f))
            ModeTile("Смешанный", est.mixedKm, Energy, Modifier.weight(1f))
            ModeTile("Трасса", est.highwayKm, Amber, Modifier.weight(1f))
        }

        Spacer(Modifier.height(14.dp))
        SectionCard(title = "Условия") {
            InfoRow("Заряд", car.socPercent?.let { "${it.toInt()}%" } ?: "—")
            InfoRow("Температура", est.tempC?.let { "${f0(it)} °C" } ?: "—")
            InfoRow("Ветер", est.windMs?.let { "${f0(it)} м/с" } ?: "—")
            InfoRow("Основа расчёта", est.basis)
            InfoRow("До «спокойных» 10%", "${est.reserve10Km} км")
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { refreshWeather() },
                colors = ButtonDefaults.buttonColors(containerColor = Surface2, contentColor = TextHi),
            ) { Text(if (weather == null && weatherTried) "Погода недоступна — обновить" else "Обновить погоду") }
        }

        Spacer(Modifier.height(14.dp))
        SectionCard(title = "Доеду до точки?") {
            OutlinedTextField(
                value = dest,
                onValueChange = { dest = it },
                label = { Text("Расстояние, км") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Energy, unfocusedBorderColor = Color(0xFF2B3340),
                    focusedLabelColor = Energy, unfocusedLabelColor = TextMid,
                    focusedTextColor = TextHi, unfocusedTextColor = TextHi, cursorColor = Energy,
                ),
            )
            val n = dest.toDoubleOrNull()
            val soc = car.socPercent?.toDouble()
            if (n != null && n > 0 && soc != null) {
                val neededKwh = n * est.mixedConsumption / 100.0
                val neededSoc = neededKwh / Store.batteryKwh * 100.0
                val left = soc - neededSoc
                Spacer(Modifier.height(10.dp))
                val (msg, color) = when {
                    left >= 15 -> "Доедешь спокойно — останется ~${f0(left)}%" to Good
                    left >= 5 -> "Доедешь, но впритык — останется ~${f0(left)}%" to Amber
                    left >= 0 -> "На грани (~${f0(left)}%) — лучше подзарядиться" to Amber
                    else -> "Не хватит ~${f0(-left)}% — нужна зарядка в пути" to Bad
                }
                Text(msg, color = color, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            "Это оценка, а не гарантия: стиль езды, трасса, печка и рельеф влияют. Чем больше поездок запишешь, тем точнее — калькулятор учится на тебе.",
            color = TextLow, fontSize = 12.sp,
        )
        Spacer(Modifier.height(90.dp))
    }
}

@Composable
private fun ModeTile(label: String, km: Int, accent: Color, modifier: Modifier = Modifier) {
    SectionCard(modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text("$km", color = accent, fontWeight = FontWeight.Bold, fontSize = 26.sp)
            Text("км", color = TextMid, fontSize = 11.sp)
            Text(label, color = TextMid, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

private fun lastKnownLatLon(context: Context): Pair<Double, Double>? {
    return try {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        loc?.let { it.latitude to it.longitude }
    } catch (_: Throwable) {
        null
    }
}
