package com.example.voicelistener

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SettingsBackup {
    private const val BACKUP_PREFS = "settings_backups"
    private const val BACKUP_KEY = "backups"
    private const val MAX_BACKUPS = 40

    // All keys worth backing up
    private val BACKUP_KEYS = listOf(
        "llama_enabled", "llama_system_prompt", "custom_vocabulary",
        "text_expansion_enabled", "text_expansion_rules",
        "overlay_focus_mode", "overlay_always_hidden",
        "overlay_scale", "overlay_alpha", "overlay_color",
        "overlay_recording_trigger",
        "app_translate_enabled", "app_clipboard_enabled",
        "app_market_enabled", "app_askllama_enabled", "app_eqs_context_enabled",
        "clipboard_history_enabled", "logs_enabled",
        "market_data_keys", "market_data_interval"
    )

    fun createBackup(context: Context, reason: String) {
        val appPrefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val backupPrefs = context.getSharedPreferences(BACKUP_PREFS, Context.MODE_PRIVATE)

        // Snapshot current settings
        val snapshot = JSONObject()
        for (key in BACKUP_KEYS) {
            val allEntries = appPrefs.all
            if (allEntries.containsKey(key)) {
                val value = allEntries[key]
                when (value) {
                    is Boolean -> snapshot.put(key, value)
                    is Float -> snapshot.put(key, value.toDouble())
                    is Int -> snapshot.put(key, value)
                    is String -> snapshot.put(key, value)
                }
            }
        }

        val entry = JSONObject()
        val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMAN)
        entry.put("timestamp", fmt.format(Date()))
        entry.put("reason", reason)
        entry.put("settings", snapshot)

        // Load existing backups
        val existingJson = backupPrefs.getString(BACKUP_KEY, "[]") ?: "[]"
        val backups = try { JSONArray(existingJson) } catch (_: Exception) { JSONArray() }

        // Add new backup at the beginning
        val newBackups = JSONArray()
        newBackups.put(entry)
        for (i in 0 until minOf(backups.length(), MAX_BACKUPS - 1)) {
            newBackups.put(backups.get(i))
        }

        backupPrefs.edit().putString(BACKUP_KEY, newBackups.toString()).apply()
    }

    fun getBackups(context: Context): JSONArray {
        val backupPrefs = context.getSharedPreferences(BACKUP_PREFS, Context.MODE_PRIVATE)
        val json = backupPrefs.getString(BACKUP_KEY, "[]") ?: "[]"
        return try { JSONArray(json) } catch (_: Exception) { JSONArray() }
    }

    fun restoreBackup(context: Context, index: Int): Boolean {
        val backups = getBackups(context)
        if (index < 0 || index >= backups.length()) return false

        val entry = backups.optJSONObject(index) ?: return false
        val settings = entry.optJSONObject("settings") ?: return false

        val appPrefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val editor = appPrefs.edit()

        val keys = settings.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = settings.get(key)
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Double -> editor.putFloat(key, value.toFloat())
                is Int -> editor.putInt(key, value)
                is String -> editor.putString(key, value)
            }
        }

        editor.apply()
        return true
    }
}
