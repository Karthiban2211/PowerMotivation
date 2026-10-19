package com.karthiban.powermotivation.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.karthiban.powermotivation.PowerMotivationApp
import com.karthiban.powermotivation.R
import com.karthiban.powermotivation.model.DisplayContext
import com.karthiban.powermotivation.ui.WidgetPlayerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

abstract class BaseMotivationWidgetProvider : AppWidgetProvider() {

    abstract val defaultPresetSize: String

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateWidgetInstance(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_stack_view)
        updateWidgetInstance(context, appWidgetManager, appWidgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_WIDGET_MUTE_TOGGLE -> {
                CoroutineScope(Dispatchers.IO).launch {
                    PowerMotivationApp.instance.settingsRepository.toggleSound(DisplayContext.HOME_SCREEN)
                    notifyAllWidgetsChanged(context)
                }
            }
            ACTION_WIDGET_OPEN_PLAYER -> {
                val itemIndex = intent.getIntExtra(EXTRA_ITEM_INDEX, 0)
                CoroutineScope(Dispatchers.IO).launch {
                    PowerMotivationApp.instance.settingsRepository.updateIndex(
                        DisplayContext.HOME_SCREEN,
                        itemIndex
                    )
                }
                val playerIntent = Intent(context, WidgetPlayerActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(WidgetPlayerActivity.EXTRA_CONTEXT_MODE, DisplayContext.HOME_SCREEN.name)
                    putExtra(WidgetPlayerActivity.EXTRA_START_INDEX, itemIndex)
                    putExtra(WidgetPlayerActivity.EXTRA_SIZE_PRESET, defaultPresetSize)
                }
                context.startActivity(playerIntent)
            }
            ACTION_REFRESH_WIDGETS -> {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(ComponentName(context, this::class.java))
                manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_stack_view)
                onUpdate(context, manager, ids)
            }
        }
    }

    private fun updateWidgetInstance(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        CoroutineScope(Dispatchers.Main).launch {
            val app = PowerMotivationApp.instance
            val settings = app.settingsRepository.getSettingsSync(DisplayContext.HOME_SCREEN)
            val views = RemoteViews(context.packageName, R.layout.widget_motivation_container)

            if (!settings.isEnabled) {
                views.setViewVisibility(R.id.widget_stack_view, View.GONE)
                views.setViewVisibility(R.id.widget_volume_button, View.GONE)
                views.setViewVisibility(R.id.widget_empty_view, View.VISIBLE)
                views.setTextViewText(R.id.widget_empty_view, context.getString(R.string.home_widget_disabled))
                appWidgetManager.updateAppWidget(appWidgetId, views)
                return@launch
            }

            val serviceIntent = Intent(context, MotivationWidgetRemoteViewsService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(R.id.widget_stack_view, serviceIntent)
            views.setEmptyView(R.id.widget_stack_view, R.id.widget_empty_view)
            views.setViewVisibility(R.id.widget_stack_view, View.VISIBLE)

            views.setViewVisibility(R.id.widget_volume_button, View.VISIBLE)
            views.setImageViewResource(
                R.id.widget_volume_button,
                if (settings.isSoundEnabled) R.drawable.ic_volume_on else R.drawable.ic_volume_off
            )

            val muteIntent = Intent(context, this@BaseMotivationWidgetProvider::class.java).apply {
                action = ACTION_WIDGET_MUTE_TOGGLE
            }
            val mutePendingIntent = PendingIntent.getBroadcast(
                context,
                appWidgetId + 1000,
                muteIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_volume_button, mutePendingIntent)

            val openPlayerTemplate = Intent(context, this@BaseMotivationWidgetProvider::class.java).apply {
                action = ACTION_WIDGET_OPEN_PLAYER
            }
            val openPlayerPending = PendingIntent.getBroadcast(
                context,
                appWidgetId + 2000,
                openPlayerTemplate,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            views.setPendingIntentTemplate(R.id.widget_stack_view, openPlayerPending)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    companion object {
        const val ACTION_WIDGET_MUTE_TOGGLE = "com.karthiban.powermotivation.ACTION_WIDGET_MUTE_TOGGLE"
        const val ACTION_WIDGET_OPEN_PLAYER = "com.karthiban.powermotivation.ACTION_WIDGET_OPEN_PLAYER"
        const val ACTION_REFRESH_WIDGETS = "com.karthiban.powermotivation.ACTION_REFRESH_WIDGETS"
        const val EXTRA_ITEM_INDEX = "extra_item_index"

        fun notifyAllWidgetsChanged(context: Context) {
            val classes = listOf(
                SmallMotivationWidgetProvider::class.java,
                LargeMotivationWidgetProvider::class.java
            )
            for (cls in classes) {
                val intent = Intent(context, cls).apply {
                    action = ACTION_REFRESH_WIDGETS
                }
                context.sendBroadcast(intent)
            }
        }
    }
}