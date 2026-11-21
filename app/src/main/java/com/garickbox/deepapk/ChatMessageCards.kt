package com.garickbox.deepapk

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ChatEmptyWelcome(
    providerLabel: String,
    modelLabel: String
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        DeepWhaleLogo(sizeDp = 56, cornerDp = 16)
        Spacer(Modifier.height(14.dp))
        Text(
            "Привет! Я $providerLabel",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "$modelLabel · Чем помочь сегодня?",
            fontSize = 13.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun UserBubbleCard(
    msg: Message,
    fontSizeSp: Int,
    onInspectAttachment: (String, String) -> Unit,
    onLongClick: () -> Unit
) {
    val (attFiles, cleanUserText) = remember(msg.id, msg.content) {
        splitUserAttachments(msg.content)
    }
    var userExpanded by remember(msg.id) { mutableStateOf(false) }
    val isLongUser = cleanUserText.length > 700 || cleanUserText.count { it == '\n' } > 12
    val shownUserText = remember(cleanUserText, isLongUser, userExpanded) {
        if (!isLongUser || userExpanded) cleanUserText
        else cleanUserText.lineSequence().take(10).joinToString("\n").take(600) + "…"
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalAlignment = Alignment.End
    ) {
        if (attFiles.isNotEmpty()) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(bottom = if (shownUserText.isNotBlank()) 6.dp else 0.dp)
            ) {
                attFiles.forEach { (fName, fBody) ->
                    val kb = (fBody.length / 1024).let { if (it > 0) "${it} КБ" else "${fBody.length} симв." }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, DeepSeekBlue.copy(alpha = 0.35f)),
                        modifier = Modifier
                            .widthIn(max = 290.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .combinedClickable(
                                onClick = { onInspectAttachment(fName, fBody) },
                                onLongClick = onLongClick
                            )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("📎", fontSize = 13.sp)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = fName,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = kb,
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
        if (shownUserText.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier
                    .widthIn(max = 310.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .combinedClickable(onClick = {}, onLongClick = onLongClick)
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(
                        text = shownUserText,
                        fontSize = fontSizeSp.sp,
                        lineHeight = (fontSizeSp + 6).sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    if (isLongUser) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = if (userExpanded) "▲ Свернуть" else "▼ Показать весь текст (${cleanUserText.length} симв.)",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = DeepSeekBlue,
                            modifier = Modifier.clickable { userExpanded = !userExpanded }
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun AssistantReasoningCard(
    msg: Message,
    expanded: Boolean,
    isDark: Boolean,
    onToggle: () -> Unit
) {
    val thoughtBarColor = if (isDark) Color(0xFF3E4554) else Color(0xFFD0D5DD)
    val thoughtBg = if (isDark) Color(0xFF181A1F) else Color(0xFFF6F7F9)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(thoughtBg)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            text = (if (expanded) "▼ " else "▶ ") + "Рассуждение (${msg.reasoning.length} симв.)",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().clickable { onToggle() }
        )
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        drawLine(thoughtBarColor, Offset(0f, 0f), Offset(0f, size.height), 2.dp.toPx())
                    }
                    .padding(start = 10.dp)
            ) {
                Text(
                    text = msg.reasoning,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
internal fun AssistantFooterCard(
    msg: Message,
    isLast: Boolean,
    streaming: Boolean,
    hasSafRetry: Boolean,
    onCopy: () -> Unit,
    onRetry: () -> Unit,
    onContinue: () -> Unit,
    onRetrySaf: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val docRep = remember(msg.content) { extractDocReport(msg.content) }
    val htmlWork = remember(msg.content, docRep) {
        if (docRep == null) DocxEngine.extractStandaloneHtmlPage(msg.content) else null
    }
    var buildingDocx by remember(msg.id) { mutableStateOf(false) }

    if (docRep != null && docRep.isComplete) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, DeepSeekBlue.copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("📄 ${docRep.title}.docx", fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "ГОСТ А4 · Таблиц: ${docRep.tablesCount} · Рисунков: ${docRep.svgCount}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { DocxEngine.openHtmlInBrowser(ctx, docRep.title, DocxEngine.buildPreviewHtml(docRep)) },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f)
                    ) { Text("🌐 Посмотреть", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    Button(
                        enabled = !buildingDocx,
                        onClick = {
                            buildingDocx = true
                            scope.launch {
                                val bytes = withContext(Dispatchers.Default) { DocxEngine.buildDocxBytes(ctx, docRep) }
                                val mime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                                val uri = DocxEngine.saveExportAndNotify(ctx, "${docRep.title}.docx", mime, bytes)
                                buildingDocx = false
                                if (uri != null) DocxEngine.openUri(ctx, uri, mime)
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f)
                    ) { Text(if (buildingDocx) "⏳ Сборка…" else "📥 Скачать .docx", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    } else if (htmlWork != null) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, DeepSeekBlue.copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("🌐 Готовая работа: ${htmlWork.first}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("HTML-файл сохранён — откройте в браузере", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { DocxEngine.openHtmlInBrowser(ctx, htmlWork.first, htmlWork.second) },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f)
                    ) { Text("🌐 Посмотреть работу", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                DocxEngine.saveExportAndNotify(ctx, "${htmlWork.first}.html", "text/html", htmlWork.second.toByteArray(Charsets.UTF_8))
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) { Text("📥 HTML", fontSize = 12.sp) }
                }
            }
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.padding(bottom = 6.dp)
    ) {
        Text(
            "❐",
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable { onCopy() }.padding(vertical = 2.dp)
        )
        if (isLast && !streaming) {
            Text(
                "↻",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { onRetry() }.padding(vertical = 2.dp)
            )
            Text(
                if (msg.truncated) "⏭ Продолжить (обрыв)" else "⏭ Продолжить",
                fontSize = 12.sp,
                fontWeight = if (msg.truncated) FontWeight.SemiBold else FontWeight.Normal,
                color = if (msg.truncated) DeepSeekBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { onContinue() }.padding(vertical = 2.dp)
            )
        }
        if (hasSafRetry) {
            Text(
                "🔁 Повторить SAF",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = DeepSeekBlue,
                modifier = Modifier.clickable { onRetrySaf() }.padding(vertical = 2.dp)
            )
        }
        Spacer(Modifier.weight(1f))
        if (msg.usage.isNotBlank()) {
            Text(
                msg.usage,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
            )
        }
    }
}
