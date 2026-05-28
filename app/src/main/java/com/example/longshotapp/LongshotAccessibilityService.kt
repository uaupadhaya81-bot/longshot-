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
     * Performs a precise scroll using a selected speed profile,
     * then applies a short hold to absorb remaining fling velocity.
     *
     * speedIndex expected range:
     * 0..7
     *
     * 0 = slowest
     * 7 = fastest
     */
    fun scrollExactDistance(
        scrollRect: Rect,
        requestedDistancePx: Int,
        speedIndex: Int,
        callback: (Int) -> Unit
    ) {
        val centerX = scrollRect.centerX().toFloat()

        // Start near the bottom of the bounding box, leaving a 15% safe margin
        val startY = (scrollRect.bottom - scrollRect.height() * 0.15f)

        // Calculate the actual distance we can travel without swiping outside the box
        val safeTopY = scrollRect.top + scrollRect.height() * 0.15f
        val maxPossibleDistance = startY - safeTopY

        // Ensure we don't try to scroll further than the physical screen allows
        val actualDistancePx = requestedDistancePx.toFloat()
            .coerceAtMost(maxPossibleDistance)
            .toInt()

        val endY = startY - actualDistancePx

        val path = Path().apply {
            moveTo(centerX, startY)
            lineTo(centerX, endY)
        }

        val normalizedSpeed = speedIndex.coerceIn(0, 7)

        // Higher slider value = faster scroll = shorter gesture duration
        val (multiplier, floor) = when (normalizedSpeed) {
            0 -> 8L to 1600L   // 0.5x
            1 -> 6L to 1200L   // 1x
            2 -> 4L to 800L    // 1.5x
            3 -> 3L to 600L    // 2x
            4 -> 2L to 400L    // 3x
            5 -> 1L to 250L    // 4x
            6 -> 1L to 180L    // 6x
            7 -> 1L to 120L    // 8x
            else -> 6L to 1200L
        }

        val duration = (actualDistancePx * multiplier).coerceAtLeast(floor)

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()

        val handler = Handler(Looper.getMainLooper())

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                // Short braking hold to reduce leftover fling
                val brakePath = Path().apply {
                    moveTo(centerX, endY)
                    lineTo(centerX, endY)
                }

                val brakeGesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(brakePath, 0, 300L))
                    .build()

                dispatchGesture(brakeGesture, object : GestureResultCallback() {
                    override fun onCompleted(stopGestureDescription: GestureDescription?) {
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
