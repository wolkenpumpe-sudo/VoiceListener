package com.example.voicelistener

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import com.example.voicelistener.services.VoiceAccessibilityService
import com.example.voicelistener.utils.FileLogger

/**
 * Invisible activity that briefly gains foreground focus to read the clipboard.
 */
class FocusedCaptureActivity : Activity() {
    
    private val TAG = "CaptureActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FileLogger.log(this, TAG, "onCreate")
        
        // Make it as invisible as possible but still an activity
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        val params = window.attributes
        params.alpha = 0.01f // Tiny bit of alpha to ensure it's "visible" to system
        params.width = 1
        params.height = 1
        window.attributes = params
    }

    override fun onResume() {
        super.onResume()
        FileLogger.log(this, TAG, "onResume - Waiting for focus...")
        
        // Wait 100ms for system to settle and grant clipboard access
        Handler(Looper.getMainLooper()).postDelayed({
            performCapture()
        }, 100)
    }

    private fun performCapture() {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            
            if (clip != null && clip.itemCount > 0) {
                val item = clip.getItemAt(0)
                val text = item.coerceToText(this)?.toString()
                
                if (!text.isNullOrBlank()) {
                    val service = VoiceAccessibilityService.instance
                    if (service != null) {
                        FileLogger.log(this, TAG, "Capture Success: ${text.take(10)}...")
                        service.addToClipboardHistory(text)
                    } else {
                        FileLogger.log(this, TAG, "Service instance is NULL!")
                        android.widget.Toast.makeText(this, "Dienst nicht verbunden!", android.widget.Toast.LENGTH_SHORT).show()
                    }
                } else {
                    FileLogger.log(this, TAG, "Clipboard is empty or blank")
                }
            } else {
                FileLogger.log(this, TAG, "No Primary Clip found")
            }
        } catch (e: Exception) {
            FileLogger.log(this, TAG, "Error: ${e.message}")
        } finally {
            finishAndRemoveTask()
            overridePendingTransition(0, 0)
        }
    }
}
