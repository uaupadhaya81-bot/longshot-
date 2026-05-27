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
        // Not needed for deterministic scrolling
    }

    override fun onInterrupt() {
        // Not needed
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    /**
     * Performs a mathematically precise scroll and returns the EXACT pixel 
     * distance traveled so the stitcher can crop perfectly without guessing.
     */
    fun scrollExactDistance(scrollRect: Rect, requestedDistancePx: Int, callback: (Int) -> Unit) {
        val centerX = scrollRect.centerX().toFloat()

        // Start near the bottom of the bounding box, leaving a 15% safe margin
        val startY = (scrollRect.bottom - scrollRect.height() * 0.15f)

        // Calculate the actual distance we can travel without swiping outside the box
        val safeTopY = scrollRect.top + scrollRect.height() * 0.15f
        val maxPossibleDistance = startY - safeTopY
        
        // Ensure we don't try to scroll further than the physical screen allows
        val actualDistancePx = requestedDistancePx.toFloat().coerceAtMost(maxPossibleDistance).toInt()
        
        val endY = startY - actualDistancePx

        val path = Path().apply {
            moveTo(centerX, startY)
            lineTo(centerX, endY)
        }

        // SLOW DRAG MECHANISM: 
        // We force the swipe to take at least 2 milliseconds per pixel (minimum 800ms).
        // This drops the release velocity to near-zero, entirely preventing the 
        // system's automatic kinetic scroll animation.
        val duration = (actualDistancePx * 4L).coerceAtLeast(800L)

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()

        val handler = Handler(Looper.getMainLooper())

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                // Wait 400ms after the physical gesture ends to ensure 
                // the screen rendering has completely settled before capturing.
                handler.postDelayed({ callback(actualDistancePx) }, 400)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                handler.postDelayed({ callback(0) }, 200)
            }
        }, handler)
    }
}
