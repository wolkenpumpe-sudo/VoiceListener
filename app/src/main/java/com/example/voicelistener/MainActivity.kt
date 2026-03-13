package com.example.voicelistener

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.RadioGroup
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.voicelistener.services.OverlayService
import com.google.android.material.textfield.TextInputEditText
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.ScrollView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import android.view.ViewGroup
import android.view.LayoutInflater
import java.util.Collections

class MainActivity : AppCompatActivity() {

    private fun sendUpdateIntent() {
        val intent = Intent(this, com.example.voicelistener.services.OverlayService::class.java)
        intent.action = "ACTION_UPDATE_SETTINGS"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private val exportSettingsLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri?.let { writeExportToUri(it) }
        }

    private val importSettingsLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { readImportFromUri(it) }
        }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
            if (audioGranted) {
                checkOverlayPermission()
            } else {
                Toast.makeText(this, "Audio permission needed", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            onCreateInner(savedInstanceState)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "FATAL onCreate error", e)
            android.widget.Toast.makeText(this, "Fehler: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun onCreateInner(savedInstanceState: Bundle?) {
        setContentView(R.layout.activity_main)

        checkPermissions()

        // Check Accessibility once on startup
        if (!isAccessibilityServiceEnabled()) {
            checkAccessibilityPermission()
        }

        val helpBtn = findViewById<Button>(R.id.helpButton)
        helpBtn.setOnClickListener {
            startActivity(Intent(this, HelpActivity::class.java))
        }
        
        val apiKeyInput = findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.apiKeyInput)
        val eqsServerUrlInput = findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.eqsServerUrlInput)
        val saveButton = findViewById<Button>(R.id.saveButton)

        val promptInput = findViewById<android.widget.EditText>(R.id.promptInput)
        val vocabInput = findViewById<android.widget.EditText>(R.id.vocabularyInput)
        val resetPromptBtn = findViewById<Button>(R.id.resetPromptButton)

        // Allow scrolling inside EditTexts within the outer ScrollView
        val scrollTouchListener = View.OnTouchListener { v, event ->
            if (v.hasFocus()) {
                v.parent.requestDisallowInterceptTouchEvent(true)
                if (event.action == android.view.MotionEvent.ACTION_UP) {
                    v.parent.requestDisallowInterceptTouchEvent(false)
                }
            }
            false
        }
        promptInput.setOnTouchListener(scrollTouchListener)
        vocabInput.setOnTouchListener(scrollTouchListener)

        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        apiKeyInput.setText(prefs.getString("groq_api_key", ""))
        eqsServerUrlInput.setText(prefs.getString("eqs_server_url", ""))

        // LLM Model Spinner
        val llmModels = arrayOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant", "qwen/qwen3-32b")
        val llmModelSpinner = findViewById<android.widget.Spinner>(R.id.llmModelSpinner)
        llmModelSpinner.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, llmModels)
        val savedModel = prefs.getString("llm_model", llmModels[0]) ?: llmModels[0]
        llmModelSpinner.setSelection(llmModels.indexOf(savedModel).coerceAtLeast(0))
        llmModelSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long) {
                prefs.edit().putString("llm_model", llmModels[pos]).apply()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        
        // Load Prompt
        val defaultPrompt = OverlayService.DEFAULT_PROMPT
        val savedPrompt = prefs.getString("llama_system_prompt", defaultPrompt)
        promptInput.setText(savedPrompt)

        val savedVocab = prefs.getString("custom_vocabulary", "")
        vocabInput.setText(savedVocab)

        resetPromptBtn.setOnClickListener {
            promptInput.setText(defaultPrompt)
            Toast.makeText(this, "Prompt zurückgesetzt (Speichern nicht vergessen!)", Toast.LENGTH_SHORT).show()
        }

        // Llama Toggle
        val llamaCheck = findViewById<CheckBox>(R.id.llamaEnabledCheckBox)
        llamaCheck.isChecked = prefs.getBoolean("llama_enabled", true)
        llamaCheck.setOnCheckedChangeListener { _, isChecked ->
             prefs.edit().putBoolean("llama_enabled", isChecked).apply()
        }

        // --- Radial Menu Config ---
        setupRadialMenuConfig(prefs)

        // --- Extra Apps Setup ---
        val appEqsContextCheck = findViewById<CheckBox>(R.id.appEqsContextCheckBox)
        val marketKeysInput = findViewById<EditText>(R.id.marketKeysInput)
        val marketIntervalInput = findViewById<EditText>(R.id.marketIntervalInput)
        val marketKeysSelectButton = findViewById<Button>(R.id.marketKeysSelectButton)
        val marketKeysDisplay = findViewById<TextView>(R.id.marketKeysDisplay)

        appEqsContextCheck.isChecked = prefs.getBoolean("app_eqs_context_enabled", true)
        marketKeysInput.setText(prefs.getString("market_data_keys", "US500FU, USTECFU, DE40FU"))
        marketIntervalInput.setText(prefs.getInt("market_data_interval", 1).toString())
        updateMarketKeysDisplay(marketKeysDisplay, prefs)

        // Market minimized values spinner (1-4)
        val marketMinValuesSpinner = findViewById<android.widget.Spinner>(R.id.marketMinValuesSpinner)
        val minValuesOptions = arrayOf("1 Wert", "2 Werte", "3 Werte", "4 Werte")
        marketMinValuesSpinner.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, minValuesOptions)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        marketMinValuesSpinner.setSelection(prefs.getInt("market_min_values", 1) - 1)
        marketMinValuesSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                prefs.edit().putInt("market_min_values", pos + 1).apply()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // Market notification checkbox
        val marketNotificationCheck = findViewById<CheckBox>(R.id.marketNotificationCheckBox)
        marketNotificationCheck.isChecked = prefs.getBoolean("market_notification_enabled", false)
        marketNotificationCheck.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("market_notification_enabled", isChecked).apply()
        }

        marketKeysSelectButton.setOnClickListener {
            showMarketKeysDialog(marketKeysDisplay, prefs)
        }

        appEqsContextCheck.setOnCheckedChangeListener { _, isChecked -> 
            prefs.edit().putBoolean("app_eqs_context_enabled", isChecked).apply()
            val state = if (isChecked) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            packageManager.setComponentEnabledSetting(
                ComponentName(this, EqsProcessTextActivity::class.java),
                state,
                PackageManager.DONT_KILL_APP
            )
        }



        // --- Interaction Mode Setup ---
        val modeGroup = findViewById<RadioGroup>(R.id.interactionModeGroup)
        val modeDoubleTap = findViewById<RadioButton>(R.id.modeDoubleTap)
        val modeLongPress = findViewById<RadioButton>(R.id.modeLongPress)
        
        // Load Mode (0 = Double Tap, 1 = Long Press)
        val savedTrigger = prefs.getInt("overlay_recording_trigger", 0)
        if (savedTrigger == 1) {
            modeLongPress.isChecked = true
        } else {
            modeDoubleTap.isChecked = true
        }

        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            val newTrigger = if (checkedId == R.id.modeLongPress) 1 else 0
            prefs.edit().putInt("overlay_recording_trigger", newTrigger).apply()
        }

        // --- Focus Mode Setup ---
        val focusModeCheck = findViewById<CheckBox>(R.id.focusModeCheckBox)
        focusModeCheck.isChecked = prefs.getBoolean("overlay_focus_mode", false)

        // --- Always Hidden Setup ---
        val alwaysHiddenCheck = findViewById<CheckBox>(R.id.alwaysHiddenCheckBox)
        alwaysHiddenCheck.isChecked = prefs.getBoolean("overlay_always_hidden", false)

        // Cross-sync: focus mode off -> also clear always hidden
        focusModeCheck.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("overlay_focus_mode", isChecked)
                .putBoolean("overlay_always_hidden", false).apply()
            alwaysHiddenCheck.isChecked = false
            sendUpdateIntent()
        }

        // Cross-sync: always hidden on -> keep focus mode, but notify service
        alwaysHiddenCheck.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("overlay_always_hidden", isChecked).apply()
            sendUpdateIntent()
        }

        // --- Swipe Gesture Settings ---
        val actionEntries = GestureManager.ACTION_LABELS.entries.toList()
        val actionLabels = actionEntries.map { it.value }
        val actionKeys = actionEntries.map { it.key }

        fun setupSwipeSpinner(spinnerId: Int, prefKey: String, defaultAction: String) {
            val spinner = findViewById<Spinner>(spinnerId)
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, actionLabels)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinner.adapter = adapter

            val currentAction = prefs.getString(prefKey, defaultAction) ?: defaultAction
            val index = actionKeys.indexOf(currentAction)
            if (index >= 0) spinner.setSelection(index)

            // Use tag to skip initial onItemSelected trigger
            spinner.tag = "init"
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    if (spinner.tag == "init") { spinner.tag = null; return }
                    prefs.edit().putString(prefKey, actionKeys[pos]).apply()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

        setupSwipeSpinner(R.id.spinnerSwipeUp, "swipe_up_action", "show_volume")
        setupSwipeSpinner(R.id.spinnerSwipeDown, "swipe_down_action", "toggle_mute")
        setupSwipeSpinner(R.id.spinnerSwipeLeft, "swipe_left_action", "show_notifications")
        setupSwipeSpinner(R.id.spinnerSwipeRight, "swipe_right_action", "media_play_pause")

        findViewById<Button>(R.id.btnGestureRecord).setOnClickListener {
            startActivity(Intent(this, GestureRecordActivity::class.java))
        }

        // --- Color Setup ---
        val colorGroup = findViewById<RadioGroup>(R.id.colorGroup)
        
        // Map Colors to IDs
        val colorMap = mapOf(
            android.graphics.Color.parseColor("#FF6200EE") to R.id.colorPurple,
            android.graphics.Color.parseColor("#2196F3") to R.id.colorBlue,
            android.graphics.Color.parseColor("#F44336") to R.id.colorRed,
            android.graphics.Color.parseColor("#4CAF50") to R.id.colorGreen,
            android.graphics.Color.parseColor("#000000") to R.id.colorBlack,
            android.graphics.Color.parseColor("#FFFFFF") to R.id.colorWhite,
            android.graphics.Color.parseColor("#C0C0C0") to R.id.colorSilver,
            android.graphics.Color.parseColor("#FFD700") to R.id.colorGold,
            android.graphics.Color.parseColor("#FF9800") to R.id.colorOrange,
            android.graphics.Color.parseColor("#E91E63") to R.id.colorPink,
            android.graphics.Color.parseColor("#00BCD4") to R.id.colorCyan,
            android.graphics.Color.parseColor("#FFEB3B") to R.id.colorYellow
        )
        
        val idToColorMap = colorMap.entries.associate { (k, v) -> v to k }

        val savedColor = prefs.getInt("overlay_color", android.graphics.Color.parseColor("#FF6200EE"))
        colorGroup.check(colorMap[savedColor] ?: R.id.colorPurple)

        colorGroup.setOnCheckedChangeListener { _, checkedId ->
            val selectedColor = idToColorMap[checkedId] ?: android.graphics.Color.parseColor("#FF6200EE")
            prefs.edit().putInt("overlay_color", selectedColor).apply()
        }

        // --- Text Expansion Setup ---
        val expansionCheck = findViewById<CheckBox>(R.id.textExpansionCheckBox)
        expansionCheck.isChecked = prefs.getBoolean("text_expansion_enabled", false)
        expansionCheck.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("text_expansion_enabled", isChecked).apply()
        }
        autoImportExpansionRules()
        refreshExpansionRules()

        findViewById<Button>(R.id.addExpansionRuleButton).setOnClickListener {
            showAddExpansionRuleDialog()
        }

        findViewById<Button>(R.id.exportExpansionRulesButton).setOnClickListener {
            exportExpansionRules()
            Toast.makeText(this, "Textbausteine exportiert", Toast.LENGTH_SHORT).show()
        }

        val stopButton = findViewById<Button>(R.id.stopButton)
        stopButton.setOnClickListener {
            val intent = Intent(this, com.example.voicelistener.services.OverlayService::class.java)
            stopService(intent)
            Toast.makeText(this, "Service gestoppt", Toast.LENGTH_SHORT).show()
        }

        val saveAndStartAction = View.OnClickListener {
            val key = apiKeyInput.text.toString().trim()
            val eqsUrl = eqsServerUrlInput.text.toString().trim().trimEnd('/')
            val prompt = promptInput.text.toString().trim()
            val vocab = vocabInput.text.toString().trim()

            if (key.isEmpty() || eqsUrl.isEmpty()) {
                if (key.isEmpty()) Toast.makeText(this, "Enter a valid API key", Toast.LENGTH_SHORT).show()
                if (eqsUrl.isEmpty()) Toast.makeText(this, "Enter a valid EQS Server URL", Toast.LENGTH_SHORT).show()
                return@OnClickListener
            }

            if (key.isNotEmpty()) {
                SettingsBackup.createBackup(this, "Save & Start")
                val marketIntervalStr = findViewById<EditText>(R.id.marketIntervalInput).text.toString()
                val marketInterval = marketIntervalStr.toIntOrNull() ?: 1

                prefs.edit()
                    .putString("groq_api_key", key)
                    .putString("eqs_server_url", eqsUrl)
                    .putString("llama_system_prompt", prompt)
                    .putString("custom_vocabulary", vocab)
                    .putBoolean("clipboard_history_enabled", findViewById<CheckBox>(R.id.clipboardHistoryEnabledCheckBox).isChecked)
                    .putBoolean("app_eqs_context_enabled", findViewById<CheckBox>(R.id.appEqsContextCheckBox).isChecked)
                    .putString("market_data_keys", prefs.getString("market_data_keys", "US500FU, USTECFU, DE40FU"))
                    .putInt("market_data_interval", marketInterval)
                    .putInt("market_min_values", findViewById<android.widget.Spinner>(R.id.marketMinValuesSpinner).selectedItemPosition + 1)
                    .putBoolean("market_notification_enabled", findViewById<CheckBox>(R.id.marketNotificationCheckBox).isChecked)
                    .apply()

                val savedKey = prefs.getString("groq_api_key", null)
                if (savedKey != null) {
                    moveTaskToBack(true)
                }

                val intent = Intent(this, com.example.voicelistener.services.OverlayService::class.java)
                intent.action = "ACTION_SHOW_OVERLAY"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                Toast.makeText(this, "Service gestartet!", Toast.LENGTH_SHORT).show()
                refreshLogs()

                if (!isAccessibilityServiceEnabled()) {
                    checkAccessibilityPermission()
                }
            } else {
                Toast.makeText(this, "Enter a valid key", Toast.LENGTH_SHORT).show()
            }
        }

        saveButton.setOnClickListener(saveAndStartAction)
        findViewById<Button>(R.id.saveButtonTop).setOnClickListener(saveAndStartAction)

        val settingsBtn = findViewById<Button>(R.id.openAccessibilitySettings)
        settingsBtn.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        findViewById<Button>(R.id.btnRestoreBackup).setOnClickListener {
            showRestoreBackupDialog()
        }

        findViewById<Button>(R.id.btnExportSettings).setOnClickListener {
            exportSettingsLauncher.launch("voicelistener_settings.json")
        }

        findViewById<Button>(R.id.btnImportSettings).setOnClickListener {
            importSettingsLauncher.launch(arrayOf("application/json", "*/*"))
        }

        val refreshBtn = findViewById<Button>(R.id.refreshLogs)
        val logView = findViewById<TextView>(R.id.logTextView)
        
        val sizeSlider = findViewById<android.widget.SeekBar>(R.id.sizeSlider)
        val sizeLabel = findViewById<android.widget.TextView>(R.id.sizeLabel)
        
        // Load Scale
        val savedScale = prefs.getFloat("overlay_scale", 1.0f)
        val savedProgress = ((savedScale - 0.5f) * 100).toInt()
        sizeSlider.progress = savedProgress.coerceIn(0, 150)
        sizeLabel.text = "Button Größe: ${(savedScale * 100).toInt()}%"

        sizeSlider.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val scale = 0.5f + (progress / 100f)
                sizeLabel.text = "Button Größe: ${(scale * 100).toInt()}%"
                prefs.edit().putFloat("overlay_scale", scale).apply()
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        // --- Transparency Setup ---
        val alphaSlider = findViewById<android.widget.SeekBar>(R.id.alphaSlider)
        val alphaLabel = findViewById<android.widget.TextView>(R.id.alphaLabel)
        
        val savedAlpha = prefs.getFloat("overlay_alpha", 1.0f)
        val alphaProgress = (savedAlpha * 100).toInt()
        alphaSlider.progress = alphaProgress
        alphaLabel.text = "Transparenz: ${alphaProgress}%"
        
        alphaSlider.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                // Min 20% to avoid invisible button
                val actualProgress = if (progress < 20) 20 else progress
                if (progress < 20) seekBar?.progress = 20
                
                val alpha = actualProgress / 100f
                alphaLabel.text = "Transparenz: ${actualProgress}%"
                prefs.edit().putFloat("overlay_alpha", alpha).apply()
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        // --- Dim Background Setup ---
        val dimSlider = findViewById<android.widget.SeekBar>(R.id.dimSlider)
        val dimLabel = findViewById<android.widget.TextView>(R.id.dimLabel)

        val savedDim = prefs.getFloat("overlay_dim", 0.0f)
        val dimProgress = (savedDim * 100).toInt()
        dimSlider.progress = dimProgress
        dimLabel.text = "Hintergrund abdunkeln: ${dimProgress}%"

        dimSlider.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val dim = progress / 100f
                dimLabel.text = "Hintergrund abdunkeln: ${progress}%"
                prefs.edit().putFloat("overlay_dim", dim).apply()
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        // --- Logs Setup ---
        val logsCheck = findViewById<CheckBox>(R.id.logsEnabledCheckBox)
        logsCheck.isChecked = prefs.getBoolean("logs_enabled", false)
        
        logsCheck.setOnCheckedChangeListener { _, isChecked ->
             prefs.edit().putBoolean("logs_enabled", isChecked).apply()
        }
        
        // --- Clipboard Setup ---
        val clipboardCheck = findViewById<CheckBox>(R.id.clipboardHistoryEnabledCheckBox)
        clipboardCheck.isChecked = prefs.getBoolean("clipboard_history_enabled", true)
        clipboardCheck.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("clipboard_history_enabled", isChecked).apply()
            // Send update signal to service if running
            val intent = Intent(this, com.example.voicelistener.services.OverlayService::class.java)
            intent.action = "ACTION_UPDATE_SETTINGS"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
        
        val clearLogsBtn = findViewById<Button>(R.id.clearLogsButton)
        clearLogsBtn.setOnClickListener {
             com.example.voicelistener.utils.FileLogger.clearLogs(this)
             refreshLogs()
             Toast.makeText(this, "Logs gelöscht", Toast.LENGTH_SHORT).show()
        }

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

    override fun onResume() {
        super.onResume()
        refreshSettingsUI()
        updatePermissionUI()
    }

    private fun updatePermissionUI() {
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasAccess = isAccessibilityServiceEnabled()

        val warningText = findViewById<TextView>(R.id.permissionWarningText)
        val settingsBtn = findViewById<Button>(R.id.openAccessibilitySettings)

        if (hasOverlay && hasAccess) {
            warningText.visibility = View.GONE
            settingsBtn.visibility = View.GONE
        } else {
            warningText.visibility = View.VISIBLE
            settingsBtn.visibility = View.VISIBLE
            
            val missing = mutableListOf<String>()
            if (!hasOverlay) missing.add("Overlay")
            if (!hasAccess) missing.add("Accessibility")
            warningText.text = "Bitte folgende Berechtigungen erteilen:\n${missing.joinToString(" & ")}"
        }
    }

    private fun refreshSettingsUI() {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        
        // Refresh Trigger
        val savedTrigger = prefs.getInt("overlay_recording_trigger", 0)
        findViewById<RadioButton>(if (savedTrigger == 1) R.id.modeLongPress else R.id.modeDoubleTap).isChecked = true
        
        // Refresh Auto-Hide
        findViewById<CheckBox>(R.id.focusModeCheckBox).isChecked = prefs.getBoolean("overlay_focus_mode", false)
        
        // Refresh Hidden Mode
        findViewById<CheckBox>(R.id.alwaysHiddenCheckBox).isChecked = prefs.getBoolean("overlay_always_hidden", false)

        // Refresh Swipe Spinners
        val actionKeys = GestureManager.ACTION_LABELS.keys.toList()
        fun refreshSpinner(spinnerId: Int, prefKey: String, defaultAction: String) {
            val spinner = findViewById<Spinner>(spinnerId)
            val current = prefs.getString(prefKey, defaultAction) ?: defaultAction
            val idx = actionKeys.indexOf(current)
            if (idx >= 0) { spinner.tag = "init"; spinner.setSelection(idx) }
        }
        refreshSpinner(R.id.spinnerSwipeUp, "swipe_up_action", "show_volume")
        refreshSpinner(R.id.spinnerSwipeDown, "swipe_down_action", "toggle_mute")
        refreshSpinner(R.id.spinnerSwipeLeft, "swipe_left_action", "show_notifications")
        refreshSpinner(R.id.spinnerSwipeRight, "swipe_right_action", "media_play_pause")

        // Refresh Clipboard
        findViewById<CheckBox>(R.id.clipboardHistoryEnabledCheckBox).isChecked = prefs.getBoolean("clipboard_history_enabled", true)
        
        // Refresh Llama
        findViewById<CheckBox>(R.id.llamaEnabledCheckBox).isChecked = prefs.getBoolean("llama_enabled", true)

        // Refresh Extra Apps
        setupRadialMenuConfig(prefs)
        findViewById<CheckBox>(R.id.appEqsContextCheckBox).isChecked = prefs.getBoolean("app_eqs_context_enabled", true)
        updateMarketKeysDisplay(findViewById(R.id.marketKeysDisplay), prefs)
        findViewById<EditText>(R.id.marketIntervalInput).setText(prefs.getInt("market_data_interval", 1).toString())

        // Refresh Text Expansion
        findViewById<CheckBox>(R.id.textExpansionCheckBox).isChecked = prefs.getBoolean("text_expansion_enabled", false)
        refreshExpansionRules()
    }

    private fun checkAccessibilityPermission() {
        if (!isAccessibilityServiceEnabled()) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Berechtigung erforderlich")
                .setMessage("Diese App benötigt den Eingabehilfe-Dienst (Accessibility Service), um Text in andere Apps einzufügen. Bitte aktiviere 'Voice Listener' in den Einstellungen.")
                .setPositiveButton("Einstellungen öffnen") { _, _ ->
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    startActivity(intent)
                }
                .setNegativeButton("Abbrechen", null)
                .show()
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = ComponentName(this, com.example.voicelistener.services.VoiceAccessibilityService::class.java)
        
        val enabledServicesSetting = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = android.text.TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)

        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledComponent = ComponentName.unflattenFromString(componentNameString)
            if (enabledComponent != null && enabledComponent == expectedComponentName) {
                return true
            }
        }
        return false
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        
        if (permissions.isNotEmpty()) {
            requestPermissionLauncher.launch(permissions.toTypedArray())
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
            startActivity(intent)
            Toast.makeText(this, "Bitte 'Am Anfang anzeigen' erlauben", Toast.LENGTH_LONG).show()
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

    private fun setupRadialMenuConfig(prefs: android.content.SharedPreferences) {
        val container = findViewById<android.widget.LinearLayout>(R.id.radialMenuContainer)
        container.removeAllViews()

        val allItems = com.example.voicelistener.services.OverlayService.ALL_RADIAL_ITEMS

        // Load saved config (ordered list of enabled IDs)
        val configJson = prefs.getString("radial_menu_config", null)
        val savedOrder: MutableList<String> = if (configJson != null) {
            try {
                val arr = org.json.JSONArray(configJson)
                (0 until arr.length()).map { arr.getString(it) }.toMutableList()
            } catch (_: Exception) { allItems.map { it.id }.toMutableList() }
        } else {
            allItems.map { it.id }.toMutableList()
        }

        // Build ordered list: saved items first, then any new items not in saved config
        val allIds = allItems.map { it.id }
        val orderedIds = mutableListOf<String>()
        for (id in savedOrder) { if (id in allIds) orderedIds.add(id) }
        for (id in allIds) { if (id !in orderedIds) orderedIds.add(id) }

        // Track which are enabled
        val enabledSet = savedOrder.toMutableSet()

        // Map id -> def
        val defMap = allItems.associateBy { it.id }

        fun saveConfig() {
            val enabled = orderedIds.filter { it in enabledSet }
            val json = org.json.JSONArray(enabled)
            prefs.edit().putString("radial_menu_config", json.toString()).apply()
        }

        fun rebuildList() {
            container.removeAllViews()
            val density = resources.displayMetrics.density
            for ((index, id) in orderedIds.withIndex()) {
                val def = defMap[id] ?: continue
                val row = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, (2 * density).toInt(), 0, (2 * density).toInt())
                }

                val cb = CheckBox(this).apply {
                    text = "${def.icon}  ${def.label}"
                    isChecked = id in enabledSet
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) enabledSet.add(id) else enabledSet.remove(id)
                        saveConfig()
                    }
                }
                row.addView(cb)

                // Up button
                if (index > 0) {
                    val upBtn = android.widget.TextView(this).apply {
                        text = "\u25B2"
                        textSize = 16f
                        setPadding((12 * density).toInt(), 0, (4 * density).toInt(), 0)
                        setOnClickListener {
                            val pos = orderedIds.indexOf(id)
                            if (pos > 0) {
                                orderedIds.removeAt(pos)
                                orderedIds.add(pos - 1, id)
                                saveConfig()
                                rebuildList()
                            }
                        }
                    }
                    row.addView(upBtn)
                }

                // Down button
                if (index < orderedIds.size - 1) {
                    val downBtn = android.widget.TextView(this).apply {
                        text = "\u25BC"
                        textSize = 16f
                        setPadding((4 * density).toInt(), 0, (8 * density).toInt(), 0)
                        setOnClickListener {
                            val pos = orderedIds.indexOf(id)
                            if (pos < orderedIds.size - 1) {
                                orderedIds.removeAt(pos)
                                orderedIds.add(pos + 1, id)
                                saveConfig()
                                rebuildList()
                            }
                        }
                    }
                    row.addView(downBtn)
                }

                container.addView(row)
            }
        }

        rebuildList()
    }

    private fun updateMarketKeysDisplay(display: TextView, prefs: android.content.SharedPreferences) {
        val selectedKeys = prefs.getString("market_data_keys", "US500FU, USTECFU, DE40FU") ?: ""
        val keys = selectedKeys.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        display.text = if (keys.isEmpty()) "Keine Werte ausgewählt" else "Ausgewählt: ${keys.joinToString(", ")}"
    }

    private fun showMarketKeysDialog(display: TextView, prefs: android.content.SharedPreferences) {
        val availableKeysStr = prefs.getString("available_market_keys", "") ?: ""
        val selectedKeysStr = prefs.getString("market_data_keys", "US500FU, USTECFU, DE40FU") ?: ""
        val selectedKeys = selectedKeysStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        val availableKeys = availableKeysStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }

        if (availableKeys.isEmpty()) {
            Toast.makeText(this, "Noch keine Marktdaten verfügbar. Bitte zuerst das Marktdaten-Widget öffnen.", Toast.LENGTH_LONG).show()
            return
        }

        // Build ordered list: selected keys first (in their order), then unselected keys
        val orderedItems = mutableListOf<Pair<String, Boolean>>() // key, isSelected
        for (key in selectedKeys) {
            if (key in availableKeys) {
                orderedItems.add(key to true)
            }
        }
        for (key in availableKeys) {
            if (orderedItems.none { it.first == key }) {
                orderedItems.add(key to false)
            }
        }

        val adapter = MarketKeyAdapter(orderedItems)
        val recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            this.adapter = adapter
            setPadding(16, 16, 16, 16)
        }

        // Drag & Drop
        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = viewHolder.adapterPosition
                val to = target.adapterPosition
                Collections.swap(adapter.items, from, to)
                adapter.notifyItemMoved(from, to)
                return true
            }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        })
        touchHelper.attachToRecyclerView(recyclerView)
        adapter.dragStartListener = { viewHolder -> touchHelper.startDrag(viewHolder) }

        android.app.AlertDialog.Builder(this)
            .setTitle("Marktdaten auswählen")
            .setView(recyclerView)
            .setPositiveButton("OK") { _, _ ->
                val result = adapter.items.filter { it.second }.map { it.first }
                prefs.edit().putString("market_data_keys", result.joinToString(", ")).apply()
                updateMarketKeysDisplay(display, prefs)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun refreshExpansionRules() {
        val container = findViewById<LinearLayout>(R.id.expansionRulesContainer)
        container.removeAllViews()
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val rulesJson = prefs.getString("text_expansion_rules", "[]") ?: "[]"
        val rules = try { org.json.JSONArray(rulesJson) } catch (e: Exception) { org.json.JSONArray() }

        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            val trigger = rule.optString("trigger", "")
            val replacement = rule.optString("replacement", "")
            val caseSensitive = rule.optBoolean("case_sensitive", false)

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 4, 0, 4)
            }
            val funcMap = com.example.voicelistener.services.VoiceAccessibilityService.FUNCTION_REPLACEMENTS
            val displayReplacement = funcMap[replacement] ?: replacement
            val label = TextView(this).apply {
                text = "$trigger → $displayReplacement"
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val idx = i
            val caseCb = CheckBox(this).apply {
                text = "Aa"
                textSize = 11f
                isChecked = caseSensitive
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                setPadding(8, 0, 8, 0)
                setOnCheckedChangeListener { _, checked ->
                    rule.put("case_sensitive", checked)
                    prefs.edit().putString("text_expansion_rules", rules.toString()).apply()
                    exportExpansionRules()
                }
            }
            val deleteBtn = Button(this).apply {
                text = "✕"
                textSize = 12f
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                setPadding(16, 4, 16, 4)
                setOnClickListener {
                    rules.remove(idx)
                    prefs.edit().putString("text_expansion_rules", rules.toString()).apply()
                    refreshExpansionRules()
                    exportExpansionRules()
                }
            }
            row.setOnClickListener {
                showEditExpansionRuleDialog(idx, trigger, replacement, caseSensitive)
            }
            row.addView(label)
            row.addView(caseCb)
            row.addView(deleteBtn)
            container.addView(row)
        }
    }

    private fun showAddExpansionRuleDialog() {
        // First: choose between text replacement or function
        val options = arrayOf("Textersetzung", "Funktion (Datum, Uhrzeit, ...)")
        android.app.AlertDialog.Builder(this)
            .setTitle("Textbaustein-Typ")
            .setItems(options) { _, which ->
                if (which == 0) {
                    showAddTextRuleDialog()
                } else {
                    showAddFunctionRuleDialog()
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun showAddTextRuleDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }
        val triggerInput = EditText(this).apply { hint = "Kürzel (z.B. Ää)" }
        val replacementInput = EditText(this).apply { hint = "Ersetzung (z.B. /)" }
        val caseCb = CheckBox(this).apply {
            text = "Groß-/Kleinschreibung beachten"
            isChecked = false
        }
        layout.addView(triggerInput)
        layout.addView(replacementInput)
        layout.addView(caseCb)

        android.app.AlertDialog.Builder(this)
            .setTitle("Textersetzung hinzufügen")
            .setView(layout)
            .setPositiveButton("Hinzufügen") { _, _ ->
                val trigger = triggerInput.text.toString()
                val replacement = replacementInput.text.toString()
                if (trigger.isNotEmpty() && replacement.isNotEmpty()) {
                    saveExpansionRule(trigger, replacement, caseCb.isChecked)
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun showAddFunctionRuleDialog() {
        val functions = com.example.voicelistener.services.VoiceAccessibilityService.FUNCTION_REPLACEMENTS
        val tokens = functions.keys.toList()
        val labels = functions.values.toTypedArray()

        android.app.AlertDialog.Builder(this)
            .setTitle("Funktion wählen")
            .setItems(labels) { _, which ->
                val selectedToken = tokens[which]
                val selectedLabel = labels[which]
                // Now ask for the trigger
                val layout = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(48, 32, 48, 16)
                }
                val info = TextView(this).apply {
                    text = "Funktion: $selectedLabel"
                    textSize = 14f
                    setPadding(0, 0, 0, 16)
                }
                val triggerInput = EditText(this).apply { hint = "Kürzel (z.B. .d für Datum)" }
                val caseCb = CheckBox(this).apply {
                    text = "Groß-/Kleinschreibung beachten"
                    isChecked = false
                }
                layout.addView(info)
                layout.addView(triggerInput)
                layout.addView(caseCb)

                android.app.AlertDialog.Builder(this)
                    .setTitle("Trigger eingeben")
                    .setView(layout)
                    .setPositiveButton("Hinzufügen") { _, _ ->
                        val trigger = triggerInput.text.toString()
                        if (trigger.isNotEmpty()) {
                            saveExpansionRule(trigger, selectedToken, caseCb.isChecked)
                        }
                    }
                    .setNegativeButton("Abbrechen", null)
                    .show()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun saveExpansionRule(trigger: String, replacement: String, caseSensitive: Boolean = false) {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val rulesJson = prefs.getString("text_expansion_rules", "[]") ?: "[]"
        val rules = try { org.json.JSONArray(rulesJson) } catch (e: Exception) { org.json.JSONArray() }
        val newRule = org.json.JSONObject()
            .put("trigger", trigger)
            .put("replacement", replacement)
            .put("case_sensitive", caseSensitive)
        rules.put(newRule)
        prefs.edit().putString("text_expansion_rules", rules.toString()).apply()
        refreshExpansionRules()
        exportExpansionRules()
    }

    private fun showEditExpansionRuleDialog(index: Int, currentTrigger: String, currentReplacement: String, currentCaseSensitive: Boolean = false) {
        val funcMap = com.example.voicelistener.services.VoiceAccessibilityService.FUNCTION_REPLACEMENTS
        val isFunction = funcMap.containsKey(currentReplacement)

        if (isFunction) {
            val functions = com.example.voicelistener.services.VoiceAccessibilityService.FUNCTION_REPLACEMENTS
            val tokens = functions.keys.toList()
            val labels = functions.values.toTypedArray()
            val currentIdx = tokens.indexOf(currentReplacement).coerceAtLeast(0)

            android.app.AlertDialog.Builder(this)
                .setTitle("Funktion ändern")
                .setSingleChoiceItems(labels, currentIdx) { dialog, which ->
                    dialog.dismiss()
                    val selectedToken = tokens[which]
                    val selectedLabel = labels[which]
                    val layout = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(48, 32, 48, 16)
                    }
                    val info = TextView(this).apply {
                        text = "Funktion: $selectedLabel"
                        textSize = 14f
                        setPadding(0, 0, 0, 16)
                    }
                    val triggerInput = EditText(this).apply {
                        hint = "Kürzel"
                        setText(currentTrigger)
                    }
                    val caseCb = CheckBox(this).apply {
                        text = "Groß-/Kleinschreibung beachten"
                        isChecked = currentCaseSensitive
                    }
                    layout.addView(info)
                    layout.addView(triggerInput)
                    layout.addView(caseCb)

                    android.app.AlertDialog.Builder(this)
                        .setTitle("Trigger bearbeiten")
                        .setView(layout)
                        .setPositiveButton("Speichern") { _, _ ->
                            val trigger = triggerInput.text.toString()
                            if (trigger.isNotEmpty()) {
                                updateExpansionRule(index, trigger, selectedToken, caseCb.isChecked)
                            }
                        }
                        .setNegativeButton("Abbrechen", null)
                        .show()
                }
                .setNegativeButton("Abbrechen", null)
                .show()
        } else {
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 32, 48, 16)
            }
            val triggerInput = EditText(this).apply {
                hint = "Kürzel"
                setText(currentTrigger)
            }
            val replacementInput = EditText(this).apply {
                hint = "Ersetzung"
                setText(currentReplacement)
            }
            val caseCb = CheckBox(this).apply {
                text = "Groß-/Kleinschreibung beachten"
                isChecked = currentCaseSensitive
            }
            layout.addView(triggerInput)
            layout.addView(replacementInput)
            layout.addView(caseCb)

            android.app.AlertDialog.Builder(this)
                .setTitle("Textersetzung bearbeiten")
                .setView(layout)
                .setPositiveButton("Speichern") { _, _ ->
                    val trigger = triggerInput.text.toString()
                    val replacement = replacementInput.text.toString()
                    if (trigger.isNotEmpty() && replacement.isNotEmpty()) {
                        updateExpansionRule(index, trigger, replacement, caseCb.isChecked)
                    }
                }
                .setNegativeButton("Abbrechen", null)
                .show()
        }
    }

    private fun updateExpansionRule(index: Int, trigger: String, replacement: String, caseSensitive: Boolean = false) {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val rulesJson = prefs.getString("text_expansion_rules", "[]") ?: "[]"
        val rules = try { org.json.JSONArray(rulesJson) } catch (e: Exception) { org.json.JSONArray() }
        if (index < rules.length()) {
            val updatedRule = org.json.JSONObject()
                .put("trigger", trigger)
                .put("replacement", replacement)
                .put("case_sensitive", caseSensitive)
            rules.put(index, updatedRule)
            prefs.edit().putString("text_expansion_rules", rules.toString()).apply()
            refreshExpansionRules()
            exportExpansionRules()
        }
    }

    private fun getExpansionRulesFile(): java.io.File {
        val dir = java.io.File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOCUMENTS
            ),
            "VoiceListener"
        )
        if (!dir.exists()) dir.mkdirs()
        return java.io.File(dir, "expansion_rules.json")
    }

    private fun exportExpansionRules() {
        try {
            val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
            val rulesJson = prefs.getString("text_expansion_rules", "[]") ?: "[]"
            val file = getExpansionRulesFile()
            file.writeText(rulesJson)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Export failed: ${e.message}")
        }
    }

    private fun autoImportExpansionRules() {
        try {
            val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
            val currentRules = prefs.getString("text_expansion_rules", "[]") ?: "[]"
            val current = try { org.json.JSONArray(currentRules) } catch (_: Exception) { org.json.JSONArray() }

            // Only import if no rules exist in prefs
            if (current.length() > 0) return

            val file = getExpansionRulesFile()
            if (!file.exists()) return

            val imported = file.readText()
            val rules = try { org.json.JSONArray(imported) } catch (_: Exception) { return }
            if (rules.length() > 0) {
                prefs.edit().putString("text_expansion_rules", rules.toString()).apply()
                Toast.makeText(this, "${rules.length()} Textbausteine importiert", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Auto-import failed: ${e.message}")
        }
    }

    private fun showRestoreBackupDialog() {
        val backups = SettingsBackup.getBackups(this)
        if (backups.length() == 0) {
            Toast.makeText(this, "Keine Backups vorhanden", Toast.LENGTH_SHORT).show()
            return
        }

        val items = Array(backups.length()) { i ->
            val entry = backups.getJSONObject(i)
            "${entry.optString("timestamp")} - ${entry.optString("reason")}"
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("Einstellungen wiederherstellen (${backups.length()})")
            .setItems(items) { _, which ->
                // Show details before restoring
                val entry = backups.getJSONObject(which)
                val settings = entry.optJSONObject("settings")
                val details = StringBuilder()
                details.append("Backup: ${entry.optString("timestamp")}\n")
                details.append("Grund: ${entry.optString("reason")}\n\n")
                details.append("Enthaltene Einstellungen:\n")
                settings?.let { s ->
                    val keys = s.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = s.get(key)
                        val displayValue = when {
                            value is String && value.length > 60 -> value.take(60) + "..."
                            else -> value.toString()
                        }
                        details.append("• $key = $displayValue\n")
                    }
                }

                android.app.AlertDialog.Builder(this)
                    .setTitle("Wiederherstellen?")
                    .setMessage(details.toString())
                    .setPositiveButton("Wiederherstellen") { _, _ ->
                        // Backup current state before restoring
                        SettingsBackup.createBackup(this, "Vor Wiederherstellung")
                        if (SettingsBackup.restoreBackup(this, which)) {
                            Toast.makeText(this, "Einstellungen wiederhergestellt", Toast.LENGTH_SHORT).show()
                            refreshSettingsUI()
                            // Reload text fields
                            val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
                            findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.promptInput)
                                ?.setText(prefs.getString("llama_system_prompt", ""))
                            findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.vocabularyInput)
                                ?.setText(prefs.getString("custom_vocabulary", ""))
                            // Notify overlay service
                            val intent = Intent(this, OverlayService::class.java)
                            intent.action = "ACTION_UPDATE_SETTINGS"
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                startForegroundService(intent)
                            } else {
                                startService(intent)
                            }
                        } else {
                            Toast.makeText(this, "Fehler beim Wiederherstellen", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("Abbrechen", null)
                    .show()
            }
            .setNegativeButton("Schließen", null)
            .show()
    }

    private fun writeExportToUri(uri: Uri) {
        try {
            val json = SettingsBackup.exportToJson(this)
            contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(json.toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(this, "Einstellungen exportiert", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export fehlgeschlagen: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun readImportFromUri(uri: Uri) {
        try {
            val json = contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader(Charsets.UTF_8).readText()
            } ?: return

            val (valid, preview) = SettingsBackup.getImportPreview(json)
            if (!valid) {
                Toast.makeText(this, preview, Toast.LENGTH_LONG).show()
                return
            }

            android.app.AlertDialog.Builder(this)
                .setTitle("Einstellungen importieren?")
                .setMessage(preview)
                .setPositiveButton("Importieren") { _, _ ->
                    val (success, message) = SettingsBackup.importFromJson(this, json)
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                    if (success) {
                        refreshSettingsUI()
                        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
                        findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.promptInput)
                            ?.setText(prefs.getString("llama_system_prompt", ""))
                        findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.vocabularyInput)
                            ?.setText(prefs.getString("custom_vocabulary", ""))
                        sendUpdateIntent()
                    }
                }
                .setNegativeButton("Abbrechen", null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "Import fehlgeschlagen: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // Inner adapter class for market key selection with drag & drop
    inner class MarketKeyAdapter(val items: MutableList<Pair<String, Boolean>>) :
        RecyclerView.Adapter<MarketKeyAdapter.VH>() {

        var dragStartListener: ((RecyclerView.ViewHolder) -> Unit)? = null

        inner class VH(val layout: LinearLayout) : RecyclerView.ViewHolder(layout) {
            val checkbox: CheckBox = layout.getChildAt(1) as CheckBox
            val dragHandle: TextView = layout.getChildAt(0) as TextView
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val layout = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                )
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(8, 8, 8, 8)
            }
            val dragHandle = TextView(parent.context).apply {
                text = "☰"
                textSize = 20f
                setPadding(16, 0, 16, 0)
            }
            val checkbox = CheckBox(parent.context).apply {
                textSize = 14f
            }
            layout.addView(dragHandle)
            layout.addView(checkbox)
            return VH(layout)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val (key, selected) = items[position]
            holder.checkbox.text = key
            holder.checkbox.setOnCheckedChangeListener(null)
            holder.checkbox.isChecked = selected
            holder.checkbox.setOnCheckedChangeListener { _, isChecked ->
                val pos = holder.adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    items[pos] = items[pos].copy(second = isChecked)
                }
            }
            holder.dragHandle.setOnTouchListener { _, event ->
                if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                    dragStartListener?.invoke(holder)
                }
                false
            }
        }

        override fun getItemCount() = items.size
    }
}
