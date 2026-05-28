package com.example.longshotapp

import android.content.Context
import android.os.Bundle
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences("LongshotPrefs", Context.MODE_PRIVATE)

        val tvSpeed = findViewById<TextView>(R.id.tv_speed_label)
        val sbSpeed = findViewById<SeekBar>(R.id.sb_speed)
        val tvWindow = findViewById<TextView>(R.id.tv_window_label)
        val sbWindow = findViewById<SeekBar>(R.id.sb_window)

        sbSpeed.progress = prefs.getInt("EXTRA_SPEED_INDEX", 1)
        sbWindow.progress = prefs.getInt("EXTRA_WINDOW_SIZE", 20) - 5

        fun updateSpeedText(progress: Int) {
            tvSpeed.text = "Scroll Speed: ${when (progress) {
                0 -> "0.5x"
                1 -> "1x"
                2 -> "2x"
                3 -> "4x"
                4 -> "6x"
                5 -> "8x"
                6 -> "10x"
                else -> "1x"
            }}"
        }

        fun updateWindowText(progress: Int) {
            val v = progress + 5
            tvWindow.text = "Scanner Window Size: ±${v}px"
        }

        updateSpeedText(sbSpeed.progress)
        updateWindowText(sbWindow.progress)

        sbSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateSpeedText(progress)
                prefs.edit().putInt("EXTRA_SPEED_INDEX", progress).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        sbWindow.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateWindowText(progress)
                prefs.edit().putInt("EXTRA_WINDOW_SIZE", progress + 5).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }
}

