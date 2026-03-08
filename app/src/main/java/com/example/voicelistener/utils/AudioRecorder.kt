package com.example.voicelistener.utils

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException

class AudioRecorder(private val context: Context) {

    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null

    fun startRecording(): File? {
        outputFile = File(context.cacheDir, "recording.m4a")
        
        Log.e("AudioRecorder", "Initializing Recorder...")
        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }

        try {
            mediaRecorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(outputFile?.absolutePath)
                Log.e("AudioRecorder", "Preparing...")
                prepare()
                Log.e("AudioRecorder", "Starting...")
                start()
                Log.e("AudioRecorder", "Recording started successfully!")
            }
            return outputFile
        } catch (e: Exception) {
            Log.e("AudioRecorder", "START FAILED: ${e.message}", e)
            mediaRecorder = null
            return null
        }
    }

    fun stopRecording() {
        Log.e("AudioRecorder", "Stopping recording...")
        try {
            mediaRecorder?.stop()
            Log.e("AudioRecorder", "Stop successful.")
        } catch (e: Exception) {
            // -1007 usually means start failed silently or empty file. 
            // We ignore it here to prevent app crash, but file will probably be empty.
            Log.e("AudioRecorder", "STOP FAILED (Ignored): ${e.message}")
        } finally {
            try {
                mediaRecorder?.release()
            } catch (e: Exception) { /* ignore */ }
            mediaRecorder = null
        }
    }

    fun getOutputFile(): File? = outputFile

    fun cancelRecording() {
         try {
            mediaRecorder?.stop()
         } catch (e: Exception) { /* ignore */ }
         mediaRecorder?.release()
         mediaRecorder = null
         outputFile?.delete() // Delete the short file
    }
}
