package com.zeekrprobe.journal.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zeekrprobe.journal.CarState
import com.zeekrprobe.journal.ChargeSession
import com.zeekrprobe.journal.Network
import com.zeekrprobe.journal.Segment
import com.zeekrprobe.journal.Stats
import com.zeekrprobe.journal.Store
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFmt = SimpleDateFormat("d MMM, HH:mm", Locale("ru"))

private fun money(v: Double?): String = if (v == null) "—" else String.format(Locale.US, "%.2f", v)
private fun num1(v: Double?): String = if (v == null) "—" else String.format(Locale.US, "%.1f", v)
private fun num1(v: Float?): String = if (v == null) "—" else String.format(Locale.US, "%.1f", v)

// ==================================================================== Dashboard

@Composable
fun DashboardScreen(
    car: CarState,
    stats: Stats,
    pending: Int,
    trip: com.zeekrprobe.journal.TripState?,
    onStartTrip: () -> Unit,
    onStopTrip: () -> Unit,
    updating: Boolean,
    updateProgress: String?,
    onUpdate: () -> Unit,
    onOpenProbe: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(4.dp))
        BatteryArc(
            soc = car.socPercent,
            rangeKm = car.rangeKm,
            charging = car.charging,
        )
        Spacer(Modifier.height(8.dp))

        if (!car.connected) {
            Text(
                "Нет связи с машиной (ECARX). Данные появятся на запущенном авто.",
                color = TextMid, fontSize = 17.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        // Live car line.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            MiniStat(Icons.Rounded.Straighten, "одометр", car.odometerKm?.let { "${it.toInt()}" } ?: "—", "км")
            MiniStat(Icons.Rounded.Speed, "скорость", car.speedKmh?.let { "${it.toInt()}" } ?: "0", "км/ч")
            MiniStat(Icons.Rounded.Thermostat, "на улице", num1(car.outsideC), "°C")
        }

        Spacer(Modifier.height(16.dp))

        if (pending > 0) {
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = EnergyDim),
                border = BorderStroke(1.dp, Outline),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Bolt, null, tint = Energy, modifier = Modifier.size(22.dp))
                    Text(
                        "  Похоже, была зарядка ($pending) — открой «Зарядки», подтверди и впиши цену.",
                        color = TextHi, fontSize = 16.sp,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // KPI tiles.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(Icons.Rounded.Bolt, num1(stats.consumptionPer100), "кВт·ч / 100 км", Energy, Modifier.weight(1f))
            StatTile(Icons.Rounded.Payments, money(stats.costPerKm), "руб / км", Amber, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(Icons.Rounded.CalendarMonth, money(stats.spentMonth), "потрачено за месяц", Violet, Modifier.weight(1f))
            StatTile(Icons.Rounded.Savings, money(stats.spentTotal), "всего на зарядку", Energy, Modifier.weight(1f))
        }

        Spacer(Modifier.height(16.dp))
        TripCard(trip = trip, onStart = onStartTrip, onStop = onStopTrip)

        Spacer(Modifier.height(16.dp))
        SectionCard(title = "Пробег и энергия") {
            InfoRow("Пройдено (по зарядкам)", "${stats.distanceKm.toInt()} км")
            InfoRow("Залито всего", "${num1(stats.energyTotal)} кВт·ч")
            InfoRow("Зарядок записано", "${stats.sessions}")
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onUpdate,
            enabled = !updating,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Surface2, contentColor = TextHi),
        ) {
            if (updating) {
                Text(updateProgress ?: "Скачиваю обновление…")
            } else {
                Icon(Icons.Rounded.Download, null, modifier = Modifier.size(18.dp))
                Text("  Обновить приложение")
            }
        }
        Text(
            "Скачает свежий APK в «Загрузки» — поставишь из APKPure.",
            color = TextLow, fontSize = 15.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onOpenProbe) {
            Text("Диагностика машины (ECARX)", color = TextMid)
        }
        Spacer(Modifier.height(80.dp))
    }
}

@Composable
private fun MiniStat(icon: ImageVector, label: String, value: String, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = TextMid, modifier = Modifier.size(18.dp))
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 4.dp)) {
            Text(value, color = TextHi, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Text(" $unit", color = TextMid, fontSize = 14.sp, modifier = Modifier.padding(bottom = 3.dp))
        }
        Text(label, color = TextMid, fontSize = 14.sp)
    }
}

@Composable
private fun TripCard(
    trip: com.zeekrprobe.journal.TripState?,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    SectionCard(title = "Запись поездки") {
        if (trip == null) {
            Text(
                "Сравнивает спидометр с GPS и проверяет одометр. Пишется в фоне — можно свернуть на карту или музыку, запись не прервётся.",
                color = TextMid, fontSize = 17.sp,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Energy, contentColor = Bg),
            ) {
                Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(20.dp))
                Text("  Начать поездку", fontWeight = FontWeight.SemiBold)
            }
        } else {
            val mm = trip.seconds / 60
            val ss = trip.seconds % 60
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SegMetric(num1(trip.gpsKm), "км GPS", Energy)
                SegMetric(trip.odoDeltaKm?.let { num1(it) } ?: "—", "км одометр", Violet)
                SegMetric("%d:%02d".format(mm, ss), "время", TextHi)
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SegMetric(trip.consumptionPer100?.let { num1(it) } ?: "—", "кВт·ч/100", Energy)
                SegMetric(trip.kwhUsed?.let { num1(it) } ?: "—", "кВт·ч съедено", Amber)
                SegMetric(trip.socUsedPct?.let { "${it.toInt()}%" } ?: "—", "заряда", Violet)
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SegMetric("${trip.speedEcarx.toInt()}", "км/ч прибор", Amber)
                SegMetric("${trip.speedGps.toInt()}", "км/ч GPS", Energy)
            }
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Bad, contentColor = Color(0xFFFFFFFF)),
            ) {
                Icon(Icons.Rounded.Stop, null, modifier = Modifier.size(20.dp))
                Text("  Остановить запись", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ==================================================================== Charges

@Composable
fun ChargesScreen(
    charges: List<ChargeSession>,
    onEdit: (ChargeSession) -> Unit,
) {
    if (charges.isEmpty()) {
        EmptyHint("Пока нет зарядок.\nНажми «+», чтобы добавить первую,\nили подключись к машине — приложение само заметит зарядку.")
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Spacer(Modifier.height(8.dp)) }
        items(charges.reversed(), key = { it.id }) { c ->
            ChargeRow(c, onEdit)
        }
        item { Spacer(Modifier.height(90.dp)) }
    }
}

@Composable
private fun ChargeRow(c: ChargeSession, onEdit: (ChargeSession) -> Unit) {
    val needsAttention = c.auto && !c.confirmed
    Card(
        Modifier.fillMaxWidth().clickable { onEdit(c) },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (needsAttention) EnergyDim else Surface1,
        ),
        border = BorderStroke(1.dp, Outline),
    ) {
        Row(
            Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    Network.title(c.network),
                    color = TextHi, fontWeight = FontWeight.SemiBold, fontSize = 18.sp,
                )
                Text(dateFmt.format(Date(c.timestamp)), color = TextMid, fontSize = 15.sp)
                val bits = buildList {
                    c.effectiveKwh()?.let { add("${num1(it)} кВт·ч") }
                    c.effectivePrice()?.let { add("${money(it)} ₽/кВт·ч") }
                    c.odometerKm?.let { add("${it.toInt()} км") }
                }
                if (bits.isNotEmpty()) {
                    Text(bits.joinToString(" · "), color = TextMid, fontSize = 17.sp,
                        modifier = Modifier.padding(top = 4.dp))
                }
                if (needsAttention) {
                    Text("нужно подтвердить", color = Energy, fontSize = 15.sp,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(money(c.effectiveCost()), color = Amber, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("руб", color = TextMid, fontSize = 15.sp)
            }
        }
    }
}

// ==================================================================== History

@Composable
fun HistoryScreen(stats: Stats) {
    if (stats.segments.isEmpty()) {
        EmptyHint("История появится после двух зарядок с одометром — покажу расход и стоимость между ними.")
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Spacer(Modifier.height(8.dp)) }
        items(stats.segments) { seg -> SegmentRow(seg) }
        item { Spacer(Modifier.height(90.dp)) }
    }
}

@Composable
private fun SegmentRow(seg: Segment) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Surface1),
        border = BorderStroke(1.dp, Outline),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "${seg.from.odometerKm?.toInt()} → ${seg.to.odometerKm?.toInt()} км  ·  ${seg.km.toInt()} км",
                color = TextHi, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
            )
            Text(
                "${dateFmt.format(Date(seg.from.timestamp))} — ${dateFmt.format(Date(seg.to.timestamp))}",
                color = TextMid, fontSize = 15.sp,
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SegMetric(num1(seg.consumptionPer100), "кВт·ч/100", Energy)
                SegMetric(money(seg.costPerKm), "руб/км", Amber)
                SegMetric(num1(seg.kwh), "кВт·ч", Violet)
                SegMetric(money(seg.cost), "руб", TextHi)
            }
        }
    }
}

@Composable
private fun SegMetric(value: String, label: String, accent: Color = TextHi) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = TextHi, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(label, color = TextMid, fontSize = 14.sp)
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = TextMid, fontSize = 15.sp)
    }
}

// ==================================================================== Add / edit

@Composable
fun AddChargeDialog(
    existing: ChargeSession?,
    car: CarState,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val isNew = existing == null
    val now = System.currentTimeMillis()
    val ts = existing?.timestamp ?: now

    var network by remember { mutableStateOf(existing?.network ?: Network.ALL.first().key) }
    var netMenu by remember { mutableStateOf(false) }

    fun str(v: Double?) = v?.let { String.format(Locale.US, if (it % 1.0 == 0.0) "%.0f" else "%.2f", it) } ?: ""

    var odo by remember { mutableStateOf(str(existing?.odometerKm ?: car.odometerKm?.toDouble())) }
    var kwh by remember { mutableStateOf(str(existing?.kwh)) }
    var cost by remember { mutableStateOf(str(existing?.cost)) }
    var price by remember {
        mutableStateOf(str(existing?.pricePerKwh ?: Store.suggestedPrice(network, ts)))
    }
    var socB by remember { mutableStateOf(str(existing?.socBefore)) }
    var socA by remember { mutableStateOf(str(existing?.socAfter ?: car.socPercent?.toDouble())) }
    var note by remember { mutableStateOf(existing?.note ?: "") }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(
            Modifier.fillMaxWidth(0.94f).padding(vertical = 24.dp),
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = Surface1),
        ) {
            Column(
                Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
            ) {
                Text(
                    if (isNew) "Новая зарядка" else "Зарядка",
                    style = MaterialTheme.typography.titleLarge, color = TextHi,
                )
                Spacer(Modifier.height(16.dp))

                // Network dropdown.
                Text("Сеть", color = TextMid, fontSize = 17.sp)
                Box {
                    Card(
                        Modifier.fillMaxWidth().clickable { netMenu = true }.padding(top = 4.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = Surface2),
                    ) {
                        Row(
                            Modifier.padding(16.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(Network.title(network), color = TextHi, fontSize = 18.sp)
                            Icon(Icons.Rounded.KeyboardArrowDown, null, tint = TextMid)
                        }
                    }
                    DropdownMenu(expanded = netMenu, onDismissRequest = { netMenu = false }) {
                        Network.ALL.forEach { n ->
                            DropdownMenuItem(
                                text = { Text(n.title) },
                                onClick = {
                                    network = n.key
                                    netMenu = false
                                    // Re-suggest the price for the newly picked network.
                                    price = str(Store.suggestedPrice(n.key, ts))
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                NumField("Одометр, км", odo, { odo = it })
                Spacer(Modifier.height(10.dp))

                Text(
                    "Впиши хотя бы одно: кВт·ч или сумму. Остальное посчитаю по цене.",
                    color = TextLow, fontSize = 15.sp, modifier = Modifier.padding(bottom = 6.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumField("кВт·ч", kwh, { kwh = it }, Modifier.weight(1f))
                    NumField("Сумма, ₽", cost, { cost = it }, Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                NumField("Цена, ₽/кВт·ч", price, { price = it })

                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumField("SoC до, %", socB, { socB = it }, Modifier.weight(1f))
                    NumField("SoC после, %", socA, { socA = it }, Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                NumField("Заметка", note, { note = it }, keyboard = KeyboardType.Text)

                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (!isNew) {
                        TextButton(onClick = { Store.delete(existing!!.id); onSaved() }) {
                            Text("Удалить", color = Bad)
                        }
                        Spacer(Modifier.weight(1f))
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    TextButton(onClick = onDismiss) { Text("Отмена", color = TextMid) }
                    Spacer(Modifier.width(4.dp))
                    Button(
                        onClick = {
                            val s = ChargeSession(
                                id = existing?.id ?: now,
                                timestamp = ts,
                                network = network,
                                odometerKm = odo.toDoubleOrNull(),
                                kwh = kwh.toDoubleOrNull(),
                                pricePerKwh = price.toDoubleOrNull(),
                                cost = cost.toDoubleOrNull(),
                                socBefore = socB.toDoubleOrNull(),
                                socAfter = socA.toDoubleOrNull(),
                                note = note,
                                auto = existing?.auto ?: false,
                                confirmed = true,
                            )
                            if (isNew) Store.add(s) else Store.update(s)
                            onSaved()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Energy, contentColor = Color(0xFF042019)),
                    ) {
                        Text("Сохранить", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun NumField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Number,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboard),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Energy,
            unfocusedBorderColor = Outline,
            focusedLabelColor = Energy,
            unfocusedLabelColor = TextMid,
            focusedTextColor = TextHi,
            unfocusedTextColor = TextHi,
            cursorColor = Energy,
        ),
    )
}
