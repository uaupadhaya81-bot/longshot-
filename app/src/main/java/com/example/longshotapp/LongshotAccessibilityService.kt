package com.example.longshotapp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
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
        // Not needed
    }

    override fun onInterrupt() {
        // Not needed
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    fun scrollWithinRect(scrollRect: Rect, distancePx: Int, callback: () -> Unit) {
        val centerX = scrollRect.centerX().toFloat()

        val startY = (scrollRect.bottom - scrollRect.height() * 0.18f)
            .coerceAtLeast(scrollRect.top + 24f)

        val endY = (startY - distancePx)
            .coerceAtLeast(scrollRect.top + 24f)

        val path = Path().apply {
            moveTo(centerX, startY)
            lineTo(centerX, endY)
        }

        val duration = (250 + distancePx / 8).coerceIn(250, 700).toLong()
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()

        val handler = Handler(Looper.getMainLooper())

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                handler.postDelayed({ callback() }, 450)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                handler.postDelayed({ callback() }, 250)
            }
        }, handler)
    }
}
