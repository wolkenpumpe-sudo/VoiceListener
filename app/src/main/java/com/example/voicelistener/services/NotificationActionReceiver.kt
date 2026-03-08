package com.example.voicelistener.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import com.example.voicelistener.utils.FileLogger

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        FileLogger.log(context, "NotificationReceiver", "Received action: ${intent.action}")

        if (intent.action == "ACTION_SHOW_OVERLAY") {
            try {
                val serviceIntent = Intent(context, OverlayService::class.java).apply {
                    action = "ACTION_SHOW_OVERLAY"
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                FileLogger.log(context, "NotificationReceiver", "FEHLER: ${e.message}")
            }
        }
    }
}
