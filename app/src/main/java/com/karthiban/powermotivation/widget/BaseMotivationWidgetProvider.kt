package com.karthiban.powermotivation.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * 100% Pure Standard Android Home Screen Widget (AppWidgetProvider).
 * Lives ONLY on your Home Screen launcher page. NEVER floats over other apps.
 * Displays a single photo or playing video directly inside the widget without opening any popup window.
 */
abstract class BaseMotivationWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        NativeWidgetVideoService.ensureRunningAndRender(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        NativeWidgetVideoService.ensureRunningAndRender(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_WIDGET_PREV -> NativeWidgetVideoService.stepMedia(context, -1)
            ACTION_WIDGET_NEXT -> NativeWidgetVideoService.stepMedia(context, 1)
            ACTION_WIDGET_MUTE_TOGGLE -> NativeWidgetVideoService.toggleMute(context)
            ACTION_REFRESH_WIDGETS -> NativeWidgetVideoService.ensureRunningAndRender(context)
        }
    }

    companion object {
        const val ACTION_WIDGET_PREV = "com.karthiban.powermotivation.ACTION_WIDGET_PREV"
        const val ACTION_WIDGET_NEXT = "com.karthiban.powermotivation.ACTION_WIDGET_NEXT"
        const val ACTION_WIDGET_MUTE_TOGGLE = "com.karthiban.powermotivation.ACTION_WIDGET_MUTE_TOGGLE"
        const val ACTION_REFRESH_WIDGETS = "com.karthiban.powermotivation.ACTION_REFRESH_WIDGETS"
    }
}