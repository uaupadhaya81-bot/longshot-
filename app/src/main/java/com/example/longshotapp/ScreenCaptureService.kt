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
import android.util.TypedValue
import android.view.Gravity
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

class ScreenCaptureService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var params: WindowManager.LayoutParams

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val capturedBitmaps = ArrayList<Bitmap>()

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    private var isFinishingSession = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = windowManager.currentWindowMetrics
            val bounds = windowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
            screenDensity = resources.configuration.densityDpi
        } else {
            val displayMetrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(displayMetrics)
            screenWidth = displayMetrics.widthPixels
            screenHeight = displayMetrics.heightPixels
            screenDensity = displayMetrics.densityDpi
        }

        startForegroundServiceNotification()
        createFloatingWidget()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val dataIntent = intent?.getParcelableExtra<Intent>("DATA_INTENT")

        if (resultCode == Activity.RESULT_OK && dataIntent != null) {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, dataIntent)
            initCaptureEngine()
        } else {
            Toast.makeText(this, "Failed to initialize capture engine", Toast.LENGTH_SHORT).show()
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun initCaptureEngine() {
        try {
            imageReader = ImageReader.newInstance(
                screenWidth,
                screenHeight,
                PixelFormat.RGBA_8888,
                2
            )

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
            .setContentTitle("Longshot Manual Mode")
            .setContentText("Tap START, then SCROLL. Tap STOP to finish.")
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

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 100
        params.y = 100

        windowManager.addView(floatingView, params)

        val buttonText = floatingView.findViewById<TextView>(R.id.button_text)
        val stopText = floatingView.findViewById<TextView>(R.id.stop_text)

        buttonText.text = "START"
        stopText.isEnabled = true

        var isFirstCapture = true

        fun finishSession() {
            if (isFinishingSession) return
            isFinishingSession = true
            buttonText.text = "STITCHING..."
            stopText.isEnabled = false
            processAndStitchImages()
        }

        stopText.setOnClickListener {
            finishSession()
        }

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isFinishingSession) return true

                if (isFirstCapture) {
                    captureSingleFrame {
                        buttonText.text = "SCROLL"
                        isFirstCapture = false
                    }
                } else {
                    floatingView.visibility = View.INVISIBLE
                    val scroller = LongshotAccessibilityService.instance

                    if (scroller != null) {
                        scroller.autoScrollDown {
                            captureSingleFrame {
                                floatingView.visibility = View.VISIBLE
                            }
                        }
                    } else {
                        floatingView.visibility = View.VISIBLE
                        Toast.makeText(
                            applicationContext,
                            "Please enable Accessibility Permission!",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                finishSession()
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
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = (event.rawX - initialTouchX).toInt()
                        val deltaY = (event.rawY - initialTouchY).toInt()
                        if (kotlin.math.abs(deltaX) > 15 || kotlin.math.abs(deltaY) > 15) {
                            params.x = initialX + deltaX
                            params.y = initialY + deltaY
                            windowManager.updateViewLayout(floatingView, params)
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun captureSingleFrame(onCaptureComplete: () -> Unit) {
        if (isFinishingSession) {
            onCaptureComplete()
            return
        }

        Handler(Looper.getMainLooper()).postDelayed({
            try {
                imageReader?.acquireLatestImage()?.use { image ->
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * screenWidth

                    val rawBitmap = Bitmap.createBitmap(
                        screenWidth + rowPadding / pixelStride,
                        screenHeight,
                        Bitmap.Config.ARGB_8888
                    )
                    rawBitmap.copyPixelsFromBuffer(buffer)

                    val cleanBitmap = Bitmap.createBitmap(rawBitmap, 0, 0, screenWidth, screenHeight)
                    val croppedBitmap = cropVisibleArea(cleanBitmap)

                    capturedBitmaps.add(croppedBitmap)
                    Toast.makeText(this, "Frame ${capturedBitmaps.size} captured", Toast.LENGTH_SHORT).show()

                    if (croppedBitmap !== cleanBitmap) {
                        cleanBitmap.recycle()
                    }
                    if (rawBitmap !== cleanBitmap) {
                        rawBitmap.recycle()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Capture missed, try again!", Toast.LENGTH_SHORT).show()
            } finally {
                onCaptureComplete()
            }
        }, 180)
    }

    private fun cropVisibleArea(bitmap: Bitmap): Bitmap {
        val scrollBounds = getScrollableBoundsOnScreenSafely()

        if (scrollBounds != null && !scrollBounds.isEmpty) {
            val left = scrollBounds.left.coerceIn(0, bitmap.width - 1)
            val top = scrollBounds.top.coerceIn(0, bitmap.height - 1)
            val right = scrollBounds.right.coerceIn(left + 1, bitmap.width)
            val bottom = scrollBounds.bottom.coerceIn(top + 1, bitmap.height)

            return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        }

        val statusBar = getStatusBarHeight()
        val navBar = getNavigationBarHeight()
        val actionBar = getActionBarHeight()

        val topCrop = maxOf(statusBar, actionBar)
        val bottomCrop = navBar

        val safeTop = topCrop.coerceAtLeast(0).coerceAtMost(bitmap.height - 1)
        val safeBottom = bottomCrop.coerceAtLeast(0).coerceAtMost(bitmap.height - safeTop - 1)
        val safeHeight = bitmap.height - safeTop - safeBottom

        if (safeHeight <= 0) return bitmap

        return Bitmap.createBitmap(bitmap, 0, safeTop, bitmap.width, safeHeight)
    }

    private fun getScrollableBoundsOnScreenSafely(): Rect? {
        return try {
            val service = LongshotAccessibilityService.instance ?: return null
            val method = service.javaClass.getMethod("getScrollableBoundsOnScreen")
            method.invoke(service) as? Rect
        } catch (_: Exception) {
            null
        }
    }

    private fun getStatusBarHeight(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else 0
    }

    private fun getNavigationBarHeight(): Int {
        val resId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else 0
    }

    private fun getActionBarHeight(): Int {
        val typedValue = TypedValue()
        return if (theme.resolveAttribute(android.R.attr.actionBarSize, typedValue, true)) {
            TypedValue.complexToDimensionPixelSize(typedValue.data, resources.displayMetrics)
        } else {
            0
        }
    }

    private fun processAndStitchImages() {
        if (capturedBitmaps.isEmpty()) {
            Toast.makeText(this, "No frames captured!", Toast.LENGTH_SHORT).show()
            stopSelf()
            return
        }

        val stitchedBitmap = ImageStitcher.stitch(capturedBitmaps)

        if (stitchedBitmap != null) {
            saveBitmapToStorage(stitchedBitmap)
            stitchedBitmap.recycle()
        } else {
            Toast.makeText(this, "Stitching failed!", Toast.LENGTH_SHORT).show()
        }

        capturedBitmaps.forEach { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        capturedBitmaps.clear()

        stopSelf()
    }

    private fun saveBitmapToStorage(bitmap: Bitmap) {
        val filename = "Longshot_${System.currentTimeMillis()}.png"
        var fos: OutputStream? = null

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/Longshots"
                    )
                }
                val uri = contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values
                )
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

    override fun onDestroy() {
        super.onDestroy()
        cleanUpEngine()
        mediaProjection?.stop()

        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
    }
}
