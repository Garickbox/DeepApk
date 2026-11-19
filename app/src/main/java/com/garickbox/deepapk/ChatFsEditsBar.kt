package com.garickbox.deepapk

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Нижний бар «Правки SAF: файлы» с раскрывающимся diff-превью.
 * Транзакция остаётся на вызывающей стороне: onApply = ProjectFs.applyWithBackup + commit/rollback.
 */
@Composable
fun PendingFsEditsBar(
    edits: List<FsWriteOp>,
    isDark: Boolean,
    onApply: () -> Unit,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    var showDiff by remember { mutableStateOf(false) }
    val previews = remember(edits) { edits.map { PatchPreviewBuilder.build(ctx, it) } }
    val summary = edits.joinToString(", ") { it.path.substringAfterLast('/') }
    val totalStats = previews.joinToString(" ") { it.stats() }

    Surface(
        color = if (isDark) Color(0xFF1A2236) else Color(0xFFEEF3FF),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 3.dp),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "✏\uFE0F Правки SAF: $summary",
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = { showDiff = !showDiff },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(8.dp)
                ) { Text("📄 $totalStats", fontSize = 11.5.sp) }
                Spacer(Modifier.width(4.dp))
                Button(
                    onClick = onApply,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(8.dp)
                ) { Text("💾 Бэкап+Применить", fontSize = 11.5.sp) }
                Spacer(Modifier.width(4.dp))
                Text(
                    "✕",
                    fontSize = 14.sp,
                    modifier = Modifier.clickable { onDismiss() }.padding(6.dp)
                )
            }
            if (showDiff) {
                Spacer(Modifier.height(4.dp))
                Surface(
                    color = if (isDark) Color(0xFF0E1422) else Color(0xFFF7FAFF),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        previews.forEach { p ->
                            Text(
                                text = p.render(),
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 13.sp
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
            }
        }
    }
}