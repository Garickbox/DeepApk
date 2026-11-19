package com.garickbox.deepapk

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast

internal fun copyPlain(ctx: Context, text: String, toastMsg: String = "Скопировано") {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("deepapk", text))
    Toast.makeText(ctx, toastMsg, Toast.LENGTH_SHORT).show()
}
