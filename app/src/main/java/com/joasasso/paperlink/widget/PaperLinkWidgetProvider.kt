package com.joasasso.paperlink.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import com.joasasso.paperlink.MainActivity
import com.joasasso.paperlink.R

class PaperLinkWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_layout)

            // Intent para abrir la cámara directamente
            Log.d("PaperLinkDebug", "PaperLinkWidgetProvider.onUpdate() -> Configuring Intent for Widget ID: $appWidgetId")
            val intent = Intent(context, MainActivity::class.java).apply {
                action = "com.joasasso.paperlink.ACTION_LAUNCH_CAMERA"
                // Añadimos categorías y flags para asegurar que se trate como un trigger único
                addCategory(Intent.CATEGORY_LAUNCHER)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
