package com.garickbox.deepapk

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun TermuxRunnerDialog(
    tr: TermuxRunnerState,
    isDark: Boolean,
    hasPerm: Boolean,
    onUpdate: (TermuxRunnerState) -> Unit,
    onStart: (TermuxRunnerState) -> Unit,
    onAddAttachment: (AttachedFile) -> Unit,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    Dialog(
        onDismissRequest = { if (!tr.running) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = if (isDark) Color(0xFF1F2229) else Color.White,
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.86f)
        ) {
            Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (tr.running) "⏳ Termux (${tr.elapsedSec}с)" else "▶ Termux",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("bash", "python3").forEach { interp ->
                                ModePill(
                                    label = if (interp == "python3") "python" else "bash",
                                    active = tr.interpreter == interp,
                                    isDark = isDark,
                                    onClick = {
                                        if (!tr.running) onUpdate(tr.copy(interpreter = interp))
                                    }
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "✕",
                            fontSize = 18.sp,
                            modifier = Modifier.clickable {
                                if (tr.running) tr.jobHandle?.requestCancel(ctx)
                                onDismiss()
                            }.padding(4.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = tr.script,
                        onValueChange = { if (!tr.running) onUpdate(tr.copy(script = it)) },
                        readOnly = tr.running,
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp, max = if (tr.running || tr.exitCode != null) 140.dp else 280.dp),
                        shape = RoundedCornerShape(12.dp)
                    )
                    if (!hasPerm) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "⚠️ Нет разрешения RUN_COMMAND (Настройки → Termux → Прямой запуск команд).",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (tr.statusLine.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(tr.statusLine, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = DeepSeekBlue)
                    }
                    if (tr.running || tr.exitCode != null) {
                        Spacer(Modifier.height(6.dp))
                        val consoleText = if (tr.running) tr.liveTail.ifBlank { "Выполняется…" } else tr.resultBody
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isDark) Color(0xFF13151A) else Color(0xFFF3F5F8),
                            modifier = Modifier.weight(1f).fillMaxWidth()
                        ) {
                            Text(
                                text = consoleText,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.5.sp,
                                lineHeight = 16.sp,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(10.dp)
                            )
                        }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(10.dp))
                    if (tr.running) {
                        Button(
                            onClick = { tr.jobHandle?.requestCancel(ctx) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("⏹ Остановить") }
                    } else if (tr.exitCode != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { copyPlain(ctx, tr.resultBody, "Вывод скопирован") },
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("❐") }
                            OutlinedButton(
                                onClick = { onStart(tr) },
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("↻") }
                            Button(
                                onClick = {
                                    val icon = if (tr.exitCode == 0) "\u2705" else "\u26A0\uFE0F"
                                    val tag = if (tr.interpreter == "python3") "python" else "bash"
                                    val txt = tr.resultBody.trim().ifBlank { "(пусто)" }
                                    val sizeLbl = if (txt.length >= 1024) "${txt.length / 1024} КБ" else "${txt.length} симв."
                                    onAddAttachment(
                                        AttachedFile(
                                            name = "\uD83D\uDCBB $tag $icon exit=${tr.exitCode} \u00B7 ${tr.durationSec}с",
                                            sizeLabel = sizeLbl,
                                            content = txt
                                        )
                                    )
                                    onDismiss()
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("📎 В чат") }
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = {
                                    TermuxBridge.sendCodeToTermux(ctx, tr.interpreter, tr.script)
                                    onDismiss()
                                },
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("📤 Файлом") }
                            Button(
                                onClick = { onStart(tr) },
                                enabled = hasPerm,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("▶ Запустить") }
                        }
                    }
            }
        }
    }
}
