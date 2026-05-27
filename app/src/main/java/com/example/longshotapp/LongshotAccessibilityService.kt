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
     * immediately followed by a secondary 50-pixel slow crawl gesture to absorb 
     * and kill any remaining inertial kinetic flinging velocity.
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
        // Maps the UI preferences directly to operational gesture timing profiles
        val (multiplier, floor) = when (speedIndex) {
            0 -> Pair(8L, 1600L)  // 0.5x Speed (Slower drag)
            2 -> Pair(2L, 400L)   // 2x Speed (Faster drag)
            3 -> Pair(1L, 200L)   // 4x Speed (Fastest drag)
            else -> Pair(4L, 800L) // 1x Speed (Original baseline app default)
        }
        val duration = (actualDistancePx * multiplier).coerceAtLeast(floor)

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()

        val handler = Handler(Looper.getMainLooper())

        // Dispatch the initial scrolling gesture
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                
                // --- INSTANT SECONDARY BRAKING GESTURE ---
                // The millisecond the primary gesture concludes, land a 50-pixel 
                // slow gesture on top of the UI thread to cancel out structural flinging.
                val brakePath = Path().apply {
                    moveTo(centerX, endY)
                    lineTo(centerX, (endY - 50f).coerceAtLeast(scrollRect.top.toFloat()))
                }

                val brakeGesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(brakePath, 0, 600L)) // 600ms intentional dampening
                    .build()

                dispatchGesture(brakeGesture, object : GestureResultCallback() {
                    override fun onCompleted(stopGestureDescription: GestureDescription?) {
                        // Allow layout buffers to settle completely before firing the snapshot engine
                        handler.postDelayed({ callback(actualDistancePx) }, 400)
                    }

                    override fun onCancelled(stopGestureDescription: GestureDescription?) {
                        handler.postDelayed({ callback(actualDistancePx) }, 400)
                    }
                }, handler)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                handler.postDelayed({ callback(0) }, 200)
            }
        }, handler)
    }
}

