package com.zeekrprobe.journal.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Colour the charge level takes as it fills: red when low, amber mid, energy-teal when healthy. */
fun socColor(soc: Float?): Color = when {
    soc == null -> TextLow
    soc < 15 -> Bad
    soc < 40 -> Amber
    else -> Energy
}

/** Big circular charge gauge with the % in the middle and range underneath. */
@Composable
fun BatteryArc(
    soc: Float?,
    rangeKm: Float?,
    charging: Boolean,
    modifier: Modifier = Modifier,
) {
    val color = socColor(soc)
    Box(modifier = modifier.size(220.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxWidth().size(220.dp)) {
            val stroke = 24.dp.toPx()
            val inset = stroke / 2 + 4.dp.toPx()
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            // Track.
            drawArc(
                color = Surface2,
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            // Fill.
            val pct = ((soc ?: 0f) / 100f).coerceIn(0f, 1f)
            drawArc(
                color = color,
                startAngle = 135f,
                sweepAngle = 270f * pct,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (soc != null) "${soc.toInt()}" else "—",
                color = TextHi,
                fontWeight = FontWeight.Bold,
                fontSize = 64.sp,
            )
            Text(
                text = if (charging) "⚡ заряжается" else "% заряда",
                color = if (charging) Energy else TextMid,
                fontSize = 14.sp,
            )
            if (rangeKm != null) {
                Text(
                    text = "~${rangeKm.toInt()} км запас",
                    color = TextMid,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** Compact KPI card: emoji, big value, caption. */
@Composable
fun StatTile(
    emoji: String,
    value: String,
    label: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Surface1),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(emoji, fontSize = 20.sp)
            Text(
                value,
                color = accent,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(label, color = TextMid, fontSize = 12.sp)
        }
    }
}

/** A titled rounded container used to group content on the screens. */
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
    ) {
        Column(Modifier.padding(18.dp)) {
            if (title != null) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextHi,
                    modifier = Modifier.padding(bottom = 12.dp),
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
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = TextMid, fontSize = 14.sp)
        Text(value, color = valueColor, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}
