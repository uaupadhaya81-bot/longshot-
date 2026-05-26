package com.example.longshotapp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

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

    fun getScrollableBoundsOnScreen(): Rect? {
        val root = rootInActiveWindow ?: return null
        return findScrollableNodeBounds(root)
    }

    private fun findScrollableNodeBounds(node: AccessibilityNodeInfo?): Rect? {
        if (node == null) return null

        val rect = Rect()

        if (node.isVisibleToUser && node.isScrollable) {
            node.getBoundsInScreen(rect)
            if (!rect.isEmpty) return rect
        }

        for (i in 0 until node.childCount) {
            findScrollableNodeBounds(node.getChild(i))?.let { return it }
        }

        return null
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
