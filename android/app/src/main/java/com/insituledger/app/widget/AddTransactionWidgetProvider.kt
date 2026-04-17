package com.insituledger.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.insituledger.app.MainActivity
import com.insituledger.app.R

class AddTransactionWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            val intent = Intent(context, MainActivity::class.java).apply {
                action = ACTION_NEW_TRANSACTION
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_FROM_WIDGET, true)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val views = RemoteViews(context.packageName, R.layout.widget_add_transaction).apply {
                setOnClickPendingIntent(R.id.widget_icon, pendingIntent)
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    companion object {
        const val ACTION_NEW_TRANSACTION = "com.insituledger.app.ACTION_NEW_TRANSACTION"
        const val EXTRA_FROM_WIDGET = "com.insituledger.app.EXTRA_FROM_WIDGET"
    }
}
