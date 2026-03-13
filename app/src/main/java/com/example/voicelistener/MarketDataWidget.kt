package com.example.voicelistener

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.Html
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MarketDataWidget : AppWidgetProvider() {

    companion object {
        const val ACTION_UPDATE = "com.example.voicelistener.MARKET_WIDGET_UPDATE"

        fun updateAllWidgets(context: Context) {
            val intent = Intent(context, MarketDataWidget::class.java).apply {
                action = ACTION_UPDATE
            }
            context.sendBroadcast(intent)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (widgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, widgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_UPDATE) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, MarketDataWidget::class.java))
            for (widgetId in ids) {
                updateWidget(context, manager, widgetId)
            }
        }
    }

    private fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val cachedData = prefs.getString("market_widget_cache", null)
        val views = RemoteViews(context.packageName, R.layout.widget_market_home)

        if (cachedData != null) {
            // Parse HTML to get colored text (green/red for price changes)
            val spanned = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Html.fromHtml(cachedData, Html.FROM_HTML_MODE_COMPACT)
            } else {
                @Suppress("DEPRECATION")
                Html.fromHtml(cachedData)
            }
            views.setTextViewText(R.id.widgetMarketText, spanned)
        } else {
            views.setTextViewText(R.id.widgetMarketText, "Keine Daten")
        }

        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        views.setTextViewText(R.id.widgetUpdateTime, time)

        // Click opens app
        val openIntent = android.app.PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widgetRoot, openIntent)

        manager.updateAppWidget(widgetId, views)
    }
}
