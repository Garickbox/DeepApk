package com.garickbox.deepapk

import android.util.TypedValue
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun ContextMenuDialog(
    message: Message,
    isDark: Boolean,
    hasCode: Boolean,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onSelectText: () -> Unit,
    onRetry: () -> Unit,
    onContinue: () -> Unit,
    onAddToSkill: () -> Unit,
    onSendToTermux: () -> Unit
) {
    val dividerCol = if (isDark) Color(0xFF2B2F38) else Color(0xFFF0F1F4)
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = if (isDark) Color(0xFF1F2229) else Color.White,
            tonalElevation = 8.dp,
            shadowElevation = 10.dp,
            modifier = Modifier.widthIn(min = 240.dp, max = 290.dp)
        ) {
            Column(Modifier.padding(vertical = 6.dp)) {
                ContextMenuItem("❐", "Копировать", onClick = onCopy)
                HorizontalDivider(color = dividerCol)
                ContextMenuItem("⬚", "Выбрать текст", onClick = onSelectText)
                HorizontalDivider(color = dividerCol)
                ContextMenuItem("↻", "Повторить", onClick = onRetry)
                if (message.role == "assistant") {
                    HorizontalDivider(color = dividerCol)
                    ContextMenuItem("⏭", "Продолжить", onClick = onContinue)
                }
                HorizontalDivider(color = dividerCol)
                ContextMenuItem("📌", "В правила заготовки", onClick = onAddToSkill)
                if (hasCode) {
                    HorizontalDivider(color = dividerCol)
                    ContextMenuItem("▶", "Передать в Termux", accent = DeepSeekBlue, onClick = onSendToTermux)
                }
            }
        }
    }
}

@Composable
internal fun SelectTextDialog(
    message: Message,
    isDark: Boolean,
    fontSizeSp: Float,
    onDismiss: () -> Unit,
    onCopyAll: () -> Unit,
    onSendToTermux: (String) -> Unit,
    onEmptySelection: () -> Unit
) {
    val textColArgb = MaterialTheme.colorScheme.onBackground.toArgb()
    var tvRef by remember(message.id) { mutableStateOf<TextView?>(null) }
    fun getSelectedText(): String {
        val tv = tvRef ?: return ""
        val a = tv.selectionStart
        val b = tv.selectionEnd
        if (a < 0 || b < 0 || a == b) return ""
        val lo = minOf(a, b).coerceIn(0, tv.text.length)
        val hi = maxOf(a, b).coerceIn(0, tv.text.length)
        return tv.text.subSequence(lo, hi).toString().trim()
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = if (isDark) Color(0xFF1F2229) else Color.White,
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.84f)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Выбрать текст", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text("Выдели маркером фрагмент для Termux", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        "✕",
                        fontSize = 18.sp,
                        modifier = Modifier.clickable { onDismiss() }.padding(4.dp)
                    )
                }
                Spacer(Modifier.height(10.dp))
                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    factory = { c ->
                        ScrollView(c).apply {
                            isFillViewport = true
                            val tv = TextView(c).apply {
                                setTextIsSelectable(true)
                                setLineSpacing(0f, 1.35f)
                                setPadding(6, 6, 6, 32)
                                customSelectionActionModeCallback = object : ActionMode.Callback {
                                    override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                                        menu?.add(0, 99123, 0, "▶ В Termux")
                                        return true
                                    }
                                    override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?) = false
                                    override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
                                        if (item?.itemId == 99123) {
                                            val sel = getSelectedText()
                                            mode?.finish()
                                            if (sel.isNotBlank()) onSendToTermux(sel)
                                            return true
                                        }
                                        return false
                                    }
                                    override fun onDestroyActionMode(mode: ActionMode?) {}
                                }
                            }
                            tvRef = tv
                            addView(
                                tv,
                                ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT
                                )
                            )
                        }
                    },
                    update = { sv ->
                        val tv = sv.getChildAt(0) as TextView
                        tvRef = tv
                        if (tv.text.toString() != message.content) tv.text = message.content
                        tv.setTextColor(textColArgb)
                        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp)
                    }
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = onCopyAll,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Копировать всё") }
                    Button(
                        onClick = {
                            val sel = getSelectedText()
                            if (sel.isBlank()) onEmptySelection() else onSendToTermux(sel)
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("▶ В Termux") }
                }
            }
        }
    }
}
