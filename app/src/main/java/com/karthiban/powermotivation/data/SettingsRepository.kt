package com.karthiban.powermotivation.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.karthiban.powermotivation.model.ContextSettings
import com.karthiban.powermotivation.model.DisplayContext
import com.karthiban.powermotivation.model.PlaylistTarget
import com.karthiban.powermotivation.model.ScaleMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "power_motivation_settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val HOME_ENABLED = booleanPreferencesKey("home_enabled")
        val HOME_AUTOPLAY = booleanPreferencesKey("home_autoplay")
        val HOME_SOUND = booleanPreferencesKey("home_sound")
        val HOME_VOLUME = floatPreferencesKey("home_volume")
        val HOME_LOOP = booleanPreferencesKey("home_loop")
        val HOME_SCALE = stringPreferencesKey("home_scale")
        val HOME_INDEX = intPreferencesKey("home_index")
        val HOME_SIZE_PRESET = stringPreferencesKey("home_size_preset")

        val LOCK_ENABLED = booleanPreferencesKey("lock_enabled")
        val LOCK_AUTOPLAY = booleanPreferencesKey("lock_autoplay")
        val LOCK_SOUND = booleanPreferencesKey("lock_sound")
        val LOCK_VOLUME = floatPreferencesKey("lock_volume")
        val LOCK_LOOP = booleanPreferencesKey("lock_loop")
        val LOCK_SCALE = stringPreferencesKey("lock_scale")
        val LOCK_INDEX = intPreferencesKey("lock_index")
        val LOCK_KEYGUARD_WAKE = booleanPreferencesKey("lock_keyguard_wake")
    }

    val homeSettingsFlow: Flow<ContextSettings> = context.dataStore.data.map { prefs ->
        ContextSettings(
            isEnabled = prefs[Keys.HOME_ENABLED] ?: true,
            autoPlayVideo = prefs[Keys.HOME_AUTOPLAY] ?: true,
            isSoundEnabled = prefs[Keys.HOME_SOUND] ?: false,
            preferredVolume = prefs[Keys.HOME_VOLUME] ?: 0.8f,
            loopVideo = prefs[Keys.HOME_LOOP] ?: true,
            scaleMode = ScaleMode.valueOf(prefs[Keys.HOME_SCALE] ?: ScaleMode.CROP.name),
            playlistFilter = PlaylistTarget.BOTH,
            currentIndex = prefs[Keys.HOME_INDEX] ?: 0,
            widgetSizePreset = prefs[Keys.HOME_SIZE_PRESET] ?: "LARGE"
        )
    }

    val lockSettingsFlow: Flow<ContextSettings> = context.dataStore.data.map { prefs ->
        ContextSettings(
            isEnabled = prefs[Keys.LOCK_ENABLED] ?: true,
            autoPlayVideo = prefs[Keys.LOCK_AUTOPLAY] ?: true,
            isSoundEnabled = prefs[Keys.LOCK_SOUND] ?: false,
            preferredVolume = prefs[Keys.LOCK_VOLUME] ?: 0.6f,
            loopVideo = prefs[Keys.LOCK_LOOP] ?: true,
            scaleMode = ScaleMode.valueOf(prefs[Keys.LOCK_SCALE] ?: ScaleMode.CROP.name),
            playlistFilter = PlaylistTarget.BOTH,
            currentIndex = prefs[Keys.LOCK_INDEX] ?: 0,
            widgetSizePreset = "FULL"
        )
    }

    val isKeyguardWakeEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.LOCK_KEYGUARD_WAKE] ?: false
    }

    suspend fun getSettingsSync(displayContext: DisplayContext): ContextSettings {
        return when (displayContext) {
            DisplayContext.HOME_SCREEN -> homeSettingsFlow.first()
            DisplayContext.LOCK_SCREEN -> lockSettingsFlow.first()
        }
    }

    suspend fun updateEnabled(displayContext: DisplayContext, enabled: Boolean) {
        context.dataStore.edit { prefs ->
            val key = if (displayContext == DisplayContext.HOME_SCREEN) Keys.HOME_ENABLED else Keys.LOCK_ENABLED
            prefs[key] = enabled
        }
    }

    suspend fun updateAutoPlay(displayContext: DisplayContext, autoPlay: Boolean) {
        context.dataStore.edit { prefs ->
            val key = if (displayContext == DisplayContext.HOME_SCREEN) Keys.HOME_AUTOPLAY else Keys.LOCK_AUTOPLAY
            prefs[key] = autoPlay
        }
    }

    suspend fun updateSoundEnabled(displayContext: DisplayContext, soundEnabled: Boolean) {
        context.dataStore.edit { prefs ->
            val key = if (displayContext == DisplayContext.HOME_SCREEN) Keys.HOME_SOUND else Keys.LOCK_SOUND
            prefs[key] = soundEnabled
        }
    }

    suspend fun toggleSound(displayContext: DisplayContext): Boolean {
        var newState = false
        context.dataStore.edit { prefs ->
            val key = if (displayContext == DisplayContext.HOME_SCREEN) Keys.HOME_SOUND else Keys.LOCK_SOUND
            val current = prefs[key] ?: false
            newState = !current
            prefs[key] = newState
        }
        return newState
    }

    suspend fun updateLoop(displayContext: DisplayContext, loop: Boolean) {
        context.dataStore.edit { prefs ->
            val key = if (displayContext == DisplayContext.HOME_SCREEN) Keys.HOME_LOOP else Keys.LOCK_LOOP
            prefs[key] = loop
        }
    }

    suspend fun updateScaleMode(displayContext: DisplayContext, mode: ScaleMode) {
        context.dataStore.edit { prefs ->
            val key = if (displayContext == DisplayContext.HOME_SCREEN) Keys.HOME_SCALE else Keys.LOCK_SCALE
            prefs[key] = mode.name
        }
    }

    suspend fun updateIndex(displayContext: DisplayContext, index: Int) {
        context.dataStore.edit { prefs ->
            val key = if (displayContext == DisplayContext.HOME_SCREEN) Keys.HOME_INDEX else Keys.LOCK_INDEX
            prefs[key] = index.coerceAtLeast(0)
        }
    }

    suspend fun updateWidgetSizePreset(preset: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.HOME_SIZE_PRESET] = preset
        }
    }

    suspend fun updateKeyguardWake(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.LOCK_KEYGUARD_WAKE] = enabled
        }
    }
}