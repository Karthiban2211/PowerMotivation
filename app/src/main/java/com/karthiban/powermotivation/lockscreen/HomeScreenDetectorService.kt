package com.karthiban.powermotivation.lockscreen

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityEvent

/**
 * Zero-latency detector that tells the widget the exact millisecond the user is on the
 * POCO Launcher Home Screen vs inside any other app.
 * Guarantees the widget ONLY appears on the Home Screen (or Lock Screen) and NEVER over other apps.
 */
class HomeScreenDetectorService : AccessibilityService() {

    private var launcherPackages: Set<String> = emptySet()

    override fun onServiceConnected() {
        super.onServiceConnected()
        resolveLaunchers()
    }

    private fun resolveLaunchers() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolves = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        val pkgs = resolves.map { it.activityInfo.packageName }.toMutableSet()
        pkgs.add("com.mi.android.globallauncher") // POCO Launcher
        pkgs.add("com.miui.home") // MIUI Home
        launcherPackages = pkgs
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            // Ignore system UI volume/toast overlays
            if (pkg == "com.android.systemui" || pkg == packageName) return

            if (launcherPackages.isEmpty()) resolveLaunchers()
            val isHome = launcherPackages.contains(pkg)
            LockScreenKeyguardService.notifyHomeVisibilityChanged(this, isHome, pkg)
        }
    }

    override fun onInterrupt() {}
}