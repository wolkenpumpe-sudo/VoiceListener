package com.example.voicelistener.services

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class VoiceAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "VoiceAccessibility"
        var instance: VoiceAccessibilityService? = null
            private set

        // Function replacement tokens
        const val FUNC_DATE = "{{DATE}}"
        const val FUNC_TIME = "{{TIME}}"
        const val FUNC_DATETIME = "{{DATETIME}}"
        const val FUNC_CLEAR = "{{CLEAR}}"
        const val FUNC_UPPERCASE = "{{UPPERCASE}}"
        const val FUNC_LOWERCASE = "{{LOWERCASE}}"
        const val FUNC_COPY_ALL = "{{COPY_ALL}}"
        const val FUNC_CUT_ALL = "{{CUT_ALL}}"
        const val FUNC_TASKER_PREFIX = "{{TASKER:"

        val FUNCTION_REPLACEMENTS = mapOf(
            FUNC_DATE to "Datum einfügen",
            FUNC_TIME to "Uhrzeit einfügen",
            FUNC_DATETIME to "Datum + Uhrzeit",
            FUNC_CLEAR to "Feld leeren",
            FUNC_UPPERCASE to "GROSSBUCHSTABEN",
            FUNC_LOWERCASE to "kleinbuchstaben",
            FUNC_COPY_ALL to "Alles kopieren",
            FUNC_CUT_ALL to "Alles ausschneiden"
        )

        /** Returns function replacements + dynamic Tasker task entries */
        fun getAllFunctionReplacements(context: android.content.Context): Map<String, String> {
            val all = LinkedHashMap(FUNCTION_REPLACEMENTS)
            val tasks = com.example.voicelistener.TaskerHelper.getTasks(context)
            for (task in tasks) {
                all["${FUNC_TASKER_PREFIX}${task.id}}}"] = "Tasker: ${task.name}"
            }
            return all
        }
    }
    
    private var lastFocusState: Boolean? = null
    // Shared History Trigger
    val currentHistory: List<String>
        get() = clipboardHistory.toList()

    private val intentReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action == "com.example.voicelistener.ACTION_INJECT_TEXT") {
                val text = intent.getStringExtra("text")
                if (text != null) {
                    injectText(text)
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.e("OverlayDEBUG", ">>> ACCESSIBILITY SERVICE CONNECTED <<<")
        Log.d(TAG, "Service connected")
        
        // Register Receiver
        val filter = android.content.IntentFilter("com.example.voicelistener.ACTION_INJECT_TEXT")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
             registerReceiver(intentReceiver, filter, android.content.Context.RECEIVER_EXPORTED)
        } else {
             registerReceiver(intentReceiver, filter)
        }
        
        setupClipboardListener()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // 1. TEXT EXPANSION (Espanso-like) - skip in own app
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED &&
            event.packageName?.toString() != packageName) {
            handleTextExpansion(event)
        }

        // 2. FOCUS MODE LOGIC (Only if enabled)
        val prefs = getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
        val focusModeEnabled = prefs.getBoolean("overlay_focus_mode", false)
        
        if (!focusModeEnabled) return
        
        // Broadcast Focus Changes ONLY on actual state change
        // Listen to more event types for better field detection
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {

            val hasFocusedInput = isInputFocused()
            if (hasFocusedInput != lastFocusState) {
                lastFocusState = hasFocusedInput
                val intent = Intent("com.example.voicelistener.ACTION_FOCUS_CHANGED")
                intent.setPackage(packageName)
                intent.putExtra("has_focus", hasFocusedInput)
                sendBroadcast(intent)
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
        instance = null
    }
    
    override fun onDestroy() {
        super.onDestroy()
        instance = null
        try {
            unregisterReceiver(intentReceiver)
        } catch (e: Exception) {
            // Ignore if not registered
        }
        // Handler cleanup removed as Handler is removed
        if (clipboardListener != null) {
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.removePrimaryClipChangedListener(clipboardListener)
        }
    }

    fun getCurrentInputText(): String? {
        val root = rootInActiveWindow ?: return null
        var focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused == null || !isEditableNode(focused)) {
            focused = findFocusedEditable(root)
        }
        if (focused == null) return null
        return focused.text?.toString()
    }

    fun replaceCurrentInputText(newText: String): Boolean {
        val root = rootInActiveWindow ?: return false
        var focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused == null || !isEditableNode(focused)) {
            focused = findFocusedEditable(root)
        }
        if (focused == null) return false
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        val success = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (success) {
            // Move cursor to end
            val cursorArgs = Bundle()
            cursorArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newText.length)
            cursorArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newText.length)
            focused.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, cursorArgs)
        }
        return success
    }

    fun isInputFocused(): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && isEditableNode(focused)) return true
        // Fallback: search the entire tree for a focused editable node
        return findFocusedEditable(root) != null
    }

    private fun isEditableNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isEditable) return true
        // Check className for known input types
        val className = node.className?.toString() ?: ""
        val editableClasses = listOf(
            "android.widget.EditText",
            "android.widget.AutoCompleteTextView",
            "android.widget.MultiAutoCompleteTextView",
            "android.widget.SearchView",
            "android.inputmethodservice.ExtractEditText",
            "android.webkit.WebView",
            "org.chromium.content.browser.ContentViewCore"
        )
        if (editableClasses.any { className.contains(it, ignoreCase = true) }) return true
        // Check if it accepts text input (inputType set)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            // On Android 11+ we can check inputType
        }
        // Check for text-related actions (SET_TEXT or PASTE support indicates editability)
        val actions = node.actionList
        if (actions != null) {
            for (action in actions) {
                if (action.id == AccessibilityNodeInfo.ACTION_SET_TEXT ||
                    action.id == AccessibilityNodeInfo.ACTION_PASTE) {
                    return true
                }
            }
        }
        return false
    }

    private fun findFocusedEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused && isEditableNode(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFocusedEditable(child)
            if (result != null) return result
        }
        return null
    }

    fun injectText(text: String): Boolean {
        com.example.voicelistener.utils.FileLogger.log(this, TAG, "--- INJECTION START ---")
        com.example.voicelistener.utils.FileLogger.log(this, TAG, "Input: '$text'")

        val root = rootInActiveWindow
        if (root == null) {
             com.example.voicelistener.utils.FileLogger.log(this, TAG, "Root window is null")
             return false
        }

        // Find the target node - try multiple strategies
        var focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused == null || !isEditableNode(focused)) {
            // Fallback: search tree for any focused editable node
            focused = findFocusedEditable(root)
        }
        if (focused == null) {
            // Fallback: search for any editable node (even unfocused)
            focused = findAnyEditable(root)
        }

        if (focused == null) {
            com.example.voicelistener.utils.FileLogger.log(this, TAG, "No editable field found at all")
            return false
        }

        com.example.voicelistener.utils.FileLogger.log(this, TAG, "Target node: ${focused.className}, editable=${focused.isEditable}, focused=${focused.isFocused}")

        // Ensure the node is focused first
        if (!focused.isFocused) {
            focused.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            focused.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            // Small delay to let focus settle
            Thread.sleep(100)
        }

        // --- STRATEGY 1: PASTE (Primary) ---
        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Voice Input", text)
        clipboard.setPrimaryClip(clip)

        val pasteSuccess = focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        if (pasteSuccess) {
            com.example.voicelistener.utils.FileLogger.log(this, TAG, "Injection (PASTE) success!")
            return true
        }

        com.example.voicelistener.utils.FileLogger.log(this, TAG, "Injection (PASTE) failed. Trying SET_TEXT...")

        // --- STRATEGY 2: SET TEXT ---
        val currentText = focused.text?.toString() ?: ""
        val hintText = focused.hintText?.toString()

        var isPlaceholder = false
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            if (focused.isShowingHintText) isPlaceholder = true
        }
        if (!isPlaceholder && hintText != null) {
            if (currentText.trim().equals(hintText.trim(), ignoreCase = true)) isPlaceholder = true
        }
        if (!isPlaceholder) {
            val known = listOf("nachricht", "broadcast", "nachricht eingeben", "message", "type a message", "suchen", "search", "eingeben", "schreiben")
            if (known.any { it.equals(currentText.trim(), ignoreCase = true) }) isPlaceholder = true
        }

        var finalCurrentText = if (isPlaceholder) "" else currentText
        val spacer = if (finalCurrentText.isNotEmpty() && !finalCurrentText.endsWith(" ")) " " else ""
        val newText = "$finalCurrentText$spacer$text"

        val arguments = Bundle()
        arguments.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            newText
        )
        val setSuccess = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        if (setSuccess) {
            com.example.voicelistener.utils.FileLogger.log(this, TAG, "Injection (SET_TEXT) success!")
            return true
        }

        com.example.voicelistener.utils.FileLogger.log(this, TAG, "Injection (SET_TEXT) failed. Trying dispatchGesture tap...")

        // --- STRATEGY 4: Click + Paste via gesture ---
        // Last resort: tap the field, then try paste again
        try {
            val rect = android.graphics.Rect()
            focused.getBoundsInScreen(rect)
            if (rect.width() > 0 && rect.height() > 0) {
                val centerX = rect.centerX().toFloat()
                val centerY = rect.centerY().toFloat()
                val path = android.graphics.Path()
                path.moveTo(centerX, centerY)
                val gesture = android.accessibilityservice.GestureDescription.Builder()
                    .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 50))
                    .build()
                dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                        // After tap, try paste again
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            val retryRoot = rootInActiveWindow ?: return@postDelayed
                            val retryFocused = retryRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                            if (retryFocused != null) {
                                val retryPaste = retryFocused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                                com.example.voicelistener.utils.FileLogger.log(this@VoiceAccessibilityService, TAG, "Injection (TAP+PASTE retry) result: $retryPaste")
                            }
                        }, 200)
                    }
                }, null)
                com.example.voicelistener.utils.FileLogger.log(this, TAG, "Injection (TAP+PASTE) dispatched at ($centerX, $centerY)")
                return true // Async - we assume it might work
            }
        } catch (e: Exception) {
            com.example.voicelistener.utils.FileLogger.log(this, TAG, "Gesture dispatch error: ${e.message}")
        }

        com.example.voicelistener.utils.FileLogger.log(this, TAG, "All injection strategies exhausted")
        return false
    }

    private fun findAnyEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Find the first editable node in the tree (for cases where nothing reports focus)
        if (isEditableNode(node) && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findAnyEditable(child)
            if (result != null) return result
        }
        return null
    }

    // --- TEXT EXPANSION LOGIC ---
    private var isExpanding = false // Prevent recursive triggers
    private var lastExpansionTime = 0L
    private var lastExpandedFullText = "" // full text after expansion
    private var lastOriginalFullText = "" // full text before expansion (with trigger)
    private var undoneText: String? = null // skip re-triggering while text matches this

    private fun handleTextExpansion(event: AccessibilityEvent) {
        if (isExpanding) return

        val prefs = getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
        if (!prefs.getBoolean("text_expansion_enabled", false)) return

        val text = event.text?.firstOrNull()?.toString() ?: return

        // Check for undo BEFORE empty check (deleting single-char replacement gives empty text)
        if (lastExpandedFullText.isNotEmpty() &&
            System.currentTimeMillis() - lastExpansionTime < 2500 &&
            text == lastExpandedFullText.dropLast(1)) {

            val source = event.source ?: return
            isExpanding = true

            val arguments = android.os.Bundle()
            arguments.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                lastOriginalFullText
            )
            source.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)

            val cursorArgs = android.os.Bundle()
            cursorArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, lastOriginalFullText.length)
            cursorArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, lastOriginalFullText.length)
            source.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, cursorArgs)

            undoneText = lastOriginalFullText
            lastExpandedFullText = ""
            lastOriginalFullText = ""

            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                isExpanding = false
            }, 100)

            com.example.voicelistener.utils.FileLogger.log(this, TAG, "Expansion undone")
            return
        }

        if (text.isEmpty()) return

        // Skip re-triggering on undone text
        if (undoneText != null) {
            if (text == undoneText) return
            // User continued typing or changed text -> re-enable expansion
            undoneText = null
        }

        val rules = loadExpansionRules()
        if (rules.isEmpty()) return

        // Collect all matching rules for the current text
        val matches = mutableListOf<Triple<String, String, Boolean>>() // trigger, replacement, caseSensitive
        for (rule in rules) {
            if (rule.trigger.isEmpty()) continue
            val isMatch = if (rule.caseSensitive) {
                text.endsWith(rule.trigger)
            } else {
                text.lowercase().endsWith(rule.trigger.lowercase())
            }
            if (isMatch) {
                // Use the actual trigger text from the input (preserving case) for replacement
                val actualTrigger = text.substring(text.length - rule.trigger.length)
                matches.add(Triple(actualTrigger, rule.replacement, rule.caseSensitive))
            }
        }

        if (matches.isEmpty()) return

        val source = event.source ?: return

        if (matches.size == 1) {
            applyExpansion(source, text, matches[0].first, matches[0].second)
        } else {
            val pairMatches = matches.map { it.first to it.second }
            showExpansionPicker(source, text, pairMatches)
        }
    }

    private fun applyExpansion(source: AccessibilityNodeInfo, fullText: String, trigger: String, replacement: String) {
        isExpanding = true
        lastOriginalFullText = fullText

        val textBeforeTrigger = fullText.substring(0, fullText.length - trigger.length)
        val resolvedReplacement = resolveReplacement(replacement, textBeforeTrigger)
        val newText: String

        when (replacement) {
            FUNC_CLEAR -> {
                newText = ""
            }
            FUNC_UPPERCASE -> {
                newText = textBeforeTrigger.uppercase()
            }
            FUNC_LOWERCASE -> {
                newText = textBeforeTrigger.lowercase()
            }
            FUNC_COPY_ALL -> {
                // Remove trigger, copy all text to clipboard
                newText = textBeforeTrigger
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("copied", textBeforeTrigger))
            }
            FUNC_CUT_ALL -> {
                // Copy all text to clipboard, then clear field
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("cut", textBeforeTrigger))
                newText = ""
            }
            else -> if (replacement.startsWith(FUNC_TASKER_PREFIX) && replacement.endsWith("}}")) {
                // Execute Tasker task, remove trigger from text
                val taskId = replacement.removePrefix(FUNC_TASKER_PREFIX).removeSuffix("}}")
                val task = com.example.voicelistener.TaskerHelper.getTasks(this).find { it.id == taskId }
                if (task != null) {
                    com.example.voicelistener.TaskerHelper.executeTask(this, task)
                    com.example.voicelistener.utils.FileLogger.log(this, TAG, "Tasker task '${task.name}' triggered via expansion")
                }
                newText = textBeforeTrigger
            } else {
                newText = textBeforeTrigger + resolvedReplacement
            }
        }

        lastExpandedFullText = newText
        lastExpansionTime = System.currentTimeMillis()

        val arguments = android.os.Bundle()
        arguments.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            newText
        )
        source.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)

        val cursorArgs = android.os.Bundle()
        cursorArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newText.length)
        cursorArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newText.length)
        source.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, cursorArgs)

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            isExpanding = false
        }, 100)

        com.example.voicelistener.utils.FileLogger.log(this, TAG, "Expanded '$trigger' -> '$replacement' (resolved: ${newText.takeLast(30)})")
    }

    private fun resolveReplacement(replacement: String, textBeforeTrigger: String): String {
        val now = java.util.Calendar.getInstance()
        val dateFmt = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.GERMAN)
        val timeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.GERMAN)

        return when (replacement) {
            FUNC_DATE -> dateFmt.format(now.time)
            FUNC_TIME -> timeFmt.format(now.time)
            FUNC_DATETIME -> "${dateFmt.format(now.time)} ${timeFmt.format(now.time)}"
            FUNC_CLEAR, FUNC_UPPERCASE, FUNC_LOWERCASE, FUNC_COPY_ALL, FUNC_CUT_ALL -> "" // handled in applyExpansion
            else -> if (replacement.startsWith(FUNC_TASKER_PREFIX)) "" else replacement
        }
    }

    private var pickerView: android.view.View? = null

    private fun showExpansionPicker(source: AccessibilityNodeInfo, fullText: String, matches: List<Pair<String, String>>) {
        // Prevent re-triggering while picker is open
        isExpanding = true

        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                dismissExpansionPicker()

                val wm = getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager

                val container = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setBackgroundColor(android.graphics.Color.WHITE)
                    elevation = 16f
                    setPadding(8, 8, 8, 8)
                }

                // Add a small label
                val label = android.widget.TextView(this).apply {
                    text = "Ersetzen mit:"
                    textSize = 11f
                    setTextColor(android.graphics.Color.GRAY)
                    setPadding(16, 4, 16, 4)
                }
                container.addView(label)

                for ((trigger, replacement) in matches) {
                    val btn = android.widget.TextView(this).apply {
                        text = replacement
                        textSize = 15f
                        setPadding(24, 16, 24, 16)
                        setTextColor(android.graphics.Color.BLACK)
                        setBackgroundResource(android.R.drawable.list_selector_background)
                        setOnClickListener {
                            applyExpansion(source, fullText, trigger, replacement)
                            dismissExpansionPicker()
                        }
                    }
                    container.addView(btn)
                }

                // Cancel option
                val cancelBtn = android.widget.TextView(this).apply {
                    text = "Abbrechen"
                    textSize = 13f
                    setPadding(24, 12, 24, 12)
                    setTextColor(android.graphics.Color.parseColor("#888888"))
                    gravity = android.view.Gravity.CENTER
                    setOnClickListener {
                        dismissExpansionPicker()
                    }
                }
                container.addView(cancelBtn)

                val params = android.view.WindowManager.LayoutParams(
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                    android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    android.graphics.PixelFormat.TRANSLUCENT
                )
                params.gravity = android.view.Gravity.CENTER

                // Dismiss on outside touch
                container.setOnTouchListener { _, event ->
                    if (event.action == android.view.MotionEvent.ACTION_OUTSIDE) {
                        dismissExpansionPicker()
                        true
                    } else false
                }

                // Back button dismisses picker
                params.flags = (params.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()) or
                    android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                container.isFocusableInTouchMode = true
                container.setOnKeyListener { _, keyCode, event ->
                    if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
                        dismissExpansionPicker()
                        true
                    } else false
                }
                wm.addView(container, params)
                pickerView = container

            } catch (e: Exception) {
                com.example.voicelistener.utils.FileLogger.log(this, TAG, "Picker error: ${e.message}")
                isExpanding = false
            }
        }
    }

    private fun dismissExpansionPicker() {
        pickerView?.let {
            try {
                val wm = getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
                wm.removeView(it)
            } catch (_: Exception) {}
            pickerView = null
        }
        isExpanding = false
    }

    data class ExpansionRule(val trigger: String, val replacement: String, val caseSensitive: Boolean)

    private fun loadExpansionRules(): List<ExpansionRule> {
        val prefs = getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
        val json = prefs.getString("text_expansion_rules", "[]") ?: "[]"
        val result = mutableListOf<ExpansionRule>()
        try {
            val arr = org.json.JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                result.add(ExpansionRule(
                    obj.getString("trigger"),
                    obj.getString("replacement"),
                    obj.optBoolean("case_sensitive", false)
                ))
            }
        } catch (_: Exception) {}
        return result
    }

    // --- CLIPBOARD LOGIC ---
    private val clipboardHistory = mutableListOf<String>()
    private var clipboardListener: android.content.ClipboardManager.OnPrimaryClipChangedListener? = null

    private fun setupClipboardListener() {
        // Hybrid monitoring removed to prevent keyboard flicker.
        // We now rely on explicit triggers when the user interacts with the UI.
        loadClipboardHistory()
    }

    fun checkClipboard() {
        // Check Preference FIRST
        val prefs = getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
        if (!prefs.getBoolean("clipboard_history_enabled", true)) return

        try {
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            // optimization removed: if (!clipboard.hasPrimaryClip()) return 

            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val item = clip.getItemAt(0)
                val text = item.coerceToText(this)?.toString()
                
                if (!text.isNullOrBlank()) {
                    if (clipboardHistory.isEmpty() || clipboardHistory[0] != text) {
                        addToClipboardHistory(text)
                    }
                }
            }
        } catch (e: Exception) {
            com.example.voicelistener.utils.FileLogger.log(this, TAG, "Poll Error: ${e.message}")
        }
    }

    fun addToClipboardHistory(text: String) {
        if (text.isBlank()) return
        
        if (clipboardHistory.contains(text)) {
            clipboardHistory.remove(text)
        }
        clipboardHistory.add(0, text)
        if (clipboardHistory.size > 50) {
            clipboardHistory.removeAt(clipboardHistory.size - 1)
        }
        com.example.voicelistener.utils.FileLogger.log(this, TAG, "NEW CLIP & History Update: ${text.take(20)}...")
        saveClipboardHistory()
        
        // USER REQUEST: Toast confirmation
        val toast = android.widget.Toast.makeText(this, "📋 Kopiert: ${text.take(15)}...", android.widget.Toast.LENGTH_SHORT)
        toast.setGravity(android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL, 0, 150)
        toast.show()
        
        // Notify UI
        val intent = Intent("com.example.voicelistener.ACTION_CLIPBOARD_HISTORY_UPDATED")
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    fun removeFromClipboardHistory(text: String) {
        if (clipboardHistory.contains(text)) {
            clipboardHistory.remove(text)
            saveClipboardHistory()
            com.example.voicelistener.utils.FileLogger.log(this, TAG, "CLIP REMOVED: ${text.take(20)}...")
        }
    }

    private fun saveClipboardHistory() {
        val prefs = getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
        val json = org.json.JSONArray(clipboardHistory).toString()
        prefs.edit().putString("clipboard_history", json).apply()
    }

    private fun loadClipboardHistory() {
        val prefs = getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
        val jsonStr = prefs.getString("clipboard_history", "[]")
        try {
            val json = org.json.JSONArray(jsonStr)
            clipboardHistory.clear()
            for (i in 0 until json.length()) {
                clipboardHistory.add(json.getString(i))
            }
        } catch (e: Exception) {
            com.example.voicelistener.utils.FileLogger.log(this, TAG, "Error loading history: ${e.message}")
        }
        com.example.voicelistener.utils.FileLogger.log(this, TAG, "Loaded History: Size=${clipboardHistory.size} Content=[${clipboardHistory.joinToString(",")}]")
    }
}
