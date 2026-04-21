package com.tezas.safestnotes.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import com.tezas.safestnotes.R
import com.tezas.safestnotes.ui.QuickCaptureActivity

/**
 * Home-screen Quick Capture widget.
 * Two tappable zones: "New Note" and "Dictate".
 */
class QuickCaptureWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) {
            updateWidget(context, appWidgetManager, id)
        }
    }

    companion object {
        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            widgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_quick_capture)

            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            else
                PendingIntent.FLAG_UPDATE_CURRENT

            // New Note tap
            val newNoteIntent = Intent(context, QuickCaptureActivity::class.java).apply {
                action = QuickCaptureActivity.ACTION_NEW_NOTE
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            views.setOnClickPendingIntent(
                R.id.widget_btn_new_note,
                PendingIntent.getActivity(context, 0, newNoteIntent, flags)
            )

            // Dictate tap
            val dictateIntent = Intent(context, QuickCaptureActivity::class.java).apply {
                action = QuickCaptureActivity.ACTION_DICTATE
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            views.setOnClickPendingIntent(
                R.id.widget_btn_dictate,
                PendingIntent.getActivity(context, 1, dictateIntent, flags)
            )

            appWidgetManager.updateAppWidget(widgetId, views)
        }
    }
}
