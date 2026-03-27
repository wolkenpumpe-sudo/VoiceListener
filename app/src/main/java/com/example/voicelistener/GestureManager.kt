package com.example.voicelistener

import android.content.Context
import android.gesture.Gesture
import android.gesture.GestureStore
import android.gesture.Prediction
import org.json.JSONObject
import java.io.File

class GestureManager(private val context: Context) {

    private val gestureStore = GestureStore().apply {
        sequenceType = GestureStore.SEQUENCE_SENSITIVE
    }
    private val gestureFile = File(context.filesDir, "gestures.dat")
    private val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val RECOGNITION_THRESHOLD = 2.0
        private const val GESTURE_ACTIONS_KEY = "gesture_actions"

        val ACTION_LABELS = linkedMapOf(
            "start_recording" to "Aufnahme starten",
            "clipboard_capture" to "Clipboard erfassen",
            "toggle_menu" to "Menü öffnen/schließen",
            "hide_button" to "Button verstecken",
            "show_volume" to "Lautstärke anzeigen",
            "toggle_mute" to "Stumm/Laut",
            "media_play_pause" to "Play/Pause",
            "show_notifications" to "Benachrichtigungen",
            "show_translator" to "Übersetzer",
            "show_clipboard_history" to "Clipboard-Verlauf",
            "toggle_market_data" to "Marktdaten",
            "toggle_ask_llama" to "AskLlama umschalten",
            "open_settings" to "Einstellungen öffnen"
        )

        /** Returns all actions including dynamic Tasker tasks */
        fun getAllActionLabels(context: Context): LinkedHashMap<String, String> {
            val all = LinkedHashMap(ACTION_LABELS)
            all.putAll(TaskerHelper.getActionLabels(context))
            return all
        }
    }

    init {
        if (gestureFile.exists()) {
            try {
                gestureFile.inputStream().use { gestureStore.load(it, true) }
            } catch (_: Exception) {
                // Corrupted file, start fresh
            }
        }
    }

    private fun save() {
        try {
            gestureFile.outputStream().use { gestureStore.save(it, true) }
        } catch (_: Exception) {
        }
    }

    fun reload() {
        // Reload gestures from disk (needed when gestures were saved by another component)
        if (gestureFile.exists()) {
            try {
                gestureFile.inputStream().use { gestureStore.load(it, true) }
            } catch (_: Exception) {}
        }
    }

    fun addGesture(name: String, gesture: Gesture) {
        gestureStore.addGesture(name, gesture)
        save()
    }

    fun removeGesture(name: String) {
        val gestures = gestureStore.getGestures(name)
        gestures?.forEach { gestureStore.removeGesture(name, it) }
        save()
        // Remove action mapping
        val actions = getActionsJson()
        actions.remove(name)
        prefs.edit().putString(GESTURE_ACTIONS_KEY, actions.toString()).apply()
    }

    fun getGesture(name: String): Gesture? {
        return gestureStore.getGestures(name)?.firstOrNull()
    }

    fun getGestureNames(): List<String> {
        return gestureStore.gestureEntries
            ?.filter { name -> gestureStore.getGestures(name)?.isNotEmpty() == true }
            ?.toList()
            ?: emptyList()
    }

    fun recognize(gesture: Gesture): Pair<String, Double>? {
        reload() // Always reload to pick up newly saved gestures
        val predictions: ArrayList<Prediction> = gestureStore.recognize(gesture)
        if (predictions.isEmpty()) {
            android.util.Log.d("GestureManager", "No predictions at all (${getGestureNames().size} gestures loaded)")
            return null
        }
        val best = predictions[0]
        android.util.Log.d("GestureManager", "Best match: ${best.name} score=${best.score} (threshold=$RECOGNITION_THRESHOLD)")
        if (best.score < RECOGNITION_THRESHOLD) return null
        return Pair(best.name, best.score)
    }

    fun getActionForGesture(name: String): String? {
        val actions = getActionsJson()
        return if (actions.has(name)) actions.getString(name) else null
    }

    fun setActionForGesture(name: String, action: String) {
        val actions = getActionsJson()
        actions.put(name, action)
        prefs.edit().putString(GESTURE_ACTIONS_KEY, actions.toString()).apply()
    }

    private fun getActionsJson(): JSONObject {
        val json = prefs.getString(GESTURE_ACTIONS_KEY, "{}") ?: "{}"
        return try { JSONObject(json) } catch (_: Exception) { JSONObject() }
    }
}
