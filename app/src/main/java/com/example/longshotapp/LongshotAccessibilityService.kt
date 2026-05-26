package com.example.longshotapp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

class LongshotAccessibilityService : AccessibilityService() {

    companion object {
        var instance: LongshotAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not needed for this app
    }

    override fun onInterrupt() {
        // Not needed for this app
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    fun autoScrollDown(callback: () -> Unit) {
        val displayMetrics = resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels
        val screenWidth = displayMetrics.widthPixels

        val startY = (screenHeight * 0.72f)
        val endY = (screenHeight * 0.42f)
        val centerX = (screenWidth / 2f)

        val swipePath = Path().apply {
            moveTo(centerX, startY)
            lineTo(centerX, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(swipePath, 0, 350))
            .build()

        val handler = Handler(Looper.getMainLooper())

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                handler.postDelayed({
                    callback()
                }, 500)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                handler.postDelayed({
                    callback()
                }, 200)
            }
        }, handler)
    }
}
