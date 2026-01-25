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
import com.example.voicelistener.R
import com.example.voicelistener.network.ChatRequest
import com.example.voicelistener.network.GroqClient
import com.example.voicelistener.network.Message
import com.example.voicelistener.utils.AudioRecorder
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

    // UI Elements
    private var overlayButton: ImageButton? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        audioRecorder = AudioRecorder(this)
        addOverlayView()
    }

    private fun addOverlayView() {
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
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params!!.x
                        initialY = params!!.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        lastAction = MotionEvent.ACTION_DOWN
                        
                        // Check if we can start recording (Accessibility Check)
                        if (checkFocus()) {
                            startRecording()
                            isRecording = true
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
                        
                        // Small movement check to not disrupt simple clicks slightly moving
                         if (kotlin.math.abs(dx) > CLICK_THRESHOLD || kotlin.math.abs(dy) > CLICK_THRESHOLD) {
                             // Only move if meaningful drag, but we are recording on hold anyway
                             params!!.x = initialX + dx
                             params!!.y = initialY + dy
                             windowManager.updateViewLayout(overlayView, params)
                             lastAction = MotionEvent.ACTION_MOVE
                         }
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun checkFocus(): Boolean {
        val accessibilityService = VoiceAccessibilityService.instance
        if (accessibilityService == null) {
            Toast.makeText(this, "Service not active", Toast.LENGTH_SHORT).show()
            return false
        }
        if (!accessibilityService.isInputFocused()) {
            Toast.makeText(this, "Active field required", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun startRecording() {
        overlayButton?.setColorFilter(Color.RED) // Visual Feedback
        audioRecorder?.startRecording()
    }

    private fun stopRecordingAndProcess() {
        overlayButton?.setColorFilter(Color.YELLOW) // Processing
        audioRecorder?.stopRecording()
        val file = File(cacheDir, "recording.m4a")
        
        if (file.exists() && file.length() > 0) {
            processAudio(file)
        } else {
            resetUI()
        }
    }

    private fun processAudio(file: File) {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val apiKey = prefs.getString("groq_api_key", "") ?: ""

        if (apiKey.isEmpty()) {
            Toast.makeText(this, "API Key missing", Toast.LENGTH_LONG).show()
            resetUI()
            return
        }

        val auth = "Bearer $apiKey"

        serviceScope.launch {
            try {
                // 1. Whisper
                val requestFile = file.asRequestBody("audio/m4a".toMediaTypeOrNull())
                val body = MultipartBody.Part.createFormData("file", file.name, requestFile)
                val modelPart = "whisper-large-v3".toRequestBody("text/plain".toMediaTypeOrNull())
                
                Log.d("OverlayService", "Transcribing...")
                val transResponse = GroqClient.api.transcribeAudio(auth, body, modelPart)
                val rawText = transResponse.text
                Log.d("OverlayService", "Raw Text: $rawText")

                // 2. Llama Correction
                val prompt = "Korrigiere folgenden Text (Rechtschreibung, Grammatik). Gib NUR den korrigierten Text zurück, keine Erklärungen oder Anführungszeichen: $rawText"
                val chatRequest = ChatRequest(
                    model = "llama-3.3-70b-versatile",
                    messages = listOf(Message("user", prompt))
                )
                
                Log.d("OverlayService", "Refining...")
                val chatResponse = GroqClient.api.chatCompletion(auth, chatRequest)
                val finalText = chatResponse.choices.firstOrNull()?.message?.content ?: rawText
                Log.d("OverlayService", "Final Text: $finalText")

                // 3. Inject
                withContext(Dispatchers.Main) {
                   VoiceAccessibilityService.instance?.injectText(finalText)
                   resetUI()
                }

            } catch (e: Exception) {
                Log.e("OverlayService", "Error", e)
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
        if (overlayView != null) {
            windowManager.removeView(overlayView)
        }
    }
}
