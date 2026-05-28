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
    private var stopAfterCurrentCycle = false
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

        fun finishSessionInternal() {
            isAutoMode = false
            stopAfterCurrentCycle = false
            if (isFinishingSession) return
            isFinishingSession = true

            buttonText.text = "STITCHING..."
            buttonText.visibility = View.VISIBLE
            autoText.visibility = View.GONE
            dividerAuto.visibility = View.GONE
            stopText.isEnabled = false

            hideOverlayChromeFully()
            selectorRoot?.let { safeRemoveView(it) }
            selectorRoot = null
            processAndStitchImages()
        }

        fun requestStopAfterCurrentCycle() {
            if (isFinishingSession) return

            isAutoMode = false
            stopAfterCurrentCycle = true
            stopText.text = "WAIT..."
            stopText.setTextColor(0xFFE57373.toInt())

            if (!isScrollActive) {
                mainHandler.post {
                    if (stopAfterCurrentCycle && !isScrollActive && !isFinishingSession) {
                        finishSessionInternal()
                    }
                }
            }
        }

        stopText.setOnClickListener {
            if (isFinishingSession) return@setOnClickListener

            if (isAutoMode || isScrollActive) {
                requestStopAfterCurrentCycle()
                return@setOnClickListener
            }
            finishSessionInternal()
        }

        autoText.setOnClickListener {
            if (isFinishingSession || !sessionStarted) return@setOnClickListener

            if (isScrollActive) {
                if (isAutoMode) {
                    manualRequestedDuringScroll = true
                    autoText.text = "WAIT..."
                    autoText.setTextColor(0xFFFFC107.toInt())
                }
                return@setOnClickListener
            }

            isAutoMode = !isAutoMode
            if (isAutoMode) {
                autoText.text = "MANUAL"
                autoText.setTextColor(0xFFFF9800.toInt())
                buttonText.visibility = View.GONE
                dividerAuto.visibility = View.GONE
                triggerNextAutoScroll(buttonText, autoText)
            } else {
                autoText.text = "AUTO"
                autoText.setTextColor(0xFF00BCD4.toInt())
                buttonText.visibility = View.VISIBLE
                dividerAuto.visibility = View.VISIBLE
            }
        }

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isFinishingSession || isScrollActive) return true

                if (selectedFrame == null) {
                    showFrameSelector(buttonText, autoText, dividerAuto)
                } else {
                    if (!sessionStarted) {
                        captureCurrentFrame(0) {
                            sessionStarted = true
                            buttonText.text = "SCROLL"
                            autoText.visibility = View.VISIBLE
                            dividerAuto.visibility = View.VISIBLE
                            showOverlayChromeFully()
                        }
                    } else {
                        scrollThenCapture(buttonText)
                    }
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (isAutoMode || isScrollActive) {
                    requestStopAfterCurrentCycle()
                } else {
                    finishSessionInternal()
                }
            }
        })

        floatingView.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                if (gestureDetector.onTouchEvent(event)) return true

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = floatingParams.x
                        initialY = floatingParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = (event.rawX - initialTouchX).toInt()
                        val deltaY = (event.rawY - initialTouchY).toInt()
                        if (abs(deltaX) > 15 || abs(deltaY) > 15) {
                            floatingParams.x = initialX + deltaX
                            floatingParams.y = initialY + deltaY
                            windowManager.updateViewLayout(floatingView, floatingParams)
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun updateFloatingText(buttonText: TextView) {
        buttonText.text = when {
            selectedFrame == null -> "SELECT FRAME"
            !sessionStarted -> "CAPTURE"
            else -> "SCROLL"
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showFrameSelector(buttonText: TextView, autoText: TextView, dividerAuto: View) {
        if (selectorRoot != null) return

        hideOverlayChromeFully()

        val root = LayoutInflater.from(this).inflate(R.layout.layout_frame_selector, null)
        val selector = root.findViewById<CaptureRectSelectorView>(R.id.selector_view)
        val lockButton = root.findViewById<Button>(R.id.btn_lock_frame)
        val cancelButton = root.findViewById<Button>(R.id.btn_cancel_frame)

        selectorView = selector
        selectorRoot = root

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        selectorParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        windowManager.addView(root, selectorParams)

        lockButton.setOnClickListener {
            val frame = selector.getFrameRect()
            selectedFrame = frame
            selectorRoot?.let { safeRemoveView(it) }
            selectorRoot = null
            selectorView = null
            showOverlayChromeFully()
            updateFloatingText(buttonText)

            autoText.visibility = View.GONE
            dividerAuto.visibility = View.GONE
            Toast.makeText(this, "Frame locked: $frame", Toast.LENGTH_SHORT).show()
        }

        cancelButton.setOnClickListener {
            selectorRoot?.let { safeRemoveView(it) }
            selectorRoot = null
            selectorView = null
            showOverlayChromeFully()
            updateFloatingText(buttonText)

            autoText.visibility = View.GONE
            dividerAuto.visibility = View.GONE
        }
    }

    private fun scrollThenCapture(buttonText: TextView, onComplete: (() -> Unit)? = null) {
        val frame = selectedFrame ?: run {
            updateFloatingText(buttonText)
            onComplete?.invoke()
            return
        }

        val scroller = LongshotAccessibilityService.instance
        if (scroller == null) {
            showOverlayChromeFully()
            Toast.makeText(this, "Enable Accessibility Service first.", Toast.LENGTH_LONG).show()
            isAutoMode = false
            onComplete?.invoke()
            return
        }

        isScrollActive = true

        if (!isAutoMode) {
            hideOverlayChromeFully()
        }

        val requestedScroll = (frame.height() * 0.75f).toInt()

        scroller.scrollExactDistance(frame, requestedScroll, currentSpeedIndex) { actualDistancePx: Int ->
            captureCurrentFrame(actualDistancePx) {
                isScrollActive = false

                val autoText = floatingView.findViewById<TextView>(R.id.auto_text)
                val dividerAuto = floatingView.findViewById<View>(R.id.divider_auto)
                val stopText = floatingView.findViewById<TextView>(R.id.stop_text)

                if (stopAfterCurrentCycle || stopRequestedDuringScroll) {
                    stopAfterCurrentCycle = false
                    stopRequestedDuringScroll = false
                    finishSessionInternal()
                    onComplete?.invoke()
                    return@captureCurrentFrame
                }

                if (manualRequestedDuringScroll) {
                    manualRequestedDuringScroll = false
                    isAutoMode = false

                    autoText.text = "AUTO"
                    autoText.setTextColor(0xFF00BCD4.toInt())
                    buttonText.visibility = View.VISIBLE
                    dividerAuto.visibility = View.VISIBLE

                    showOverlayChromeFully()
                    onComplete?.invoke()
                    return@captureCurrentFrame
                }

                if (!isAutoMode) {
                    showOverlayChromeFully()
                }

                onComplete?.invoke()
            }
        }
    }

    private fun triggerNextAutoScroll(buttonText: TextView, autoText: TextView) {
        if (!isAutoMode || isFinishingSession) return

        mainHandler.postDelayed({
            if (!isAutoMode || isFinishingSession || manualRequestedDuringScroll || stopRequestedDuringScroll || stopAfterCurrentCycle) return@postDelayed

            scrollThenCapture(buttonText) {
                triggerNextAutoScroll(buttonText, autoText)
            }
        }, 450)
    }

    private fun hideOverlayChromeFully() {
        if (::floatingView.isInitialized) {
            floatingView.visibility = View.GONE
        }
    }

    private fun showOverlayChromeFully() {
        if (::floatingView.isInitialized && !isFinishingSession) {
            floatingView.visibility = View.VISIBLE
        }
    }
    private fun captureCurrentFrame(distanceScrolled: Int, onDone: () -> Unit) {
        if (isFinishingSession) {
            onDone()
            return
        }

        hideOverlayChromeFully()

        mainHandler.postDelayed({
            try {
                val fullBitmap = readScreenBitmap() ?: run {
                    showOverlayChromeFully()
                    onDone()
                    return@postDelayed
                }

                val frame = selectedFrame
                val finalBitmap = if (frame != null) cropToFrame(fullBitmap, frame) else fullBitmap

                if (finalBitmap !== fullBitmap) {
                    fullBitmap.recycle()
                }

                val savedPath = saveBitmapToCacheFile(finalBitmap)
                if (!finalBitmap.isRecycled) {
                    finalBitmap.recycle()
                }

                if (savedPath != null) {
                    capturedFrames.add(CaptureFrame(savedPath, distanceScrolled))
                } else {
                    Toast.makeText(this, "Failed to cache frame", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Capture failed: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                if (!isFinishingSession && !stopRequestedDuringScroll && !manualRequestedDuringScroll) {
                    showOverlayChromeFully()
                }
                onDone()
            }
        }, 500)
    }

    private fun saveBitmapToCacheFile(bitmap: Bitmap): String? {
        return try {
            if (!cacheFramesDir.exists()) cacheFramesDir.mkdirs()

            val file = File(cacheFramesDir, "frame_${System.currentTimeMillis()}_${capturedFrames.size}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            file.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    private fun readScreenBitmap(): Bitmap? {
        val image = imageReader?.acquireLatestImage() ?: return null

        image.use { img ->
            val plane = img.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * img.width

            val bitmap = Bitmap.createBitmap(
                img.width + rowPadding / pixelStride,
                img.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            val cropped = Bitmap.createBitmap(bitmap, 0, 0, img.width, img.height)
            if (cropped != bitmap) {
                bitmap.recycle()
            }
            return cropped
        }
    }

    private fun cropToFrame(bitmap: Bitmap, frame: Rect): Bitmap {
        val left = frame.left.coerceIn(0, bitmap.width - 1)
        val top = frame.top.coerceIn(0, bitmap.height - 1)
        val right = frame.right.coerceIn(left + 1, bitmap.width)
        val bottom = frame.bottom.coerceIn(top + 1, bitmap.height)

        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }

    private fun processAndStitchImages() {
        if (capturedFrames.isEmpty()) {
            Toast.makeText(this, "No frames captured!", Toast.LENGTH_SHORT).show()
            stopSelf()
            return
        }

        val stitchedBitmap = ImageStitcher.stitchExact(capturedFrames, currentWindowSize)

        if (stitchedBitmap != null) {
            saveBitmapToStorage(stitchedBitmap)
            stitchedBitmap.recycle()
        } else {
            Toast.makeText(this, "Stitching failed!", Toast.LENGTH_SHORT).show()
        }

        deleteCachedFramesOnce()
        capturedFrames.clear()
        stopSelf()
    }

    private fun deleteCachedFramesOnce() {
        if (cacheFramesDeleted) return
        cacheFramesDeleted = true

        capturedFrames.forEach { frame ->
            try {
                File(frame.filePath).delete()
            } catch (_: Exception) {
            }
        }
    }

    private fun saveBitmapToStorage(bitmap: Bitmap) {
        val filename = "Longshot_${System.currentTimeMillis()}.png"
        var fos: OutputStream? = null

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Longshots")
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                fos = uri?.let { contentResolver.openOutputStream(it) }
            } else {
                val imagesDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "Longshots"
                )
                if (!imagesDir.exists()) imagesDir.mkdirs()
                val image = File(imagesDir, filename)
                fos = FileOutputStream(image)
            }

            fos?.use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                Toast.makeText(this, "Longshot saved to Gallery!", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to save: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun cleanUpEngine() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
    }

    private fun safeRemoveView(view: View) {
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        isAutoMode = false
        cleanUpEngine()
        mediaProjection?.stop()

        deleteCachedFramesOnce()
        capturedFrames.clear()

        if (::floatingView.isInitialized) {
            safeRemoveView(floatingView)
        }

        selectorRoot?.let { safeRemoveView(it) }
        selectorRoot = null
    }
}
