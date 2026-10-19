package com.karthiban.powermotivation.lockscreen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.karthiban.powermotivation.PowerMotivationApp
import com.karthiban.powermotivation.R
import com.karthiban.powermotivation.model.DisplayContext
import com.karthiban.powermotivation.ui.WidgetPlayerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class LockScreenKeyguardService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val screenWakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                serviceScope.launch {
                    val settings = PowerMotivationApp.instance.settingsRepository
                        .getSettingsSync(DisplayContext.LOCK_SCREEN)
                    if (settings.isEnabled) {
                        val lockPlayerIntent = Intent(context, WidgetPlayerActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            putExtra(WidgetPlayerActivity.EXTRA_CONTEXT_MODE, DisplayContext.LOCK_SCREEN.name)
                        }
                        context.startActivity(lockPlayerIntent)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startMinimalForegroundNotification()
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        registerReceiver(screenWakeReceiver, filter)
    }

    private fun startMinimalForegroundNotification() {
        val channelId = "power_motivation_lock_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Lock Screen Motivation Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Lock screen media experience active")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()

        startForeground(1001, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterReceiver(screenWakeReceiver)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}