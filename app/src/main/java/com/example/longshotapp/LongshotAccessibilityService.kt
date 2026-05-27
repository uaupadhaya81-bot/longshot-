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
     * Performs a mathematically precise scroll utilizing a dynamic speed setting,
     * immediately followed by a zero-distance static touch hold to absorb 
     * and kill any remaining inertial kinetic flinging velocity instantly.
     */
    fun scrollExactDistance(scrollRect: Rect, requestedDistancePx: Int, speedIndex: Int, callback: (Int) -> Unit) {
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

        // --- DYNAMIC SPEED MULTIPLIER LOGIC ---
        val (multiplier, floor) = when (speedIndex) {
            0 -> Pair(8L, 1600L)  // 0.5x Speed
            2 -> Pair(2L, 400L)   // 2x Speed
            3 -> Pair(1L, 200L)   // 4x Speed
            else -> Pair(4L, 800L) // 1x Speed
        }
        val duration = (actualDistancePx * multiplier).coerceAtLeast(floor)

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()

        val handler = Handler(Looper.getMainLooper())

        // Dispatch the initial scrolling gesture
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                
                // --- FIXED AUTOMATED BRAKING GESTURE ---
                val brakePath = Path().apply {
                    moveTo(centerX, endY)
                    lineTo(centerX, endY) 
                }

                val brakeGesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(brakePath, 0, 300L)) // 300ms hold kills inertia completely
                    .build()

                dispatchGesture(brakeGesture, object : GestureResultCallback() {
                    override fun onCompleted(stopGestureDescription: GestureDescription?) {
                        // --- SPEED OPTIMIZATION TUNING ---
                        // Reduced from 400ms to 150ms. Safe hardware render window.
                        handler.postDelayed({ callback(actualDistancePx) }, 150)
                    }

                    override fun onCancelled(stopGestureDescription: GestureDescription?) {
                        handler.postDelayed({ callback(actualDistancePx) }, 150)
                    }
                }, handler)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                handler.postDelayed({ callback(0) }, 150)
            }
        }, handler)
    }
}
