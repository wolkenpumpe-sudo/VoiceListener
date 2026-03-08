package com.example.voicelistener

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.example.voicelistener.services.OverlayService
import com.example.voicelistener.utils.FileLogger

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            FileLogger.log(context, "BootReceiver", "Boot completed detected.")
            
            // Check Overlay Permission before starting
            if (Settings.canDrawOverlays(context)) {
                val serviceIntent = Intent(context, OverlayService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                FileLogger.log(context, "BootReceiver", "OverlayService start requested.")
            } else {
                FileLogger.log(context, "BootReceiver", "Cannot start: Overlay permission missing.")
            }
        }
    }
}
