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
        // Console Log
        Log.d(tag, message)

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
        return if (file.exists()) file.readText() else "No logs yet."
    }

    fun clearLogs(context: Context) {
        val file = File(context.filesDir, FILE_NAME)
        if (file.exists()) file.delete()
    }
}
