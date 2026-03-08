package com.example.voicelistener.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileLogger {
    private const val FILE_NAME = "app_log.txt"

    fun log(context: Context, tag: String, message: String) {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("logs_enabled", true)) return

        // Console Log (Force Error level for visibility during debugging)
        Log.e(tag, message)

        // File Log
        try {
            val file = File(context.filesDir, FILE_NAME)
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val logEntry = "$timestamp [$tag]: $message\n"
            
            val writer = FileWriter(file, true) // Append mode
            writer.append(logEntry)
            writer.flush()
            writer.close()
        } catch (e: IOException) {
            Log.e("FileLogger", "Failed to write log", e)
        }
    }

    fun getLogContent(context: Context): String {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return "No logs yet."

        val maxLength = 50 * 1024 // 50 KB
        try {
            if (file.length() > maxLength) {
                java.io.RandomAccessFile(file, "r").use { raf ->
                    raf.seek(file.length() - maxLength)
                    val buffer = ByteArray(maxLength)
                    raf.readFully(buffer)
                    return "[... Old logs truncated ...]\n" + String(buffer, Charsets.UTF_8)
                }
            } else {
                return file.readText()
            }
        } catch (e: Exception) {
            return "Error reading logs: ${e.message}"
        }
    }

    fun clearLogs(context: Context) {
        val file = File(context.filesDir, FILE_NAME)
        if (file.exists()) file.delete()
    }
}
