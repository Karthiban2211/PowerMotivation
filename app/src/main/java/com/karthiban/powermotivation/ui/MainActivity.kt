package com.karthiban.powermotivation.ui

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.karthiban.powermotivation.PowerMotivationApp
import com.karthiban.powermotivation.databinding.ActivityMainBinding
import com.karthiban.powermotivation.lockscreen.MotivationWallpaperService
import com.karthiban.powermotivation.model.DisplayContext
import com.karthiban.powermotivation.model.ScaleMode
import com.karthiban.powermotivation.widget.NativeWidgetVideoService
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
                NativeWidgetVideoService.notifyPlaylistOrSettingsChanged(this@MainActivity)
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

        NativeWidgetVideoService.ensureRunningAndRender(this)
    }

    private fun setupMediaLibraryRecyclerView() {
        val repo = PowerMotivationApp.instance.mediaRepository
        mediaAdapter = MediaAdapter(
            onCycleTarget = { item ->
                lifecycleScope.launch {
                    repo.cyclePlaylistTarget(item)
                }
            },
            onDelete = { item ->
                lifecycleScope.launch {
                    repo.removeMedia(item)
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

        binding.btnRefreshNormalWidget.setOnClickListener {
            NativeWidgetVideoService.ensureRunningAndRender(this)
            Toast.makeText(
                this,
                "Home Screen Widget refreshed! Long-press your Home Screen -> Widgets -> Add Power Motivation.",
                Toast.LENGTH_LONG
            ).show()
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
                NativeWidgetVideoService.notifyPlaylistOrSettingsChanged(this@MainActivity)
            }
        }
        binding.switchHomeAutoplay.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                settingsRepo.updateAutoPlay(DisplayContext.HOME_SCREEN, isChecked)
                NativeWidgetVideoService.notifyPlaylistOrSettingsChanged(this@MainActivity)
            }
        }
        binding.switchHomeSound.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                settingsRepo.updateSoundEnabled(DisplayContext.HOME_SCREEN, isChecked)
                NativeWidgetVideoService.notifyPlaylistOrSettingsChanged(this@MainActivity)
            }
        }
        binding.switchHomeLoop.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                settingsRepo.updateLoop(DisplayContext.HOME_SCREEN, isChecked)
                NativeWidgetVideoService.notifyPlaylistOrSettingsChanged(this@MainActivity)
            }
        }
        binding.rgHomeScale.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == binding.rbHomeCrop.id) ScaleMode.CROP else ScaleMode.FIT
            lifecycleScope.launch {
                settingsRepo.updateScaleMode(DisplayContext.HOME_SCREEN, mode)
                NativeWidgetVideoService.notifyPlaylistOrSettingsChanged(this@MainActivity)
            }
        }

        binding.switchLockEnabled.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                settingsRepo.updateEnabled(DisplayContext.LOCK_SCREEN, isChecked)
            }
        }
        binding.switchLockAutoplay.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                settingsRepo.updateAutoPlay(DisplayContext.LOCK_SCREEN, isChecked)
            }
        }
        binding.switchLockSound.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                settingsRepo.updateSoundEnabled(DisplayContext.LOCK_SCREEN, isChecked)
            }
        }
        binding.switchLockLoop.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                settingsRepo.updateLoop(DisplayContext.LOCK_SCREEN, isChecked)
            }
        }
        binding.rgLockScale.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == binding.rbLockCrop.id) ScaleMode.CROP else ScaleMode.FIT
            lifecycleScope.launch {
                settingsRepo.updateScaleMode(DisplayContext.LOCK_SCREEN, mode)
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
    }
}