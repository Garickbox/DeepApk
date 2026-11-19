package com.garickbox.deepapk

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun ChatDrawerSheet(
    drawerSearch: String,
    onDrawerSearchChange: (String) -> Unit,
    filteredSessions: List<ChatSession>,
    activeSessionId: String?,
    onSwitchSession: (ChatSession) -> Unit,
    onDeleteSession: (String) -> Unit,
    onCloseDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenOcrBase: () -> Unit,
    settings: AppSettings,
    balanceText: String
) {
    ModalDrawerSheet(
                    modifier = Modifier.width(300.dp),
                    drawerContainerColor = MaterialTheme.colorScheme.background
                ) {
                    Column(Modifier.fillMaxHeight().padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(22.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("🔍", fontSize = 14.sp)
                            Spacer(Modifier.width(8.dp))
                            BasicTextField(
                                value = drawerSearch,
                                onValueChange = onDrawerSearchChange,
                                singleLine = true,
                                textStyle = TextStyle(
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onBackground
                                ),
                                cursorBrush = SolidColor(DeepSeekBlue),
                                modifier = Modifier.weight(1f),
                                decorationBox = { inner ->
                                    Box {
                                        if (drawerSearch.isEmpty()) {
                                            Text(
                                                "Поиск в содержимом чата…",
                                                fontSize = 14.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        inner()
                                    }
                                }
                            )
                            if (drawerSearch.isNotEmpty()) {
                                Text(
                                    "✕",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.clickable { onDrawerSearchChange("") }.padding(2.dp)
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        LazyColumn(Modifier.weight(1f)) {
                            items(filteredSessions.size, key = { filteredSessions[it].id }) { i ->
                                val s = filteredSessions[i]
                                val selected = s.id == activeSessionId
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            if (selected) MaterialTheme.colorScheme.surface
                                            else Color.Transparent
                                        )
                                        .clickable {
                                            onSwitchSession(s)
                                            onCloseDrawer()
                                        }
                                        .padding(horizontal = 10.dp, vertical = 11.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = s.title,
                                        fontSize = 14.5.sp,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        "🗑",
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .clickable { onDeleteSession(s.id) }
                                            .padding(start = 8.dp, top = 2.dp, bottom = 2.dp)
                                    )
                                }
                            }
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 8.dp),
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    onCloseDrawer()
                                    onOpenOcrBase()
                                }
                                .padding(horizontal = 6.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("📚", fontSize = 16.sp, modifier = Modifier.width(34.dp))
                            Column(Modifier.weight(1f)) {
                                Text("База OCR", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    "офлайн-кеш распознанных страниц",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text("›", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    onCloseDrawer()
                                    onOpenSettings()
                                }
                                .padding(horizontal = 6.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            DeepWhaleLogo(sizeDp = 34, isGemini = settings.provider == "gemini")
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("DeepApk · ${BuildConfig.VERSION_NAME}", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    if (settings.provider == "deepseek" && balanceText.isNotBlank()) "Баланс: $balanceText"
                                    else settings.activeModelLabel(),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text("⋯", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
}
