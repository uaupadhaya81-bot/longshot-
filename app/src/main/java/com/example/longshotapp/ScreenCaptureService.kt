package com.example.longshotapp

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import kotlin.math.abs

data class CaptureFrame(val filePath: String, val scrollDistance: Int)

class ScreenCaptureService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var floatingParams: WindowManager.LayoutParams

    private var selectorRoot: View? = null
    private var selectorParams: WindowManager.LayoutParams? = null
    private var selectorView: CaptureRectSelectorView? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val capturedFrames = ArrayList<CaptureFrame>()

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    private var selectedFrame: Rect? = null
    private var sessionStarted = false
    private var isFinishingSession = false

    private var isAutoMode = false
    private var isScrollActive = false
    private var stopRequestedDuringScroll = false
    private var manualRequestedDuringScroll = false

    private var currentSpeedIndex = 1
    private var currentWindowSize = 20

    private lateinit var cacheFramesDir: File
    private var cacheFramesDeleted = false

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        cacheFramesDir = File(cacheDir, "longshot_frames").apply {
            if (!exists()) mkdirs()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
            screenDensity = resources.configuration.densityDpi
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            screenDensity = metrics.densityDpi
        }

        startForegroundServiceNotification()
        createFloatingWidget()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val dataIntent = intent?.getParcelableExtra<Intent>("DATA_INTENT")

        currentSpeedIndex = intent?.getIntExtra("EXTRA_SPEED_INDEX", 1) ?: 1
        currentWindowSize = intent?.getIntExtra("EXTRA_WINDOW_SIZE", 20) ?: 20

        if (resultCode == Activity.RESULT_OK && dataIntent != null) {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, dataIntent)
            initCaptureEngine()
            showOverlayChromeFully()
        } else {
            Toast.makeText(this, "Failed to initialize capture engine", Toast.LENGTH_SHORT).show()
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun initCaptureEngine() {
        try {
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)

            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    cleanUpEngine()
                }
            }, Handler(Looper.getMainLooper()))

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "LongshotDisplay",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                null
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Engine init failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startForegroundServiceNotification() {
        val channelId = "longshot_service_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Longshot Capture",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Longshot Deterministic Mode")
            .setContentText("Tap the floating button to select frame, capture, scroll, and stop.")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createFloatingWidget() {
        floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_widget, null)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        floatingParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        floatingParams.gravity = Gravity.TOP or Gravity.START
        floatingParams.x = 100
        floatingParams.y = 100

        windowManager.addView(floatingView, floatingParams)

        val buttonText = floatingView.findViewById<TextView>(R.id.button_text)
        val autoText = floatingView.findViewById<TextView>(R.id.auto_text)
        val stopText = floatingView.findViewById<TextView>(R.id.stop_text)
        val dividerAuto = floatingView.findViewById<View>(R.id.divider_auto)

        updateFloatingText(buttonText)
