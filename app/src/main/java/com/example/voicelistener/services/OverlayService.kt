package com.example.voicelistener.services

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.Toast
import androidx.core.app.NotificationCompat
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import com.example.voicelistener.R
import com.example.voicelistener.GestureManager
import com.example.voicelistener.SettingsBackup
import com.example.voicelistener.network.ChatRequest
import com.example.voicelistener.network.GroqClient
import com.example.voicelistener.network.Message
import com.example.voicelistener.utils.AudioRecorder
import com.example.voicelistener.utils.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.ContextCompat
import java.io.File
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import org.json.JSONArray
import com.example.voicelistener.VoiceChatWidget

class OverlayService : Service() {
    
    companion object {
        const val DEFAULT_PROMPT = """
You are a strict text processing engine. Your ONLY goal is to output the single best corrected version of the text in its ORIGINAL language.

RULES:
1. DETECT the language of the input text.
2. Output ONLY the corrected text in the SAME language as the input.
3. NO conversational filler. NO introductory phrases.
4. PROVIDE A SINGLE OPTION. Do not give alternatives.
5. DO NOT repeat the input prompt.
6. If the input is a question, DO NOT answer it unless explicitly told to "Answer: ...". Just correct the grammar of the question itself.

CRITICAL: DO NOT TRANSLATE unless the user explicitly asks for translation (e.g. "Translate to English"). 
If the input is German, the output MUST be German.
If the input is English, the output MUST be English.
Do not switch languages.

Output:
"""

        val ALL_RADIAL_ITEMS = listOf(
            RadialItemDef("translate_es", "ES", "Spanisch", "#E91E63"),
            RadialItemDef("translate_en", "EN", "Englisch", "#2196F3"),
            RadialItemDef("translate_de", "DE", "Deutsch", "#FF9800"),
            RadialItemDef("translator", "\uD83D\uDD04", "Übersetzen", "#BB86FC"),
            RadialItemDef("clipboard", "\uD83D\uDCCB", "Clipboard", "#03DAC5"),
            RadialItemDef("market", "\uD83D\uDCC8", "Markt", "#4CAF50"),
            RadialItemDef("ask_llama", "\uD83E\uDD99", "Llama", "#607D8B"),
            RadialItemDef("settings_ai", "\uD83E\uDD16", "AI Set.", "#795548"),
            RadialItemDef("fix_text", "\u270F", "Fix Text", "#00BCD4"),
            RadialItemDef("timeout", "\u23F1", "Timeout", "#FF5722"),
            RadialItemDef("settings", "\u2699", "Settings", "#6200EE"),
        )

        /** Returns all radial items including dynamically added Tasker tasks and custom items */
        fun getAllRadialItems(context: android.content.Context): List<RadialItemDef> {
            val items = ALL_RADIAL_ITEMS.toMutableList()
            // Add Tasker tasks
            val taskerTasks = com.example.voicelistener.TaskerHelper.getTasks(context)
            for (task in taskerTasks) {
                val actionId = com.example.voicelistener.TaskerHelper.actionIdForTask(task)
                items.add(RadialItemDef(actionId, "\u26A1", "T: ${task.name}", "#9C27B0"))
            }
            // Add custom items
            items.addAll(getCustomRadialItems(context))
            // Add groups as items (they appear in the main radial menu)
            for (group in getRadialGroups(context)) {
                items.add(RadialItemDef(group.id, group.icon, group.label, group.color))
            }
            return items
        }

        private const val CUSTOM_ITEMS_KEY = "custom_radial_items"

        fun getCustomRadialItems(context: android.content.Context): List<RadialItemDef> {
            val prefs = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
            val json = prefs.getString(CUSTOM_ITEMS_KEY, "[]") ?: "[]"
            return try {
                val arr = org.json.JSONArray(json)
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    RadialItemDef(
                        id = obj.getString("id"),
                        icon = obj.optString("icon", "\u2B50"),
                        label = obj.getString("label"),
                        defaultColor = obj.optString("color", "#FF9800")
                    )
                }
            } catch (_: Exception) { emptyList() }
        }

        /** Get the source action ID for a custom radial item */
        fun getCustomItemAction(context: android.content.Context, customId: String): String? {
            val prefs = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
            val json = prefs.getString(CUSTOM_ITEMS_KEY, "[]") ?: "[]"
            return try {
                val arr = org.json.JSONArray(json)
                (0 until arr.length()).firstNotNullOfOrNull { i ->
                    val obj = arr.getJSONObject(i)
                    if (obj.getString("id") == customId) obj.getString("actionId") else null
                }
            } catch (_: Exception) { null }
        }

        fun saveCustomItem(context: android.content.Context, id: String, label: String, actionId: String, icon: String, color: String) {
            val prefs = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
            val json = prefs.getString(CUSTOM_ITEMS_KEY, "[]") ?: "[]"
            val arr = try { org.json.JSONArray(json) } catch (_: Exception) { org.json.JSONArray() }
            arr.put(org.json.JSONObject().apply {
                put("id", id)
                put("label", label)
                put("actionId", actionId)
                put("icon", icon)
                put("color", color)
            })
            prefs.edit().putString(CUSTOM_ITEMS_KEY, arr.toString()).apply()
        }

        fun removeCustomItem(context: android.content.Context, customId: String) {
            val prefs = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
            val json = prefs.getString(CUSTOM_ITEMS_KEY, "[]") ?: "[]"
            val arr = try { org.json.JSONArray(json) } catch (_: Exception) { return }
            val newArr = org.json.JSONArray()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (obj.getString("id") != customId) newArr.put(obj)
            }
            prefs.edit().putString(CUSTOM_ITEMS_KEY, newArr.toString()).apply()
        }
        // --- Radial Groups (Sub-Menus) ---
        private const val GROUPS_KEY = "radial_groups"

        data class RadialGroupDef(
            val id: String,
            val label: String,
            val icon: String,
            val color: String,
            val iconType: String = "emoji", // "emoji", "text", or "gallery"
            val iconUri: String? = null,
            val children: List<String> // action IDs of child items
        )

        fun getRadialGroups(context: android.content.Context): List<RadialGroupDef> {
            val prefs = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
            val json = prefs.getString(GROUPS_KEY, "[]") ?: "[]"
            return try {
                val arr = org.json.JSONArray(json)
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    val childArr = obj.getJSONArray("children")
                    val children = (0 until childArr.length()).map { c -> childArr.getString(c) }
                    RadialGroupDef(
                        id = obj.getString("id"),
                        label = obj.getString("label"),
                        icon = obj.optString("icon", "\uD83D\uDCC2"),
                        color = obj.optString("color", "#9C27B0"),
                        iconType = obj.optString("iconType", "emoji"),
                        iconUri = if (obj.has("iconUri") && obj.getString("iconUri").isNotEmpty()) obj.getString("iconUri") else null,
                        children = children
                    )
                }
            } catch (_: Exception) { emptyList() }
        }

        fun saveRadialGroup(context: android.content.Context, group: RadialGroupDef) {
            val prefs = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
            val json = prefs.getString(GROUPS_KEY, "[]") ?: "[]"
            val arr = try { org.json.JSONArray(json) } catch (_: Exception) { org.json.JSONArray() }
            // Remove existing with same id
            val newArr = org.json.JSONArray()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (obj.getString("id") != group.id) newArr.put(obj)
            }
            newArr.put(org.json.JSONObject().apply {
                put("id", group.id)
                put("label", group.label)
                put("icon", group.icon)
                put("color", group.color)
                put("iconType", group.iconType)
                put("iconUri", group.iconUri ?: "")
                put("children", org.json.JSONArray(group.children))
            })
            prefs.edit().putString(GROUPS_KEY, newArr.toString()).apply()
        }

        fun removeRadialGroup(context: android.content.Context, groupId: String) {
            val prefs = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
            val json = prefs.getString(GROUPS_KEY, "[]") ?: "[]"
            val arr = try { org.json.JSONArray(json) } catch (_: Exception) { return }
            val newArr = org.json.JSONArray()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (obj.getString("id") != groupId) newArr.put(obj)
            }
            prefs.edit().putString(GROUPS_KEY, newArr.toString()).apply()
        }

        // --- Per-item icon/color overrides ---
        private const val ITEM_APPEARANCE_KEY = "item_appearance_overrides"

        fun getItemAppearance(context: android.content.Context, itemId: String): ItemAppearance? {
            val prefs = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
            val json = prefs.getString(ITEM_APPEARANCE_KEY, "{}") ?: "{}"
            return try {
                val obj = org.json.JSONObject(json)
                if (obj.has(itemId)) {
                    val item = obj.getJSONObject(itemId)
                    ItemAppearance(
                        iconType = item.optString("iconType", "emoji"),
                        icon = if (item.has("icon")) item.getString("icon") else null,
                        iconUri = if (item.has("iconUri") && item.getString("iconUri").isNotEmpty()) item.getString("iconUri") else null,
                        color = if (item.has("color")) item.getString("color") else null
                    )
                } else null
            } catch (_: Exception) { null }
        }

        fun saveItemAppearance(context: android.content.Context, itemId: String, appearance: ItemAppearance) {
            val prefs = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
            val json = prefs.getString(ITEM_APPEARANCE_KEY, "{}") ?: "{}"
            val obj = try { org.json.JSONObject(json) } catch (_: Exception) { org.json.JSONObject() }
            obj.put(itemId, org.json.JSONObject().apply {
                put("iconType", appearance.iconType)
                put("icon", appearance.icon ?: "")
                put("iconUri", appearance.iconUri ?: "")
                if (appearance.color != null) put("color", appearance.color)
            })
            prefs.edit().putString(ITEM_APPEARANCE_KEY, obj.toString()).apply()
        }

        fun removeItemAppearance(context: android.content.Context, itemId: String) {
            val prefs = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
            val json = prefs.getString(ITEM_APPEARANCE_KEY, "{}") ?: "{}"
            val obj = try { org.json.JSONObject(json) } catch (_: Exception) { return }
            obj.remove(itemId)
            prefs.edit().putString(ITEM_APPEARANCE_KEY, obj.toString()).apply()
        }
    }

    data class RadialItemDef(val id: String, val icon: String, val label: String, val defaultColor: String)

    data class ItemAppearance(
        val iconType: String = "emoji", // "emoji", "text", or "gallery"
        val icon: String? = null,
        val iconUri: String? = null,
        val color: String? = null
    )

    private val PRIMARY_MODEL get() = getSharedPreferences("app_prefs", MODE_PRIVATE)
        .getString("llm_model", "llama-3.3-70b-versatile") ?: "llama-3.3-70b-versatile"
    private val FALLBACK_MODEL get(): String {
        val primary = PRIMARY_MODEL
        return if (primary == "llama-3.1-8b-instant") "llama-3.3-70b-versatile" else "llama-3.1-8b-instant"
    }

    private fun stripThinkingBlock(text: String): String {
        return text.replace(Regex("<think>[\\s\\S]*?</think>\\s*"), "").trim()
    }

    private fun stripThinkingFromResponse(response: com.example.voicelistener.network.ChatResponse): com.example.voicelistener.network.ChatResponse {
        return response.copy(choices = response.choices.map { choice ->
            choice.copy(message = choice.message.copy(content = stripThinkingBlock(choice.message.content)))
        })
    }

    private fun isGeminiModel(model: String): Boolean = model.startsWith("gemini")
    private fun useGoogleSearch(model: String): Boolean = model.contains("+ Search")
    private fun geminiModelId(model: String): String = when {
        model.contains("2.5-flash") -> "gemini-2.5-flash"
        model.contains("2.5-pro") -> "gemini-2.5-pro"
        else -> "gemini-2.5-flash"
    }

    private suspend fun chatWithFallback(auth: String, messages: List<Message>, temperature: Double = 0.0): com.example.voicelistener.network.ChatResponse {
        val primary = PRIMARY_MODEL

        // Route to Gemini if selected
        if (isGeminiModel(primary)) {
            val geminiKey = getSharedPreferences("app_prefs", MODE_PRIVATE)
                .getString("gemini_api_key", "") ?: ""
            if (geminiKey.isNotEmpty()) {
                val result = chatWithGemini(geminiKey, messages, geminiModelId(primary), useGoogleSearch(primary))
                // Wrap in ChatResponse format for compatibility
                return com.example.voicelistener.network.ChatResponse(
                    choices = listOf(com.example.voicelistener.network.Choice(
                        message = Message("assistant", result)
                    ))
                )
            } else {
                FileLogger.log(this, "LLM", "Gemini selected but no API key, falling back to Groq")
                withContext(Dispatchers.Main) { showTopMessage("Gemini Key fehlt — Groq Fallback") }
            }
        }

        // Groq path
        val raw = try {
            val request = ChatRequest(model = primary.replace(" + Search", ""), messages = messages, temperature = temperature)
            GroqClient.api.chatCompletion(auth, request)
        } catch (e: retrofit2.HttpException) {
            if (e.code() == 429) {
                val fallback = FALLBACK_MODEL
                FileLogger.log(this, "LLM", "Rate limit (429) on $primary, falling back to $fallback")
                withContext(Dispatchers.Main) { showTopMessage("Rate limit — Fallback: $fallback") }
                val request = ChatRequest(model = fallback, messages = messages, temperature = temperature)
                GroqClient.api.chatCompletion(auth, request)
            } else throw e
        }
        return stripThinkingFromResponse(raw)
    }

    private suspend fun chatWithGemini(apiKey: String, messages: List<Message>, model: String, withSearch: Boolean): String {
        return withContext(Dispatchers.IO) {
            // Build Gemini contents from messages
            val contents = JSONArray()
            for (msg in messages) {
                val role = if (msg.role == "assistant") "model" else msg.role
                // Gemini uses "user" and "model" roles; system goes into systemInstruction
                if (role == "system") continue
                contents.put(JSONObject().apply {
                    put("role", role)
                    put("parts", JSONArray().put(JSONObject().apply {
                        put("text", msg.content)
                    }))
                })
            }

            val requestBody = JSONObject().apply {
                put("contents", contents)

                // System instruction (if any)
                val systemMsg = messages.firstOrNull { it.role == "system" }
                if (systemMsg != null) {
                    put("systemInstruction", JSONObject().apply {
                        put("parts", JSONArray().put(JSONObject().apply {
                            put("text", systemMsg.content)
                        }))
                    })
                }

                // Google Search tool
                if (withSearch) {
                    put("tools", JSONArray().put(JSONObject().apply {
                        put("google_search", JSONObject())
                    }))
                }
            }

            FileLogger.log(this@OverlayService, "Gemini", "Calling $model (search=$withSearch)")

            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
                .post(requestBody.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                .build()

            val client = OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                FileLogger.log(this@OverlayService, "Gemini", "Error ${response.code}: ${body.take(200)}")
                throw Exception("Gemini API error ${response.code}")
            }

            val json = JSONObject(body)
            val parts = json.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")

            // Concatenate all text parts (Gemini may return multiple)
            val result = StringBuilder()
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                if (part.has("text")) {
                    result.append(part.getString("text"))
                }
            }

            val text = stripThinkingBlock(result.toString())
            FileLogger.log(this@OverlayService, "Gemini", "Response: ${text.take(100)}")
            text
        }
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var dimView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var audioRecorder: AudioRecorder? = null
    private var overlayButton: ImageButton? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private val CHANNEL_ID = "VoiceListenerSilentChannel"
    
    // Gesture recognition
    private val gestureManager by lazy { GestureManager(this) }
    private var savedVolumeBeforeMute: Int = -1

    // TTS
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var audioTrack: AudioTrack? = null
    private var isGeminiSpeaking = false

    // Voice Chat Widget
    private var isVoiceChatRecording = false

    // Visibility Logic
    private var isRecording = false
    private var isProcessing = false
    private var recordingStartTime: Long = 0L
    private var lastShowTime: Long = 0L
    private val HIDE_DELAY_MS = 5000L
    private var hideRunnable: Runnable? = null

    private val focusReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action == "com.example.voicelistener.ACTION_FOCUS_CHANGED") {
                val hasFocus = intent.getBooleanExtra("has_focus", false)
                val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
                val focusModeEnabled = prefs.getBoolean("overlay_focus_mode", false)
                if (!focusModeEnabled) {
                    // Always On mode: Ensure visible if it was hidden for some reason
                    if (overlayView?.visibility != View.VISIBLE) {
                        overlayView?.visibility = View.VISIBLE
                        startForegroundNotification(false)
                    }
                    return
                }

                if (hasFocus) {
                    val alwaysHidden = prefs.getBoolean("overlay_always_hidden", false)
                    if (alwaysHidden) {
                         overlayView?.visibility = View.GONE
                         dimView?.visibility = View.GONE
                         return@onReceive
                    }

                    // Show overlay
                    if (overlayView?.visibility != View.VISIBLE) {
                        overlayView?.visibility = View.VISIBLE
                        startForegroundNotification(false)
                    }
                    // Reset timer on focus gain
                    lastShowTime = System.currentTimeMillis()
                    hideRunnable?.let { overlayView?.removeCallbacks(it) }
                    
                } else {
                    // Lost focus -> Check if we should hide
                    checkHideOverlay()
                }
            }
        }
    }

    private val clipboardUpdateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.voicelistener.ACTION_CLIPBOARD_HISTORY_UPDATED") {
                if (clipboardView != null && clipboardView?.isAttachedToWindow == true) {
                     serviceScope.launch(Dispatchers.Main) {
                         refreshClipboardList()
                     }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        FileLogger.log(this, "OverlayService", "Service destroying...")
        tts?.stop()
        tts?.shutdown()
        tts = null
        stopGeminiAudio()
        try {
            unregisterReceiver(focusReceiver)
            unregisterReceiver(clipboardUpdateReceiver)
        } catch(e: Exception) {}
        
        closeMarketDataWidget()
        
        if (dimView != null) try { windowManager.removeView(dimView) } catch(e: Exception) {}
        if (overlayView != null) try { windowManager.removeView(overlayView) } catch(e: Exception) {}
        if (menuView != null) try { windowManager.removeView(menuView) } catch(e: Exception) {}
        if (clipboardView != null) try { windowManager.removeView(clipboardView) } catch(e: Exception) {}
    }

    override fun onCreate() {
        super.onCreate()
        Log.e("OverlayDEBUG", ">>> ON CREATE STARTED <<<")
        FileLogger.log(this, "OverlayService", "Service creating...")
        
        // 0. Crash Handler for this thread
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
             FileLogger.log(this, "CRASH", "Uncaught Exception: ${throwable.stackTraceToString()}")
        }

        try {
            // Must be called ASAP in onCreate for Foreground Services
            startForegroundNotification(false) // <--- MISSING CALL ADDED HERE

            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            audioRecorder = AudioRecorder(this)
            addOverlayView()
            
            // Register focus receiver
            val focusFilter = android.content.IntentFilter("com.example.voicelistener.ACTION_FOCUS_CHANGED")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(focusReceiver, focusFilter, android.content.Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(focusReceiver, focusFilter)
            }
            
            FileLogger.log(this, "OverlayService", "Service created successfully")

            // DIAGNOSTIC START
            val accessService = com.example.voicelistener.services.VoiceAccessibilityService.instance
            if (accessService == null) {
                FileLogger.log(this, "OverlayService", "WARNING: VoiceAccessibilityService is NULL! Clipboard will NOT work.")
            } else {
                 FileLogger.log(this, "OverlayService", "VoiceAccessibilityService is connected.")
            }
            // DIAGNOSTIC END
            
            // Register Clipboard Receiver
            val clipFilter = android.content.IntentFilter("com.example.voicelistener.ACTION_CLIPBOARD_HISTORY_UPDATED")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(clipboardUpdateReceiver, clipFilter, android.content.Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(clipboardUpdateReceiver, clipFilter)
            }

        } catch (e: Exception) {
            FileLogger.log(this, "OverlayService", "Error in onCreate: ${e.message}")
        }
    }

    private val CHANNEL_ID_SILENT = "VoiceListenerSilent"
    private val CHANNEL_ID_ACTIVE = "VoiceListenerActive"

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            
            // Channel 1: Silent (Min Priority) - For when Overlay is visible
            val channelSilent = NotificationChannel(
                CHANNEL_ID_SILENT,
                "Voice Listener (Overlay sichtbar)",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Minimierte Benachrichtigung, wenn Overlay aktiv ist"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channelSilent)
            
            // Channel 2: Active (Low/Default Priority) - For when Overlay is hidden
            val channelActive = NotificationChannel(
                CHANNEL_ID_ACTIVE,
                "Voice Listener (Overlay versteckt)",
                NotificationManager.IMPORTANCE_LOW // Higher than MIN, ensures it's visible
            ).apply {
                description = "Sichtbare Benachrichtigung zum Wiederherstellen des Overlays"
                setShowBadge(true)
            }
            manager.createNotificationChannel(channelActive)

        }
    }

    private fun startForegroundNotification(isHidden: Boolean = false) {
        createNotificationChannel()
        
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val recordingTrigger = prefs.getInt("overlay_recording_trigger", 0)

        val channelId = if (isHidden) CHANNEL_ID_ACTIVE else CHANNEL_ID_SILENT
        val priority = if (isHidden) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_MIN

        val title = if (isHidden) "Voice Listener (Versteckt)" else "Voice Listener Aktiv"
        val text = when {
            isHidden -> "Tippen zum Anzeigen des Buttons"
            recordingTrigger == 0 -> "2x=Aufnahme | Lang=Menü | 3x=Verstecken"
            else -> "Lang=Aufnahme | 2x=Menü | 3x=Verstecken"
        }
        
        // Show ActionIntent (via Trampoline Activity to bypass background limits)
        val showIntent = Intent(this, com.example.voicelistener.OverlayTrampolineActivity::class.java)
        // Note: Action is handled in onCreate of Activity, no need to set it here if we just start the class?
        // Actually, let's keep it clean. The Activity starts the service with the Action.
        
        // Use getActivity instead of getService/getBroadcast
        val showPendingIntent = PendingIntent.getActivity(this, 1, showIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        // Settings Action
        val settingsIntent = Intent(this, com.example.voicelistener.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val settingsPendingIntent = PendingIntent.getActivity(this, 2, settingsIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(showPendingIntent) // Click on body -> Show Overlay
            .addAction(R.drawable.ic_mic, "Einstellungen", settingsPendingIntent)
            .setOngoing(true)
            .setPriority(priority)
            .setSilent(true)

        val notification = builder.build()
            
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } catch (e: Exception) {
                // Fallback if type not allowed
                startForeground(1, notification)
            }
        } else {
            startForeground(1, notification)
        }
    }

    private var messageView: android.widget.TextView? = null
    private var undoToastView: View? = null

    private fun makeBackDismissible(view: View, params: WindowManager.LayoutParams, onDismiss: () -> Unit) {
        params.flags = (params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()) or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        view.isFocusableInTouchMode = true
        view.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
                onDismiss()
                true
            } else false
        }
        view.post { view.requestFocus() }
    }

    private fun showTopMessage(text: String) {
        serviceScope.launch(Dispatchers.Main) {
            // Remove existing message if present
            if (messageView != null) {
                try { windowManager.removeView(messageView) } catch (e: Exception) {}
                messageView = null
            }

            try {
                val msgParams = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
                )
                msgParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                msgParams.y = 150 // Top margin (below status bar/notch)

                val tv = android.widget.TextView(this@OverlayService)
                tv.text = text
                tv.setTextColor(Color.WHITE)
                tv.textSize = 14f
                tv.setPadding(32, 16, 32, 16)
                
                // Custom Background
                val shape = android.graphics.drawable.GradientDrawable()
                shape.shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                shape.cornerRadius = 20f
                shape.setColor(Color.parseColor("#EE333333")) // Dark grey, high opacity
                tv.background = shape
                
                windowManager.addView(tv, msgParams)
                messageView = tv

                // Auto hide after 2.5s
                tv.postDelayed({
                    try {
                        if (messageView == tv) {
                            windowManager.removeView(tv)
                            messageView = null
                        }
                    } catch (e: Exception) {}
                }, 2500)
                
            } catch (e: Exception) {
                FileLogger.log(this@OverlayService, "OverlayService", "Failed to show top message: ${e.message}")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        FileLogger.log(this, "OverlayService", "onStartCommand called! Action=$action")
        
        // Handle Voice Chat Widget toggle
        if (action == "ACTION_VOICE_CHAT_TOGGLE") {
            if (tts?.isSpeaking == true || isGeminiSpeaking) {
                // Cancel TTS playback
                tts?.stop()
                stopGeminiAudio()
                VoiceChatWidget.updateState(this, "idle")
                FileLogger.log(this, "VoiceChat", "TTS cancelled by user")
            } else if (isRecording) {
                Toast.makeText(this, "Aufnahme laeuft bereits", Toast.LENGTH_SHORT).show()
            } else if (isVoiceChatRecording) {
                stopVoiceChatRecording()
            } else {
                startVoiceChatRecording()
            }
            return START_STICKY
        }

        // Handle "Show Overlay" request
        if (action == "ACTION_SHOW_OVERLAY" || action == null || action == "ACTION_UPDATE_SETTINGS") {
             if (action == "ACTION_UPDATE_SETTINGS") FileLogger.log(this, "OverlayService", "Settings updated.")
             
             FileLogger.log(this, "OverlayService", "Triggering Overlay Restore...")

             // Reset "always hidden" when restoring via notification or start
             if (action == "ACTION_SHOW_OVERLAY") {
                 getSharedPreferences("app_prefs", MODE_PRIVATE)
                     .edit().putBoolean("overlay_always_hidden", false).apply()
             }

             if (overlayView != null) {
                 // Update Size from Prefs (in case user changed slider)
                 val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
                 val scale = prefs.getFloat("overlay_scale", 1.0f)
                 val baseSize = 96 // dp
                 val sizeInPx = (baseSize * resources.displayMetrics.density * scale).toInt()
                 params?.width = sizeInPx
                 params?.height = sizeInPx

                 // Clamp position after size change
                 val dm = resources.displayMetrics
                 params?.x = (params?.x ?: 0).coerceIn(0, (dm.widthPixels - sizeInPx).coerceAtLeast(0))
                 params?.y = (params?.y ?: 0).coerceIn(0, (dm.heightPixels - sizeInPx).coerceAtLeast(0))

                 // Fast Path: Just make visible again
                 overlayView?.visibility = View.VISIBLE
                 // Force layout update to be sure AND apply new size
                 try {
                     windowManager.updateViewLayout(overlayView, params)
                 } catch (e: Exception) {}
                 
                 // Apply Transparency
                 val alpha = prefs.getFloat("overlay_alpha", 1.0f)
                 overlayView?.alpha = alpha
                 
                 // Apply Color
                 normalBackground()
                 
                 showTopMessage("Overlay bereit (${(scale*100).toInt()}%)")
             } else {
                 // Slow Path: Recreate if really gone
                 try {
                    addOverlayView()
                    showTopMessage("Overlay gestartet")
                 } catch(e: Exception) {
                     FileLogger.log(this, "OverlayService", "FATAL: Restore failed: ${e.message}")
                 }
             }
             
             // Reset Notification
             overlayButton?.postDelayed({
                startForegroundNotification(false) 
             }, 100)
        }
        return START_STICKY
    }
    
    private fun normalBackground() {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val color = prefs.getInt("overlay_color", Color.parseColor("#FF6200EE"))
        
        val drawable = ContextCompat.getDrawable(this, R.drawable.button_bg_normal)?.mutate() as? android.graphics.drawable.GradientDrawable
        drawable?.setColor(color)
        overlayButton?.background = drawable
        overlayButton?.setImageResource(R.drawable.ic_mic)
    }

    // ... [addOverlayView and setupTouchListener omitted for brevity, ensure Toast calls inside setupTouchListener are replaced] ...
    
    // Helper to replace Toasts in setupTouchListener (this needs to be manually applied to the inner class calls via search/replace in next steps if not covered here)
    
    private fun addOverlayView() {
        FileLogger.log(this, "OverlayService", "Adding overlay view...")
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        params?.gravity = Gravity.TOP or Gravity.START

        // Dynamic Sizing
        val scale = prefs.getFloat("overlay_scale", 1.0f)
        val baseSize = 96 // dp
        val sizeInPx = (baseSize * resources.displayMetrics.density * scale).toInt()

        params?.width = sizeInPx
        params?.height = sizeInPx

        // Clamp saved position to screen bounds so button is always fully visible
        val dm = resources.displayMetrics
        params?.x = prefs.getInt("overlay_x", 0).coerceIn(0, (dm.widthPixels - sizeInPx).coerceAtLeast(0))
        params?.y = prefs.getInt("overlay_y", 100).coerceIn(0, (dm.heightPixels - sizeInPx).coerceAtLeast(0))

        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.overlay_layout, null)
        overlayButton = overlayView?.findViewById(R.id.overlay_button)
        // Scale padding proportionally so icon stays centered in smaller buttons
        val basePaddingPx = (24 * resources.displayMetrics.density * scale).toInt()
        overlayButton?.setPadding(basePaddingPx, basePaddingPx, basePaddingPx, basePaddingPx)
        normalBackground()
        
        setupTouchListener()

        // Apply Transparency
        val alpha = prefs.getFloat("overlay_alpha", 1.0f)
        overlayView?.alpha = alpha

        // Fullscreen dim background for better button visibility
        val dimLevel = prefs.getFloat("overlay_dim", 0.0f)
        if (dimLevel > 0f) {
            dimView = View(this).apply {
                setBackgroundColor(Color.argb((dimLevel * 255).toInt(), 0, 0, 0))
                visibility = View.GONE
            }
            val dimParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            windowManager.addView(dimView, dimParams)
        }

        windowManager.addView(overlayView, params)
        FileLogger.log(this, "OverlayService", "Overlay view added")
    }

    private fun setupTouchListener() {
        overlayButton?.setOnTouchListener(object : View.OnTouchListener {
            private var lastAction: Int = 0
            private var initialX: Int = 0
            private var initialY: Int = 0
            private var initialTouchX: Float = 0f
            private var initialTouchY: Float = 0f
            
            // Swipe Gesture (detected on ACTION_UP: fast + directional)
            private val SWIPE_MIN_DISTANCE = 50 // min pixels (reduced for edge cases)
            private val SWIPE_MAX_DURATION = 210L // max ms for a swipe
            private val SWIPE_MIN_VELOCITY = 0.3f // min pixels per ms
            private var savedVolumeBeforeMute: Int = -1 // -1 = not muted
            

            private var isMoving = false
            private val CLICK_THRESHOLD = 30
            private val touchPoints = mutableListOf<FloatArray>() // [x, y, timestamp] for gesture recognition
            
            private var lastTouchDownTime: Long = 0L
            private var clickCount = 0
            private var clickHandler = android.os.Handler(android.os.Looper.getMainLooper())
            private var clickRunnable: Runnable? = null
            private var lastClickTime: Long = 0L // Keep for double tap logic if needed elsewhere
            private var longPressRunnable: Runnable? = null
            private var menuOpenOnDown: Boolean = false
            private var recordingStopClickCount = 0
            private var recordingStopRunnable: Runnable? = null
            
            // Mode Preference: 0 = Hold, 1 = Toggle
            private var interactionMode = 0

            init {
                val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
                interactionMode = prefs.getInt("overlay_interaction_mode", 0)
            }

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
                interactionMode = prefs.getInt("overlay_interaction_mode", 0) // Always re-read to be fresh

                try {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            lastTouchDownTime = System.currentTimeMillis()
                            initialX = params!!.x
                            initialY = params!!.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            lastAction = MotionEvent.ACTION_DOWN
                            isMoving = false
                            touchPoints.clear()
                            touchPoints.add(floatArrayOf(event.rawX, event.rawY, event.eventTime.toFloat()))
                            menuOpenOnDown = menuView != null
                            
                            // Setup Long Press
                            longPressRunnable = Runnable {
                                if (!isMoving) {
                                    val recordingTrigger = prefs.getInt("overlay_recording_trigger", 0) // 0=Double, 1=Long
                                    if (recordingTrigger == 1) {
                                        // Trigger 1 (Long Press): Start Recording
                                        if (!isRecording) {
                                            startRecording()
                                            isRecording = true
                                        }
                                    } else {
                                        // Trigger 0 (Double Tap): Long Press = Open/Close Menu
                                        FileLogger.log(this@OverlayService, "Touch", "Long Press Triggered -> Toggle Menu")
                                        if (menuView != null) closeMenu() else showMenu()
                                    }
                                }
                            }
                            v.handler?.postDelayed(longPressRunnable!!, 500) // 500ms threshold
                            return true
                        }
                        
                        MotionEvent.ACTION_MOVE -> {
                            val dx = (event.rawX - initialTouchX).toInt()
                            val dy = (event.rawY - initialTouchY).toInt()

                            touchPoints.add(floatArrayOf(event.rawX, event.rawY, event.eventTime.toFloat()))

                            if (kotlin.math.abs(dx) > CLICK_THRESHOLD || kotlin.math.abs(dy) > CLICK_THRESHOLD) {
                                isMoving = true
                                longPressRunnable?.let { v.handler?.removeCallbacks(it) }

                                // Always move button (drag) - clamp to screen bounds
                                val dm = resources.displayMetrics
                                val btnW = params!!.width
                                val btnH = params!!.height
                                params!!.x = (initialX + dx).coerceIn(0, dm.widthPixels - btnW)
                                params!!.y = (initialY + dy).coerceIn(0, dm.heightPixels - btnH)
                                windowManager.updateViewLayout(overlayView, params)

                                lastAction = MotionEvent.ACTION_MOVE
                            }
                            return true
                        }
                        
                        MotionEvent.ACTION_UP -> {
                            longPressRunnable?.let { v.handler?.removeCallbacks(it) }
                            val duration = System.currentTimeMillis() - lastTouchDownTime

                            if (isMoving) {
                                val totalDx = (event.rawX - initialTouchX).toInt()
                                val totalDy = (event.rawY - initialTouchY).toInt()
                                val absDx = kotlin.math.abs(totalDx)
                                val absDy = kotlin.math.abs(totalDy)

                                // Check if this was a quick swipe (not a drag)
                                // Use velocity (px/ms) so even short swipes at screen edge are detected
                                val velocityX = if (duration > 0) absDx.toFloat() / duration else 0f
                                val velocityY = if (duration > 0) absDy.toFloat() / duration else 0f
                                val isVerticalSwipe = duration < SWIPE_MAX_DURATION && absDy > SWIPE_MIN_DISTANCE && absDy > absDx * 2 && velocityY > SWIPE_MIN_VELOCITY
                                val isHorizontalSwipe = duration < SWIPE_MAX_DURATION && absDx > SWIPE_MIN_DISTANCE && absDx > absDy * 2 && velocityX > SWIPE_MIN_VELOCITY

                                if (isVerticalSwipe || isHorizontalSwipe) {
                                    // It's a swipe! Snap button back to original position
                                    params!!.x = initialX
                                    params!!.y = initialY
                                    windowManager.updateViewLayout(overlayView, params)

                                    if (isVerticalSwipe) {
                                        if (totalDy < 0) handleSwipeUp() else handleSwipeDown()
                                    } else {
                                        if (totalDx > 0) handleSwipeRight() else handleSwipeLeft()
                                    }
                                } else {
                                    // Not a swipe — try custom gesture recognition
                                    val recognized = tryRecognizeGesture()
                                    if (recognized) {
                                        // Gesture matched — snap back to original position
                                        params!!.x = initialX
                                        params!!.y = initialY
                                        windowManager.updateViewLayout(overlayView, params)
                                    } else {
                                        // Normal drag - save new position
                                        prefs.edit().putInt("overlay_x", params!!.x).putInt("overlay_y", params!!.y).apply()
                                    }
                                }
                            } else {
                                if (isRecording) {
                                    // Recording active: single click = stop & process, quick double click = cancel
                                    recordingStopClickCount++
                                    recordingStopRunnable?.let { clickHandler.removeCallbacks(it) }
                                    recordingStopRunnable = Runnable {
                                        val count = recordingStopClickCount
                                        recordingStopClickCount = 0
                                        if (count >= 2) {
                                            // Double click = cancel recording, don't send
                                            FileLogger.log(this@OverlayService, "Recording", "Cancelled by double click")
                                            audioRecorder?.cancelRecording()
                                            isRecording = false
                                            pendingTranslateLang = null
                                            resetUI()
                                            showTopMessage("Aufnahme abgebrochen")
                                            checkHideOverlay()
                                        } else {
                                            // Single click = stop and process normally
                                            stopRecordingAndProcess(duration)
                                            isRecording = false
                                            resetUI()
                                        }
                                    }
                                    clickHandler.postDelayed(recordingStopRunnable!!, 300)
                                } else if (duration < 500) {
                                    // CLICK / TAP Handling (Use Click Counter for Triple Click Support)
                                    clickCount++
                                    
                                    // Remove pending click actions
                                    clickRunnable?.let { clickHandler.removeCallbacks(it) }
                                    
                                    val wasMenuOpen = menuOpenOnDown || menuView != null
                                    clickRunnable = Runnable {
                                        val currentCount = clickCount
                                        clickCount = 0 // Reset

                                        handleMultiClick(currentCount, prefs, wasMenuOpen)
                                    }
                                    
                                    // 300ms window for multi-clicks
                                    clickHandler.postDelayed(clickRunnable!!, 300)
                                }
                            }
                            return true
                        }
                    }
                } catch (e: Exception) {
                     FileLogger.log(applicationContext, "TouchError", "Error in onTouch: ${e.message}")
                }
                return false
            }

            private fun handleMultiClick(count: Int, prefs: android.content.SharedPreferences, wasMenuOpen: Boolean) {
                if (isRecording && count > 0) return // Should not happen but safety first

                val recordingTrigger = prefs.getInt("overlay_recording_trigger", 0) // 0=Double, 1=Long

                when (count) {
                    1 -> {
                        // SINGLE CLICK - If menu was open or just closed, just close it (no clipboard action)
                        val menuRecentlyClosed = (System.currentTimeMillis() - menuCloseTime) < 600
                        if (wasMenuOpen || menuView != null || menuRecentlyClosed) {
                            closeMenu()
                            return
                        }

                        val isClipboardAppEnabled = prefs.getBoolean("app_clipboard_enabled", true)
                        val isClipboardHistoryEnabled = prefs.getBoolean("clipboard_history_enabled", true)
                        if (!isClipboardAppEnabled && !isClipboardHistoryEnabled) {
                            FileLogger.log(this@OverlayService, "Overlay", "Single Tap ignored (Clipboard disabled)")
                            return
                        }

                        FileLogger.log(this@OverlayService, "Overlay", "Single Tap Detected -> Launching Capture Activity")
                        val intent = Intent(this@OverlayService, com.example.voicelistener.FocusedCaptureActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        }
                        try {
                            startActivity(intent)
                        } catch (e: Exception) {
                            FileLogger.log(this@OverlayService, "Overlay", "Launch Error: ${e.message}")
                            com.example.voicelistener.services.VoiceAccessibilityService.instance?.checkClipboard()
                        }
                    }
                    2 -> {
                        // DOUBLE TAP
                        if (recordingTrigger == 0) {
                            // Trigger 0 (Double Tap): Start Recording
                            startRecording()
                            isRecording = true
                        } else {
                            // Trigger 1 (Long Press): Double Tap = Open/Close Menu
                            FileLogger.log(this@OverlayService, "Touch", "Double Tap Triggered -> Toggle Menu")
                            if (menuView != null) closeMenu() else showMenu()
                        }
                    }
                    3 -> {
                        // TRIPLE TAP - Always hide button
                        FileLogger.log(this@OverlayService, "Touch", "Triple Tap Detected -> hideOverlayButton()")
                        hideOverlayButton()
                    }
                }
            }

            private fun handleSwipeUp() {
                val action = getSharedPreferences("app_prefs", MODE_PRIVATE)
                    .getString("swipe_up_action", "show_volume") ?: "show_volume"
                executeAction(action)
            }

            private fun handleSwipeDown() {
                val action = getSharedPreferences("app_prefs", MODE_PRIVATE)
                    .getString("swipe_down_action", "toggle_mute") ?: "toggle_mute"
                executeAction(action)
            }

            private fun handleSwipeRight() {
                val action = getSharedPreferences("app_prefs", MODE_PRIVATE)
                    .getString("swipe_right_action", "media_play_pause") ?: "media_play_pause"
                executeAction(action)
            }

            private fun handleSwipeLeft() {
                val action = getSharedPreferences("app_prefs", MODE_PRIVATE)
                    .getString("swipe_left_action", "show_notifications") ?: "show_notifications"
                executeAction(action)
            }

            private fun tryRecognizeGesture(): Boolean {
                if (touchPoints.size < 10) return false // Too few points for a meaningful gesture

                // Filter: gesture must be drawn quickly (< 800ms total)
                val startTime = touchPoints.first()[2].toLong()
                val endTime = touchPoints.last()[2].toLong()
                val gestureDuration = endTime - startTime
                if (gestureDuration > 1200) {
                    FileLogger.log(this@OverlayService, "Gesture", "Rejected: too slow (${gestureDuration}ms)")
                    return false
                }

                // Filter: must have at least one direction change (not just a straight line)
                // Calculate total path length and direct distance
                var pathLength = 0f
                for (i in 1 until touchPoints.size) {
                    val dx = touchPoints[i][0] - touchPoints[i - 1][0]
                    val dy = touchPoints[i][1] - touchPoints[i - 1][1]
                    pathLength += kotlin.math.sqrt(dx * dx + dy * dy)
                }
                val directDx = touchPoints.last()[0] - touchPoints.first()[0]
                val directDy = touchPoints.last()[1] - touchPoints.first()[1]
                val directDist = kotlin.math.sqrt(directDx * directDx + directDy * directDy)

                // Sinuosity ratio: path length / direct distance
                // A straight line has ratio ~1.0, complex gestures have higher ratios
                val sinuosity = if (directDist > 1f) pathLength / directDist else pathLength / 1f
                if (sinuosity < 1.3f) {
                    FileLogger.log(this@OverlayService, "Gesture", "Rejected: too straight (sinuosity=$sinuosity)")
                    return false
                }

                // Build a Gesture from collected touch points
                val gesturePoints = ArrayList<android.gesture.GesturePoint>(touchPoints.size)
                for (pt in touchPoints) {
                    gesturePoints.add(android.gesture.GesturePoint(pt[0], pt[1], pt[2].toLong()))
                }
                val stroke = android.gesture.GestureStroke(gesturePoints)
                val gesture = android.gesture.Gesture()
                gesture.addStroke(stroke)

                FileLogger.log(this@OverlayService, "Gesture", "Trying recognition: ${touchPoints.size} pts, ${gestureDuration}ms, sinuosity=$sinuosity")
                val result = gestureManager.recognize(gesture)
                if (result != null) {
                    val (name, score) = result
                    val actionId = gestureManager.getActionForGesture(name)
                    if (actionId != null) {
                        FileLogger.log(this@OverlayService, "Gesture", "Recognized: $name (score=$score) -> $actionId")
                        executeAction(actionId)
                        return true
                    }
                }
                FileLogger.log(this@OverlayService, "Gesture", "No match (result=$result)")
                return false
            }
        })
    }
    
    // --- MENU LOGIC ---
    private var menuParams: WindowManager.LayoutParams? = null
    private var menuView: View? = null
    private var isAskLlamaActive = false
    private var isSettingsAIActive = false
    private var pendingTranslateLang: String? = null  // e.g. "Spanisch", "Englisch", "Deutsch"

    private var menuCloseTime: Long = 0L
    
    // --- GESTURE ACTION DISPATCH ---
    private fun executeAction(actionId: String) {
        when (actionId) {
            "start_recording" -> { startRecording(); isRecording = true }
            "clipboard_capture" -> {
                val intent = Intent(this, com.example.voicelistener.FocusedCaptureActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                try { startActivity(intent) } catch (e: Exception) {
                    FileLogger.log(this, "Action", "Launch Capture Error: ${e.message}")
                }
            }
            "toggle_menu" -> { if (menuView != null) closeMenu() else showMenu() }
            "hide_button" -> hideOverlayButton()
            "show_volume" -> actionShowVolume()
            "toggle_mute" -> actionToggleMute()
            "media_play_pause" -> actionMediaPlayPause()
            "show_notifications" -> actionShowNotifications()
            "show_translator" -> showTranslator()
            "show_clipboard_history" -> showClipboardHistory()
            "toggle_market_data" -> toggleMarketDataWidget()
            "toggle_ask_llama" -> { isAskLlamaActive = !isAskLlamaActive; showTopMessage(if (isAskLlamaActive) "AskLlama AN" else "AskLlama AUS") }
            "open_settings" -> {
                val settingsIntent = Intent(this, com.example.voicelistener.MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                startActivity(settingsIntent)
            }
            else -> {
                if (com.example.voicelistener.TaskerHelper.isTaskerAction(actionId)) {
                    val task = com.example.voicelistener.TaskerHelper.getTaskForAction(this, actionId)
                    if (task != null) {
                        com.example.voicelistener.TaskerHelper.executeTask(this, task)
                        showTopMessage("Tasker: ${task.name}")
                    } else {
                        FileLogger.log(this, "Action", "Tasker task not found: $actionId")
                    }
                } else {
                    FileLogger.log(this, "Action", "Unknown action: $actionId")
                }
            }
        }
    }

    private fun actionShowVolume() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val currentVol = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        val maxVol = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)

        if (currentVol < maxVol) {
            audioManager.adjustStreamVolume(
                android.media.AudioManager.STREAM_MUSIC,
                android.media.AudioManager.ADJUST_RAISE,
                android.media.AudioManager.FLAG_SHOW_UI
            )
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, currentVol, 0)
            }, 50)
        } else {
            audioManager.adjustStreamVolume(
                android.media.AudioManager.STREAM_MUSIC,
                android.media.AudioManager.ADJUST_LOWER,
                android.media.AudioManager.FLAG_SHOW_UI
            )
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, currentVol, 0)
            }, 50)
        }
    }

    private fun actionToggleMute() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val currentVol = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)

        if (savedVolumeBeforeMute == -1) {
            savedVolumeBeforeMute = if (currentVol > 0) currentVol else 1
            audioManager.setStreamVolume(
                android.media.AudioManager.STREAM_MUSIC, 0,
                android.media.AudioManager.FLAG_SHOW_UI
            )
            showTopMessage("Stummgeschaltet")
        } else {
            audioManager.setStreamVolume(
                android.media.AudioManager.STREAM_MUSIC, savedVolumeBeforeMute,
                android.media.AudioManager.FLAG_SHOW_UI
            )
            val maxVol = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            val pct = (savedVolumeBeforeMute.toFloat() / maxVol * 100).toInt()
            showTopMessage("Lautstärke: $pct%")
            savedVolumeBeforeMute = -1
        }
    }

    private fun actionMediaPlayPause() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val keyDown = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        val keyUp = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        audioManager.dispatchMediaKeyEvent(keyDown)
        audioManager.dispatchMediaKeyEvent(keyUp)

        if (audioManager.isMusicActive) {
            showTopMessage("Pause")
        } else {
            showTopMessage("Play")
        }
    }

    private fun actionShowNotifications() {
        @Suppress("WrongConstant")
        try {
            val statusBarService = getSystemService("statusbar")
            val statusBarManager = statusBarService.javaClass
            val expandMethod = statusBarManager.getMethod("expandNotificationsPanel")
            expandMethod.invoke(statusBarService)
        } catch (e: Exception) {
            FileLogger.log(this, "Swipe", "Notification bar error: ${e.message}")
            showTopMessage("Benachrichtigungen nicht verfügbar")
        }
    }

    private fun showMenu() {
        FileLogger.log(this, "Menu", "showMenu() called. current menuView presence: ${menuView != null}")
        if (menuView != null) return // Already open

        try {
            val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
            val density = resources.displayMetrics.density
            val screenWidth = resources.displayMetrics.widthPixels
            val screenHeight = resources.displayMetrics.heightPixels

            // Button center in screen coordinates
            val btnX = (params?.x ?: 0) + (overlayButton?.width ?: 0) / 2
            val btnY = (params?.y ?: 0) + (overlayButton?.height ?: 0) / 2

            // Determine which side has more space -> fan opens away from screen edge
            val buttonOnRight = btnX > screenWidth / 2
            val buttonOnBottom = btnY > screenHeight / 2

            // Build menu items from config
            data class RadialItem(val icon: String, val label: String, val color: String, val action: () -> Unit)

            // Read radial menu config (ordered list of enabled item IDs)
            val configJson = prefs.getString("radial_menu_config", null)
            val enabledIds = if (configJson != null) {
                try {
                    val arr = org.json.JSONArray(configJson)
                    (0 until arr.length()).map { arr.getString(it) }
                } catch (_: Exception) { null }
            } else null
            // Fallback: all items in default order (including Tasker tasks)
            val allItems = getAllRadialItems(this@OverlayService)
            val orderedIds = enabledIds ?: allItems.map { it.id }
            val finalOrderedIds = orderedIds

            val actionMap = mutableMapOf<String, () -> RadialItem>(
                "translate_es" to { RadialItem("ES", "Spanisch", "#E91E63") {
                    closeMenu(); pendingTranslateLang = "Spanisch"; isAskLlamaActive = false; isSettingsAIActive = false
                    startRecording(); isRecording = true
                }},
                "translate_en" to { RadialItem("EN", "Englisch", "#2196F3") {
                    closeMenu(); pendingTranslateLang = "Englisch"; isAskLlamaActive = false; isSettingsAIActive = false
                    startRecording(); isRecording = true
                }},
                "translate_de" to { RadialItem("DE", "Deutsch", "#FF9800") {
                    closeMenu(); pendingTranslateLang = "Deutsch"; isAskLlamaActive = false; isSettingsAIActive = false
                    startRecording(); isRecording = true
                }},
                "translator" to { RadialItem("\uD83D\uDD04", "Übersetzen", "#BB86FC") {
                    closeMenu(); showTranslator()
                }},
                "clipboard" to { RadialItem("\uD83D\uDCCB", "Clipboard", "#03DAC5") {
                    closeMenu(); showClipboardHistory()
                }},
                "market" to { RadialItem("\uD83D\uDCC8", "Markt", "#4CAF50") {
                    closeMenu(); toggleMarketDataWidget()
                }},
                "ask_llama" to { RadialItem("\uD83E\uDD99", "Llama", if (isAskLlamaActive) "#4CAF50" else "#607D8B") {
                    isAskLlamaActive = !isAskLlamaActive
                    if (isAskLlamaActive) isSettingsAIActive = false
                    if (isAskLlamaActive) { closeMenu(); startRecording(); isRecording = true } else closeMenu()
                }},
                "settings_ai" to { RadialItem("\uD83E\uDD16", "AI Set.", if (isSettingsAIActive) "#4CAF50" else "#795548") {
                    isSettingsAIActive = !isSettingsAIActive
                    if (isSettingsAIActive) isAskLlamaActive = false
                    if (isSettingsAIActive) { closeMenu(); startRecording(); isRecording = true } else closeMenu()
                }},
                "fix_text" to { RadialItem("\u270F", "Fix Text", "#00BCD4") {
                    closeMenu(); fixCurrentText()
                }},
                "timeout" to { RadialItem("\u23F1", "Timeout", "#FF5722") {
                    closeMenu(); showScreenTimeoutPicker()
                }},
                "settings" to { RadialItem("\u2699", "Settings", "#6200EE") {
                    closeMenu()
                    startActivity(Intent(this, com.example.voicelistener.MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    })
                }}
            )

            // Dynamically add Tasker tasks to actionMap
            val taskerTasks = com.example.voicelistener.TaskerHelper.getTasks(this@OverlayService)
            for (tTask in taskerTasks) {
                val actionId = com.example.voicelistener.TaskerHelper.actionIdForTask(tTask)
                actionMap[actionId] = { RadialItem("\u26A1", "T: ${tTask.name}", "#9C27B0") {
                    closeMenu()
                    com.example.voicelistener.TaskerHelper.executeTask(this@OverlayService, tTask)
                    showTopMessage("Tasker: ${tTask.name}")
                }}
            }

            // Add custom items: delegate to their source action
            val customItems = getCustomRadialItems(this@OverlayService)
            for (cItem in customItems) {
                val sourceActionId = getCustomItemAction(this@OverlayService, cItem.id)
                if (sourceActionId != null && actionMap.containsKey(sourceActionId)) {
                    val srcFactory = actionMap[sourceActionId]!!
                    actionMap[cItem.id] = {
                        val src = srcFactory()
                        RadialItem(cItem.icon, cItem.label, cItem.defaultColor, src.action)
                    }
                }
            }

            // Load groups for sub-menu handling
            val groups = getRadialGroups(this@OverlayService)
            val groupMap = groups.associateBy { it.id }

            // Add group actions to actionMap (before filtering, so groups appear in menu)
            for (group in groups) {
                actionMap[group.id] = { RadialItem(group.icon, group.label, group.color) {
                    // Sub-menu opening handled per-button in rendering loop below
                }}
            }

            val items = finalOrderedIds.mapNotNull { id -> actionMap[id]?.invoke() }
            val itemIds = finalOrderedIds.filter { actionMap.containsKey(it) }
            if (items.isEmpty()) {
                showTopMessage("Keine Menü-Items aktiviert")
                return
            }

            val btnSize = (48 * density).toInt()
            val minGap = (8 * density)  // minimum gap between button edges
            val minSpacing = btnSize + minGap  // minimum center-to-center distance

            // Calculate radius so buttons don't overlap
            val maxArcDeg = if (items.size <= 5) 180.0
                else kotlin.math.min(180.0 + (items.size - 5) * 20.0, 300.0)

            val maxArcRad = Math.toRadians(maxArcDeg)
            val minRadius = if (items.size > 1) {
                (minSpacing * (items.size - 1) / maxArcRad).toFloat()
            } else 80f * density

            val radius = kotlin.math.max(minRadius, 80f * density)
            val actualArc = if (items.size > 1) {
                val neededArc = (minSpacing * (items.size - 1)) / radius
                kotlin.math.min(neededArc.toDouble(), maxArcRad)
            } else 0.0

            // Container size
            val maxR = radius + btnSize / 2 + 10 * density
            val containerSize = (2 * maxR).toInt()
            val containerW = containerSize
            val containerH = containerSize
            val container = android.widget.FrameLayout(this).apply {
                minimumWidth = containerW
                minimumHeight = containerH
            }

            val centerX = containerW / 2f
            val centerY = containerH / 2f

            // Fan direction
            val midAngle = if (buttonOnRight) Math.toRadians(180.0) else Math.toRadians(0.0)
            val startAngle = midAngle - actualArc / 2
            val angleStep = if (items.size > 1) actualArc / (items.size - 1) else 0.0

            // Stack-based nested sub-menu system
            data class MenuLevel(val buttons: MutableList<View>, val groupId: String?)
            val menuStack = mutableListOf<MenuLevel>()
            var lastCloseClickTime = 0L

            fun hideLevel(level: MenuLevel) {
                level.buttons.forEach { v ->
                    v.animate().alpha(0f).setDuration(150).start()
                    v.isClickable = false
                    v.visibility = View.INVISIBLE
                }
            }

            fun showLevel(level: MenuLevel) {
                level.buttons.forEach { v ->
                    v.visibility = View.VISIBLE
                    v.animate().alpha(1f).setDuration(200).start()
                    v.isClickable = true
                }
            }

            fun closeCurrentLevel() {
                if (menuStack.size <= 1) { closeMenu(); return }
                val current = menuStack.removeAt(menuStack.lastIndex)
                current.buttons.forEach { v ->
                    v.animate().scaleX(0f).scaleY(0f).alpha(0f).setDuration(150).withEndAction {
                        container.removeView(v)
                    }.start()
                }
                // Show only the now-top level, keep all others hidden
                if (menuStack.isNotEmpty()) showLevel(menuStack.last())
            }

            // Universal button view creator with appearance override support
            fun createRadialButtonView(
                itemId: String, defaultIcon: String, defaultColor: String, label: String,
                elev: Float = 8f, animated: Boolean = false
            ): View {
                // Check for per-item appearance override
                val override = getItemAppearance(this@OverlayService, itemId)
                // For groups, use group's own iconType/icon/iconUri as defaults
                val group = groupMap[itemId]
                val effectiveIconType = override?.iconType ?: group?.iconType ?: "emoji"
                val effectiveIcon = override?.icon ?: group?.icon ?: defaultIcon
                val effectiveColor = override?.color ?: group?.color ?: defaultColor
                val effectiveIconUri = override?.iconUri ?: group?.iconUri

                val btn: View = if (effectiveIconType == "gallery" && !effectiveIconUri.isNullOrEmpty()) {
                    android.widget.ImageView(this).apply {
                        try { setImageURI(android.net.Uri.parse(effectiveIconUri)) }
                        catch (_: Exception) { setImageResource(android.R.drawable.ic_menu_gallery) }
                        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                        clipToOutline = true
                        outlineProvider = object : android.view.ViewOutlineProvider() {
                            override fun getOutline(view: View, outline: android.graphics.Outline) {
                                outline.setOval(0, 0, view.width, view.height)
                            }
                        }
                        val gd = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                            setColor(Color.parseColor(effectiveColor))
                        }
                        background = gd
                        elevation = elev
                    }
                } else {
                    val isTextIcon = effectiveIconType == "text"
                    android.widget.TextView(this).apply {
                        text = effectiveIcon
                        textSize = if (isTextIcon) 11f else 18f
                        gravity = Gravity.CENTER
                        setTextColor(Color.WHITE)
                        if (isTextIcon) {
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            setPadding((2 * density).toInt(), 0, (2 * density).toInt(), 0)
                            maxLines = 2
                            ellipsize = android.text.TextUtils.TruncateAt.END
                        }
                        val gd = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                            setColor(Color.parseColor(effectiveColor))
                        }
                        background = gd
                        elevation = elev
                    }
                }
                if (animated) { btn.scaleX = 0f; btn.scaleY = 0f; btn.alpha = 0f }
                btn.setOnLongClickListener { showTopMessage(label); true }
                return btn
            }

            fun openSubMenu(groupId: String, groupAngle: Double) {
                val group = groupMap[groupId] ?: return
                val childItems = group.children.mapNotNull { childId ->
                    actionMap[childId]?.invoke()?.let { childId to it }
                }
                if (childItems.isEmpty()) { showTopMessage("Keine Items in Gruppe"); return }

                // Hide ALL previous levels completely
                for (level in menuStack) hideLevel(level)

                // Use same radius as main menu for consistent placement
                val subArc = if (childItems.size > 1) {
                    val maxArcSub = if (childItems.size <= 5) 180.0
                        else kotlin.math.min(180.0 + (childItems.size - 5) * 20.0, 300.0)
                    val maxArcRadSub = Math.toRadians(maxArcSub)
                    val needed = (minSpacing * (childItems.size - 1)) / radius
                    kotlin.math.min(needed.toDouble(), maxArcRadSub)
                } else 0.0
                val subStartAngle = midAngle - subArc / 2
                val subAngleStep = if (childItems.size > 1) subArc / (childItems.size - 1) else 0.0

                val levelButtons = mutableListOf<View>()
                for ((ci, pair) in childItems.withIndex()) {
                    val (childId, childItem) = pair
                    val cAngle = subStartAngle + subAngleStep * ci
                    val cx = (centerX + radius * kotlin.math.cos(cAngle) - btnSize / 2).toInt()
                    val cy = (centerY + radius * kotlin.math.sin(cAngle) - btnSize / 2).toInt()

                    val childBtn = createRadialButtonView(
                        childId, childItem.icon, childItem.color, childItem.label, 10f, true
                    ).also { v ->
                        if (groupMap.containsKey(childId)) {
                            v.setOnClickListener { openSubMenu(childId, cAngle) }
                        } else {
                            v.setOnClickListener { childItem.action() }
                        }
                    }

                    val clp = android.widget.FrameLayout.LayoutParams(btnSize, btnSize).apply {
                        leftMargin = cx; topMargin = cy
                    }
                    container.addView(childBtn, clp)
                    levelButtons.add(childBtn)
                    childBtn.animate().scaleX(1f).scaleY(1f).alpha(1f)
                        .setDuration(250).setStartDelay(ci * 50L).start()
                }
                menuStack.add(MenuLevel(levelButtons, groupId))
            }

            val mainLevelButtons = mutableListOf<View>()

            for ((index, id) in itemIds.withIndex()) {
                val item = actionMap[id]?.invoke() ?: continue
                val angle = startAngle + angleStep * index
                val ix = (centerX + radius * kotlin.math.cos(angle) - btnSize / 2).toInt()
                val iy = (centerY + radius * kotlin.math.sin(angle) - btnSize / 2).toInt()

                val isGroup = groupMap.containsKey(id)

                val btn = createRadialButtonView(id, item.icon, item.color, item.label).also { v ->
                    if (isGroup) {
                        v.setOnClickListener { openSubMenu(id, angle) }
                    } else {
                        v.setOnClickListener { item.action() }
                    }
                }

                val lp = android.widget.FrameLayout.LayoutParams(btnSize, btnSize).apply {
                    leftMargin = ix; topMargin = iy
                }
                container.addView(btn, lp)
                mainLevelButtons.add(btn)
            }

            // Register main level on menu stack
            menuStack.add(MenuLevel(mainLevelButtons, null))

            // Close button in center: single click = back one level, double click = close all
            val closeBtn = android.widget.TextView(this).apply {
                text = "\u2716"
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                val gd = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.parseColor("#44FFFFFF"))
                }
                background = gd
                setOnClickListener {
                    val now = System.currentTimeMillis()
                    if (now - lastCloseClickTime < 300) {
                        // Double click -> close entire menu
                        closeMenu()
                    } else {
                        lastCloseClickTime = now
                        if (menuStack.size > 1) closeCurrentLevel() else closeMenu()
                    }
                }
            }
            val closeLp = android.widget.FrameLayout.LayoutParams(btnSize, btnSize).apply {
                leftMargin = (centerX - btnSize / 2).toInt()
                topMargin = (centerY - btnSize / 2).toInt()
            }
            container.addView(closeBtn, closeLp)

            menuView = container
            menuParams = WindowManager.LayoutParams(
                containerW, containerH,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = kotlin.math.max(0, kotlin.math.min(btnX - containerW / 2, screenWidth - containerW))
                y = kotlin.math.max(0, kotlin.math.min(btnY - containerH / 2, screenHeight - containerH))
            }

            // Close on touch outside
            container.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) { closeMenu(); true } else false
            }

            makeBackDismissible(container, menuParams!!) { closeMenu() }
            dimView?.visibility = View.VISIBLE
            windowManager.addView(menuView, menuParams)
            FileLogger.log(this, "Menu", "Radial menu added successfully")

        } catch (e: Exception) {
            FileLogger.log(this, "Menu", "Error showing menu: ${e.message}")
        }
    }

    private fun fixCurrentText() {
        val a11y = VoiceAccessibilityService.instance
        if (a11y == null) {
            showTopMessage("Accessibility Service nicht aktiv")
            FileLogger.log(this, "FixText", "Aborted: AccessibilityService instance is null")
            return
        }

        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val apiKey = prefs.getString("groq_api_key", "") ?: ""
        if (apiKey.isEmpty()) { showTopMessage("API Key fehlt!"); return }

        // Delay needed: after closeMenu() removes the overlay window,
        // Android needs time to shift focus back to the underlying app's input field.
        serviceScope.launch {
            delay(400) // wait for focus to settle after menu close

            val originalText = withContext(Dispatchers.Main) {
                val text = a11y.getCurrentInputText()
                FileLogger.log(this@OverlayService, "FixText", "Read input text: '${text?.take(80)}' (null=${text == null})")
                text
            }

            if (originalText.isNullOrBlank()) {
                withContext(Dispatchers.Main) { showTopMessage("Kein Text im Eingabefeld") }
                return@launch
            }

            withContext(Dispatchers.Main) { showTopMessage("Text wird korrigiert...") }

            try {
                val vocabulary = prefs.getString("custom_vocabulary", "") ?: ""
                val promptBase = "You are a proofreader. Fix all grammar, spelling, and punctuation errors in the following text. Keep the original language. Output ONLY the corrected text, nothing else. No explanations, no quotes."
                val prompt = if (vocabulary.isNotBlank()) {
                    "$promptBase\n\nCONTEXT / VOCABULARY (use correct spellings for names, brands, technical terms if they appear):\n$vocabulary"
                } else promptBase
                val response = chatWithFallback("Bearer $apiKey", listOf(Message("system", prompt), Message("user", originalText)))
                val fixed = response.choices.firstOrNull()?.message?.content ?: originalText
                FileLogger.log(this@OverlayService, "FixText", "API returned: '${fixed.take(80)}'")

                withContext(Dispatchers.Main) {
                    // Safety check: verify the text in the field hasn't changed since we read it
                    val textNow = a11y.getCurrentInputText()
                    if (textNow == null) {
                        showTopMessage("Eingabefeld nicht mehr verfügbar")
                        return@withContext
                    }
                    if (textNow != originalText) {
                        showTopMessage("Text wurde geändert — Korrektur abgebrochen")
                        FileLogger.log(this@OverlayService, "FixText", "Aborted: field text changed during API call")
                        return@withContext
                    }
                    if (fixed == originalText) {
                        showTopMessage("Text ist bereits korrekt")
                        return@withContext
                    }
                    val success = a11y.replaceCurrentInputText(fixed)
                    FileLogger.log(this@OverlayService, "FixText", "replaceCurrentInputText success=$success")
                    if (success) {
                        showTopMessage("Text korrigiert")
                    } else {
                        showTopMessage("Ersetzen fehlgeschlagen")
                    }
                }
            } catch (e: Exception) {
                FileLogger.log(this@OverlayService, "FixText", "Error: ${e.message}")
                withContext(Dispatchers.Main) {
                    showTopMessage("Fehler: ${e.message?.take(50)}")
                }
            }
        }
    }

    private fun showScreenTimeoutPicker() {
        val options = arrayOf("30 Sek", "2 Min", "5 Min", "10 Min", "30 Min")
        val values = intArrayOf(30000, 120000, 300000, 600000, 1800000)

        val density = resources.displayMetrics.density
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#DD222222"))
            setPadding(32, 24, 32, 24)
        }
        val title = android.widget.TextView(this).apply {
            text = "Screen Timeout"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(0, 0, 0, 16)
        }
        layout.addView(title)

        for (i in options.indices) {
            val btn = android.widget.TextView(this).apply {
                text = options[i]
                setTextColor(Color.WHITE)
                textSize = 15f
                setPadding(24, 16, 24, 16)
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener {
                    try {
                        android.provider.Settings.System.putInt(
                            contentResolver,
                            android.provider.Settings.System.SCREEN_OFF_TIMEOUT,
                            values[i]
                        )
                        showTopMessage("Timeout: ${options[i]}")
                    } catch (e: Exception) {
                        showTopMessage("Fehler: WRITE_SETTINGS nötig")
                    }
                    closeTimeoutPicker()
                }
            }
            layout.addView(btn)
        }

        val pickerParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        layout.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) { closeTimeoutPicker(); true } else false
        }

        timeoutPickerView = layout
        timeoutPickerParams = pickerParams
        makeBackDismissible(layout, pickerParams) { closeTimeoutPicker() }
        windowManager.addView(layout, pickerParams)
    }

    private var timeoutPickerView: View? = null
    private var timeoutPickerParams: WindowManager.LayoutParams? = null

    private fun closeTimeoutPicker() {
        timeoutPickerView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        timeoutPickerView = null
    }
    
    private fun closeMenu() {
        if (menuView != null) {
            try {
                windowManager.removeView(menuView)
            } catch (e: Exception) {}
            menuView = null
            menuCloseTime = System.currentTimeMillis()
            dimView?.visibility = View.GONE
        }
    }

    // --- MARKET DATA WIDGET LOGIC ---
    private var marketDataView: View? = null
    private var marketDataParams: WindowManager.LayoutParams? = null
    private var marketDataJob: Job? = null
    private val httpClient = OkHttpClient()
    private var isMarketDataMinimized = false
    private var isMarketDataFullscreen = false
    private var marketDataFullText: CharSequence = ""
    private var marketDataFullscreenView: View? = null
    private var savedScreenTimeout: Int = -1

    private fun toggleMarketDataWidget() {
        if (marketDataView != null) {
            closeMarketDataWidget()
        } else {
            showMarketDataWidget()
        }
    }

    private fun closeMarketDataWidget() {
        if (isMarketDataFullscreen) closeMarketDataFullscreen()
        marketDataJob?.cancel()
        marketDataJob = null
        if (marketDataView != null) {
            try {
                windowManager.removeView(marketDataView)
            } catch (e: Exception) {
                FileLogger.log(this, "MarketData", "Error removing view: ${e.message}")
            }
            marketDataView = null
        }
        clearMarketNotification()
    }

    private fun showMarketDataWidget() {
        try {
            isMarketDataMinimized = false
            val inflater = LayoutInflater.from(this)
            marketDataView = inflater.inflate(R.layout.widget_market_data, null)

            // Apply saved font size
            val savedWidgetFontSize = getSharedPreferences("app_prefs", MODE_PRIVATE).getFloat("market_widget_font_size", 12f)
            marketDataView?.findViewById<android.widget.TextView>(R.id.tvMarketData)?.textSize = savedWidgetFontSize

            marketDataParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            marketDataParams?.gravity = Gravity.TOP or Gravity.START

            val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
            marketDataParams?.x = prefs.getInt("market_x", 0)
            marketDataParams?.y = prefs.getInt("market_y", 300)

            // Setup Close Button
            marketDataView?.findViewById<View>(R.id.btnMarketDataClose)?.setOnClickListener {
                closeMarketDataWidget()
            }

            // Setup Drag Listener with triple/double click
            setupMarketDataDragListener()

            windowManager.addView(marketDataView, marketDataParams)

            // Start Data Fetch
            val currentPrefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
            val dataSource = currentPrefs.getString("market_data_source", "firebase") ?: "firebase"

            if (dataSource == "firebase") {
                startFirebaseSSE()
            } else {
                startEqsPolling()
            }

        } catch (e: Exception) {
            FileLogger.log(this, "MarketData", "Error showing widget: ${e.message}")
        }
    }

    /** Firebase SSE streaming - receives data push when it changes */
    private fun startFirebaseSSE() {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val fbUrl = (prefs.getString("firebase_market_url",
            "https://marktdaten-2146e-default-rtdb.europe-west1.firebasedatabase.app/aktuelle_kurse") ?: "").trim()
        if (fbUrl.isEmpty()) return

        val sseUrl = if (fbUrl.endsWith(".json")) fbUrl else "$fbUrl.json"

        marketDataJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    FileLogger.log(this@OverlayService, "MarketData", "SSE connecting to $sseUrl")
                    val request = Request.Builder()
                        .url(sseUrl)
                        .header("Accept", "text/event-stream")
                        .build()

                    // Use a separate client with no timeout for streaming
                    val sseClient = httpClient.newBuilder()
                        .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
                        .build()

                    sseClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            FileLogger.log(this@OverlayService, "MarketData", "SSE failed: ${response.code}")
                            delay(5000L)
                            return@use
                        }

                        val source = response.body?.source() ?: return@use
                        while (isActive && !source.exhausted()) {
                            val line = source.readUtf8Line() ?: break

                            // SSE format: "event: put" then "data: {json}"
                            if (line.startsWith("data: ")) {
                                val dataStr = line.removePrefix("data: ").trim()
                                if (dataStr.isEmpty() || dataStr == "null") continue

                                try {
                                    val eventObj = JSONObject(dataStr)
                                    val rawData = eventObj.get("data")

                                    // "put" event: data contains the full or partial value
                                    val pricesMap = parseFirebaseData(rawData)
                                    if (pricesMap.isNotEmpty()) {
                                        updateMarketDisplay(pricesMap)
                                    }
                                } catch (e: Exception) {
                                    FileLogger.log(this@OverlayService, "MarketData", "SSE parse error: ${e.message}")
                                }
                            }
                        }
                    }
                    // Connection closed, reconnect after delay
                    FileLogger.log(this@OverlayService, "MarketData", "SSE connection closed, reconnecting...")
                    delay(3000L)
                } catch (e: java.io.IOException) {
                    FileLogger.log(this@OverlayService, "MarketData", "SSE IO error: ${e.message}")
                    delay(5000L)
                } catch (e: Exception) {
                    FileLogger.log(this@OverlayService, "MarketData", "SSE error: ${e.message}")
                    delay(5000L)
                }
            }
        }
    }

    /** Parse Firebase data (can be JSONArray or JSONObject) into symbol -> (price, change) */
    private fun parseFirebaseData(rawData: Any): Map<String, Pair<Double, Double>> {
        val pricesMap = mutableMapOf<String, Pair<Double, Double>>()
        when (rawData) {
            is org.json.JSONArray -> {
                for (i in 0 until rawData.length()) {
                    val item = rawData.optJSONObject(i) ?: continue
                    val symbol = item.optString("symbol", "")
                    if (symbol.isNotEmpty()) {
                        pricesMap[symbol] = Pair(item.getDouble("price"), item.getDouble("change"))
                    }
                }
            }
            is org.json.JSONObject -> {
                // Could be keyed object: {"US500FU": {"price":x, "change":y, "symbol":"US500FU"}}
                val keysIter = rawData.keys()
                while (keysIter.hasNext()) {
                    val key = keysIter.next()
                    val item = rawData.optJSONObject(key) ?: continue
                    val symbol = item.optString("symbol", key)
                    pricesMap[symbol] = Pair(item.getDouble("price"), item.getDouble("change"))
                }
            }
        }
        return pricesMap
    }

    /** EQS polling - fetches every N seconds */
    private fun startEqsPolling() {
        marketDataJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val currentPrefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
                    val interval = currentPrefs.getInt("market_data_interval", 1) * 1000L
                    val eqsBaseUrl = currentPrefs.getString("eqs_server_url", "") ?: ""
                    val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager

                    if (powerManager.isInteractive && eqsBaseUrl.isNotEmpty()) {
                        val request = Request.Builder().url("${eqsBaseUrl}/live_prices.json").build()
                        httpClient.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                val jsonStr = response.body?.string()
                                if (jsonStr != null) {
                                    val jsonObj = JSONObject(jsonStr)
                                    val pricesObj = jsonObj.getJSONObject("prices")
                                    val pricesMap = mutableMapOf<String, Pair<Double, Double>>()
                                    val keysIter = pricesObj.keys()
                                    while (keysIter.hasNext()) {
                                        val key = keysIter.next()
                                        val item = pricesObj.getJSONObject(key)
                                        pricesMap[key] = Pair(item.getDouble("price"), item.getDouble("change"))
                                    }
                                    updateMarketDisplay(pricesMap)
                                }
                            }
                        }
                    }
                    delay(interval)
                } catch (e: Exception) {
                    FileLogger.log(this@OverlayService, "MarketData", "EQS Fetch Error: ${e.message}")
                    delay(1000L)
                }
            }
        }
    }

    /** Shared display update logic for both data sources */
    private suspend fun updateMarketDisplay(pricesMap: Map<String, Pair<Double, Double>>) {
        val currentPrefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val keysString = currentPrefs.getString("market_data_keys", "US500FU, USTECFU, DE40FU") ?: ""
        val targetKeys = keysString.split(",").map { it.trim() }.filter { it.isNotEmpty() }

        // Save all available keys
        val allKeys = pricesMap.keys.sorted()
        currentPrefs.edit().putString("available_market_keys", allKeys.joinToString(",")).apply()

        val sb = StringBuilder()
        for (key in targetKeys) {
            val data = pricesMap[key]
            if (data != null) {
                val price = data.first
                val change = data.second
                val formattedPrice = String.format(java.util.Locale.US, "%.2f", price)
                val formattedChange = String.format(java.util.Locale.US, "%.2f", change)
                val colorHex = when {
                    change > 0 -> "#4CAF50"
                    change < 0 -> "#F44336"
                    else -> "#FFFFFF"
                }
                val sign = if (change > 0) "+" else ""
                val displayKey = key.take(5)
                sb.append("$displayKey: $formattedPrice (<font color='$colorHex'>$sign$formattedChange%</font>)&nbsp;&nbsp;&nbsp;")
            }
        }
        val fullHtml = sb.toString().trim()
        withContext(Dispatchers.Main) {
            val tv = marketDataView?.findViewById<android.widget.TextView>(R.id.tvMarketData)
            if (tv != null) {
                val spanned = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    android.text.Html.fromHtml(fullHtml, android.text.Html.FROM_HTML_MODE_COMPACT)
                } else {
                    @Suppress("DEPRECATION")
                    android.text.Html.fromHtml(fullHtml)
                }
                marketDataFullText = spanned
                if (isMarketDataFullscreen) {
                    val fsSpanned = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        android.text.Html.fromHtml(fullHtml.replace("&nbsp;&nbsp;&nbsp;", "<br/><br/>"), android.text.Html.FROM_HTML_MODE_COMPACT)
                    } else {
                        @Suppress("DEPRECATION")
                        android.text.Html.fromHtml(fullHtml.replace("&nbsp;&nbsp;&nbsp;", "<br/><br/>"))
                    }
                    updateFullscreenText(fsSpanned)
                }
                if (!isMarketDataMinimized) {
                    tv.text = spanned
                    tv.maxLines = 10
                } else {
                    val minValues = currentPrefs.getInt("market_min_values", 1)
                    val entries = fullHtml.split("&nbsp;&nbsp;&nbsp;").filter { it.isNotBlank() }
                    val limitedHtml = entries.take(minValues).joinToString("  ")
                    tv.text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        android.text.Html.fromHtml(limitedHtml, android.text.Html.FROM_HTML_MODE_COMPACT)
                    } else {
                        @Suppress("DEPRECATION")
                        android.text.Html.fromHtml(limitedHtml)
                    }
                    tv.maxLines = minValues
                }
            }
            if (currentPrefs.getBoolean("market_notification_enabled", false)) {
                updateMarketNotification(fullHtml)
            }
            val widgetHtml = fullHtml.split("&nbsp;&nbsp;&nbsp;").filter { it.isNotBlank() }.joinToString("<br/>")
            currentPrefs.edit().putString("market_widget_cache", widgetHtml).apply()
            com.example.voicelistener.MarketDataWidget.updateAllWidgets(this@OverlayService)
        }
    }

    private fun toggleMarketDataMinimized() {
        val tv = marketDataView?.findViewById<android.widget.TextView>(R.id.tvMarketData) ?: return
        val closeBtn = marketDataView?.findViewById<View>(R.id.btnMarketDataClose)
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels

        isMarketDataMinimized = !isMarketDataMinimized

        if (isMarketDataMinimized) {
            // Minimize: show configured number of values, hide close button, dock to left edge
            val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
            val minValues = prefs.getInt("market_min_values", 1)
            val fullText = marketDataFullText.toString()
            val parts = fullText.split("   ").filter { it.isNotBlank() }
            tv.text = parts.take(minValues).joinToString("  ").trim()
            tv.maxLines = minValues
            closeBtn?.visibility = View.GONE

            marketDataParams?.x = 0
            try { windowManager.updateViewLayout(marketDataView, marketDataParams) } catch (_: Exception) {}
        } else {
            // Maximize: show all values, show close button, use full width
            tv.text = marketDataFullText
            tv.maxLines = 10
            closeBtn?.visibility = View.VISIBLE

            // Restore to full width at x=0
            marketDataParams?.x = 0
            try { windowManager.updateViewLayout(marketDataView, marketDataParams) } catch (_: Exception) {}
        }
    }

    private val MARKET_NOTIFICATION_ID = 42
    private val CHANNEL_ID_MARKET = "VoiceListenerMarket"
    private var lastMarketIconText: String? = null
    private var cachedMarketIcon: androidx.core.graphics.drawable.IconCompat? = null

    private fun createMarketNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID_MARKET) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID_MARKET,
                    "Marktdaten",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Zeigt Marktdaten in der Status-Bar"
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun createTextIcon(text: String): android.graphics.Bitmap {
        val density = resources.displayMetrics.density
        val size = (24 * density).toInt()
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textAlign = android.graphics.Paint.Align.CENTER
            // Auto-size text to fit
            textSize = when {
                text.length <= 3 -> 11f * density
                text.length <= 5 -> 8f * density
                else -> 6.5f * density
            }
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val x = size / 2f
        val y = size / 2f - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(text, x, y, paint)
        return bitmap
    }

    private fun updateMarketNotification(fullHtml: String) {
        val entries = fullHtml.split("&nbsp;&nbsp;&nbsp;").filter { it.isNotBlank() }
        val parsed = entries.map { entry ->
            val plain = entry.replace(Regex("<[^>]*>"), "").replace("&nbsp;", " ").trim()
            plain
        }
        if (parsed.isEmpty()) return

        // Extract short price for icon (e.g. "BTC: 85000.50 (+2.5%)" -> "85000")
        val firstEntry = parsed.first()
        val priceMatch = Regex(":\\s*([\\d.]+)").find(firstEntry)
        val shortPrice = priceMatch?.groupValues?.get(1)?.let {
            // Remove decimals and shorten for icon
            val intPart = it.substringBefore(".")
            if (intPart.length > 5) intPart.substring(0, 3) + "k" else intPart
        } ?: "---"

        // Full text for notification body
        val bodyText = parsed.joinToString("  |  ") { it.replace(Regex("\\s*\\([^)]*\\)"), "").trim() }

        createMarketNotificationChannel()
        // Only regenerate bitmap if price text actually changed
        if (shortPrice != lastMarketIconText) {
            val icon = createTextIcon(shortPrice)
            cachedMarketIcon = androidx.core.graphics.drawable.IconCompat.createWithBitmap(icon)
            lastMarketIconText = shortPrice
        }
        val iconCompat = cachedMarketIcon ?: return

        val openIntent = PendingIntent.getActivity(
            this, 10,
            Intent(this, com.example.voicelistener.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID_MARKET)
            .setSmallIcon(iconCompat)
            .setContentTitle("Marktdaten")
            .setContentText(bodyText)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(MARKET_NOTIFICATION_ID, notification)
    }

    private fun clearMarketNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.cancel(MARKET_NOTIFICATION_ID)
    }

    private fun showMarketDataContextMenu() {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val items = if (isMarketDataFullscreen) {
            arrayOf("Verstecken", "Fullscreen schließen", "Hilfe")
        } else {
            arrayOf("Verstecken", "Fullscreen", "Hilfe")
        }

        val menuLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#DD222222"))
            setPadding(32, 24, 32, 24)
        }

        val menuParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        // Font size controls row
        val fontSizeKey = if (isMarketDataFullscreen) "market_fullscreen_font_size" else "market_widget_font_size"
        val defaultSize = if (isMarketDataFullscreen) 28f else 12f
        var currentSize = prefs.getFloat(fontSizeKey, defaultSize)

        val sizeRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(48, 16, 48, 16)
        }
        val sizeLabel = android.widget.TextView(this).apply {
            text = "Schrift: ${currentSize.toInt()}sp"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 16f
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnSmaller = android.widget.TextView(this).apply {
            text = " A- "
            setTextColor(android.graphics.Color.WHITE)
            textSize = 20f
            setPadding(32, 16, 32, 16)
            setBackgroundColor(android.graphics.Color.parseColor("#44FFFFFF"))
        }
        val btnBigger = android.widget.TextView(this).apply {
            text = " A+ "
            setTextColor(android.graphics.Color.WHITE)
            textSize = 20f
            setPadding(32, 16, 32, 16)
            setBackgroundColor(android.graphics.Color.parseColor("#44FFFFFF"))
        }

        fun applyFontSize(newSize: Float) {
            currentSize = newSize
            prefs.edit().putFloat(fontSizeKey, newSize).apply()
            sizeLabel.text = "Schrift: ${newSize.toInt()}sp"
            if (isMarketDataFullscreen) {
                val fsView = marketDataFullscreenView ?: return
                val tv = (fsView as? android.widget.FrameLayout)?.getChildAt(0) as? android.widget.TextView
                tv?.textSize = newSize
            } else {
                val tv = marketDataView?.findViewById<android.widget.TextView>(R.id.tvMarketData)
                tv?.textSize = newSize
            }
        }

        btnSmaller.setOnClickListener {
            val newSize = (currentSize - 1f).coerceAtLeast(6f)
            applyFontSize(newSize)
        }
        btnBigger.setOnClickListener {
            val newSize = (currentSize + 1f).coerceAtMost(80f)
            applyFontSize(newSize)
        }

        sizeRow.addView(sizeLabel)
        sizeRow.addView(btnSmaller)
        val spacer = android.widget.Space(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams((8 * resources.displayMetrics.density).toInt(), 1)
        }
        sizeRow.addView(spacer)
        sizeRow.addView(btnBigger)
        menuLayout.addView(sizeRow)

        // Divider
        val divider = View(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#44FFFFFF"))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply { topMargin = 8; bottomMargin = 8 }
        }
        menuLayout.addView(divider)

        for (item in items) {
            val btn = android.widget.TextView(this@OverlayService).apply {
                text = item
                setTextColor(android.graphics.Color.WHITE)
                textSize = 18f
                setPadding(48, 32, 48, 32)
                setOnClickListener {
                    try { windowManager.removeView(menuLayout) } catch (_: Exception) {}
                    when (item) {
                        "Verstecken" -> {
                            if (isMarketDataFullscreen) closeMarketDataFullscreen()
                            closeMarketDataWidget()
                        }
                        "Fullscreen" -> showMarketDataFullscreen()
                        "Fullscreen schließen" -> closeMarketDataFullscreen()
                        "Hilfe" -> showMarketDataHelp()
                    }
                }
            }
            menuLayout.addView(btn)
        }

        // Close on outside touch
        menuLayout.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                try { windowManager.removeView(menuLayout) } catch (_: Exception) {}
                true
            } else false
        }

        makeBackDismissible(menuLayout, menuParams) {
            try { windowManager.removeView(menuLayout) } catch (_: Exception) {}
        }
        try { windowManager.addView(menuLayout, menuParams) } catch (e: Exception) {
            FileLogger.log(this, "MarketData", "Error showing context menu: ${e.message}")
        }
    }

    private fun showMarketDataHelp() {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val keysString = prefs.getString("market_data_keys", "US500FU, USTECFU, DE40FU") ?: ""
        val interval = prefs.getInt("market_data_interval", 1)
        val status = if (isMarketDataFullscreen) "Fullscreen" else if (isMarketDataMinimized) "Minimiert" else "Normal"

        val helpText = """
            |Marktdaten-Widget Hilfe
            |
            |Aktuelle Einstellungen:
            |  Werte: $keysString
            |  Intervall: ${interval}s
            |  Status: $status
            |
            |Bedienung:
            |  Verschieben: Ziehen (Drag)
            |  Doppelklick: Minimieren/Maximieren
            |    (Minimiert: nur erster Wert, dockt am Rand)
            |  Triple-Klick: Widget schließen
            |  Lang drücken: Dieses Menü öffnen
            |
            |Menü-Optionen:
            |  Verstecken: Widget schließen
            |  Fullscreen: Vollbild mit großer Schrift,
            |    Bildschirm bleibt an
            |  Fullscreen schließen: Zurück zum
            |    normalen Widget
            |
            |Einstellungen ändern:
            |  Über Extra-Menü → Einstellungen →
            |  'Marktdaten auswählen' Button
        """.trimMargin()

        val helpLayout = android.widget.ScrollView(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#EE222222"))
            setPadding(48, 32, 48, 32)
        }

        val innerLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }

        val tvHelp = android.widget.TextView(this).apply {
            text = helpText
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            setLineSpacing(4f, 1.1f)
        }
        innerLayout.addView(tvHelp)

        val btnClose = android.widget.Button(this).apply {
            text = "Schließen"
            setOnClickListener {
                try { windowManager.removeView(helpLayout) } catch (_: Exception) {}
            }
        }
        innerLayout.addView(btnClose, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 24 })

        helpLayout.addView(innerLayout)

        val helpParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        makeBackDismissible(helpLayout, helpParams) {
            try { windowManager.removeView(helpLayout) } catch (_: Exception) {}
        }
        try { windowManager.addView(helpLayout, helpParams) } catch (e: Exception) {
            FileLogger.log(this, "MarketData", "Error showing help: ${e.message}")
        }
    }

    private fun showMarketDataFullscreen() {
        if (isMarketDataFullscreen) return
        isMarketDataFullscreen = true

        // Save current screen timeout and set screen to stay on
        try {
            savedScreenTimeout = android.provider.Settings.System.getInt(
                contentResolver, android.provider.Settings.System.SCREEN_OFF_TIMEOUT
            )
        } catch (_: Exception) {
            savedScreenTimeout = 60000 // default 1 min
        }

        // Hide the normal widget
        marketDataView?.visibility = View.GONE

        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        // Create fullscreen overlay
        val fsLayout = android.widget.FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#F0000000"))
            keepScreenOn = true
        }

        // Format text with line breaks for fullscreen
        val fsText = marketDataFullText.toString().replace("   ", "\n")
        val savedFsFontSize = getSharedPreferences("app_prefs", MODE_PRIVATE).getFloat("market_fullscreen_font_size", 28f)
        val tvFullscreen = android.widget.TextView(this).apply {
            text = fsText
            setTextColor(android.graphics.Color.WHITE)
            textSize = savedFsFontSize
            setPadding(48, 48, 48, 48)
            gravity = Gravity.CENTER
        }
        fsLayout.addView(tvFullscreen, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        ).apply { gravity = Gravity.CENTER })

        // Close button top-right
        val closeBtn = android.widget.ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setColorFilter(android.graphics.Color.WHITE)
            setPadding(24, 24, 24, 24)
            setOnClickListener { closeMarketDataFullscreen() }
        }
        fsLayout.addView(closeBtn, android.widget.FrameLayout.LayoutParams(
            96, 96
        ).apply { gravity = Gravity.TOP or Gravity.END; topMargin = 32; marginEnd = 32 })

        val fsParams = WindowManager.LayoutParams(
            screenWidth,
            screenHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        // Long press on fullscreen to show context menu
        fsLayout.setOnLongClickListener {
            showMarketDataContextMenu()
            true
        }

        makeBackDismissible(fsLayout, fsParams) { closeMarketDataFullscreen() }
        marketDataFullscreenView = fsLayout
        try { windowManager.addView(fsLayout, fsParams) } catch (e: Exception) {
            FileLogger.log(this, "MarketData", "Error showing fullscreen: ${e.message}")
        }

        FileLogger.log(this, "MarketData", "Fullscreen mode enabled")
    }

    private fun closeMarketDataFullscreen() {
        if (!isMarketDataFullscreen) return
        isMarketDataFullscreen = false

        // Remove fullscreen view
        marketDataFullscreenView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        marketDataFullscreenView = null

        // Restore screen timeout
        if (savedScreenTimeout > 0) {
            try {
                android.provider.Settings.System.putInt(
                    contentResolver, android.provider.Settings.System.SCREEN_OFF_TIMEOUT, savedScreenTimeout
                )
            } catch (_: Exception) {
                FileLogger.log(this, "MarketData", "Could not restore screen timeout")
            }
            savedScreenTimeout = -1
        }

        // Show normal widget again
        marketDataView?.visibility = View.VISIBLE

        FileLogger.log(this, "MarketData", "Fullscreen mode disabled")
    }

    private fun updateFullscreenText(text: CharSequence) {
        val fsView = marketDataFullscreenView ?: return
        val tv = (fsView as? android.widget.FrameLayout)?.getChildAt(0) as? android.widget.TextView
        tv?.text = text
    }

    private fun setupMarketDataDragListener() {
        marketDataView?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isMoving = false
            private val CLICK_THRESHOLD = 10
            private var clickCount = 0
            private var clickHandler = android.os.Handler(android.os.Looper.getMainLooper())
            private var clickRunnable: Runnable? = null
            private var longPressRunnable: Runnable? = null
            private var longPressTriggered = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = marketDataParams!!.x
                        initialY = marketDataParams!!.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isMoving = false
                        longPressTriggered = false

                        // Setup long press for context menu
                        longPressRunnable = Runnable {
                            if (!isMoving) {
                                longPressTriggered = true
                                showMarketDataContextMenu()
                            }
                        }
                        v.handler?.postDelayed(longPressRunnable!!, 600)
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()

                        if (kotlin.math.abs(dx) > CLICK_THRESHOLD || kotlin.math.abs(dy) > CLICK_THRESHOLD) {
                            isMoving = true
                            longPressRunnable?.let { v.handler?.removeCallbacks(it) }
                            marketDataParams!!.x = initialX + dx
                            marketDataParams!!.y = initialY + dy
                            windowManager.updateViewLayout(marketDataView, marketDataParams)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        longPressRunnable?.let { v.handler?.removeCallbacks(it) }
                        if (longPressTriggered) {
                            // Long press already handled
                            return true
                        }
                        if (isMoving) {
                            val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
                            prefs.edit().putInt("market_x", marketDataParams!!.x).putInt("market_y", marketDataParams!!.y).apply()
                        } else {
                            // Multi-click detection
                            clickCount++
                            clickRunnable?.let { clickHandler.removeCallbacks(it) }
                            clickRunnable = Runnable {
                                val count = clickCount
                                clickCount = 0
                                when (count) {
                                    2 -> toggleMarketDataMinimized()
                                    3 -> closeMarketDataWidget()
                                }
                            }
                            clickHandler.postDelayed(clickRunnable!!, 350)
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    // --- CLIPBOARD HISTORY LOGIC ---
    private var clipboardView: View? = null
    private val clipboardHistory = mutableListOf<String>()
    private val clipboardFavorites = mutableListOf<String>()
    private var currentClipboardTab = "history" // "history" or "favorites"
    private var clipboardSearchQuery = ""
    // Listener moved to VoiceAccessibilityService

    // Only Load is needed for UI
    private fun loadClipboardHistory() {
        // Try to load from active service (RAM) first for speed and accuracy
        val service = com.example.voicelistener.services.VoiceAccessibilityService.instance
        if (service != null) {
             // Force fresh check immediately (User Request: "Fetch on Click")
             service.checkClipboard()
             
             clipboardHistory.clear()
             try {
                clipboardHistory.addAll(service.currentHistory)
                // Log success
                // FileLogger.log(this, "Clipboard", "Loaded from Service RAM: ${clipboardHistory.size}")
                return
             } catch (e: Exception) {
                 FileLogger.log(this, "Clipboard", "Error loading from Service: ${e.message}")
             }
        }

        // Fallback to Prefs (Disk) if service is dead or error
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val jsonStr = prefs.getString("clipboard_history", "[]")
        try {
            val json = org.json.JSONArray(jsonStr)
            clipboardHistory.clear()
            for (i in 0 until json.length()) {
                clipboardHistory.add(json.getString(i))
            }
        } catch (e: Exception) {
            FileLogger.log(this, "Clipboard", "Error loading history: ${e.message}")
        }
    }

    private fun loadClipboardFavorites() {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val jsonStr = prefs.getString("clipboard_favorites", "[]")
        try {
            val json = org.json.JSONArray(jsonStr)
            clipboardFavorites.clear()
            for (i in 0 until json.length()) {
                clipboardFavorites.add(json.getString(i))
            }
        } catch (e: Exception) {
            FileLogger.log(this, "Clipboard", "Error loading favorites: ${e.message}")
        }
    }

    private fun saveClipboardFavorites() {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val json = org.json.JSONArray(clipboardFavorites).toString()
        prefs.edit().putString("clipboard_favorites", json).apply()
    }

    private fun showClipboardHistory() {
        loadClipboardHistory()
        loadClipboardFavorites()
        currentClipboardTab = "history"
        clipboardSearchQuery = ""

        try {
            val inflater = LayoutInflater.from(this)
            clipboardView = inflater.inflate(R.layout.clipboard_history, null)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_DIM_BEHIND or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            // Make focusable to allow keyboard input for search
            params.flags = WindowManager.LayoutParams.FLAG_DIM_BEHIND
            params.dimAmount = 0.5f
            params.gravity = Gravity.CENTER

            // Set up Search
            val etSearch = clipboardView?.findViewById<android.widget.EditText>(R.id.etClipboardSearch)
            val btnClearSearch = clipboardView?.findViewById<android.widget.ImageButton>(R.id.btnClearSearch)
            
            btnClearSearch?.setOnClickListener {
                etSearch?.setText("")
            }

            etSearch?.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val query = s?.toString() ?: ""
                    clipboardSearchQuery = query
                    btnClearSearch?.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
                    refreshClipboardList()
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })

            // Set up Tabs
            val btnTabHistory = clipboardView?.findViewById<android.widget.Button>(R.id.btnTabHistory)
            val btnTabFavorites = clipboardView?.findViewById<android.widget.Button>(R.id.btnTabFavorites)
            
            fun updateTabStyle() {
                if (currentClipboardTab == "history") {
                    btnTabHistory?.setBackgroundColor(Color.parseColor("#4CAF50"))
                    btnTabHistory?.setTextColor(Color.WHITE)
                    btnTabFavorites?.setBackgroundColor(Color.parseColor("#CCCCCC"))
                    btnTabFavorites?.setTextColor(Color.parseColor("#333333"))
                } else {
                    btnTabFavorites?.setBackgroundColor(Color.parseColor("#4CAF50"))
                    btnTabFavorites?.setTextColor(Color.WHITE)
                    btnTabHistory?.setBackgroundColor(Color.parseColor("#CCCCCC"))
                    btnTabHistory?.setTextColor(Color.parseColor("#333333"))
                }
            }
            updateTabStyle()

            btnTabHistory?.setOnClickListener {
                currentClipboardTab = "history"
                updateTabStyle()
                refreshClipboardList()
            }
            btnTabFavorites?.setOnClickListener {
                currentClipboardTab = "favorites"
                updateTabStyle()
                refreshClipboardList()
            }

            // Keep Open CheckBox
            val cbKeepOpen = clipboardView?.findViewById<android.widget.CheckBox>(R.id.cbKeepOpen)
            val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
            cbKeepOpen?.isChecked = prefs.getBoolean("clipboard_keep_open", false)
            cbKeepOpen?.setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("clipboard_keep_open", isChecked).apply()
            }

            clipboardView?.findViewById<View>(R.id.btnCloseClipboard)?.setOnClickListener {
                 closeClipboardHistory()
            }

            makeBackDismissible(clipboardView!!, params) { closeClipboardHistory() }
            windowManager.addView(clipboardView, params)
            
            // Sync clipboard to capture items copied outside the app
            clipboardView?.postDelayed({
                try {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = clipboard.primaryClip
                    if (clip != null && clip.itemCount > 0) {
                        val text = clip.getItemAt(0).coerceToText(this@OverlayService)?.toString()
                        if (!text.isNullOrBlank()) {
                            com.example.voicelistener.services.VoiceAccessibilityService.instance?.addToClipboardHistory(text)
                        }
                    }
                } catch(e: Exception) {}
            }, 150)
            
            refreshClipboardList()
        } catch (e: Exception) {
            FileLogger.log(this, "Clipboard", "Error showing history: ${e.message}")
        }
    }

    private fun refreshClipboardList() {
        val container = clipboardView?.findViewById<android.widget.LinearLayout>(R.id.clipboardListContainer) ?: return
        container.removeAllViews()
        
        loadClipboardHistory()
        loadClipboardFavorites()
        
        val cbKeepOpen = clipboardView?.findViewById<android.widget.CheckBox>(R.id.cbKeepOpen)
        
        val sourceList = if (currentClipboardTab == "history") clipboardHistory else clipboardFavorites
        val filteredList = if (clipboardSearchQuery.isBlank()) {
            sourceList
        } else {
            sourceList.filter { it.contains(clipboardSearchQuery, ignoreCase = true) }
        }

        if (filteredList.isEmpty()) {
            val tv = android.widget.TextView(this)
            tv.text = "Keine Einträge"
            tv.setTextColor(Color.LTGRAY)
            tv.setPadding(16, 16, 16, 16)
            container.addView(tv)
        } else {
            for (item in filteredList) {
                val itemLayout = android.widget.LinearLayout(this)
                itemLayout.orientation = android.widget.LinearLayout.HORIZONTAL
                itemLayout.setPadding(0, 16, 0, 16)
                itemLayout.background = getResources().getDrawable(android.R.drawable.list_selector_background, null)
                itemLayout.isClickable = true
                
                val tv = android.widget.TextView(this)
                tv.text = if (item.length > 50) item.substring(0, 50) + "..." else item
                tv.setTextColor(Color.BLACK)
                tv.textSize = 14f
                tv.layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                
                itemLayout.addView(tv)
                container.addView(itemLayout)
                
                // Click -> Inject Text
                itemLayout.setOnClickListener {
                    val textToInsert = "$item "
                    injectTextOrCopyFallback(textToInsert)
                    if (cbKeepOpen?.isChecked != true) {
                        closeClipboardHistory()
                    }
                }

                // Long Click -> Context Menu
                itemLayout.setOnLongClickListener {
                    val builder = android.app.AlertDialog.Builder(this@OverlayService)
                    builder.setTitle("Optionen")
                    if (currentClipboardTab == "history") {
                        val items = arrayOf("Anzeigen", "Einfügen", "Als Favorit speichern", "Löschen")
                        builder.setItems(items) { dialog, which ->
                            when (which) {
                                0 -> showFullTextDialog(item)
                                1 -> {
                                    injectTextOrCopyFallback("$item ")
                                    if (cbKeepOpen?.isChecked != true) closeClipboardHistory()
                                }
                                2 -> {
                                    if (clipboardFavorites.contains(item)) {
                                        clipboardFavorites.remove(item)
                                    }
                                    clipboardFavorites.add(0, item)
                                    saveClipboardFavorites()
                                    val favToast = android.widget.Toast.makeText(this@OverlayService, "Als Favorit gespeichert", android.widget.Toast.LENGTH_SHORT)
                                    favToast.setGravity(android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL, 0, 150)
                                    favToast.show()
                                }
                                3 -> {
                                    try {
                                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        val clip = clipboard.primaryClip
                                        if (clip != null && clip.itemCount > 0) {
                                            val currentText = clip.getItemAt(0).coerceToText(this@OverlayService)?.toString()
                                            if (currentText == item) {
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                                    clipboard.clearPrimaryClip()
                                                } else {
                                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {}
                                    
                                    val service = com.example.voicelistener.services.VoiceAccessibilityService.instance
                                    if (service != null) {
                                        service.removeFromClipboardHistory(item)
                                    } else {
                                        clipboardHistory.remove(item)
                                        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                                        val json = org.json.JSONArray(clipboardHistory).toString()
                                        prefs.edit().putString("clipboard_history", json).apply()
                                    }
                                    
                                    refreshClipboardList()
                                }
                            }
                        }
                    } else {
                        val items = arrayOf("Anzeigen", "Einfügen", "Aus Favoriten entfernen")
                        builder.setItems(items) { dialog, which ->
                            when (which) {
                                0 -> showFullTextDialog(item)
                                1 -> {
                                    injectTextOrCopyFallback("$item ")
                                    if (cbKeepOpen?.isChecked != true) closeClipboardHistory()
                                }
                                2 -> {
                                    clipboardFavorites.remove(item)
                                    saveClipboardFavorites()
                                    refreshClipboardList()
                                }
                            }
                        }
                    }
                    val dialog = builder.create()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                    } else {
                        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                    }
                    dialog.show()
                    true
                }
                
                // Separator
                val sep = View(this)
                sep.layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1)
                sep.setBackgroundColor(Color.LTGRAY)
                container.addView(sep)
            }
        }
    }

    private fun showFullTextDialog(text: String) {
        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle("Vollständiger Text")
        
        val scrollView = android.widget.ScrollView(this)
        val tv = android.widget.TextView(this)
        tv.text = text
        tv.setPadding(32, 32, 32, 32)
        tv.setTextColor(Color.BLACK)
        tv.setTextIsSelectable(true)
        scrollView.addView(tv)
        
        builder.setView(scrollView)
        builder.setPositiveButton("Schließen", null)
        
        val dialog = builder.create()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        } else {
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        }
        dialog.show()
    }

    private fun closeClipboardHistory() {
        if (clipboardView != null) {
            try { windowManager.removeView(clipboardView) } catch (e: Exception) {}
            clipboardView = null
        }
    }
    
    // --- TRANSLATOR LOGIC ---
    private var translateView: View? = null
    
    private fun showTranslator() {
        try {
            val inflater = LayoutInflater.from(this)
            translateView = inflater.inflate(R.layout.dialog_translator, null)
            
            // Use 80% screen width so the overlay button remains accessible on the right
            val screenWidth = resources.displayMetrics.widthPixels
            val dialogWidth = (screenWidth * 0.80).toInt()

            val trParams = WindowManager.LayoutParams(
                dialogWidth,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )
            trParams.gravity = Gravity.CENTER_VERTICAL or Gravity.START
            
            // 1. Get Clipboard - try system clipboard first, fallback to our own history
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            var clipText = ""
            try {
                clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            } catch (_: Exception) {}
            // Fallback: use our clipboard history (works even when system clipboard is blocked)
            if (clipText.isEmpty()) {
                val service = VoiceAccessibilityService.instance
                if (service != null && service.currentHistory.isNotEmpty()) {
                    clipText = service.currentHistory[0]
                } else {
                    // Last resort: load from SharedPrefs
                    val histJson = getSharedPreferences("app_prefs", MODE_PRIVATE).getString("clipboard_history", "[]") ?: "[]"
                    try {
                        val arr = org.json.JSONArray(histJson)
                        if (arr.length() > 0) clipText = arr.getString(0)
                    } catch (_: Exception) {}
                }
            }
            if (clipText.length > 500) clipText = clipText.substring(0, 500) + "..."
            
            val sourcePreview = translateView?.findViewById<android.widget.EditText>(R.id.sourceTextPreview)
            
            if (clipText.isEmpty()) {
                 sourcePreview?.hint = "Zwischenablage leer. Hier Text eingeben..."
                 sourcePreview?.setText("")
            } else {
                 sourcePreview?.setText(clipText)
            }

            // Clear source button
            translateView?.findViewById<View>(R.id.btnClearSource)?.setOnClickListener {
                sourcePreview?.setText("")
                sourcePreview?.requestFocus()
            }

            // 2. Setup Spinner
            val languages = arrayOf("Deutsch", "Englisch", "Spanisch")
            val spinner = translateView?.findViewById<android.widget.Spinner>(R.id.languageSpinner)
            val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, languages)
            spinner?.adapter = adapter
            
            val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
            val lastLang = prefs.getInt("last_translate_lang", 0)
            spinner?.setSelection(lastLang)
            
            // 3. Buttons
            val btnSend = translateView?.findViewById<android.widget.Button>(R.id.btnStartTranslate)
            val progressBar = translateView?.findViewById<android.widget.ProgressBar>(R.id.translateProgress)
            val resultText = translateView?.findViewById<android.widget.EditText>(R.id.resultText)
            
            btnSend?.setOnClickListener {
                val textToTranslate = sourcePreview?.text?.toString() ?: ""
                
                if (textToTranslate.isEmpty()) {
                    showTopMessage("Nichts zum Übersetzen!")
                    return@setOnClickListener
                }
                
                val langPos = spinner?.selectedItemPosition ?: 0
                prefs.edit().putInt("last_translate_lang", langPos).apply()
                val targetLang = languages[langPos]
                
                // Start Translation
                progressBar?.visibility = View.VISIBLE
                btnSend.isEnabled = false
                resultText?.setText("")
                
                performTranslation(textToTranslate, targetLang, resultText, progressBar, btnSend)
            }
            
            translateView?.findViewById<View>(R.id.btnCloseDialog)?.setOnClickListener {
                closeTranslator()
            }
            
            translateView?.findViewById<View>(R.id.btnCopyResult)?.setOnClickListener {
                val txt = resultText?.text.toString()
                if (txt.isNotEmpty()) {
                    val clip = android.content.ClipData.newPlainText("Translation", txt)
                    clipboard.setPrimaryClip(clip)
                    showTopMessage("Kopiert!")
                    closeTranslator()
                }
            }
            
            val btnInsert = translateView?.findViewById<View>(R.id.btnInsertResult)
            btnInsert?.setOnClickListener {
                 val txt = resultText?.text.toString()
                 if (txt.isNotEmpty()) {
                     // FIX: Close dialog FIRST to return focus to underlying app
                     closeTranslator()
                     // Delay injection slightly
                     android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        injectTextOrCopyFallback(txt)
                     }, 200)
                 }
            }
            
            makeBackDismissible(translateView!!, trParams) { closeTranslator() }
            windowManager.addView(translateView, trParams)

            // Re-add overlay button on top so it stays above the translator dialog
            if (overlayView != null && params != null) {
                try {
                    windowManager.removeView(overlayView)
                    windowManager.addView(overlayView, params)
                } catch (_: Exception) {}
            }

        } catch (e: Exception) {
            FileLogger.log(this, "Translator", "Error: ${e.message}")
        }
    }
    
    private fun closeTranslator() {
        if (translateView != null) {
            try { windowManager.removeView(translateView) } catch (e: Exception) {}
            translateView = null
        }
    }
    
    private fun performTranslation(source: String, targetLang: String, resultView: android.widget.EditText?, progress: View?, btn: View?) {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val apiKey = prefs.getString("groq_api_key", "") ?: ""
        if (apiKey.isEmpty()) {
            showTopMessage("API Key fehlt!")
            progress?.visibility = View.GONE
            btn?.isEnabled = true
            return
        }
        
        serviceScope.launch {
            try {
                val prompt = "You are a professional translator. Translate the following text to $targetLang. Output ONLY the translation, nothing else."
                val userMsg = "Text to translate:\n$source"
                
                val response = chatWithFallback("Bearer $apiKey", listOf(Message("system", prompt), Message("user", userMsg)))
                val translation = response.choices.firstOrNull()?.message?.content ?: "[Fehler]"
                
                withContext(Dispatchers.Main) {
                    resultView?.setText(translation)
                    progress?.visibility = View.GONE
                    btn?.isEnabled = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    resultView?.setText("Fehler: ${e.message}")
                    progress?.visibility = View.GONE
                    btn?.isEnabled = true
                }
            }
        }
    }
    
    private fun injectTextOrCopyFallback(text: String) {
        // Reuse logic from processAudio (refactor ideally, but duplicating for now to avoid breaking existing flow)
        // Actually, let's look at checkFocus()
        val isFocused = checkFocus()
        if (isFocused) {
           val intent = Intent("com.example.voicelistener.ACTION_INJECT_TEXT")
           intent.setPackage(packageName)
           intent.putExtra("text", text)
           sendBroadcast(intent)
        } else {
           val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
           val clip = android.content.ClipData.newPlainText("Voice Text", text)
           clipboard.setPrimaryClip(clip)
           showTopMessage("In die Zwischenablage kopiert")
        }
    }

    private fun checkFocus(): Boolean {
        val accessibilityService = VoiceAccessibilityService.instance
        val isActive = accessibilityService != null && accessibilityService.isInputFocused()
        // FileLogger.log(this, "FocusCheck", "Service: ${accessibilityService != null}, Focused: $isActive") // Reduce spam
        return isActive
    }
    
    // --- VISIBILITY LOGIC ---
    private fun hideOverlayButton() {
        FileLogger.log(this, "OverlayService", "Hiding overlay via gesture")
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        // Set "always hidden" so Auto-Hide doesn't bring it back
        prefs.edit().putBoolean("overlay_always_hidden", true).apply()
        overlayView?.visibility = View.GONE
        dimView?.visibility = View.GONE
        closeMenu()
        startForegroundNotification(true)
        showTopMessage("Button versteckt (Notification zum Wiederherstellen)")
    }

    private fun checkHideOverlay() {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)

        val focusModeEnabled = prefs.getBoolean("overlay_focus_mode", false)

        // 0. If Always On mode -> DO NOT HIDE
        if (!focusModeEnabled) {
            if (overlayView?.visibility != View.VISIBLE) {
                overlayView?.visibility = View.VISIBLE
                startForegroundNotification(false)
            }
            return
        }

        // 1. If Recording or Processing -> STAY VISIBLE
        if (isRecording || isProcessing) {
            return
        }
        
        // 2. If Focused -> STAY VISIBLE
        if (checkFocus()) {
             return
        }
        
        // 3. If Menu Open -> STAY VISIBLE
        if (menuView != null || translateView != null || clipboardView != null) return

        // 4. Timer Logic
        val timeVisible = System.currentTimeMillis() - lastShowTime
        if (timeVisible < HIDE_DELAY_MS) {
            val remaining = HIDE_DELAY_MS - timeVisible
            
            hideRunnable?.let { overlayView?.removeCallbacks(it) }
            hideRunnable = Runnable { checkHideOverlay() }
            overlayView?.postDelayed(hideRunnable, remaining)
            return
        }
        
        // 5. HIDE
        overlayView?.visibility = View.GONE
        dimView?.visibility = View.GONE
        startForegroundNotification(true)
    }

    // ─── Voice Chat Widget (Record → LLM → TTS) ───

    private fun startVoiceChatRecording() {
        if (isRecording || isVoiceChatRecording) return

        // Stop TTS if still speaking from previous recording
        if (tts?.isSpeaking == true || isGeminiSpeaking) {
            tts?.stop()
            stopGeminiAudio()
            FileLogger.log(this, "VoiceChat", "Stopped TTS for new recording")
        }

        isVoiceChatRecording = true
        recordingStartTime = System.currentTimeMillis()
        FileLogger.log(this, "VoiceChat", "Starting recording...")

        // Haptic feedback
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))

        try {
            audioRecorder?.startRecording()
            VoiceChatWidget.updateState(this, "recording")
        } catch (e: Exception) {
            FileLogger.log(this, "VoiceChat", "Start failed: ${e.message}")
            isVoiceChatRecording = false
            VoiceChatWidget.updateState(this, "idle")
        }
    }

    private fun stopVoiceChatRecording() {
        if (!isVoiceChatRecording) return
        val realDuration = System.currentTimeMillis() - recordingStartTime
        FileLogger.log(this, "VoiceChat", "Stopping recording... (${realDuration}ms)")
        isVoiceChatRecording = false

        if (realDuration < 1000) {
            FileLogger.log(this, "VoiceChat", "Dropped: Too short (< 1s)")
            audioRecorder?.cancelRecording()
            VoiceChatWidget.updateState(this, "idle")
            return
        }

        VoiceChatWidget.updateState(this, "processing")

        serviceScope.launch(Dispatchers.IO) {
            try {
                audioRecorder?.stopRecording()
                val file = audioRecorder?.getOutputFile()
                if (file != null && file.exists() && file.length() > 0) {
                    processVoiceChatAudio(file)
                } else {
                    FileLogger.log(this@OverlayService, "VoiceChat", "File failed (empty or missing)")
                    withContext(Dispatchers.Main) { VoiceChatWidget.updateState(this@OverlayService, "idle") }
                }
            } catch (e: Exception) {
                FileLogger.log(this@OverlayService, "VoiceChat", "Stop failed: ${e.message}")
                withContext(Dispatchers.Main) { VoiceChatWidget.updateState(this@OverlayService, "idle") }
            }
        }
    }

    private fun processVoiceChatAudio(file: File) {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val apiKey = prefs.getString("groq_api_key", "") ?: ""

        if (apiKey.isEmpty()) {
            FileLogger.log(this, "VoiceChat", "No API Key found")
            serviceScope.launch(Dispatchers.Main) {
                Toast.makeText(this@OverlayService, "API Key fehlt!", Toast.LENGTH_SHORT).show()
                VoiceChatWidget.updateState(this@OverlayService, "idle")
            }
            return
        }

        val auth = "Bearer $apiKey"

        serviceScope.launch {
            try {
                // 1. Whisper transcription
                FileLogger.log(this@OverlayService, "VoiceChat", "Sending to Whisper...")
                val requestFile = file.asRequestBody("audio/m4a".toMediaTypeOrNull())
                val body = MultipartBody.Part.createFormData("file", file.name, requestFile)
                val modelPart = "whisper-large-v3".toRequestBody("text/plain".toMediaTypeOrNull())
                val transResponse = GroqClient.api.transcribeAudio(auth, body, modelPart)
                val rawText = transResponse.text
                FileLogger.log(this@OverlayService, "VoiceChat", "Whisper: $rawText")

                // 2. LLM - prefer Gemini + Search for Voice Chat, fallback to Groq
                val geminiKey = prefs.getString("gemini_api_key", "") ?: ""
                val cleanAnswer: String
                if (geminiKey.isNotEmpty()) {
                    FileLogger.log(this@OverlayService, "VoiceChat", "Sending to Gemini + Search...")
                    cleanAnswer = chatWithGemini(geminiKey, listOf(Message("user", rawText)), "gemini-2.5-flash", true)
                } else {
                    FileLogger.log(this@OverlayService, "VoiceChat", "Sending to Groq LLM...")
                    val chatResponse = chatWithFallback(auth, listOf(Message("user", rawText)))
                    cleanAnswer = stripThinkingBlock(chatResponse.choices.firstOrNull()?.message?.content ?: "")
                }
                FileLogger.log(this@OverlayService, "VoiceChat", "LLM: ${cleanAnswer.take(100)}")

                // 3. TTS
                withContext(Dispatchers.Main) {
                    speakText(cleanAnswer)
                }
            } catch (e: Exception) {
                FileLogger.log(this@OverlayService, "VoiceChat", "Error: ${e.message}")
                withContext(Dispatchers.Main) {
                    VoiceChatWidget.updateState(this@OverlayService, "idle")
                    Toast.makeText(this@OverlayService, "Fehler: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private var pendingSpeakText: String? = null

    private fun speakText(text: String) {
        if (text.isBlank()) {
            VoiceChatWidget.updateState(this, "idle")
            return
        }

        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val geminiKey = prefs.getString("gemini_api_key", "") ?: ""

        if (geminiKey.isNotEmpty()) {
            speakWithGemini(text, geminiKey)
        } else {
            speakWithSystemTts(text)
        }
    }

    private fun speakWithGemini(text: String, apiKey: String) {
        VoiceChatWidget.updateState(this, "speaking")
        FileLogger.log(this, "GeminiTTS", "Starting TTS for: ${text.take(60)}...")

        serviceScope.launch(Dispatchers.IO) {
            try {
                val requestBody = JSONObject().apply {
                    put("contents", JSONArray().put(JSONObject().apply {
                        put("parts", JSONArray().put(JSONObject().apply {
                            put("text", text)
                        }))
                    }))
                    put("generationConfig", JSONObject().apply {
                        put("responseModalities", JSONArray().put("AUDIO"))
                        put("speechConfig", JSONObject().apply {
                            put("voiceConfig", JSONObject().apply {
                                put("prebuiltVoiceConfig", JSONObject().apply {
                                    put("voiceName", "Kore")
                                })
                            })
                        })
                    })
                }

                val request = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-preview-tts:generateContent?key=$apiKey")
                    .post(requestBody.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                    .build()

                val client = OkHttpClient.Builder()
                    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    FileLogger.log(this@OverlayService, "GeminiTTS", "API error ${response.code}: ${responseBody.take(200)}")
                    withContext(Dispatchers.Main) {
                        // Fallback to system TTS
                        speakWithSystemTts(text)
                    }
                    return@launch
                }

                val json = JSONObject(responseBody)
                val audioData = json
                    .getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getJSONObject("inlineData")
                    .getString("data")

                val pcmBytes = Base64.decode(audioData, Base64.DEFAULT)
                FileLogger.log(this@OverlayService, "GeminiTTS", "Got ${pcmBytes.size} bytes PCM audio")

                withContext(Dispatchers.Main) {
                    playPcmAudio(pcmBytes)
                }
            } catch (e: Exception) {
                FileLogger.log(this@OverlayService, "GeminiTTS", "Error: ${e.message}")
                withContext(Dispatchers.Main) {
                    // Fallback to system TTS
                    speakWithSystemTts(text)
                }
            }
        }
    }

    private fun playPcmAudio(pcmBytes: ByteArray) {
        stopGeminiAudio()

        val sampleRate = 24000
        val audioFormat = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(pcmBytes.size)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        audioTrack?.write(pcmBytes, 0, pcmBytes.size)
        audioTrack?.setNotificationMarkerPosition(pcmBytes.size / 2) // 2 bytes per sample (16-bit)
        audioTrack?.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(track: AudioTrack?) {
                isGeminiSpeaking = false
                VoiceChatWidget.updateState(this@OverlayService, "idle")
                track?.release()
                audioTrack = null
            }
            override fun onPeriodicNotification(track: AudioTrack?) {}
        })

        isGeminiSpeaking = true
        audioTrack?.play()
    }

    private fun stopGeminiAudio() {
        if (isGeminiSpeaking) {
            try {
                audioTrack?.stop()
                audioTrack?.release()
            } catch (_: Exception) {}
            audioTrack = null
            isGeminiSpeaking = false
        }
    }

    private fun speakWithSystemTts(text: String) {
        // Shutdown old TTS instance
        tts?.stop()
        tts?.shutdown()
        ttsReady = false

        pendingSpeakText = text
        VoiceChatWidget.updateState(this, "speaking")
        FileLogger.log(this, "SystemTTS", "Starting TTS for: ${text.take(60)}...")

        val initListener = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                val toSpeak = pendingSpeakText ?: return@OnInitListener
                pendingSpeakText = null

                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        VoiceChatWidget.updateState(this@OverlayService, "idle")
                    }
                    override fun onError(utteranceId: String?) {
                        VoiceChatWidget.updateState(this@OverlayService, "idle")
                    }
                })
                tts?.speak(toSpeak, TextToSpeech.QUEUE_FLUSH, null, "voice_chat_response")
            } else {
                FileLogger.log(this@OverlayService, "SystemTTS", "Init failed status=$status")
                VoiceChatWidget.updateState(this@OverlayService, "idle")
            }
        }

        tts = TextToSpeech(this, initListener)
    }

    // ─── Overlay Recording ───

    private fun startRecording() {
        recordingStartTime = System.currentTimeMillis()
        FileLogger.log(this, "Recording", "Starting recording...")
        try {
            // Ensure Visible & Update Timestamp (so it doesn't hide immediately after stop)
            if (overlayView?.visibility != View.VISIBLE) {
                overlayView?.visibility = View.VISIBLE
                startForegroundNotification(false)
            }
            lastShowTime = System.currentTimeMillis() // Extend visibility
            hideRunnable?.let { overlayView?.removeCallbacks(it) } // Cancel hide timer while recording
            
            // Haptic Feedback
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))

            // Visual Feedback (Ring)
            overlayButton?.setImageDrawable(null) // Remove icon to show background clearly? Or keep icon? 
            // Better: Keep icon but make background distinct
            overlayButton?.setBackgroundResource(R.drawable.button_bg_recording)
            overlayButton?.setColorFilter(Color.WHITE) // Ensure icon is white on red
            
            audioRecorder?.startRecording()
        } catch (e: Exception) {
            FileLogger.log(this, "RecordError", "Start failed: ${e.message}")
        }
    }

    private fun stopRecordingAndProcess(durationMs: Long) {
        val realDuration = System.currentTimeMillis() - recordingStartTime
        FileLogger.log(this, "Recording", "Stopping recording... (Touch: ${durationMs}ms, Real: ${realDuration}ms)")
        try {
            if (realDuration < 1000) {
                 FileLogger.log(this, "Recording", "Dropped: Too short (< 1s)")
                 audioRecorder?.cancelRecording()
                 resetUI()
                 isRecording = false
                 checkHideOverlay()
                 return
            }

            overlayButton?.setColorFilter(Color.YELLOW)
            isProcessing = true
            isRecording = false
            showTopMessage("Verarbeite...")
            
            // Processing in Background
            serviceScope.launch(Dispatchers.IO) {
                audioRecorder?.stopRecording()
            val file = audioRecorder?.getOutputFile()
            
            if (file != null && file.exists() && file.length() > 0) {
                FileLogger.log(this@OverlayService, "Recording", "File created: ${file.length()} bytes. Processing...")
                processAudio(file)
                } else {
                    FileLogger.log(this@OverlayService, "Recording", "File failed (empty or missing)")
                    resetUI()
                }
            }
        } catch (e: Exception) {
             FileLogger.log(this, "RecordError", "Stop failed: ${e.message}")
             resetUI()
        }
    }

    private fun processAudio(file: File) {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val apiKey = prefs.getString("groq_api_key", "") ?: ""

        if (apiKey.isEmpty()) {
            FileLogger.log(this, "API", "No API Key found")
            showTopMessage("API Key fehlt!")
            resetUI()
            return
        }

        val auth = "Bearer $apiKey"

        serviceScope.launch {
            try {
                // 1. Whisper
                FileLogger.log(this@OverlayService, "API", "Sending to Whisper...")
                val requestFile = file.asRequestBody("audio/m4a".toMediaTypeOrNull())
                val body = MultipartBody.Part.createFormData("file", file.name, requestFile)
                val modelPart = "whisper-large-v3".toRequestBody("text/plain".toMediaTypeOrNull())
                
                val transResponse = GroqClient.api.transcribeAudio(auth, body, modelPart)
                val rawText = transResponse.text
                FileLogger.log(this@OverlayService, "API", "Whisper Text: $rawText")

                // Check Llama Enabled
                val llamaEnabled = prefs.getBoolean("llama_enabled", true)
                var finalText = rawText

                if (llamaEnabled) {
                    // 2. Llama Correction
                    FileLogger.log(this@OverlayService, "API", "Sending to Llama...")
                    
                    // Strict System Prompt (REFINED)
                    val basePrompt = prefs.getString("llama_system_prompt", DEFAULT_PROMPT) ?: DEFAULT_PROMPT
                    val vocabulary = prefs.getString("custom_vocabulary", "") ?: ""

                    val resolvedSystemPrompt: String
                    val resolvedUserPrompt: String

                    val translateLang = pendingTranslateLang
                    if (translateLang != null) {
                        // Quick Translate Mode: translate the spoken text directly
                        pendingTranslateLang = null
                        val translateBase = "You are a professional translator. Translate the following text to $translateLang. Output ONLY the translation, nothing else."
                        resolvedSystemPrompt = if (vocabulary.isNotBlank()) {
                            "$translateBase\n\nCONTEXT / VOCABULARY (use correct spellings for names, brands, technical terms if they appear):\n$vocabulary"
                        } else translateBase
                        resolvedUserPrompt = rawText
                    } else if (isSettingsAIActive) {
                        // Settings AI Mode: show confirmation overlay first
                        isSettingsAIActive = false
                        withContext(Dispatchers.Main) {
                            showSettingsAIConfirmation(rawText, auth)
                        }
                        return@launch
                    } else if (isAskLlamaActive) {
                        // Ask Llama Mode
                        isAskLlamaActive = false // Reset for next time (only here!)
                        
                        resolvedSystemPrompt = if (vocabulary.isNotBlank()) "Context vocabulary:\n$vocabulary" else "You are a helpful assistant."
                        resolvedUserPrompt = "$rawText\n\nFühre diesen Prompt ohne jeden weiteren Kommentar in der Antwort aus."
                    } else {
                        // Standard Correction Mode
                        resolvedSystemPrompt = if (vocabulary.isNotBlank()) {
                            "$basePrompt\n\nCONTEXT / VOCABULARY:\nUse these terms/spellings if relevant to the text context:\n$vocabulary"
                        } else {
                            basePrompt
                        }
                        
                        resolvedUserPrompt = """
                            $rawText

                            --------------------------------------------------
                            INSTRUCTIONS:
                            1. Correct the grammar and spelling of the text above.
                            2. Maintain the ORIGINAL language. Do NOT translate.
                            3. Output ONLY the corrected text.
                        """.trimIndent()
                    }
                    
                    val chatResponse = chatWithFallback(auth, listOf(
                        Message("system", resolvedSystemPrompt),
                        Message("user", resolvedUserPrompt)
                    ))
                    finalText = chatResponse.choices.firstOrNull()?.message?.content ?: rawText
                    FileLogger.log(this@OverlayService, "API", "Llama Text: $finalText")
                } else {
                    FileLogger.log(this@OverlayService, "API", "Skipping Llama (Disabled in settings)")
                }
                
                // Add trailing space for convenience
                finalText = finalText.trim() + " "

                FileLogger.log(this@OverlayService, "Process", "Final Text: $finalText")
                
                withContext(Dispatchers.Main) {
                   resetUI()
                   
                   val isFocused = checkFocus()
                   if (isFocused) {
                       // Inject
                       val intent = Intent("com.example.voicelistener.ACTION_INJECT_TEXT")
                       intent.setPackage(packageName)
                       intent.putExtra("text", finalText)
                       sendBroadcast(intent)
                       FileLogger.log(this@OverlayService, "Injector", "Broadcast sent (Focus detected).")
                       
                       // Vibrate success
                        val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                             (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
                        } else {
                             @Suppress("DEPRECATION")
                             getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                        }
                        vib.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))

                   } else {
                       // Fallback: Copy to Clipboard
                       val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                       val clip = android.content.ClipData.newPlainText("Voice Text", finalText)
                       clipboard.setPrimaryClip(clip)
                       showTopMessage("In die Zwischenablage kopiert")
                       FileLogger.log(this@OverlayService, "Injector", "Fallback: Copied to clipboard (No Focus).")

                       // Vibrate slightly to indicate copy
                       val vib2 = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
                       } else {
                            @Suppress("DEPRECATION")
                            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                       }
                       vib2.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
                   }
                   
                   // Reset Timer to keep overlay visible for a moment
                   lastShowTime = System.currentTimeMillis()
                   isProcessing = false
                   checkHideOverlay()
                }

            } catch (e: Exception) {
                FileLogger.log(this@OverlayService, "API Error", "Exception: ${e.message}\n${e.stackTraceToString()}")
                withContext(Dispatchers.Main) {
                    showTopMessage("Fehler: ${e.message}")
                    resetUI()
                    isProcessing = false
                    checkHideOverlay()
                }
            }
        }
    }

    // --- SETTINGS AI ---
    private var settingsAIView: View? = null

    private val SETTINGS_AI_SYSTEM_PROMPT = """
Du bist ein Settings-Assistent für die Voice Listener App.

REGEL 1 - BEFEHL: Wenn der User eine Einstellung ändern will, antworte NUR mit einem JSON-Array von Aktionen. Keine Erklärung, kein Text - NUR das JSON-Array.
REGEL 2 - FRAGE: Wenn der User eine Frage stellt (z.B. "Was kann ich ändern?", "Wie mache ich X?", "Hilfe"), antworte mit einer kurzen, hilfreichen Erklärung auf Deutsch. Kein JSON. Erkläre welche Einstellungen es gibt und wie ein Sprachbefehl dafür aussehen würde.

Verfügbare Aktionen (für Befehle):
1. {"action":"set_boolean","key":"<key>","value":true/false}
2. {"action":"set_float","key":"<key>","value":<0.0-2.0>}
3. {"action":"set_int","key":"<key>","value":<number>}
4. {"action":"set_string","key":"<key>","value":"<text>"}
5. {"action":"add_expansion_rule","trigger":"<kürzel>","replacement":"<ersetzung>"}
6. {"action":"remove_expansion_rule","trigger":"<kürzel>"}
7. {"action":"set_color","value":"purple|blue|red|green|black"}

Erlaubte Boolean-Keys:
- llama_enabled (Llama-Korrektur an/aus)
- text_expansion_enabled (Textbausteine an/aus)
- overlay_focus_mode (Auto-Hide: Overlay nur bei Textfeld zeigen)
- overlay_always_hidden (Immer versteckt, nur über Notification steuerbar)
- app_translate_enabled (Übersetzen-Button im Extra-Menü)
- app_clipboard_enabled (Zwischenablage-Button im Extra-Menü)
- app_market_enabled (Marktdaten-Button im Extra-Menü)
- app_askllama_enabled (AskLlama-Button im Extra-Menü)
- app_eqs_context_enabled (EQS-Button im Extra-Menü)
- clipboard_history_enabled (Zwischenablage-Verlauf erfassen)
- logs_enabled (Logging an/aus)

Erlaubte Float-Keys:
- overlay_scale (Button-Größe, 0.5 bis 2.0, z.B. 1.5 = 150%)
- overlay_alpha (Transparenz, 0.2 bis 1.0, z.B. 0.5 = halbtransparent)

Erlaubte Int-Keys:
- market_data_interval (Aktualisierung in Sekunden, 1-60)
- overlay_recording_trigger (0=Doppeltipp startet Aufnahme, 1=LongPress startet Aufnahme)

Erlaubte String-Keys:
- llama_system_prompt (System-Prompt für Llama-Korrektur)
- custom_vocabulary (Wörterbuch: Fachbegriffe, Namen für bessere Erkennung)

Farben für den Button:
- purple, blue, red, green, black

Textbausteine (Expansion Rules):
- Kürzel → Ersetzung (z.B. "aa" → "/")
- Funktions-Ersetzungen: {{DATE}} (Datum), {{TIME}} (Uhrzeit), {{DATETIME}} (Datum+Uhrzeit), {{CLEAR}} (Feld leeren), {{UPPERCASE}} (Großbuchstaben), {{LOWERCASE}} (Kleinbuchstaben), {{COPY_ALL}} (Alles kopieren), {{CUT_ALL}} (Alles ausschneiden)

Beispiele für Befehle:
"Mach den Button größer, 150 Prozent" → [{"action":"set_float","key":"overlay_scale","value":1.5}]
"Füge Textbaustein aa gleich Schrägstrich hinzu" → [{"action":"add_expansion_rule","trigger":"aa","replacement":"/"}]
"Deaktiviere Llama und mach den Button blau" → [{"action":"set_boolean","key":"llama_enabled","value":false},{"action":"set_color","value":"blue"}]
"Füge Textbaustein .d für das Datum hinzu" → [{"action":"add_expansion_rule","trigger":".d","replacement":"{{DATE}}"}]
""".trimIndent()

    private fun showSettingsAIConfirmation(transcribedText: String, auth: String) {
        dismissSettingsAIView()

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            elevation = 24f
            setPadding(32, 24, 32, 24)
        }

        val title = android.widget.TextView(this).apply {
            text = "Settings AI - Befehl prüfen:"
            textSize = 14f
            setTextColor(Color.parseColor("#795548"))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        container.addView(title)

        val editText = android.widget.EditText(this).apply {
            setText(transcribedText)
            textSize = 14f
            setBackgroundResource(android.R.drawable.edit_text)
            setPadding(16, 12, 16, 12)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 }
        }
        container.addView(editText)

        val btnRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 }
        }

        val cancelBtn = android.widget.Button(this).apply {
            text = "Abbrechen"
            textSize = 12f
            setOnClickListener {
                dismissSettingsAIView()
                resetUI()
                isProcessing = false
            }
        }
        btnRow.addView(cancelBtn)

        val spacer = View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(16, 0)
        }
        btnRow.addView(spacer)

        val executeBtn = android.widget.Button(this).apply {
            text = "Ausführen"
            textSize = 12f
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#4CAF50"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                val userText = editText.text.toString().trim()
                dismissSettingsAIView()
                showTopMessage("Verarbeite Einstellung...")
                executeSettingsAI(userText, auth)
            }
        }
        btnRow.addView(executeBtn)

        container.addView(btnRow)

        val params = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.85).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            android.graphics.PixelFormat.TRANSLUCENT
        )
        params.gravity = android.view.Gravity.CENTER

        container.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_OUTSIDE) {
                dismissSettingsAIView()
                resetUI()
                isProcessing = false
                true
            } else false
        }

        makeBackDismissible(container, params) { dismissSettingsAIView(); resetUI(); isProcessing = false }
        windowManager.addView(container, params)
        settingsAIView = container
    }

    private fun dismissSettingsAIView() {
        settingsAIView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            settingsAIView = null
        }
    }

    private fun showSettingsAIHelpResponse(text: String) {
        dismissSettingsAIView()

        val scrollView = android.widget.ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            elevation = 24f
        }
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
        }
        scrollView.addView(container)

        val title = android.widget.TextView(this).apply {
            this.text = "Settings AI - Hilfe"
            textSize = 15f
            setTextColor(Color.parseColor("#795548"))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        container.addView(title)

        val body = android.widget.TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(Color.parseColor("#333333"))
            setLineSpacing(0f, 1.3f)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 }
        }
        container.addView(body)

        val closeBtn = android.widget.Button(this).apply {
            this.text = "Schließen"
            textSize = 12f
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 16
                gravity = android.view.Gravity.END
            }
            setOnClickListener { dismissSettingsAIView() }
        }
        container.addView(closeBtn)

        val dm = resources.displayMetrics
        val params = WindowManager.LayoutParams(
            (dm.widthPixels * 0.85).toInt(),
            (dm.heightPixels * 0.6).coerceAtMost(WindowManager.LayoutParams.WRAP_CONTENT.toDouble()).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            android.graphics.PixelFormat.TRANSLUCENT
        )
        // Use WRAP_CONTENT for height with max constraint via ScrollView
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        params.gravity = android.view.Gravity.CENTER

        scrollView.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_OUTSIDE) {
                dismissSettingsAIView()
                true
            } else false
        }

        // Limit max height
        scrollView.post {
            val maxH = (dm.heightPixels * 0.6).toInt()
            if (scrollView.height > maxH) {
                val lp = scrollView.layoutParams
                lp.height = maxH
                windowManager.updateViewLayout(scrollView, lp)
            }
        }

        makeBackDismissible(scrollView, params) { dismissSettingsAIView() }
        windowManager.addView(scrollView, params)
        settingsAIView = scrollView
    }

    private fun showCriticalChangeConfirmation(responseText: String) {
        dismissSettingsAIView()

        // Parse what will be changed for display
        val criticalLabels = mapOf(
            "llama_system_prompt" to "Llama System-Prompt",
            "custom_vocabulary" to "Wörterbuch"
        )
        val jsonMatch = Regex("\\[.*]", RegexOption.DOT_MATCHES_ALL).find(responseText)
        val changeList = mutableListOf<String>()
        jsonMatch?.value?.let { json ->
            try {
                val actions = org.json.JSONArray(json)
                for (i in 0 until actions.length()) {
                    val action = actions.getJSONObject(i)
                    val key = action.optString("key", "")
                    val label = criticalLabels[key]
                    if (label != null) {
                        val newValue = action.optString("value", "")
                        val preview = if (newValue.length > 80) newValue.take(80) + "..." else newValue
                        changeList.add("$label:\n\"$preview\"")
                    }
                }
            } catch (_: Exception) {}
        }

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            elevation = 24f
            setPadding(32, 24, 32, 24)
        }

        val title = android.widget.TextView(this).apply {
            text = "Kritische Änderung"
            textSize = 16f
            setTextColor(Color.parseColor("#B00020"))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        container.addView(title)

        val warning = android.widget.TextView(this).apply {
            text = "Settings AI will folgende wichtige Einstellungen ändern:"
            textSize = 13f
            setTextColor(Color.parseColor("#333333"))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12 }
        }
        container.addView(warning)

        for (change in changeList) {
            val changeView = android.widget.TextView(this).apply {
                text = change
                textSize = 12f
                setTextColor(Color.parseColor("#795548"))
                setBackgroundColor(Color.parseColor("#FFF3E0"))
                setPadding(16, 8, 16, 8)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 8 }
            }
            container.addView(changeView)
        }

        val btnRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 20 }
        }

        val cancelBtn = android.widget.Button(this).apply {
            text = "Abbrechen"
            textSize = 12f
            setOnClickListener {
                dismissSettingsAIView()
                resetUI()
                isProcessing = false
                showTopMessage("Abgebrochen")
            }
        }
        btnRow.addView(cancelBtn)

        val spacer = View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(16, 0)
        }
        btnRow.addView(spacer)

        val confirmBtn = android.widget.Button(this).apply {
            text = "Trotzdem ändern"
            textSize = 12f
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#B00020"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                dismissSettingsAIView()
                SettingsBackup.createBackup(this@OverlayService, "Settings AI (kritisch)")
                val changes = applySettingsAIActions(responseText)
                resetUI()
                isProcessing = false
                if (changes.isNotEmpty()) {
                    showUndoToast(changes)
                    reloadOverlaySettings()
                } else {
                    showTopMessage("Keine Änderungen erkannt")
                }
            }
        }
        btnRow.addView(confirmBtn)
        container.addView(btnRow)

        val params = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.88).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            android.graphics.PixelFormat.TRANSLUCENT
        )
        params.gravity = android.view.Gravity.CENTER

        container.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_OUTSIDE) {
                dismissSettingsAIView()
                resetUI()
                isProcessing = false
                true
            } else false
        }

        makeBackDismissible(container, params) { dismissSettingsAIView(); resetUI(); isProcessing = false }
        windowManager.addView(container, params)
        settingsAIView = container
    }

    private fun executeSettingsAI(userCommand: String, auth: String) {
        serviceScope.launch {
            try {
                val chatResponse = chatWithFallback(auth, listOf(
                    Message("system", SETTINGS_AI_SYSTEM_PROMPT),
                    Message("user", userCommand)
                ))
                val responseText = chatResponse.choices.firstOrNull()?.message?.content ?: ""
                FileLogger.log(this@OverlayService, "SettingsAI", "Response: $responseText")

                // Check if response contains JSON actions or is a text answer (help/question)
                val hasJson = Regex("\\[.*]", RegexOption.DOT_MATCHES_ALL).containsMatchIn(responseText)

                if (hasJson) {
                    // Check for critical changes that need confirmation
                    val criticalKeys = setOf("llama_system_prompt", "custom_vocabulary")
                    val jsonMatch = Regex("\\[.*]", RegexOption.DOT_MATCHES_ALL).find(responseText)
                    val hasCritical = jsonMatch?.value?.let { json ->
                        try {
                            val actions = org.json.JSONArray(json)
                            (0 until actions.length()).any { i ->
                                val action = actions.getJSONObject(i)
                                action.optString("key", "") in criticalKeys
                            }
                        } catch (_: Exception) { false }
                    } ?: false

                    if (hasCritical) {
                        withContext(Dispatchers.Main) {
                            showCriticalChangeConfirmation(responseText)
                        }
                    } else {
                        SettingsBackup.createBackup(this@OverlayService, "Settings AI")
                        val changes = applySettingsAIActions(responseText)
                        withContext(Dispatchers.Main) {
                            resetUI()
                            isProcessing = false
                            if (changes.isNotEmpty()) {
                                showUndoToast(changes)
                                reloadOverlaySettings()
                            } else {
                                showTopMessage("Keine Änderungen erkannt")
                            }
                        }
                    }
                } else {
                    // Text answer (help/question) - show in overlay
                    withContext(Dispatchers.Main) {
                        resetUI()
                        isProcessing = false
                        showSettingsAIHelpResponse(responseText)
                    }
                }
            } catch (e: Exception) {
                FileLogger.log(this@OverlayService, "SettingsAI", "Error: ${e.message}")
                withContext(Dispatchers.Main) {
                    showTopMessage("Fehler: ${e.message}")
                    resetUI()
                    isProcessing = false
                }
            }
        }
    }

    private fun applySettingsAIActions(responseText: String): List<String> {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val editor = prefs.edit()
        val changes = mutableListOf<String>()

        // Extract JSON array from response (may contain extra text)
        val jsonMatch = Regex("\\[.*]", RegexOption.DOT_MATCHES_ALL).find(responseText)
        val jsonStr = jsonMatch?.value ?: return emptyList()

        try {
            val actions = org.json.JSONArray(jsonStr)
            val allowedBooleans = setOf(
                "llama_enabled", "text_expansion_enabled", "overlay_focus_mode",
                "overlay_always_hidden", "app_translate_enabled", "app_clipboard_enabled",
                "app_market_enabled", "app_askllama_enabled", "app_eqs_context_enabled",
                "clipboard_history_enabled", "logs_enabled"
            )
            val allowedFloats = mapOf(
                "overlay_scale" to (0.5f..2.0f),
                "overlay_alpha" to (0.2f..1.0f)
            )
            val allowedInts = mapOf(
                "market_data_interval" to (1..60),
                "overlay_recording_trigger" to (0..1)
            )
            val allowedStrings = setOf("llama_system_prompt", "custom_vocabulary")
            val colorMap = mapOf(
                "purple" to "#FF6200EE", "blue" to "#2196F3",
                "red" to "#F44336", "green" to "#4CAF50", "black" to "#000000",
                "white" to "#FFFFFF", "silver" to "#C0C0C0", "gold" to "#FFD700",
                "orange" to "#FF9800", "pink" to "#E91E63", "cyan" to "#00BCD4",
                "yellow" to "#FFEB3B"
            )

            for (i in 0 until actions.length()) {
                val action = actions.getJSONObject(i)
                val type = action.optString("action", "")

                when (type) {
                    "set_boolean" -> {
                        val key = action.optString("key", "")
                        if (key in allowedBooleans) {
                            val v = action.optBoolean("value", false)
                            editor.putBoolean(key, v)
                            changes.add("$key = $v")
                            FileLogger.log(this, "SettingsAI", "Set $key = $v")
                        }
                    }
                    "set_float" -> {
                        val key = action.optString("key", "")
                        val range = allowedFloats[key]
                        if (range != null) {
                            val value = action.optDouble("value", 1.0).toFloat().coerceIn(range)
                            editor.putFloat(key, value)
                            changes.add("$key = $value")
                            FileLogger.log(this, "SettingsAI", "Set $key = $value")
                        }
                    }
                    "set_int" -> {
                        val key = action.optString("key", "")
                        val range = allowedInts[key]
                        if (range != null) {
                            val value = action.optInt("value", 1).coerceIn(range)
                            editor.putInt(key, value)
                            changes.add("$key = $value")
                            FileLogger.log(this, "SettingsAI", "Set $key = $value")
                        }
                    }
                    "set_string" -> {
                        val key = action.optString("key", "")
                        if (key in allowedStrings) {
                            val v = action.optString("value", "")
                            editor.putString(key, v)
                            val preview = if (v.length > 30) v.take(30) + "…" else v
                            changes.add("$key = \"$preview\"")
                            FileLogger.log(this, "SettingsAI", "Set $key = ${v.take(30)}")
                        }
                    }
                    "set_color" -> {
                        val colorName = action.optString("value", "").lowercase()
                        val hex = colorMap[colorName]
                        if (hex != null) {
                            editor.putInt("overlay_color", Color.parseColor(hex))
                            changes.add("Farbe = $colorName")
                            FileLogger.log(this, "SettingsAI", "Set color = $colorName")
                        }
                    }
                    "add_expansion_rule" -> {
                        val trigger = action.optString("trigger", "")
                        val replacement = action.optString("replacement", "")
                        if (trigger.isNotEmpty() && replacement.isNotEmpty()) {
                            val rulesJson = prefs.getString("text_expansion_rules", "[]") ?: "[]"
                            val rules = try { org.json.JSONArray(rulesJson) } catch (_: Exception) { org.json.JSONArray() }
                            rules.put(org.json.JSONObject().put("trigger", trigger).put("replacement", replacement))
                            editor.putString("text_expansion_rules", rules.toString())
                            changes.add("+ Regel: $trigger → $replacement")
                            FileLogger.log(this, "SettingsAI", "Added rule: $trigger -> $replacement")
                        }
                    }
                    "remove_expansion_rule" -> {
                        val trigger = action.optString("trigger", "")
                        if (trigger.isNotEmpty()) {
                            val rulesJson = prefs.getString("text_expansion_rules", "[]") ?: "[]"
                            val rules = try { org.json.JSONArray(rulesJson) } catch (_: Exception) { org.json.JSONArray() }
                            val newRules = org.json.JSONArray()
                            for (j in 0 until rules.length()) {
                                val rule = rules.optJSONObject(j) ?: continue
                                if (rule.optString("trigger") != trigger) {
                                    newRules.put(rule)
                                }
                            }
                            editor.putString("text_expansion_rules", newRules.toString())
                            changes.add("- Regel: $trigger")
                            FileLogger.log(this, "SettingsAI", "Removed rule: $trigger")
                        }
                    }
                }
            }

            if (changes.isNotEmpty()) editor.apply()
        } catch (e: Exception) {
            FileLogger.log(this, "SettingsAI", "Parse error: ${e.message}")
        }
        return changes
    }

    private fun dismissUndoToast() {
        undoToastView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            undoToastView = null
        }
    }

    private fun showUndoToast(changes: List<String>) {
        dismissUndoToast()

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val shape = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 24f
                setColor(Color.parseColor("#EE333333"))
            }
            background = shape
            setPadding(32, 20, 16, 20)
        }

        val textPart = android.widget.TextView(this).apply {
            text = "${changes.size} Änderung(en):\n${changes.joinToString("\n")}"
            setTextColor(Color.WHITE)
            textSize = 12f
            maxLines = 6
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        container.addView(textPart)

        val undoBtn = android.widget.TextView(this).apply {
            text = "UNDO"
            setTextColor(Color.parseColor("#BB86FC"))
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(32, 16, 16, 16)
        }
        container.addView(undoBtn)

        val toastParams = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        )
        toastParams.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
        toastParams.y = 120

        windowManager.addView(container, toastParams)
        undoToastView = container

        // Handle touches via onTouch to work with FLAG_NOT_FOCUSABLE
        container.setOnTouchListener { v, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                // Check if touch is on UNDO button area (right side)
                val undoLeft = undoBtn.left
                if (event.x >= undoLeft) {
                    // UNDO pressed
                    dismissUndoToast()
                    if (SettingsBackup.restoreBackup(this@OverlayService, 0)) {
                        showTopMessage("Rückgängig gemacht")
                        reloadOverlaySettings()
                    }
                } else {
                    // Tapped on text - just dismiss
                    dismissUndoToast()
                }
            }
            true
        }

        // Auto-dismiss after 10 seconds
        container.postDelayed({
            if (undoToastView == container) dismissUndoToast()
        }, 10000)
    }

    private fun reloadOverlaySettings() {
        val intent = Intent(this, OverlayService::class.java)
        intent.action = "ACTION_UPDATE_SETTINGS"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun resetUI() {
        overlayButton?.clearColorFilter()
        // overlayButton?.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FF6200EE")) 
        // Use Drawable instead of Tint for custom shape
        overlayButton?.setBackgroundResource(R.drawable.button_bg_normal)
        overlayButton?.setImageResource(R.drawable.ic_mic) // Restore icon if removed (though we didn't remove it)
    }

    // Duplicate onDestroy removed
}
