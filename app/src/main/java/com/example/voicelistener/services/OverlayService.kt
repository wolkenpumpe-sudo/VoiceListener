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
import java.io.File

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var audioRecorder: AudioRecorder? = null
    private var overlayButton: ImageButton? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private val CHANNEL_ID = "OverlayServiceChannel"

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        FileLogger.log(this, "OverlayService", "Service creating...")
        
        // 0. Crash Handler for this thread
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
             FileLogger.log(this, "CRASH", "Uncaught Exception: ${throwable.stackTraceToString()}")
        }

        // 1. Notification (Foreground Service)
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Voice Listener Active")
            .setContentText("Overlay is running. Tap to view logs.")
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, com.example.voicelistener.MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
        startForeground(1, notification)

        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            audioRecorder = AudioRecorder(this)
            addOverlayView()
            FileLogger.log(this, "OverlayService", "Service created successfully")
        } catch (e: Exception) {
            FileLogger.log(this, "OverlayService", "Error in onCreate: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Helper Overlay",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun addOverlayView() {
        FileLogger.log(this, "OverlayService", "Adding overlay view...")
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        params?.gravity = Gravity.TOP or Gravity.START
        params?.x = 0
        params?.y = 100

        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.overlay_layout, null)
        overlayButton = overlayView?.findViewById(R.id.overlay_button)
        
        setupTouchListener()

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
            private var isRecording = false
            private val CLICK_THRESHOLD = 5

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                try {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = params!!.x
                            initialY = params!!.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            lastAction = MotionEvent.ACTION_DOWN
                            
                            if (checkFocus()) {
                                startRecording()
                                isRecording = true
                            } else {
                                 FileLogger.log(applicationContext, "Touch", "No active focus found")
                            }
                            return true
                        }
                        MotionEvent.ACTION_UP -> {
                            if (isRecording) {
                                 stopRecordingAndProcess()
                                 isRecording = false
                            }
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = (event.rawX - initialTouchX).toInt()
                            val dy = (event.rawY - initialTouchY).toInt()
                            if (kotlin.math.abs(dx) > CLICK_THRESHOLD || kotlin.math.abs(dy) > CLICK_THRESHOLD) {
                                params!!.x = initialX + dx
                                params!!.y = initialY + dy
                                windowManager.updateViewLayout(overlayView, params)
                                lastAction = MotionEvent.ACTION_MOVE
                            }
                            return true
                        }
                    }
                } catch (e: Exception) {
                     FileLogger.log(applicationContext, "TouchError", "Error in onTouch: ${e.message}")
                }
                return false
            }
        })
    }

    private fun checkFocus(): Boolean {
        val accessibilityService = VoiceAccessibilityService.instance
        val isActive = accessibilityService != null && accessibilityService.isInputFocused()
        FileLogger.log(this, "FocusCheck", "Service: ${accessibilityService != null}, Focused: $isActive")
        return isActive
    }

    private fun startRecording() {
        FileLogger.log(this, "Recording", "Starting recording...")
        try {
            overlayButton?.setColorFilter(Color.RED)
            audioRecorder?.startRecording()
        } catch (e: Exception) {
            FileLogger.log(this, "RecordError", "Start failed: ${e.message}")
        }
    }

    private fun stopRecordingAndProcess() {
        FileLogger.log(this, "Recording", "Stopping recording...")
        try {
            overlayButton?.setColorFilter(Color.YELLOW)
            audioRecorder?.stopRecording()
            val file = File(cacheDir, "recording.m4a")
            
            if (file.exists() && file.length() > 0) {
                FileLogger.log(this, "Recording", "File created: ${file.length()} bytes. Processing...")
                processAudio(file)
            } else {
                FileLogger.log(this, "Recording", "File failed (empty or missing)")
                resetUI()
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
            Toast.makeText(this, "API Key missing", Toast.LENGTH_LONG).show()
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

                // 2. Llama Correction
                FileLogger.log(this@OverlayService, "API", "Sending to Llama...")
                val prompt = "Korrigiere folgenden Text (Rechtschreibung, Grammatik). Gib NUR den korrigierten Text zurück, keine Erklärungen oder Anführungszeichen: $rawText"
                val chatRequest = ChatRequest(
                    model = "llama-3.3-70b-versatile",
                    messages = listOf(Message("user", prompt))
                )
                
                val chatResponse = GroqClient.api.chatCompletion(auth, chatRequest)
                val finalText = chatResponse.choices.firstOrNull()?.message?.content ?: rawText
                FileLogger.log(this@OverlayService, "API", "Llama Text: $finalText")

                // 3. Inject
                withContext(Dispatchers.Main) {
                   FileLogger.log(this@OverlayService, "Injector", "Injecting text...")
                   val injected = VoiceAccessibilityService.instance?.injectText(finalText)
                   FileLogger.log(this@OverlayService, "Injector", "Success: $injected")
                   resetUI()
                }

            } catch (e: Exception) {
                FileLogger.log(this@OverlayService, "API Error", "Exception: ${e.message}\n${e.stackTraceToString()}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    resetUI()
                }
            }
        }
    }

    private fun resetUI() {
        overlayButton?.clearColorFilter()
        overlayButton?.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FF6200EE")) 
    }

    override fun onDestroy() {
        super.onDestroy()
        FileLogger.log(this, "OverlayService", "Destroyed")
        if (overlayView != null) {
            windowManager.removeView(overlayView)
        }
    }
}
