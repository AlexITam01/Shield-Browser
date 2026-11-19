package com.shieldbrowser.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * Home-screen search bar widget. Home-screen widgets cannot take direct text
 * input, so tapping the bar opens Shield with the address bar focused and the
 * keyboard up, ready to type (same as the Google search bar widget behaviour).
 */
class ShieldSearchWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_search_bar)
            val intent = Intent(context, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_FOCUS_SEARCH, true)
                // MainActivity is singleTask, so this brings the existing
                // instance forward and delivers the intent via onNewIntent.
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pending = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_bar, pending)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
