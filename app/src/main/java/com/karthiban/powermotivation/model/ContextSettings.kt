package com.karthiban.powermotivation.model

enum class ScaleMode {
    FIT,
    CROP
}

enum class DisplayContext {
    HOME_SCREEN,
    LOCK_SCREEN
}

data class ContextSettings(
    val isEnabled: Boolean = true,
    val autoPlayVideo: Boolean = true,
    val isSoundEnabled: Boolean = false,
    val preferredVolume: Float = 0.8f,
    val loopVideo: Boolean = true,
    val scaleMode: ScaleMode = ScaleMode.CROP,
    val playlistFilter: PlaylistTarget = PlaylistTarget.BOTH,
    val currentIndex: Int = 0,
    val widgetSizePreset: String = "LARGE"
)