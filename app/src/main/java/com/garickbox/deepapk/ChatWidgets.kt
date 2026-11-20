package com.garickbox.deepapk

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun ContextMenuItem(
    icon: String,
    label: String,
    accent: Color? = null,
    onClick: () -> Unit
) {
    val col = accent ?: MaterialTheme.colorScheme.onBackground
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, fontSize = 17.sp, color = col, modifier = Modifier.width(28.dp))
        Text(label, fontSize = 15.5.sp, color = col, fontWeight = FontWeight.Normal)
    }
}

@Composable
internal fun ModePill(
    label: String,
    active: Boolean,
    isDark: Boolean,
    onClick: () -> Unit
) {
    val bg = when {
        active && isDark -> Color(0xFF1F2A48)
        active -> Color(0xFFE8F0FE)
        isDark -> Color(0xFF23262E)
        else -> Color(0xFFFFFFFF)
    }
    val border = if (active) DeepSeekBlue.copy(alpha = 0.6f)
    else (if (isDark) Color(0xFF353944) else Color(0xFFE0E3EA))
    val textCol = if (active) (if (isDark) Color(0xFF8AA4FF) else DeepSeekBlue)
    else MaterialTheme.colorScheme.onBackground

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = bg,
        border = BorderStroke(1.dp, border),
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            fontSize = 12.5.sp,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
            color = textCol,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp)
        )
    }
}

@Composable
internal fun DeepWhaleLogo(sizeDp: Int, cornerDp: Int = sizeDp / 2) {
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(RoundedCornerShape(cornerDp.dp))
            .background(DeepSeekBlue),
        contentAlignment = Alignment.Center
    ) {
            Canvas(Modifier.size((sizeDp * 0.72f).dp)) {
                val w = size.width
                val h = size.height
                val whale = Path().apply {
                    moveTo(w * 0.08f, h * 0.54f)
                    cubicTo(w * 0.08f, h * 0.28f, w * 0.28f, h * 0.16f, w * 0.50f, h * 0.19f)
                    cubicTo(w * 0.66f, h * 0.21f, w * 0.75f, h * 0.34f, w * 0.79f, h * 0.46f)
                    lineTo(w * 0.96f, h * 0.30f)
                    lineTo(w * 0.91f, h * 0.52f)
                    lineTo(w * 0.96f, h * 0.72f)
                    lineTo(w * 0.77f, h * 0.62f)
                    cubicTo(w * 0.70f, h * 0.78f, w * 0.56f, h * 0.84f, w * 0.38f, h * 0.84f)
                    cubicTo(w * 0.18f, h * 0.84f, w * 0.08f, h * 0.72f, w * 0.08f, h * 0.54f)
                    close()
                }
                drawPath(whale, Color.White)
                val prompt = Path().apply {
                    moveTo(w * 0.31f, h * 0.40f)
                    lineTo(w * 0.44f, h * 0.52f)
                    lineTo(w * 0.31f, h * 0.64f)
                }
                drawPath(
                    prompt,
                    DeepSeekBlue,
                    style = Stroke(width = w * 0.075f, cap = StrokeCap.Round)
                )
                drawLine(
                    color = DeepSeekBlue,
                    start = Offset(w * 0.49f, h * 0.64f),
                    end = Offset(w * 0.63f, h * 0.64f),
                    strokeWidth = w * 0.075f,
                    cap = StrokeCap.Round
                )
            }
    }
}
