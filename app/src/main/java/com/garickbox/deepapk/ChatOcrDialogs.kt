package com.garickbox.deepapk

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun OcrBaseDialog(
    ocrBusy: Boolean,
    ocrCur: Int,
    ocrTotal: Int,
    ocrRefreshKey: Int,
    onDismiss: () -> Unit,
    onCancelOcr: () -> Unit,
    onPickPdf: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var reloadKey by remember { mutableStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    var ocrStats by remember { mutableStateOf(OcrCache.Stats(0, 0, 0L, false)) }
    var ocrList by remember { mutableStateOf<List<OcrCache.Entry>>(emptyList()) }
    LaunchedEffect(reloadKey, ocrRefreshKey) {
        ocrStats = withContext(Dispatchers.IO) { OcrCache.stats(ctx) }
        ocrList = withContext(Dispatchers.IO) { OcrCache.list(ctx) }
    }
    AlertDialog(
        onDismissRequest = { onDismiss(); confirmClear = false },
        title = { Text("📚 База OCR", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "Страниц: ${ocrStats.pages} · Файлов: ${ocrStats.files} · ${OcrCache.formatBytes(ocrStats.bytes)}",
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                if (ocrBusy) {
                    Text(
                        "📖 OCR: $ocrCur / ${if (ocrTotal > 0) ocrTotal else "?"}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(6.dp))
                    if (ocrTotal > 0) {
                        LinearProgressIndicator(
                            progress = { ocrCur.toFloat() / ocrTotal.toFloat() },
                            modifier = Modifier.fillMaxWidth().height(6.dp)
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Можно отменить — уже распознанные страницы остаются в базе",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                }
                if (ocrList.isEmpty()) {
                    Text(
                        "Пока пусто. Распознай PDF — каждая страница сохранится здесь (по содержимому, не по имени). Повторная обработка того же файла пойдёт из базы — секунды вместо минут.",
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    ocrList.forEach { e ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(e.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${e.pages} стр. · ${OcrCache.formatBytes(e.bytes)}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                "🗑",
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .clickable {
                                        scope.launch(Dispatchers.IO) { OcrCache.deleteOne(ctx, e.hash); withContext(Dispatchers.Main) { reloadKey++ } }
                                    }
                                    .padding(start = 10.dp, top = 4.dp, bottom = 4.dp)
                            )
                        }
                    }
                }
                if (confirmClear && ocrList.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Нажми «Удалить всё» ещё раз для подтверждения",
                        fontSize = 12.sp,
                        color = Color(0xFFD32F2F)
                    )
                }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (ocrList.isNotEmpty() && !ocrBusy) {
                    if (confirmClear) {
                        Button(
                            onClick = {
                                scope.launch(Dispatchers.IO) { OcrCache.clear(ctx); withContext(Dispatchers.Main) { confirmClear = false; reloadKey++ } }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                        ) { Text("Удалить всё") }
                        Spacer(Modifier.width(6.dp))
                    } else {
                        OutlinedButton(onClick = { confirmClear = true }) { Text("🗑") }
                        Spacer(Modifier.width(6.dp))
                    }
                }
                if (ocrBusy) {
                    Button(
                        onClick = onCancelOcr,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                    ) { Text("⏹ Отменить") }
                } else {
                    Button(onClick = onPickPdf) { Text("📥 Загрузить PDF") }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { onDismiss(); confirmClear = false }) { Text("Закрыть") }
        }
    )
}