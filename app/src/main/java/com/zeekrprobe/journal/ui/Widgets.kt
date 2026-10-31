package com.zeekrprobe.journal.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Colour the charge level takes as it fills: red when low, amber mid, accent when healthy. */
fun socColor(soc: Float?): Color = when {
    soc == null -> NeutralGray
    soc < 15 -> Bad
    soc < 40 -> Amber
    else -> Energy
}

/** Big circular charge gauge: coloured ring, neutral number in the middle, range underneath. */
@Composable
fun BatteryArc(
    soc: Float?,
    rangeKm: Float?,
    charging: Boolean,
    modifier: Modifier = Modifier,
) {
    val ring = socColor(soc)
    val track = Surface2
    Box(modifier = modifier.size(216.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(216.dp)) {
            val stroke = 16.dp.toPx()
            val inset = stroke / 2 + 6.dp.toPx()
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            drawArc(track, 135f, 270f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            val pct = ((soc ?: 0f) / 100f).coerceIn(0f, 1f)
            drawArc(ring, 135f, 270f * pct, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = if (soc != null) "${soc.toInt()}" else "—",
                    color = TextHi, fontWeight = FontWeight.Bold, fontSize = 60.sp,
                )
                Text("%", color = TextMid, fontSize = 22.sp, modifier = Modifier.padding(top = 8.dp, start = 2.dp))
            }
            if (charging) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.BatteryChargingFull, null, tint = Energy, modifier = Modifier.size(16.dp))
                    Text(" заряжается", color = Energy, fontSize = 14.sp)
                }
            } else {
                Text("заряд", color = TextMid, fontSize = 14.sp)
            }
            if (rangeKm != null) {
                Text("${rangeKm.toInt()} км запас", color = TextMid, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/** Compact KPI card: small tinted icon, big neutral value, caption. */
@Composable
fun StatTile(
    icon: ImageVector,
    value: String,
    label: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Surface1),
        border = BorderStroke(1.dp, Outline),
    ) {
        Column(Modifier.padding(16.dp)) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Surface2),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = accent, modifier = Modifier.size(19.dp)) }
            Text(
                value, color = TextHi, fontWeight = FontWeight.Bold, fontSize = 23.sp,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(label, color = TextMid, fontSize = 13.sp)
        }
    }
}

/** A titled rounded container with a hairline border; title as a quiet uppercase label. */
@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Surface1),
        border = BorderStroke(1.dp, Outline),
    ) {
        Column(Modifier.padding(18.dp)) {
            if (title != null) {
                Text(
                    title.uppercase(),
                    color = TextMid,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.2.sp,
                    modifier = Modifier.padding(bottom = 14.dp),
                )
            }
            content()
        }
    }
}

/** A label/value line. */
@Composable
fun InfoRow(label: String, value: String, valueColor: Color = TextHi) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = TextMid, fontSize = 16.sp)
        Text(value, color = valueColor, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}
