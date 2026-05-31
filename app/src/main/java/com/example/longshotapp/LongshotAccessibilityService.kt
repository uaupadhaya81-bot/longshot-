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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    fun scrollExactDistance(scrollRect: Rect, requestedDistancePx: Int, speedIndex: Int, callback: (Int) -> Unit) {
        val centerX = scrollRect.centerX().toFloat()
        val startY = (scrollRect.bottom - scrollRect.height() * 0.15f)
        val safeTopY = scrollRect.top + scrollRect.height() * 0.15f
        val maxPossibleDistance = startY - safeTopY
        
        val actualDistancePx = requestedDistancePx.toFloat().coerceAtMost(maxPossibleDistance).toInt()
        val endY = startY - actualDistancePx

        val path = Path().apply {
            moveTo(centerX, startY)
            lineTo(centerX, endY)
        }

        // --- EXPANDED 10x SPEED LOGIC ---
        val (multiplier, floor) = when (speedIndex) {
            0 -> Pair(8f, 1600L)    // 0.5x
            1 -> Pair(4f, 800L)     // 1x
            2 -> Pair(2f, 400L)     // 2x
            3 -> Pair(1f, 200L)     // 4x
            4 -> Pair(0.66f, 130L)  // 6x
            5 -> Pair(0.5f, 100L)   // 8x
            6 -> Pair(0.4f, 80L)    // 10x
            else -> Pair(4f, 800L)
        }
        val duration = (actualDistancePx * multiplier).toLong().coerceAtLeast(floor)

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()

        val handler = Handler(Looper.getMainLooper())

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
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
