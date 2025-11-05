package com.brill.zero.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import android.os.Looper
import android.os.Handler

class CopyCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = intent.getStringExtra("code") ?: return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("verification_code", code))
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, "验证码已复制: $code", Toast.LENGTH_SHORT).show()
        }
    }
}