package com.example.voicelistener

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.example.voicelistener.services.OverlayService

class VoiceChatWidget : AppWidgetProvider() {

    companion object {
        const val ACTION_TOGGLE = "com.example.voicelistener.VOICE_CHAT_TOGGLE"

        fun updateState(context: Context, state: String) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, VoiceChatWidget::class.java))
            for (id in ids) {
                val views = RemoteViews(context.packageName, R.layout.widget_voice_chat)

                val bgColor = when (state) {
                    "recording" -> Color.parseColor("#CC0000")
                    "processing" -> Color.parseColor("#CC9900")
                    "speaking" -> Color.parseColor("#CC0000")
                    else -> Color.parseColor("#444444") // idle
                }
                views.setInt(R.id.voiceChatButton, "setColorFilter", Color.WHITE)
                views.setInt(R.id.voiceChatButton, "setBackgroundColor", bgColor)

                // Always set the click PendingIntent
                val toggleIntent = Intent(context, VoiceChatWidget::class.java).apply {
                    action = ACTION_TOGGLE
                }
                val pi = PendingIntent.getBroadcast(
                    context, 0, toggleIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.voiceChatButton, pi)

                manager.updateAppWidget(id, views)
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_voice_chat)

            val toggleIntent = Intent(context, VoiceChatWidget::class.java).apply {
                action = ACTION_TOGGLE
            }
            val pi = PendingIntent.getBroadcast(
                context, 0, toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.voiceChatButton, pi)

            appWidgetManager.updateAppWidget(id, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE) {
            val serviceIntent = Intent(context, OverlayService::class.java).apply {
                action = "ACTION_VOICE_CHAT_TOGGLE"
            }
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}
