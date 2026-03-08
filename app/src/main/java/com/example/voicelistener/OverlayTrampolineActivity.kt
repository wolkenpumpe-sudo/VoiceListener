package com.example.voicelistener

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import com.example.voicelistener.services.OverlayService
import com.example.voicelistener.utils.FileLogger

/**
 * An invisible activity used as a trampoline to start the service from a notification click.
 * This bypasses Android 10+ background start restrictions.
 */
class OverlayTrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FileLogger.log(this, "Trampoline", "Bouing! Trampoline started.")
        
        // Forward the action to the Service
        val serviceIntent = Intent(this, OverlayService::class.java).apply {
            action = "ACTION_SHOW_OVERLAY"
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            FileLogger.log(this, "Trampoline", "Service start command sent from Activity Context.")
            Toast.makeText(this, "Wiederherstellen...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            FileLogger.log(this, "Trampoline", "Failed to start service: ${e.message}")
            Toast.makeText(this, "Fehler: ${e.message}", Toast.LENGTH_LONG).show()
        }

        // Close this activity immediately
        finish()
        // Remove transition animation to make it feel invisible
        overridePendingTransition(0, 0)
    }
}
