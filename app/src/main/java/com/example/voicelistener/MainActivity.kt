package com.example.voicelistener

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.voicelistener.services.OverlayService
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                checkOverlayPermission()
            } else {
                Toast.makeText(this, "Audio permission needed", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        checkPermissions()
        
        val apiKeyInput = findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.apiKeyInput)
        val saveButton = findViewById<Button>(R.id.saveButton)
        
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        apiKeyInput.setText(prefs.getString("groq_api_key", ""))

        saveButton.setOnClickListener {
            val key = apiKeyInput.text.toString().trim()
            if (key.isNotEmpty()) {
                prefs.edit().putString("groq_api_key", key).apply()
                
                // Start Overlay Service
                val intent = Intent(this, com.example.voicelistener.services.OverlayService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                Toast.makeText(this, "Service gestartet!", Toast.LENGTH_SHORT).show()
                refreshLogs()
            } else {
                Toast.makeText(this, "Enter a valid key", Toast.LENGTH_SHORT).show()
            }
        }

        val refreshBtn = findViewById<Button>(R.id.refreshLogs)
        val logView = findViewById<TextView>(R.id.logTextView)
        
        refreshBtn.setOnClickListener {
            refreshLogs()
            // Copy to clipboard
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("App Logs", logView.text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Logs kopiert!", Toast.LENGTH_SHORT).show()
        }
        
        refreshLogs()
    }
    
    private fun refreshLogs() {
        val logView = findViewById<TextView>(R.id.logTextView)
        logView.text = com.example.voicelistener.utils.FileLogger.getLogContent(this)
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            checkOverlayPermission()
        }
    }

    private fun checkOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            // Ideally use startActivityResult to know when they come back
            startActivity(intent)
            Toast.makeText(this, "Please grant overlay permission", Toast.LENGTH_LONG).show()
        } else {
            startOverlayService()
        }
    }

    private fun startOverlayService() {
        val intent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
