package com.example.voicelistener

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SettingsBackup {
    private const val BACKUP_PREFS = "settings_backups"
    private const val BACKUP_KEY = "backups"
    private const val MAX_BACKUPS = 40

    // All keys worth backing up — keep in sync when adding new settings!
    private val BACKUP_KEYS = listOf(
        // API & Auth
        "groq_api_key", "gemini_api_key", "eqs_server_url",
        // LLM/AI
        "llama_enabled", "llama_system_prompt", "llm_model", "custom_vocabulary",
        // Text Expansion
        "text_expansion_enabled", "text_expansion_rules",
        // Overlay/Button
        "overlay_focus_mode", "overlay_always_hidden",
        "overlay_scale", "overlay_alpha", "overlay_color", "overlay_dim",
        "overlay_x", "overlay_y",
        "overlay_recording_trigger", "overlay_interaction_mode",
        // Feature toggles
        "app_translate_enabled", "app_clipboard_enabled",
        "app_market_enabled", "app_askllama_enabled", "app_eqs_context_enabled",
        // Clipboard
        "clipboard_history_enabled", "clipboard_history", "clipboard_favorites", "clipboard_keep_open",
        // Logging
        "logs_enabled",
        // Market Data
        "market_data_source", "firebase_market_url",
        "market_data_keys", "market_data_interval", "market_min_values",
        "market_notification_enabled",
        "market_x", "market_y", "market_widget_font_size", "market_fullscreen_font_size",
        // Translation
        "last_translate_lang",
        // Gestures
        "gesture_actions",
        "swipe_up_action", "swipe_down_action", "swipe_left_action", "swipe_right_action",
        // Tasker
        "tasker_tasks",
        // Radial Menu
        "radial_menu_config", "radial_groups", "custom_radial_items",
        // Per-item appearance overrides
        "item_appearance_overrides"
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

        // Backup gestures.dat as Base64
        try {
            val gestureFile = File(context.filesDir, "gestures.dat")
            if (gestureFile.exists()) {
                entry.put("gestures_dat", Base64.encodeToString(gestureFile.readBytes(), Base64.NO_WRAP))
            }
        } catch (_: Exception) {}

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

    fun exportToJson(context: Context): String {
        val appPrefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val snapshot = JSONObject()
        val allEntries = appPrefs.all
        for (key in BACKUP_KEYS) {
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

        val export = JSONObject()
        export.put("app", "VoiceListener")
        export.put("format_version", 2)
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        export.put("exported_at", fmt.format(Date()))
        export.put("settings", snapshot)

        // Export gestures.dat as Base64
        try {
            val gestureFile = File(context.filesDir, "gestures.dat")
            if (gestureFile.exists()) {
                val bytes = gestureFile.readBytes()
                export.put("gestures_dat", Base64.encodeToString(bytes, Base64.NO_WRAP))
            }
        } catch (_: Exception) {}

        return export.toString(2)
    }

    fun importFromJson(context: Context, json: String): Pair<Boolean, String> {
        val root: JSONObject
        try {
            root = JSONObject(json)
        } catch (_: Exception) {
            return Pair(false, "Ungültige JSON-Datei")
        }

        if (root.optString("app") != "VoiceListener") {
            return Pair(false, "Keine VoiceListener-Exportdatei")
        }

        val settings = root.optJSONObject("settings")
            ?: return Pair(false, "Keine Einstellungen in der Datei")

        if (settings.length() == 0) {
            return Pair(false, "Keine Einstellungen in der Datei")
        }

        // Create backup before importing
        createBackup(context, "Vor Import")

        val appPrefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val editor = appPrefs.edit()
        var count = 0

        val keys = settings.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = settings.get(key)
            when (value) {
                is Boolean -> { editor.putBoolean(key, value); count++ }
                is Double -> { editor.putFloat(key, value.toFloat()); count++ }
                is Int -> { editor.putInt(key, value); count++ }
                is String -> { editor.putString(key, value); count++ }
            }
        }

        editor.apply()

        // Import gestures.dat if present
        var gestureMsg = ""
        try {
            val gesturesB64 = root.optString("gestures_dat", "")
            if (gesturesB64.isNotEmpty()) {
                val bytes = Base64.decode(gesturesB64, Base64.NO_WRAP)
                val gestureFile = File(context.filesDir, "gestures.dat")
                gestureFile.writeBytes(bytes)
                gestureMsg = " + Gesten importiert"
            }
        } catch (_: Exception) {}

        return Pair(true, "$count Einstellungen importiert$gestureMsg")
    }

    fun getImportPreview(json: String): Pair<Boolean, String> {
        val root: JSONObject
        try {
            root = JSONObject(json)
        } catch (_: Exception) {
            return Pair(false, "Ungültige JSON-Datei")
        }

        if (root.optString("app") != "VoiceListener") {
            return Pair(false, "Keine VoiceListener-Exportdatei")
        }

        val settings = root.optJSONObject("settings")
            ?: return Pair(false, "Keine Einstellungen in der Datei")

        val formatVersion = root.optInt("format_version", 1)
        val exportedAt = root.optString("exported_at", "unbekannt")

        val sb = StringBuilder()
        sb.append("Exportiert: $exportedAt\n")
        if (formatVersion > 2) {
            sb.append("Hinweis: Neueres Format (v$formatVersion) - Best-Effort Import\n")
        }
        val hasGestures = root.optString("gestures_dat", "").isNotEmpty()
        sb.append("${settings.length()} Einstellungen")
        if (hasGestures) sb.append(" + Gesten")
        sb.append(":\n\n")

        val keys = settings.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = settings.get(key)
            val displayValue = when {
                value is String && value.length > 50 -> value.take(50) + "..."
                else -> value.toString()
            }
            sb.append("• $key = $displayValue\n")
        }

        return Pair(true, sb.toString())
    }

    fun restoreBackup(context: Context, index: Int): Boolean {
        val backups = getBackups(context)
        if (index < 0 || index >= backups.length()) return false

        val entry = backups.optJSONObject(index) ?: return false
        val settings = entry.optJSONObject("settings") ?: return false

        // Create backup before restoring
        createBackup(context, "Vor Wiederherstellung")

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

        // Restore gestures.dat if present
        try {
            val gesturesB64 = entry.optString("gestures_dat", "")
            if (gesturesB64.isNotEmpty()) {
                val bytes = Base64.decode(gesturesB64, Base64.NO_WRAP)
                File(context.filesDir, "gestures.dat").writeBytes(bytes)
            }
        } catch (_: Exception) {}

        return true
    }
}
