package com.example.voicelistener

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import java.net.URLEncoder

class EqsProcessTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val selectedText = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()

        if (!selectedText.isNullOrBlank()) {
            try {
                val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
                val baseUrl = prefs.getString("eqs_server_url", "") ?: ""
                if (baseUrl.isEmpty()) {
                    finish()
                    return
                }
                val encodedText = URLEncoder.encode(selectedText.trim(), "UTF-8")
                val url = "${baseUrl}/ms.php?t=$encodedText"
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(browserIntent)
            } catch (e: Exception) {
                // Ignore or log error
            }
        }
        finish() // Close the invisible activity immediately
    }
}
