package com.karthiban.powermotivation

import android.app.Application
import com.karthiban.powermotivation.data.AppDatabase
import com.karthiban.powermotivation.data.MediaRepository
import com.karthiban.powermotivation.data.SettingsRepository

class PowerMotivationApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    val mediaRepository: MediaRepository by lazy { MediaRepository(this, database.mediaDao()) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: PowerMotivationApp
            private set
    }
}