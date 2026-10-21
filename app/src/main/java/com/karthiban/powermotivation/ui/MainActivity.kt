package com.karthiban.powermotivation.ui

import android.app.AppOpsManager
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.karthiban.powermotivation.PowerMotivationApp
import com.karthiban.powermotivation.databinding.ActivityMainBinding
import com.karthiban.powermotivation.lockscreen.HomeScreenDetectorService
import com.karthiban.powermotivation.lockscreen.LockScreenKeyguardService
import com.karthiban.powermotivation.lockscreen.MotivationWallpaperService
import com.karthiban.powermotivation.model.DisplayContext
import com.karthiban.powermotivation.model.ScaleMode
import com.karthiban.powermotivation.widget.BaseMotivationWidgetProvider
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mediaAdapter: MediaAdapter

    private val pickMultipleMediaLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            lifecycleScope.launch {
                PowerMotivationApp.instance.mediaRepository.importMediaUris(uris)
                LockScreenKeyguardService.notifyEngineReload(this@MainActivity)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupMediaLibraryRecyclerView()
        setupActionButtons()
        observeSettingsAndLibrary()

        if (Settings.canDrawOverlays(this)) {
            startWidgetEngineService()
        }
    }

    override fun onResume() {
        super.onResume()
        // While user is inside Power Motivation settings app, hide the floating widget so it doesn't cover settings
        LockScreenKeyguardService.notifyHomeVisibilityChanged(this, false, packageName)
    }

    private fun startWidgetEngineService() {
        val serviceIntent = Intent(this, LockScreenKeyguardService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun isAccessibilityDetectorEnabled(): Boolean {
        val expected = ComponentName(this, HomeScreenDetectorService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(expected)
    }

    private fun activateHomeOnlyAutoplayWidget() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Step 1: Enable 'Display over other apps'",
                Toast.LENGTH_LONG
            ).show()
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }

        if (!isAccessibilityDetectorEnabled() && !hasUsageStatsPermission()) {
            Toast.makeText(
                this,
                "Step 2: Enable 'Usage Access' for Power Motivation so the widget ONLY shows on your Home Screen and hides in other apps!",
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            return
        }

        startWidgetEngineService()
        Toast.makeText(
            this,
            "Home-Only & Lock-Only Widget Active! Press Home to see it.",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun setupMediaLibraryRecyclerView() {
        val repo = PowerMotivationApp.instance.mediaRepository
        mediaAdapter = MediaAdapter(
            onCycleTarget = { item ->
                lifecycleScope.launch {
                    repo.cyclePlaylistTarget(item)
                    LockScreenKeyguardService.notifyEngineReload(this@MainActivity)
                }
            },
            onDelete = { item ->
                lifecycleScope.launch {
                    repo.removeMedia(item)
                    LockScreenKeyguardService.notifyEngineReload(this@MainActivity)
                }
            }
        )

        binding.rvMediaLibrary.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = mediaAdapter
        }

        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                val currentList = mediaAdapter.currentList
                lifecycleScope.launch {
                    repo.moveItem(from, to, currentList)
                    LockScreenKeyguardService.notifyEngineReload(this@MainActivity)
                }
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        })
        touchHelper.attachToRecyclerView(binding.rvMediaLibrary)
    }

    private fun setupActionButtons() {
        val settingsRepo = PowerMotivationApp.instance.settingsRepository

        binding.btnAddMedia.setOnClickListener {
            pickMultipleMediaLauncher.launch(arrayOf("image/*", "video/*"))
        }

        binding.btnLaunchFloatingWidget.text = "Enable Home-Only Widget"
        binding.btnLaunchFloatingWidget.setOnClickListener {
            activateHomeOnlyAutoplayWidget()
        }

        binding.btnActivateLockWallpaper.setOnClickListener {
            try {
                val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                    putExtra(
                        WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                        ComponentName(this@MainActivity, MotivationWallpaperService::class.java)
                    )
                }
                startActivity(intent)
            } catch (e: Exception) {
                val fallback = Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
                startActivity(fallback)
            }
        }

        binding.switchHomeEnabled.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                settingsRepo.updateEnabled(DisplayContext.HOME_SCREEN, isChecked)
                BaseMotivationWidgetProvider.notifyAllWidgetsChanged(this@MainActivity)
                LockScreenKeyguardService.notifyEngineReload(this@MainActivity)
            }
        }
        binding.switchHomeAutoplay.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                settingsRepo.updateAutoPlay(DisplayContext.HOME_SCREEN, isChecked)
                LockScreenKeyguardService.notifyEngineReload(this@MainActivity)
            }
        }
        binding.switchHomeSound.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                settingsRepo.updateSoundEnabled(DisplayContext.HOME_SCREEN, isChecked)
                BaseMotivationWidgetProvider.notifyAllWidgetsChanged(this@MainActivity)
                LockScreenKeyguardService.notifyEngineReload(this@MainActivity)
            }
        }
        binding.switchHomeLoop.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                settingsRepo.updateLoop(DisplayContext.HOME_SCREEN, isChecked)
                LockScreenKeyguardService.notifyEngineReload(this@MainActivity)
            }
        }
        binding.rgHomeScale.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == binding.rbHomeCrop.id) ScaleMode.CROP else ScaleMode.FIT
            lifecycleScope.launch {
                settingsRepo.updateScaleMode(DisplayContext.HOME_SCREEN, mode)
                BaseMotivationWidgetProvider.notifyAllWidgetsChanged(this@MainActivity)
                LockScreenKeyguardService.notifyEngineReload(this@MainActivity)
            }
        }
        binding.rgHomeSizePreset.setOnCheckedChangeListener { _, checkedId ->
            val preset = if (checkedId == binding.rbHomeSizeSmall.id) "SMALL" else "LARGE"
            lifecycleScope.launch {
                settingsRepo.updateWidgetSizePreset(preset)
                LockScreenKeyguardService.notifyEngineReload(this@MainActivity)
            }
        }

        binding.switchLockEnabled.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                settingsRepo.updateEnabled(DisplayContext.LOCK_SCREEN, isChecked)
                LockScreenKeyguardService.notifyEngineReload(this@MainActivity)
            }
        }
        binding.switchLockAutoplay.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                settingsRepo.updateAutoPlay(DisplayContext.LOCK_SCREEN, isChecked)
                LockScreenKeyguardService.notifyEngineReload(this@MainActivity)
            }
        }
        binding.switchLockSound.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                settingsRepo.updateSoundEnabled(DisplayContext.LOCK_SCREEN, isChecked)
                LockScreenKeyguardService.notifyEngineReload(this@MainActivity)
            }
        }
        binding.switchLockLoop.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                settingsRepo.updateLoop(DisplayContext.LOCK_SCREEN, isChecked)
                LockScreenKeyguardService.notifyEngineReload(this@MainActivity)
            }
        }
        binding.rgLockScale.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == binding.rbLockCrop.id) ScaleMode.CROP else ScaleMode.FIT
            lifecycleScope.launch {
                settingsRepo.updateScaleMode(DisplayContext.LOCK_SCREEN, mode)
                LockScreenKeyguardService.notifyEngineReload(this@MainActivity)
            }
        }
        binding.switchLockKeyguardOverlay.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch { settingsRepo.updateKeyguardWake(isChecked) }
            if (isChecked) {
                activateHomeOnlyAutoplayWidget()
            }
        }
    }

    private fun observeSettingsAndLibrary() {
        val app = PowerMotivationApp.instance

        lifecycleScope.launch {
            app.mediaRepository.allMedia.collectLatest { items ->
                mediaAdapter.submitList(items)
                binding.tvLibraryCount.text = "Playlist (${items.size} items — Drag to reorder)"
            }
        }

        lifecycleScope.launch {
            app.settingsRepository.homeSettingsFlow.collectLatest { home ->
                if (binding.switchHomeEnabled.isChecked != home.isEnabled)
                    binding.switchHomeEnabled.isChecked = home.isEnabled
                if (binding.switchHomeAutoplay.isChecked != home.autoPlayVideo)
                    binding.switchHomeAutoplay.isChecked = home.autoPlayVideo
                if (binding.switchHomeSound.isChecked != home.isSoundEnabled)
                    binding.switchHomeSound.isChecked = home.isSoundEnabled
                if (binding.switchHomeLoop.isChecked != home.loopVideo)
                    binding.switchHomeLoop.isChecked = home.loopVideo

                if (home.scaleMode == ScaleMode.CROP && !binding.rbHomeCrop.isChecked) {
                    binding.rbHomeCrop.isChecked = true
                } else if (home.scaleMode == ScaleMode.FIT && !binding.rbHomeFit.isChecked) {
                    binding.rbHomeFit.isChecked = true
                }

                if (home.widgetSizePreset == "SMALL" && !binding.rbHomeSizeSmall.isChecked) {
                    binding.rbHomeSizeSmall.isChecked = true
                } else if (home.widgetSizePreset == "LARGE" && !binding.rbHomeSizeLarge.isChecked) {
                    binding.rbHomeSizeLarge.isChecked = true
                }
            }
        }

        lifecycleScope.launch {
            app.settingsRepository.lockSettingsFlow.collectLatest { lock ->
                if (binding.switchLockEnabled.isChecked != lock.isEnabled)
                    binding.switchLockEnabled.isChecked = lock.isEnabled
                if (binding.switchLockAutoplay.isChecked != lock.autoPlayVideo)
                    binding.switchLockAutoplay.isChecked = lock.autoPlayVideo
                if (binding.switchLockSound.isChecked != lock.isSoundEnabled)
                    binding.switchLockSound.isChecked = lock.isSoundEnabled
                if (binding.switchLockLoop.isChecked != lock.loopVideo)
                    binding.switchLockLoop.isChecked = lock.loopVideo

                if (lock.scaleMode == ScaleMode.CROP && !binding.rbLockCrop.isChecked) {
                    binding.rbLockCrop.isChecked = true
                } else if (lock.scaleMode == ScaleMode.FIT && !binding.rbLockFit.isChecked) {
                    binding.rbLockFit.isChecked = true
                }
            }
        }

        lifecycleScope.launch {
            app.settingsRepository.isKeyguardWakeEnabledFlow.collectLatest { wake ->
                if (binding.switchLockKeyguardOverlay.isChecked != wake) {
                    binding.switchLockKeyguardOverlay.isChecked = wake
                }
            }
        }
    }
}