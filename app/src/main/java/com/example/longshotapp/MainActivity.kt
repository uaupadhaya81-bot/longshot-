package com.example.longshotapp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(this)) {
                checkAndStartService()
            } else {
                Toast.makeText(this, "Overlay permission is required!", Toast.LENGTH_SHORT).show()
            }
        }

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val speedIndex = findViewById<SeekBar>(R.id.sb_speed).progress
                val windowSize = findViewById<SeekBar>(R.id.sb_window).progress + 5
                val lastWindowSize = findViewById<SeekBar>(R.id.sb_last_window).progress + 5

                val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    putExtra("RESULT_CODE", result.resultCode)
                    putExtra("DATA_INTENT", result.data)
                    putExtra("EXTRA_SPEED_INDEX", speedIndex)
                    putExtra("EXTRA_WINDOW_SIZE", windowSize)
                    putExtra("EXTRA_LAST_WINDOW_SIZE", lastWindowSize)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }

                moveTaskToBack(true)
            } else {
                Toast.makeText(this, "Screen capture permission denied!", Toast.LENGTH_SHORT).show()
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                checkAndStartService()
            } else {
                Toast.makeText(this, "Notification permission is required!", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val tvSpeedLabel = findViewById<TextView>(R.id.tv_speed_label)
        val sbSpeed = findViewById<SeekBar>(R.id.sb_speed)

        val tvWindowLabel = findViewById<TextView>(R.id.tv_window_label)
        val sbWindow = findViewById<SeekBar>(R.id.sb_window)

        val tvLastWindowLabel = findViewById<TextView>(R.id.tv_last_window_label)
        val sbLastWindow = findViewById<SeekBar>(R.id.sb_last_window)

        sbSpeed.max = 7
        sbSpeed.progress = 1

        sbWindow.max = 80
        sbWindow.progress = 15

        sbLastWindow.max = 80
        sbLastWindow.progress = 10

        sbSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvSpeedLabel.text = when (progress) {
                    0 -> "Scroll Speed: 0.5x (Slowest)"
                    1 -> "Scroll Speed: 1x"
                    2 -> "Scroll Speed: 1.5x"
                    3 -> "Scroll Speed: 2x"
                    4 -> "Scroll Speed: 3x"
                    5 -> "Scroll Speed: 4x"
                    6 -> "Scroll Speed: 6x"
                    7 -> "Scroll Speed: 8x (Fastest)"
                    else -> "Scroll Speed: 1x"
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        sbWindow.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val actualVal = progress + 5
                tvWindowLabel.text = "Scanner Window Size: ±${actualVal}px (${actualVal * 2}px Total)"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        sbLastWindow.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val actualVal = progress + 5
                tvLastWindowLabel.text = "Last Frame Window Size: ±${actualVal}px (${actualVal * 2}px Total)"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        findViewById<Button>(R.id.btn_start).setOnClickListener {
            checkAndStartService()
        }
    }

    private fun checkAndStartService() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED -> {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }

            !isAccessibilityServiceEnabled() -> {
                Toast.makeText(
                    this,
                    "Please enable Accessibility Service first",
                    Toast.LENGTH_LONG
                ).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }

            !Settings.canDrawOverlays(this) -> {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                overlayPermissionLauncher.launch(intent)
            }

            else -> {
                requestScreenCapturePermission()
            }
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponent = ComponentName(
            this,
            LongshotAccessibilityService::class.java
        ).flattenToString()

        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return enabledServices.split(':').any { it.equals(expectedComponent, ignoreCase = true) }
    }

    private fun requestScreenCapturePermission() {
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }
}
