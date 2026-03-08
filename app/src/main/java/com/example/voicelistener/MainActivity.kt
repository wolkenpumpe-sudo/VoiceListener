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
import android.widget.EditText
import android.widget.LinearLayout
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

        // --- Extra Apps Setup ---
        val appTranslateCheck = findViewById<CheckBox>(R.id.appTranslateCheckBox)
        val appClipboardCheck = findViewById<CheckBox>(R.id.appClipboardCheckBox)
        val appMarketCheck = findViewById<CheckBox>(R.id.appMarketCheckBox)
        val appAskLlamaCheck = findViewById<CheckBox>(R.id.appAskLlamaCheckBox)
        val appEqsContextCheck = findViewById<CheckBox>(R.id.appEqsContextCheckBox)
        val marketKeysInput = findViewById<EditText>(R.id.marketKeysInput)
        val marketIntervalInput = findViewById<EditText>(R.id.marketIntervalInput)
        val marketKeysSelectButton = findViewById<Button>(R.id.marketKeysSelectButton)
        val marketKeysDisplay = findViewById<TextView>(R.id.marketKeysDisplay)

        appTranslateCheck.isChecked = prefs.getBoolean("app_translate_enabled", true)
        appClipboardCheck.isChecked = prefs.getBoolean("app_clipboard_enabled", true)
        appMarketCheck.isChecked = prefs.getBoolean("app_market_enabled", false)
        appAskLlamaCheck.isChecked = prefs.getBoolean("app_askllama_enabled", true)
        appEqsContextCheck.isChecked = prefs.getBoolean("app_eqs_context_enabled", true)
        marketKeysInput.setText(prefs.getString("market_data_keys", "US500FU, USTECFU, DE40FU"))
        marketIntervalInput.setText(prefs.getInt("market_data_interval", 1).toString())
        updateMarketKeysDisplay(marketKeysDisplay, prefs)

        marketKeysSelectButton.setOnClickListener {
            showMarketKeysDialog(marketKeysDisplay, prefs)
        }

        appTranslateCheck.setOnCheckedChangeListener { _, isChecked -> prefs.edit().putBoolean("app_translate_enabled", isChecked).apply() }
        appClipboardCheck.setOnCheckedChangeListener { _, isChecked -> prefs.edit().putBoolean("app_clipboard_enabled", isChecked).apply() }
        appMarketCheck.setOnCheckedChangeListener { _, isChecked -> prefs.edit().putBoolean("app_market_enabled", isChecked).apply() }
        appAskLlamaCheck.setOnCheckedChangeListener { _, isChecked -> prefs.edit().putBoolean("app_askllama_enabled", isChecked).apply() }
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

        // --- Color Setup ---
        val colorGroup = findViewById<RadioGroup>(R.id.colorGroup)
        
        // Map Colors to IDs
        val colorMap = mapOf(
            android.graphics.Color.parseColor("#FF6200EE") to R.id.colorPurple,
            android.graphics.Color.parseColor("#2196F3") to R.id.colorBlue,
            android.graphics.Color.parseColor("#F44336") to R.id.colorRed,
            android.graphics.Color.parseColor("#4CAF50") to R.id.colorGreen,
            android.graphics.Color.parseColor("#000000") to R.id.colorBlack
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
                    .putBoolean("app_translate_enabled", findViewById<CheckBox>(R.id.appTranslateCheckBox).isChecked)
                    .putBoolean("app_clipboard_enabled", findViewById<CheckBox>(R.id.appClipboardCheckBox).isChecked)
                    .putBoolean("app_market_enabled", findViewById<CheckBox>(R.id.appMarketCheckBox).isChecked)
                    .putBoolean("app_askllama_enabled", findViewById<CheckBox>(R.id.appAskLlamaCheckBox).isChecked)
                    .putBoolean("app_eqs_context_enabled", findViewById<CheckBox>(R.id.appEqsContextCheckBox).isChecked)
                    .putString("market_data_keys", prefs.getString("market_data_keys", "US500FU, USTECFU, DE40FU"))
                    .putInt("market_data_interval", marketInterval)
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

        // Refresh Clipboard
        findViewById<CheckBox>(R.id.clipboardHistoryEnabledCheckBox).isChecked = prefs.getBoolean("clipboard_history_enabled", true)
        
        // Refresh Llama
        findViewById<CheckBox>(R.id.llamaEnabledCheckBox).isChecked = prefs.getBoolean("llama_enabled", true)

        // Refresh Extra Apps
        findViewById<CheckBox>(R.id.appTranslateCheckBox).isChecked = prefs.getBoolean("app_translate_enabled", true)
        findViewById<CheckBox>(R.id.appClipboardCheckBox).isChecked = prefs.getBoolean("app_clipboard_enabled", true)
        findViewById<CheckBox>(R.id.appMarketCheckBox).isChecked = prefs.getBoolean("app_market_enabled", false)
        findViewById<CheckBox>(R.id.appAskLlamaCheckBox).isChecked = prefs.getBoolean("app_askllama_enabled", true)
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
            val deleteBtn = Button(this).apply {
                text = "✕"
                textSize = 12f
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                setPadding(16, 4, 16, 4)
                val idx = i
                setOnClickListener {
                    rules.remove(idx)
                    prefs.edit().putString("text_expansion_rules", rules.toString()).apply()
                    refreshExpansionRules()
                    exportExpansionRules()
                }
            }
            // Click on row to edit
            val idx = i
            row.setOnClickListener {
                showEditExpansionRuleDialog(idx, trigger, replacement)
            }
            row.addView(label)
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
        layout.addView(triggerInput)
        layout.addView(replacementInput)

        android.app.AlertDialog.Builder(this)
            .setTitle("Textersetzung hinzufügen")
            .setView(layout)
            .setPositiveButton("Hinzufügen") { _, _ ->
                val trigger = triggerInput.text.toString()
                val replacement = replacementInput.text.toString()
                if (trigger.isNotEmpty() && replacement.isNotEmpty()) {
                    saveExpansionRule(trigger, replacement)
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
                layout.addView(info)
                layout.addView(triggerInput)

                android.app.AlertDialog.Builder(this)
                    .setTitle("Trigger eingeben")
                    .setView(layout)
                    .setPositiveButton("Hinzufügen") { _, _ ->
                        val trigger = triggerInput.text.toString()
                        if (trigger.isNotEmpty()) {
                            saveExpansionRule(trigger, selectedToken)
                        }
                    }
                    .setNegativeButton("Abbrechen", null)
                    .show()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun saveExpansionRule(trigger: String, replacement: String) {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val rulesJson = prefs.getString("text_expansion_rules", "[]") ?: "[]"
        val rules = try { org.json.JSONArray(rulesJson) } catch (e: Exception) { org.json.JSONArray() }
        val newRule = org.json.JSONObject().put("trigger", trigger).put("replacement", replacement)
        rules.put(newRule)
        prefs.edit().putString("text_expansion_rules", rules.toString()).apply()
        refreshExpansionRules()
        exportExpansionRules()
    }

    private fun showEditExpansionRuleDialog(index: Int, currentTrigger: String, currentReplacement: String) {
        val funcMap = com.example.voicelistener.services.VoiceAccessibilityService.FUNCTION_REPLACEMENTS
        val isFunction = funcMap.containsKey(currentReplacement)

        if (isFunction) {
            // Edit function rule: let user pick a new function and/or change trigger
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
                    layout.addView(info)
                    layout.addView(triggerInput)

                    android.app.AlertDialog.Builder(this)
                        .setTitle("Trigger bearbeiten")
                        .setView(layout)
                        .setPositiveButton("Speichern") { _, _ ->
                            val trigger = triggerInput.text.toString()
                            if (trigger.isNotEmpty()) {
                                updateExpansionRule(index, trigger, selectedToken)
                            }
                        }
                        .setNegativeButton("Abbrechen", null)
                        .show()
                }
                .setNegativeButton("Abbrechen", null)
                .show()
        } else {
            // Edit text rule
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
            layout.addView(triggerInput)
            layout.addView(replacementInput)

            android.app.AlertDialog.Builder(this)
                .setTitle("Textersetzung bearbeiten")
                .setView(layout)
                .setPositiveButton("Speichern") { _, _ ->
                    val trigger = triggerInput.text.toString()
                    val replacement = replacementInput.text.toString()
                    if (trigger.isNotEmpty() && replacement.isNotEmpty()) {
                        updateExpansionRule(index, trigger, replacement)
                    }
                }
                .setNegativeButton("Abbrechen", null)
                .show()
        }
    }

    private fun updateExpansionRule(index: Int, trigger: String, replacement: String) {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val rulesJson = prefs.getString("text_expansion_rules", "[]") ?: "[]"
        val rules = try { org.json.JSONArray(rulesJson) } catch (e: Exception) { org.json.JSONArray() }
        if (index < rules.length()) {
            val updatedRule = org.json.JSONObject().put("trigger", trigger).put("replacement", replacement)
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
